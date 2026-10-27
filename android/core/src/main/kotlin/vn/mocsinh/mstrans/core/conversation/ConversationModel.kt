package vn.mocsinh.mstrans.core.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.mocsinh.mstrans.core.KeyValueStore
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.LanguageDetector
import vn.mocsinh.mstrans.core.Settings
import vn.mocsinh.mstrans.core.SpokenContext
import vn.mocsinh.mstrans.core.TokenUsage
import vn.mocsinh.mstrans.core.TranslateError
import vn.mocsinh.mstrans.core.Translation
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.Translator
import java.util.Locale
import java.util.UUID

/** The microphone and the speaker, as the phone gives them. */
interface AudioPort {
    /** 24 kHz mono samples, on the audio thread. */
    var onSamples: ((ShortArray) -> Unit)?
    /** A phone call or another app took the sound (true) or gave it back; on the main thread. */
    var onInterruption: ((Boolean) -> Unit)?

    /** Starts listening; throws with a message the user can read. */
    fun start(echoCancelling: Boolean)
    /**
     * Echo cancelling switched while listening (Đọc to turned on or off): the microphone opens again
     * with it or without it; throws with a message the user can read.
     */
    fun setEchoCancelling(on: Boolean)
    fun stop()
    /** Runs `block` on the audio thread, after the sound already taken. */
    fun onAudioThread(block: () -> Unit)
    /** Plays 24 kHz mono PCM16 after what is already playing; `done` on the main thread once heard. */
    fun play(pcm: ByteArray, done: () -> Unit)
    /** Seconds of sound queued to play and not yet heard (as made, before `setRate`). */
    val backlog: Double
    /** How fast what is played is read: 1 as made, up to 1.35 while catching up. The pitch stays. */
    fun setRate(rate: Float)
    fun stopPlayback()
    /** Plays with the microphone off (Đọc lại after listening stopped). */
    fun playAlone(pcm: ByteArray, done: () -> Unit)
    fun stopAlone()
}

/** The phone's own voice: free, made on the phone, turned into 24 kHz mono PCM16. */
fun interface PhoneVoice {
    suspend fun pcm(text: String, languageCode: String, heard: (ByteArray) -> Unit): ByteArray
}

/** The calls to OpenAI (and the phone's and Google's voices), replaceable in a test. */
class ConversationServices(
    val transcribe: suspend (apiKey: String, wav: ByteArray, prompt: String) -> SpeechServices.Transcript,
    /** `spare`: asked again while the first request hangs, on a connection of its own. */
    val translate: suspend (apiKey: String, text: String, target: Language, context: SpokenContext, model: TranslationModel,
                            spare: Boolean) -> Translation,
    /** 24 kHz mono PCM16 of `text`; `heard` gets it as it is made (OpenAI's and the phone's voices). */
    val speak: suspend (apiKey: String, text: String, voice: String, language: Language, heard: (ByteArray) -> Unit) -> ByteArray,
    /** Opens the connection to OpenAI before a sentence needs it (every 45 s at most), so no translation waits for it. */
    val warmUp: () -> Unit = { Translator.warmUp() },
) {
    companion object {
        fun live(settings: Settings, phone: PhoneVoice) = ConversationServices(
            transcribe = { key, wav, prompt -> SpeechServices(key).transcribe(wav, prompt) },
            translate = { key, text, target, context, model, spare ->
                Translator(key, model, settings, spare = spare).translateSpoken(text, target, context)
            },
            speak = { key, text, voice, language, heard ->
                val google = GoogleVoice.nameOf(voice)
                val googleKey = settings.googleKey
                if (google != null && googleKey != null) {
                    try {
                        GoogleVoice(googleKey).speak(text, google, language.code)
                    } catch (e: GoogleVoice.Failure) {
                        if (e.kind != GoogleVoice.Failure.Kind.UNSUPPORTED) throw e
                        phone.pcm(text, language.code, heard)
                    }
                } else if (!ConversationModel.isOpenAIVoice(voice)) {
                    // A Google voice without its key reads with the phone's until the key is there.
                    phone.pcm(text, language.code, heard)
                } else {
                    SpeechServices(key).speak(text, voice, language, heard)
                }
            },
        )
    }
}

/**
 * Runs on the audio thread: cuts what the others say into sentences, keeps what the user says while
 * the Tôi nói button is down, and in live mode passes the sound on as it comes. Everything it reports
 * goes to the main thread through `post`.
 */
class Listener(silence: Double, val streams: Boolean, private val post: (() -> Unit) -> Unit) {
    // Ten seconds at most: a room that talks on is translated as it goes, not 15 s late.
    private val segmenter = SpeechSegmenter(silence, maxLength = 10.0)
    /** The last moments of sound, so the user's first syllable is kept. */
    private val recent = SampleBuffer()
    private var mine: SampleBuffer? = null
    private var minePieces = 0
    private val chunk = SampleBuffer()
    private var sinceLevel = 0
    var onUtterance: ((SpeechSegmenter.Utterance) -> Unit)? = null
    /** What the user said while holding the button: the whole turn, or a long turn piece by piece. */
    var onMine: ((samples: ShortArray, first: Boolean, last: Boolean) -> Unit)? = null
    var onLevel: ((Double, Boolean) -> Unit)? = null
    /**
     * The sound just heard (about a tenth of a second, as the microphone gives it), whether someone is
     * speaking in it, and whether a sound some way above the room's noise is (a far or quiet voice, or
     * what echo cancelling left of the phone's).
     */
    var onChunk: ((ByteArray, Boolean, Boolean) -> Unit)? = null

    companion object {
        private const val RECENT_SAMPLES = PCM.RATE * 3 / 10
        /** Live mode sends the sound as soon as there is this much of it (a fiftieth of a second). */
        private const val SMALLEST_CHUNK = PCM.RATE / 50
        /** A turn longer than this goes on as it is said, a piece at a time (cut at a breath). */
        private const val LONGEST_PIECE = PCM.RATE * 20
    }

    fun take(samples: ShortArray) {
        val held = mine
        if (held != null) {
            held.append(samples)
            if (held.size >= LONGEST_PIECE) giveMine(last = false)
        } else {
            for (utterance in segmenter.push(samples)) deliver(utterance)
        }
        recent.append(samples)
        if (recent.size > RECENT_SAMPLES) recent.removeFirst(recent.size - RECENT_SAMPLES)
        if (streams) {
            chunk.append(samples)
            // All of it at once. It went in even tenths of a second, and the end of each of the
            // microphone's pieces waited a tenth of a second for the next.
            if (chunk.size >= SMALLEST_CHUNK) {
                val data = PCM.data(chunk.array, 0, chunk.size)
                chunk.clear()
                val speech = mine != null || segmenter.speaking
                val audible = segmenter.audible
                post { onChunk?.invoke(data, speech, audible) }
            }
        }
        sinceLevel += samples.size
        if (sinceLevel >= PCM.RATE / 10) {
            sinceLevel = 0
            val from = maxOf(0, samples.size - PCM.RATE / 10)
            val level = PCM.dbfs(samples, from, samples.size)
            val speaking = mine != null || segmenter.speaking
            post { onLevel?.invoke(level, speaking) }
        }
    }

    /** The button went down: what the others were saying goes out, and from now on the sound is the user's. */
    fun beginMine() {
        if (mine != null) return
        val phoneSpoke = segmenter.muted
        segmenter.flush()?.let { deliver(it) }
        mine = SampleBuffer().also { if (!phoneSpoke) it.append(recent) }
        minePieces = 0
    }

    fun endMine() = giveMine(last = true)

    /** The turn so far: all of it at the end, otherwise up to its last breath. */
    private fun giveMine(last: Boolean) {
        val held = mine ?: return
        val split = if (last) held.size else SpeechSegmenter.split(held.array, held.size)
        val piece = held.toArray(0, split)
        mine = if (last) null else SampleBuffer().also { it.append(held.array, split, held.size) }
        val first = minePieces == 0
        minePieces += 1
        post { onMine?.invoke(piece, first, last) }
    }

    fun mute() {
        segmenter.mute()?.let { deliver(it) }
    }

    fun unmute() = segmenter.unmute()

    fun setSilence(seconds: Double) {
        segmenter.silence = seconds
    }

    fun flush() {
        endMine()
        segmenter.flush()?.let { deliver(it) }
        // The last moment of sound goes too: it may end the last word.
        if (streams && chunk.size > 0) {
            val data = PCM.data(chunk.array, 0, chunk.size)
            post { onChunk?.invoke(data, false, false) }
        }
        chunk.clear()
    }

    private fun deliver(utterance: SpeechSegmenter.Utterance) {
        post { onUtterance?.invoke(utterance) }
    }
}

/**
 * The Hội thoại tab, for a meeting or a conversation with foreign partners.
 *
 * While it runs, everything the others say comes up as Vietnamese text (Vietnamese speakers as they
 * spoke), and with Đọc to it is also read aloud through the speaker; Chỉ chữ only shows it. When the
 * user wants to speak, they hold the Tôi nói button: what they say is translated into the partners'
 * language and read aloud.
 *
 * How it translates (Cách dịch):
 * - Chính xác: each sentence is recognised and translated once it ends, with the glossary and the
 *   meeting's context; several at once, shown in the order they were said.
 * - Dịch liền: OpenAI's live speech translation, text (and voice) as the others speak, cut into
 *   sentences, each translated again by GPT for the text.
 * - Soniox: Soniox's live recognition and translation, text word by word as people speak. With an
 *   OpenAI key, sentences are cut speaker by speaker and each is translated again by GPT with the
 *   meeting's context before it is read aloud.
 *
 * A room that talks on is not let to run ahead of the voice: it reads faster while behind, and an old
 * sentence with newer ones waiting is left on the screen unread. The speed log (menu ⋯) says how long
 * each step of each sentence took, for a real meeting that felt slow.
 *
 * Used on the main thread only (`scope`).
 */
class ConversationModel(
    private val settings: Settings,
    private val defaults: KeyValueStore,
    private val audio: AudioPort,
    private val meetings: MeetingStore,
    private val scope: CoroutineScope,
    var services: ConversationServices,
) {
    enum class Engine(val id: String, val title: String) {
        ACCURATE("accurate", "Chính xác (sau mỗi câu)"),
        LIVE("live", "Dịch liền (ngay khi nói)"),
        SONIOX("soniox", "Soniox: dịch liền, rẻ nhất");

        /** The sound goes to a live session as it is heard (not sentence by sentence). */
        val streams: Boolean get() = this != ACCURATE
    }

    /** How the others' words are translated (the user's own always use the app's translation model). */
    enum class Quality(val id: String, val title: String, val model: TranslationModel) {
        ECONOMY("economy", "Tiết kiệm · GPT-6 Luna", TranslationModel.LUNA),
        BEST("best", "Chuẩn nhất · GPT-6.1 Sol", TranslationModel.SOL),
    }

    /** How large the translations are written, for a phone lying on the table. */
    /** How large the translations are written. Kept by `code`: SMALL came after the others. */
    enum class TextSize(val title: String, val code: Int) { SMALL("Nhỏ", 3), MEDIUM("Vừa", 0), LARGE("Lớn", 1), EXTRA_LARGE("Rất lớn", 2) }

    sealed class State {
        /** Live captions still coming in. */
        data object Hearing : State()
        data object Recognizing : State()
        data object Translating : State()
        data object Done : State()
        data class Failed(val message: String) : State()
    }

    class Entry(
        val id: String,
        val at: Long,
        /** Said by the user with the Tôi nói button (translated and read aloud). */
        val mine: Boolean,
        /** What it was spoken in ("vi", "zh"…), empty until known. */
        var language: String,
        var text: String,
        var translation: String,
        /** What it is translated into. */
        var target: String,
        var state: State,
        /** Who said it, as Soniox tells the room's speakers apart ("1", "2"…); empty when not told. */
        var speaker: String = "",
    ) {
        val isVietnamese: Boolean get() = language == Language.vietnamese.code
    }

    private object Keys {
        /**
         * A new key: the others' words are no longer read aloud unless chosen again (most meetings want
         * the text, and their voice held up the user's).
         */
        const val READ_ALOUD = "conversation.readOthersAloud"
        const val SHOWS_ORIGINAL = "conversation.showsOriginal"
        const val ENGINE = "conversation.engine"
        const val PARTNER = "conversation.partner"
        const val SILENCE = "conversation.silence"
        const val VOICE = "conversation.voice"
        const val QUALITY = "conversation.quality"
        const val TEXT_SIZE = "conversation.textSize"
        const val TOPIC = "conversation.topic"
    }

    companion object {
        val silenceChoices = listOf(0.4, 0.6, 0.8, 1.0, 1.2, 1.4)
        /** The phone's own voice: free, made on the phone, less natural than OpenAI's. */
        const val PHONE_VOICE = "phone"
        val voices = listOf(
            "marin" to "Marin (nữ, OpenAI)", "cedar" to "Cedar (nam, OpenAI)",
            GoogleVoice.id(GoogleVoice.FEMALE) to "Aoede (nữ, Google)", GoogleVoice.id(GoogleVoice.MALE) to "Charon (nam, Google)",
            PHONE_VOICE to "Giọng điện thoại (miễn phí)",
        )

        /** Made by OpenAI with the OpenAI key, and paid by the minute (the phone's and Google's are not). */
        fun isOpenAIVoice(voice: String) = voice != PHONE_VOICE && GoogleVoice.nameOf(voice) == null

        /** Sentences recognised and translated at once: a busy meeting does not fall behind. */
        private const val PARALLEL = 4
        /**
         * The others' sentence whose voice is this late (seconds after its translation), with newer ones
         * waiting, is left on the screen unread: the voice keeps up with the room.
         */
        private const val STALE = 6.0
        /**
         * GPT this slow to answer is asked again alongside (a phone network can stall one connection for
         * many seconds): the first answer is taken. Luna answers a sentence in about a second.
         */
        private const val LUNA_PATIENCE = 2.5
        private const val SOL_PATIENCE = 5.0
        /** The meeting's latest sentences GPT gets with each one. */
        private const val CONTEXT_LINES = 8
        /** How long the sound after the button goes up is still the user's turn (ms). */
        private const val MINE_TAIL = 350L
        /**
         * A Soniox sentence paused without a full stop ends once its speaker has said nothing more for this
         * long (ms): a thought said in two breaths ("if you order five hundred, … we give five percent") is
         * translated as one.
         */
        private const val PAUSE_ENDS = 1200L
        /** Seconds before each try to open a dropped Soniox session again. */
        private val RECONNECT_DELAYS = listOf(0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 5.0, 5.0)
        /**
         * The room's words wait this long on the screen for Soniox's translation before GPT is asked to
         * translate ahead (seconds). GPT gets this long to answer, and is asked at most this many times a
         * sentence.
         */
        private const val AHEAD_WAIT = 1.2
        private const val AHEAD_PATIENCE = 4000L
        private const val AHEAD_ASKS = 12
        /** Soniox's translation shows again instead of GPT's once it reaches this far past it (ms). */
        private const val AHEAD_MARGIN = 1500
        const val LONGEST_TOPIC = 200

        /** Sound enough to begin reading without running dry while the rest is made: 0.4 s. */
        private const val STARTING_SOUND = (PCM.RATE * 2 * 0.4).toInt()

        fun language(code: String): Language = Language.ofCode(code)

        /**
         * The language a sentence was spoken in: what the recognizer heard, checked against the text
         * (Vietnamese written with its marks is Vietnamese, whatever was heard).
         */
        fun language(text: String, heard: String?): String {
            if (LanguageDetector.isVietnamese(text)) return Language.vietnamese.code
            if (!heard.isNullOrEmpty()) {
                if (heard == "zh" && LanguageDetector.language(text)?.code == "zh-Hant") return "zh-Hant"
                return heard
            }
            return LanguageDetector.language(text)?.code ?: "en"
        }

        private const val SUMMARY_INSTRUCTIONS =
            "You write the minutes of a business meeting for a Vietnamese professional. The input is its transcript: each " +
                "sentence with its time, who spoke (\"Tôi\" is the user, otherwise the language spoken, after \"Người 1\", \"Người 2\"… " +
                "when the speakers were told apart, which may now and then be wrong), what was said (recognised " +
                "automatically, so it may hold recognition mistakes) and its translation. Write in Vietnamese, short and plain, " +
                "under these headings: \"Nội dung chính\" (bullets), \"Đã thống nhất\" (decisions; numbers, prices, quantities and " +
                "dates exactly as said), \"Việc cần làm\" (who does what, by when, when said), \"Còn bỏ ngỏ\" (open questions). " +
                "Leave out a heading with nothing under it. Never add anything the transcript does not say."

        /** Minutes of the meeting in Vietnamese, with what writing them cost. */
        suspend fun summarize(meeting: Meeting, settings: Settings): Triple<String, TokenUsage?, TranslationModel> {
            val apiKey = settings.apiKey ?: throw TranslateError.noKey()
            val model = settings.model
            val (text, usage) = Translator(apiKey, model, settings).write(SUMMARY_INSTRUCTIONS, meeting.transcript)
            return Triple(text, usage, model)
        }

        fun name(model: TranslationModel): String = if (model == TranslationModel.LUNA) "Luna" else "Sol"

        /** A block holds this many sentences at most, each said within this long of the one before (ms). */
        const val BLOCK_SIZE = 6
        const val BLOCK_GAP = 60_000L

        /**
         * The transcript as it is shown: sentences one person said one after another (the user's turn cut in
         * pieces, a speaker Soniox told apart talking on) in one block, under their name once. The others'
         * sentences with no speaker told (Chính xác, Dịch liền) each stand alone: two in a row may be two people.
         */
        fun blocks(entries: List<Entry>): List<List<Entry>> {
            val blocks = ArrayList<ArrayList<Entry>>()
            for (entry in entries) {
                val block = blocks.lastOrNull()
                val last = block?.lastOrNull()
                if (block != null && last != null && block.size < BLOCK_SIZE &&
                    last.mine == entry.mine && last.speaker == entry.speaker && (entry.mine || entry.speaker.isNotEmpty()) &&
                    last.target == entry.target && (last.language.isEmpty() || entry.language.isEmpty() || last.language == entry.language) &&
                    entry.at - last.at < BLOCK_GAP
                ) {
                    block.add(entry)
                } else {
                    blocks.add(arrayListOf(entry))
                }
            }
            return blocks
        }

        private fun now(): Double = System.currentTimeMillis() / 1000.0
        private fun millis(): Long = System.currentTimeMillis()
    }

    /** Called on the main thread after anything shown changes. */
    var onChange: (() -> Unit)? = null
    /** Keeps the screen on while listening. */
    var keepScreenOn: ((Boolean) -> Unit)? = null
    /** Asks for the microphone; true when allowed. */
    var requestPermission: (suspend () -> Boolean)? = null
    /** The app's version, for the speed log. */
    var appVersion = "?"
    /** Where Soniox is reached (a test's own server, in a test). */
    var sonioxUrl = SonioxSession.URL

    private fun changed() {
        onChange?.invoke()
    }

    // Settings, kept between launches

    /** Đọc to: the others' words are read aloud in Vietnamese too. Chỉ chữ: only shown. */
    var readAloud: Boolean = defaults.get(Keys.READ_ALOUD)?.toBooleanStrictOrNull() ?: false
        set(value) {
            val switched = field != value
            field = value
            defaults.set(Keys.READ_ALOUD, value.toString())
            if (running && switched) cancelEchoIfNeeded()
            changed()
        }
    var engine: Engine = Engine.entries.firstOrNull { it.id == defaults.get(Keys.ENGINE) } ?: Engine.ACCURATE
        set(value) {
            field = value
            defaults.set(Keys.ENGINE, value.id)
            changed()
        }
    /** `Language.AUTO`: the language the others spoke last. */
    var partnerCode: String = defaults.get(Keys.PARTNER) ?: Language.AUTO
        set(value) {
            field = value
            defaults.set(Keys.PARTNER, value)
            changed()
        }
    /** The pause that ends someone's sentence. */
    var silence: Double = defaults.get(Keys.SILENCE)?.toDoubleOrNull()?.takeIf { it in silenceChoices } ?: 0.8
        set(value) {
            field = value
            defaults.set(Keys.SILENCE, value.toString())
            listener?.let { l -> audio.onAudioThread { l.setSilence(value) } }
            changed()
        }
    var voice: String = defaults.get(Keys.VOICE)?.takeIf { saved -> voices.any { it.first == saved } } ?: "marin"
        set(value) {
            field = value
            defaults.set(Keys.VOICE, value)
            changed()
        }
    var quality: Quality = Quality.entries.firstOrNull { it.id == defaults.get(Keys.QUALITY) } ?: Quality.ECONOMY
        set(value) {
            field = value
            defaults.set(Keys.QUALITY, value.id)
            changed()
        }
    var textSize: TextSize = TextSize.entries.firstOrNull { it.code == defaults.get(Keys.TEXT_SIZE)?.toIntOrNull() } ?: TextSize.MEDIUM
        set(value) {
            field = value
            defaults.set(Keys.TEXT_SIZE, value.code.toString())
            changed()
        }
    /** The words as said, under their translation. */
    var showsOriginal: Boolean = defaults.get(Keys.SHOWS_ORIGINAL)?.toBooleanStrictOrNull() ?: true
        set(value) {
            field = value
            defaults.set(Keys.SHOWS_ORIGINAL, value.toString())
            changed()
        }
    /**
     * What the meeting is about, in the user's words ("báo giá máy CNC với nhà cung cấp Thâm Quyến"): GPT
     * is told with every sentence, Soniox when listening starts, and it goes in the minutes.
     */
    var topic: String = defaults.get(Keys.TOPIC) ?: ""
        private set

    fun setTopic(text: String) {
        val value = text.trim().take(LONGEST_TOPIC)
        topic = value
        defaults.set(Keys.TOPIC, value)
        meeting.topic = value.ifEmpty { null }
        log(null, if (value.isEmpty()) "Bỏ chủ đề cuộc họp" else "Chủ đề cuộc họp: ${TranscriptFilter.syllableCount(value)} âm tiết")
        changed()
    }

    // State

    val entries = ArrayList<Entry>()
    var running = false
        private set
    var starting = false
        private set
    /** 0…1, for the meter. */
    var level = 0.0
        private set
    var hearsSpeech = false
        private set
    var startedAt: Long? = null
        private set
    var problem: String? = null
        set(value) {
            field = value
            changed()
        }
    var micDenied = false
        private set
    /** The Tôi nói button is down: what the microphone hears is the user. */
    var talking = false
        private set
    /** The phone is reading a translation aloud; `speakingMine`: the user's. */
    var speaking = false
        private set
    var speakingMine = false
        private set
    var interrupted = false
        private set
    var summarizing = false
        private set
    /** The language the others spoke last (for "Tự động"). */
    var detectedPartner: Language? = null
        private set
    var meeting = Meeting(topic = topic.ifEmpty { null })
        private set

    private var listener: Listener? = null
    private var apiKey: String? = null
    private var wantsToTalk = false
    private var heardThisTurn = false
    private class Waiting(val id: String, val samples: ShortArray, val mine: Boolean)
    private val waiting = ArrayList<Waiting>()
    private var inFlight = 0
    /** Sound of sentences that could not be recognised (no network), to try again. */
    private val unrecognised = HashMap<String, ShortArray>()
    private val echo = EchoGuard()
    private var speechChain: Job? = null
    private var speechGeneration = 0
    private var playingAloud = 0
    private var playingMine = 0
    private var liveToVietnamese: LiveSession? = null
    private var liveToPartner: LiveSession? = null
    /** The entry being heard live, per side (true: the others'), and when it last grew. */
    private class Caption(val id: String, val last: Long)
    private val captions = HashMap<Boolean, Caption>()
    /** Dịch liền's captions cut into sentences (true: the others'), and each caption's entry. */
    private val liveCaptions = HashMap<Boolean, LiveCaptions>()
    private val liveEntries = HashMap<Boolean, HashMap<Int, String>>()
    private var partnerTailUntil = 0L
    /**
     * The button just went up: the sound said just before is still on its way, and goes to the user's
     * session until then (it went to the room's, and the user's last syllable was lost: "…được không"
     * read as "…được", a question as a yes).
     */
    private var mineTailUntil = 0L
    /** The user's voice taken out of what the room's Soniox session hears in their turns. */
    private var myVoice = MyVoiceFilter()
    private var liveRetries = 0
    private var sonioxToVietnamese: SonioxSession? = null
    private var sonioxToPartner: SonioxSession? = null
    /**
     * The sound lately sent to each side's Soniox session (true: the others'), and where in it each
     * session's sound begins: a session that drops is followed by one sent again what the first had not
     * settled, and what was said meanwhile (it was lost).
     */
    private val trails = HashMap<Boolean, SoundTrail>()
    private val origins = HashMap<Boolean, Double>()
    private val replayFrom = HashMap<Boolean, Double>()
    /**
     * Listening stopped with Soniox: its sessions settle what they heard, and their last words and
     * translations still come in, two seconds at most (they were lost when the sessions closed at once).
     * The sides whose sessions have answered.
     */
    private var draining = false
    private val settledAll = HashSet<Boolean>()
    private val drained = HashSet<Boolean>()
    private var drainJob: Job? = null
    /** What each Soniox session has heard (true: the others'), cut into sentences. */
    private val transcripts = HashMap<Boolean, SonioxTranscript>()
    /** Dịch liền: the others' sound is sent while someone speaks, not while the room is quiet. */
    private val othersGate = LiveGate()
    /** Seconds sent to a live session and not yet in the meeting's cost (added a sentence at a time). */
    private var unbilledLive = 0.0
    /** Voices already made: Đọc lại plays them at once, without paying again. */
    private val voiceCache = VoiceCache()
    private var aloneTask: Job? = null
    /** Soniox sentences GPT is translating, by their number in the transcript (true: the others'). */
    private val sonioxEntries = HashMap<Boolean, HashMap<Int, String>>()
    /** Seconds of sound sent to each Soniox session (true: the others'), for how far it lags. */
    private val sonioxSent = HashMap<Boolean, Double>()
    /** When the last of it was sent (`now()`'s clock). */
    private val sonioxSentAt = HashMap<Boolean, Double>()
    /** Entries already read aloud: one read from Soniox's translation is not read again for GPT's. */
    private val voiced = HashSet<String>()
    /** Counts the others' sentences queued to be read, so one can tell how many wait behind it. */
    private var theirsQueued = 0
    /** Counts the user's sentences given to be read: an older sentence of the others' is not read before them. */
    private var mineQueued = 0
    private var lastDropNote = 0L
    private val speedLog = SpeedLog()

    /**
     * Seconds into a Soniox session's sound: where a sentence's words begin and end, and how much had
     * been sent when its words, then its first translation, showed (for the speed log). And how late its
     * words, and Soniox's and GPT's translations of them, came at worst: the sound sent after a word when
     * it, or a translation reaching it, showed.
     */
    private class HeardTime(var start: Double? = null, var end: Double? = null, var shown: Double? = null, var translated: Double? = null) {
        var covered: Double? = null
        var newest: Double? = null
        var wordsLate = 0.0
        var sonioxLate = 0.0
        var gptLate: Double? = null
    }
    private val heardTimes = HashMap<String, HeardTime>()

    /**
     * GPT's translation of the room's sentence while it is still being said (dịch trước). Soniox's own
     * translation follows the words it has settled, which in a noisy room of several people can be seconds
     * after they were said: the text fell behind the speaker. When words have waited on the screen a
     * moment for their translation, or the speaker pauses, GPT translates the sentence so far (guessed
     * words too); whichever translation has come further shows, and when the sentence ends as GPT was asked
     * it, that answer is its final translation at once. By the sentence's entry.
     */
    private class Ahead {
        /** The words asked, while GPT translates them. */
        var asked: String? = null
        var job: Job? = null
        /** GPT's latest translation, of which words, and where they end in the session's sound (ms). */
        var answer: AheadAnswer? = null
        /** Soniox's translation of the sentence as last told, and where the words it reaches end. */
        var sonioxTranslation: String? = null
        var sonioxCovered: Int? = null
        /** GPT's is the translation shown: the next is asked as soon as this one is in. */
        var showing = false
        var asks = 0
        /** When the sentence's words came (`now()`), by where they end (ms): how long the first word without a translation has waited. */
        val seen = ArrayList<Pair<Int, Double>>()
    }
    private class AheadAnswer(val translation: String, val text: String, val endMs: Int)
    private val ahead = HashMap<String, Ahead>()

    /**
     * The room's Soniox session, over the last half minute: how far behind the sound sent it has heard
     * (the network, and Soniox keeping up) and settled (its guesses waiting to be sure); GPT asked to
     * translate ahead, and how long its answers took.
     */
    private class SonioxLags(val since: Double = 0.0) {
        var count = 0
        var heard = 0.0
        var heardMost = 0.0
        var settled = 0.0
        var settledMost = 0.0
        var asks = 0
        var answers = 0
        var answering = 0.0
    }
    private var sonioxLags = SonioxLags()

    /**
     * The sound sent to one side's Soniox sessions lately, a tenth of a second at a time, each with where
     * it begins in all the sound that side was sent.
     */
    private class SoundTrail {
        class Chunk(val at: Double, val data: ByteArray)

        val chunks = ArrayDeque<Chunk>()
        /** Seconds of sound that side was sent, all its sessions together. */
        var end = 0.0
            private set

        fun add(data: ByteArray) {
            chunks.addLast(Chunk(end, data))
            end += PCM.seconds(data)
            while (chunks.isNotEmpty() && end - chunks.first().at > KEPT) chunks.removeFirst()
        }

        /** The sound from `position` on, from the tenth of a second it falls in. */
        fun since(position: Double): List<Chunk> = chunks.filter { it.at + PCM.seconds(it.data) > position }

        companion object {
            /** Long enough for a session to be opened again a few times. */
            const val KEPT = 15.0
        }
    }

    init {
        audio.onInterruption = { began ->
            interrupted = began
            changed()
        }
    }

    /** The language the user's words are translated into: the one chosen, or the one the others spoke last. */
    val partnerLanguage: Language
        get() {
            if (partnerCode != Language.AUTO) Language.named(partnerCode)?.let { return it }
            return detectedPartner ?: Language.named(settings.lastPartnerCode) ?: Language.all[0]
        }

    /** OpenAI's live translation speaks 13 languages; Thai, Malay and Arabic are not among them. */
    val liveSpeaksPartner: Boolean get() = LiveSession.outputCode(partnerLanguage) != null

    // Start and stop

    suspend fun start() {
        if (running || starting) return
        endDrain()
        problem = null
        // Soniox hears and translates by itself: the OpenAI key is only for its voices and summaries.
        val key: String
        if (engine == Engine.SONIOX) {
            if (settings.sonioxKey == null) {
                problem = "Chưa có key Soniox. Dán key trong Cài đặt › Soniox, hoặc đổi Cách dịch (menu ⋯)."
                return
            }
            key = settings.apiKey ?: ""
            if (key.isEmpty() && isOpenAIVoice(voice)) voice = PHONE_VOICE
        } else {
            val openAIKey = settings.apiKey
            if (openAIKey == null) {
                problem = TranslateError.noKey().message
                return
            }
            key = openAIKey
        }
        starting = true
        changed()
        try {
            if (requestPermission?.invoke() == false) {
                micDenied = true
                problem = "MSTrans chưa được dùng micro. Cho phép micro trong Cài đặt của điện thoại › Ứng dụng › MSTrans › Quyền."
                return
            }
            micDenied = false
            apiKey = key
            if (key.isNotEmpty()) services.warmUp()
            val listener = Listener(silence, engine.streams) { block -> scope.launch { block() } }
            listener.onUtterance = { heard(it) }
            listener.onMine = { samples, first, last -> said(samples, first, last) }
            listener.onLevel = { level, speaking -> heard(level, speaking) }
            listener.onChunk = { data, speech, audible -> stream(data, speech, audible) }
            this.listener = listener
            audio.onSamples = { listener.take(it) }
            try {
                audio.start(echoCancelling)
            } catch (e: Exception) {
                problem = e.message ?: "Không mở được micro."
                this.listener = null
                return
            }
            running = true
            interrupted = false
            startedAt = millis()
            if (meeting.lines.isEmpty()) meeting.started = millis()
            keepScreenOn?.invoke(true)
            if (engine == Engine.LIVE) openLive()
            if (engine == Engine.SONIOX) openSoniox()
            if (wantsToTalk) talk(true)
            warmUpGoogle()
            log(null, "Bắt đầu · ${engine.id} · ${if (readAloud) "đọc to" else "chỉ chữ"} · dịch đối phương ${name(quality.model)}" +
                " · lời tôi ${name(settings.model)} · giọng $voice")
        } finally {
            starting = false
            changed()
        }
    }

    /** With a Google voice, a key that does not work is told at once rather than at the first sentence. */
    private fun warmUpGoogle() {
        if (GoogleVoice.nameOf(voice) == null) return
        val googleKey = settings.googleKey ?: return
        scope.launch {
            val failure = GoogleVoice(googleKey).warmUp() ?: return@launch
            problem = failure.message
        }
    }

    fun stop() {
        if (!running) return
        if (talking) talk(false)
        wantsToTalk = false
        running = false
        level = 0.0
        hearsSpeech = false
        stopSpeaking()
        audio.stop()
        // Soniox is sent the last of the sound, then asked to settle all it heard.
        draining = engine == Engine.SONIOX && (sonioxToVietnamese != null || sonioxToPartner != null)
        val soniox = draining
        // After the sound already queued: the sentence being said still goes in.
        val l = listener
        if (l != null) {
            audio.onAudioThread {
                l.flush()
                if (soniox) scope.launch { drainSoniox() }
            }
        } else if (soniox) {
            drainSoniox()
        }
        closeLive()
        endLiveCaptions(all = true)
        if (!draining) {
            for (current in captions.values.toList()) finishCaption(current.id)
            captions.clear()
        }
        billLive()
        keepScreenOn?.invoke(false)
        meetings.save(meeting)
        log(null, "Dừng")
        changed()
    }

    /** Sentences still being recognised or translated. */
    private val settling: Boolean get() = entries.any { it.state == State.Recognizing || it.state == State.Translating }

    /** A new meeting can start once listening has stopped and the last sentences are in this one. */
    val canStartNewMeeting: Boolean get() = !running && !draining && !summarizing && !settling && entries.isNotEmpty()

    /** The meeting so far goes to the saved ones; the next starts empty. */
    fun newMeeting() {
        if (running || draining || summarizing || settling) return
        billLive()
        meetings.save(meeting)
        meeting = Meeting(topic = topic.ifEmpty { null })
        entries.clear()
        heardTimes.clear()
        for (state in ahead.values) state.job?.cancel()
        ahead.clear()
        unrecognised.clear()
        detectedPartner = null
        problem = null
        changed()
    }

    private fun heard(level: Double, speaking: Boolean) {
        if (!running) return
        // -60 dBFS (a quiet room) to -15 (a voice close by).
        this.level = ((level + 60) / 45).coerceIn(0.0, 1.0)
        hearsSpeech = speaking
        if (engine == Engine.LIVE) endLiveCaptions(all = false)
        if (engine == Engine.SONIOX) {
            expireSentences()
            translateAhead()
        }
        changed()
    }

    // The Tôi nói button

    /** The button went down (true) or up. Pressed before listening started, listening starts. */
    fun talk(down: Boolean) {
        wantsToTalk = down
        if (!running) {
            if (down) scope.launch { start() }
            return
        }
        if (talking == down) return
        if (down) {
            // The user speaks now: the phone stops reading what it was reading.
            stopSpeaking()
            if (engine == Engine.LIVE) {
                openPartnerSession()
                if (liveToPartner == null) {
                    problem = "Dịch liền chưa nói được ${partnerLanguage.name}. Đổi Cách dịch sang Chính xác (menu ⋯)."
                    wantsToTalk = false
                    return
                }
            }
            if (engine == Engine.SONIOX) {
                openSonioxPartner()
                myVoice.began(trails[true]?.end ?: 0.0)
                // Whoever spoke in the room has stopped: their sentence ends now, as at Soniox's end of a
                // sentence, which it does not send while the user talks on.
                if (sonioxUsesGPT) transcripts[true]?.endHere()?.forEach { finishSentence(it, true) }
            }
        } else if (engine == Engine.LIVE) {
            // The sound said just before the button went up, then a moment of silence lets the last words through.
            mineTailUntil = millis() + MINE_TAIL
            partnerTailUntil = millis() + 1500
        } else if (engine == Engine.SONIOX) {
            // The sound said just before the button went up, then the user's last words are settled and
            // translated at once, not after a pause.
            mineTailUntil = millis() + MINE_TAIL
            val session = sonioxToPartner
            scope.launch {
                delay(MINE_TAIL + 50)
                if (!running || talking) return@launch
                myVoice.ended(trails[true]?.end ?: 0.0)
                if (sonioxToPartner === session) session?.finalize()
            }
        }
        talking = down
        val l = listener
        if (engine == Engine.ACCURATE && l != null) {
            audio.onAudioThread { if (down) l.beginMine() else l.endMine() }
        }
        changed()
    }

    // Chính xác: sentence by sentence

    /** Someone else finished a sentence. */
    private fun heard(utterance: SpeechSegmenter.Utterance) {
        if (engine != Engine.ACCURATE || apiKey == null) return
        enqueue(utterance.samples, mine = false, at = millis() - (utterance.duration * 1000).toLong())
    }

    /** The user let go of the button (`last`), or a long turn goes on piece by piece. */
    private fun said(samples: ShortArray, first: Boolean, last: Boolean) {
        if (apiKey == null) return
        if (first) heardThisTurn = false
        val seconds = samples.size.toDouble() / PCM.RATE
        // Nothing louder than the room: the button was tapped, nobody spoke.
        var loudest = -100.0
        var start = 0
        while (start < samples.size - SpeechSegmenter.FRAME) {
            loudest = maxOf(loudest, PCM.dbfs(samples, start, start + SpeechSegmenter.FRAME))
            start += SpeechSegmenter.FRAME
        }
        if (seconds < 0.5 || loudest <= -50) {
            if (last && !heardThisTurn) problem = "Chưa nghe thấy bạn nói. Giữ nút Tôi nói trong lúc nói nhé."
            return
        }
        heardThisTurn = true
        enqueue(samples, mine = true, at = millis() - (seconds * 1000).toLong())
    }

    private fun enqueue(samples: ShortArray, mine: Boolean, at: Long) {
        val entry = Entry(
            UUID.randomUUID().toString(), at, mine, if (mine) Language.vietnamese.code else "", "", "",
            if (mine) partnerLanguage.code else Language.vietnamese.code, State.Recognizing,
        )
        entries.add(entry)
        services.warmUp()
        // The user's own words go first (in the order said): the room is waiting for them.
        val place = if (mine) waiting.indexOfFirst { !it.mine }.let { if (it < 0) waiting.size else it } else waiting.size
        waiting.add(place, Waiting(entry.id, samples, mine))
        changed()
        pump()
    }

    private fun pump() {
        while (inFlight < PARALLEL && waiting.isNotEmpty()) {
            val next = waiting.removeAt(0)
            inFlight += 1
            scope.launch {
                try {
                    recognise(next.id, next.samples)
                } finally {
                    inFlight -= 1
                    pump()
                }
            }
        }
    }

    private suspend fun recognise(id: String, samples: ShortArray) {
        val apiKey = apiKey ?: return
        val seconds = samples.size.toDouble() / PCM.RATE
        val wav = withContext(Dispatchers.Default) { PCM.wav(samples) }
        val began = millis()
        val transcript = try {
            services.transcribe(apiKey, wav, recognitionPrompt())
        } catch (e: CancellationException) {
            remove(id)
            throw e
        } catch (e: Exception) {
            unrecognised[id] = samples
            update(id) { it.state = State.Failed(e.message ?: "Lỗi không rõ.") }
            return
        }
        log(id, "nhận ${SpeedLog.seconds((millis() - began) / 1000.0)} · câu dài ${SpeedLog.seconds(seconds)}")
        unrecognised.remove(id)
        charge { it.transcribed(seconds) }
        val entry = entries.firstOrNull { it.id == id } ?: return
        val text = transcript.text
        // Noise the recognizer wrote words for, or the phone's own voice heard again.
        val said = entry.at / 1000.0
        if (TranscriptFilter.isNoise(text, seconds) || (!entry.mine && echo.isEcho(text, said, said + seconds))) {
            if (entry.mine) problem = "Chưa nghe rõ bạn nói. Thử lại, nói gần điện thoại hơn."
            remove(id)
            return
        }
        val language = language(text, transcript.language)
        update(id) {
            it.text = text
            it.language = language
        }
        translate(id)
    }

    private suspend fun translate(id: String) {
        val apiKey = apiKey ?: return
        val entry = entries.firstOrNull { it.id == id } ?: return
        // A Vietnamese speaker in the room: shown as they said it.
        if (!entry.mine && entry.isVietnamese) {
            update(id) {
                it.target = Language.vietnamese.code
                it.state = State.Done
            }
            record(id)
            return
        }
        if (!entry.mine) noteSpoken(entry.language, entry.text)
        val target = if (entry.mine) partnerLanguage else Language.vietnamese
        update(id) {
            it.target = target.code
            it.state = State.Translating
        }
        // What the partners hear is the user's own words: the app's translation model. The others'
        // words are only read by the user: the cheaper model by default.
        val model = if (entry.mine) settings.model else quality.model
        val began = millis()
        try {
            val result = gptTranslation(entry, target, model, apiKey)
            charge { it.used(result.usage, model) }
            log(id, "GPT ${name(model)} ${SpeedLog.seconds((millis() - began) / 1000.0)}")
            update(id) {
                it.translation = result.text
                it.state = State.Done
            }
            record(id)
            if (entry.mine || readAloud) say(result.text, entry.mine, target, entry = id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(id) { it.state = State.Failed(e.message ?: "Lỗi không rõ.") }
        }
    }

    fun retry(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.state !is State.Failed) return
        if (apiKey == null) apiKey = settings.apiKey
        val samples = unrecognised[id]
        if (samples != null) {
            update(id) { it.state = State.Recognizing }
            scope.launch { recognise(id, samples) }
        } else if (entry.text.isNotEmpty()) {
            scope.launch { translate(id) }
        }
    }

    /** Reads a translation aloud again: at once, in Chỉ chữ too, and after listening has stopped. */
    fun repeatAloud(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.translation.isEmpty()) return
        if (apiKey == null) apiKey = settings.apiKey
        val language = language(entry.target)
        if (running) say(entry.translation, entry.mine, language, requested = true) else sayAlone(entry.translation, language)
    }

    /** A foreign sentence long enough to tell: "Tự động" now means its language. */
    private fun noteSpoken(code: String, text: String) {
        if (code == Language.vietnamese.code || TranscriptFilter.syllableCount(text) < 4) return
        val language = Language.named(code) ?: LanguageDetector.language(text) ?: return
        detectedPartner = language
        if (Language.named(language.code) != null) settings.lastPartnerCode = language.code
    }

    /**
     * What `entry` is translated with: who said it, the meeting's latest sentences before it (those
     * still being translated too: several are at once) with who said them and their translations once
     * done (names and terms stay the same from one sentence to the next), and the topic.
     */
    private fun spokenContext(entry: Entry): SpokenContext {
        val lines = entries.filter { it.at < entry.at && it.text.isNotEmpty() }.takeLast(CONTEXT_LINES).map {
            SpokenContext.Line(SpokenContext.speaker(it.mine, it.speaker), it.text, if (it.state == State.Done) it.translation else "")
        }
        return SpokenContext(SpokenContext.speaker(entry.mine, entry.speaker), lines, topic)
    }

    /**
     * GPT's translation of `entry`, asked a second time alongside when the first answer is slow:
     * whichever comes first is taken and the other cancelled. It fails only once both have.
     */
    private suspend fun gptTranslation(entry: Entry, target: Language, model: TranslationModel, key: String): Translation = coroutineScope {
        val translate = services.translate
        val text = entry.text
        val context = spokenContext(entry)
        val patience = if (model == TranslationModel.LUNA) LUNA_PATIENCE else SOL_PATIENCE
        val answers = Channel<Result<Translation>>(Channel.UNLIMITED)
        val asks = ArrayList<Job>()
        fun ask() {
            // The second on a connection of its own: a stalled one would hold it too.
            val spare = asks.isNotEmpty()
            asks.add(launch {
                try {
                    answers.trySend(Result.success(translate(key, text, target, context, model, spare)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    answers.trySend(Result.failure(e))
                }
            })
        }
        ask()
        var failures = 0
        try {
            var translation: Translation? = null
            while (translation == null) {
                val answer = if (asks.size == 1) withTimeoutOrNull((patience * 1000).toLong()) { answers.receive() } else answers.receive()
                if (answer == null) {
                    log(entry.id, "GPT chưa xong sau ${SpeedLog.seconds(patience)}: hỏi thêm lần nữa")
                    ask()
                    continue
                }
                translation = answer.getOrNull()
                if (translation != null) break
                failures += 1
                if (failures == asks.size) throw answer.exceptionOrNull() ?: TranslateError.network()
            }
            translation!!
        } finally {
            asks.forEach { it.cancel() }
        }
    }

    /** Names and terms the recognizer should spell right: the glossary only (the last sentences once had Vietnamese written in Chinese). */
    private fun recognitionPrompt(): String {
        val terms = ArrayList<String>()
        for (line in settings.glossary.lines()) {
            for (side in line.split("=")) {
                val term = side.trim()
                if (term.isNotEmpty() && term.length <= 40) terms.add(term)
            }
        }
        return if (terms.isEmpty()) "" else "Thuật ngữ / Terms: " + terms.take(40).joinToString(", ")
    }

    /** Into the minutes, in the order things were said. */
    private fun record(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.text.isEmpty()) return
        val line = Meeting.Line(entry.at, entry.mine, entry.language, entry.text, entry.translation, entry.target,
            entry.speaker.ifEmpty { null })
        val index = meeting.lines.indexOfLast { it.at <= line.at }.let { if (it < 0) 0 else it + 1 }
        meeting.lines.add(index, line)
        billLive()
        meetings.save(meeting)
    }

    /** Into the meeting's cost (kept with it). */
    private fun charge(change: (CostMeter) -> Unit) {
        val cost = meeting.cost ?: CostMeter().also { meeting.cost = it }
        change(cost)
    }

    private fun billLive() {
        if (unbilledLive <= 0) return
        val seconds = unbilledLive
        val soniox = engine == Engine.SONIOX
        unbilledLive = 0.0
        charge { it.streamed(seconds, soniox) }
    }

    /** What this meeting has cost so far, estimated. */
    val spent: Double
        get() {
            val rate = if (engine == Engine.SONIOX) CostMeter.Rate.SONIOX_PER_MINUTE else CostMeter.Rate.LIVE_PER_MINUTE
            return (meeting.cost?.dollars ?: 0.0) + unbilledLive / 60 * rate
        }

    /** Only a change is drawn: Soniox sends its guesses again with every message, mostly the same. */
    private fun update(id: String, change: (Entry) -> Unit) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        fun look() = listOf(entry.language, entry.text, entry.translation, entry.target, entry.state, entry.speaker)
        val before = look()
        change(entry)
        if (look() != before) changed()
    }

    private fun remove(id: String) {
        entries.removeAll { it.id == id }
        unrecognised.remove(id)
        ahead.remove(id)?.job?.cancel()
        changed()
    }

    // Reading aloud

    /**
     * Each translation is fetched as soon as it is ready and read after the one before, in the order the
     * sentences were said (`entry`: one translated sooner waits a little for those said before it on the
     * same side). `mine`: the user's words, in the partners' language; otherwise the others', in
     * Vietnamese. `requested`: Đọc lại, read at once, even in Chỉ chữ and without waiting for a pause.
     */
    private fun say(text: String, mine: Boolean, language: Language, requested: Boolean = false, entry: String? = null) {
        val apiKey = apiKey ?: return
        if (!running) return
        // Counted at once, not once queued: the others' sentence that waits for it must not go first meanwhile.
        if (mine && !requested) mineQueued += 1
        val generation = speechGeneration
        val ready = millis()
        val sound = voiceTask(text, voice, language, apiKey)
        if (entry == null) {
            queueSpeech(sound, text, mine, requested, generation, ready, null)
            return
        }
        scope.launch {
            waitForEarlier(entry)
            queueSpeech(sound, text, mine, requested, generation, ready, entry)
        }
    }

    /** Read aloud once: a sentence read from Soniox's translation is not read again when GPT's comes. */
    private fun sayOnce(entry: Entry) {
        if (!voiced.add(entry.id)) return
        say(entry.translation, entry.mine, language(entry.target), entry = entry.id)
    }

    /** Read after what is already queued (unless the queue was dropped meanwhile). */
    private fun queueSpeech(sound: VoiceStream, text: String, mine: Boolean, requested: Boolean, generation: Int, ready: Long, entry: String?) {
        if (generation != speechGeneration) return
        var serial = 0
        val mineBefore = mineQueued
        if (!mine && !requested) {
            theirsQueued += 1
            serial = theirsQueued
        }
        val previous = speechChain
        speechChain = scope.launch {
            previous?.join()
            if (generation != speechGeneration) return@launch
            play(sound, text, generation, mine, requested, ready, serial, mineBefore, entry)
        }
    }

    /** The user's words still being heard, recognised or translated: they are read before anything of the others' that waits. */
    private val mineSettling: Boolean
        get() = entries.any { it.mine && (it.state == State.Hearing || it.state == State.Recognizing || it.state == State.Translating) }

    /** Until the sentences said shortly before `id` on its side are translated, eight seconds at most. */
    private suspend fun waitForEarlier(id: String) {
        repeat(80) {
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0) return
            val entry = entries[index]
            val earlierBusy = entries.subList(0, index).any {
                it.mine == entry.mine && entry.at - it.at < 60_000 &&
                    (it.state == State.Recognizing || it.state == State.Translating)
            }
            if (!earlierBusy) return
            delay(100)
        }
    }

    /** Read again after listening stopped: the speaker only. */
    private fun sayAlone(text: String, language: Language) {
        val voice = voice
        if (isOpenAIVoice(voice) && apiKey == null) {
            problem = TranslateError.noKey().message
            return
        }
        aloneTask?.cancel()
        audio.stopAlone()
        val sound = voiceTask(text, voice, language, apiKey ?: "")
        aloneTask = scope.launch {
            val result = sound.whole()
            if (running) return@launch
            result.onSuccess { pcm -> audio.playAlone(pcm) {} }
            result.onFailure { error -> if (error !is CancellationException) problem = voiceProblem(error) }
            meetings.save(meeting)
        }
    }

    /** The voice for `text`: the one made before, or a new one, its sound coming in as it is made. */
    private fun voiceTask(text: String, voice: String, language: Language, apiKey: String): VoiceStream {
        val key = VoiceCache.key(text, voice, language.code)
        voiceCache.get(key)?.let { return VoiceStream(it) }
        val sound = VoiceStream()
        val speak = services.speak
        scope.launch {
            try {
                val pcm = speak(apiKey, text, voice, language) { sound.append(it) }
                sound.finish(pcm)
                voiceCache.put(key, pcm)
                if (isOpenAIVoice(voice)) charge { it.voiced(PCM.seconds(pcm)) }
            } catch (e: CancellationException) {
                sound.finish(null, e)
                throw e
            } catch (e: Exception) {
                sound.finish(null, e)
            }
        }
        return sound
    }

    private fun voiceProblem(error: Throwable): String =
        if (error is PhoneVoiceFailure) error.message ?: "" else
            "Không đọc to được: ${error.message ?: ""} Bản dịch vẫn hiện trên màn hình."

    /**
     * `serial`: the others' sentence's place in the queue (0 for the user's and Đọc lại). `mineBefore`: how
     * many of the user's sentences had been queued before it.
     */
    private suspend fun play(
        sound: VoiceStream, text: String, generation: Int, mine: Boolean, requested: Boolean, ready: Long, serial: Int,
        mineBefore: Int, entry: String?,
    ) {
        // Never over the user: a translation ready while they speak waits until they let go.
        while (talking && running) delay(100)
        // The user's words go first (the partners wait for them): the others' sentence waits while the
        // user's are still being translated, and stays on the screen unread once one of them is queued.
        if (serial > 0) {
            while ((talking || mineSettling) && running && generation == speechGeneration) delay(100)
            if (mineQueued != mineBefore && generation == speechGeneration) {
                log(entry, "bỏ đọc: câu của bạn đọc trước")
                return
            }
        }
        // Like an interpreter, the others' words are read once the room pauses (Chính xác stops
        // listening while it reads). A sentence that finds no pause within ten seconds of being
        // translated gets the text only.
        if (!mine && !requested && engine == Engine.ACCURATE) {
            val latest = ready + 10_000
            while (hearsSpeech && running && millis() < latest && generation == speechGeneration) delay(100)
            if (hearsSpeech) return
        }
        if (!running || generation != speechGeneration || !(mine || requested || readAloud)) return
        // Read as soon as there is enough to go on with, not once all of it is made.
        var next = sound.sound(0)
        while (!next.finished && next.pcm.size < STARTING_SOUND && running && generation == speechGeneration) {
            delay(20)
            next = sound.sound(0)
        }
        if (!running || generation != speechGeneration) return
        if (next.pcm.size <= 1) {
            val error = next.failure
            if (error != null && error !is CancellationException) problem = voiceProblem(error)
            return
        }
        // A room that talks on: an old sentence is left on the screen while newer ones wait to be read,
        // and the voice reads faster while it is behind (the pitch stays).
        val behind = (millis() - ready) / 1000.0
        val newer = if (serial > 0) theirsQueued - serial else 0
        if (newer > 0 && behind > STALE) {
            log(entry, "bỏ đọc: chậm ${SpeedLog.seconds(behind)}, $newer câu mới hơn đang chờ")
            return
        }
        val rate = when {
            serial == 0 -> 1f
            newer >= 2 || behind > 5 -> 1.35f
            newer >= 1 || behind > 2.5 -> 1.2f
            else -> 1f
        }
        audio.setRate(rate)
        if (serial > 0) {
            val faster = String.format(Locale.US, " · nhanh %.2f×", rate).replace(".", ",")
            log(entry, "đọc sau ${SpeedLog.seconds(behind)}" + (if (rate > 1) faster else "") + (if (newer > 0) " · $newer câu chờ" else ""))
        }
        beginAloud(mine)
        // What the room's recognizer can hear of it: with Soniox, the room is sent silence while the user's
        // words are read, and while anything is read without echo cancelling.
        val heard = engine != Engine.SONIOX || (!mine && echoCancelling)
        if (heard) echo.spoke(text, now())
        var played = 0
        var playing = 0
        while (running && generation == speechGeneration) {
            // Whole samples only: the odd byte goes with the next part.
            val whole = next.pcm.size / 2 * 2
            if (whole > 0) {
                played += whole
                playing += 1
                audio.play(next.pcm.copyOf(whole)) { playing -= 1 }
            }
            if (next.finished) break
            delay(20)
            next = sound.sound(played)
        }
        // Until the last part has been heard (or playback was stopped).
        val heardBy = millis() + (PCM.seconds(played) * 1000).toLong() + 2000
        while (playing > 0 && millis() < heardBy) delay(20)
        if (heard) echo.stopped(now())
        endAloud(mine)
        val error = next.failure
        if (error != null && error !is CancellationException && generation == speechGeneration) problem = voiceProblem(error)
    }

    /** The button went down, or listening stopped: what is being read, or waiting to be, stops. */
    private fun stopSpeaking() {
        speechGeneration += 1
        speechChain = null
        audio.stopPlayback()
    }

    /**
     * The microphone hears the phone's speaker. Chính xác listens to nobody meanwhile (it would
     * translate its own voice); Dịch liền and Soniox cancel the echo, or send silence (see `stream`).
     */
    private fun beginAloud(mine: Boolean) {
        playingAloud += 1
        if (mine) playingMine += 1
        speaking = true
        speakingMine = playingMine > 0
        val l = listener
        if (engine == Engine.ACCURATE && playingAloud == 1 && l != null) audio.onAudioThread { l.mute() }
        changed()
    }

    private fun endAloud(mine: Boolean) {
        // The room's echo dies down a moment after the voice.
        scope.launch {
            delay(400)
            playingAloud = maxOf(0, playingAloud - 1)
            if (mine) playingMine = maxOf(0, playingMine - 1)
            speakingMine = playingMine > 0
            if (playingAloud == 0) {
                speaking = false
                listener?.let { l -> audio.onAudioThread { l.unmute() } }
            }
            changed()
        }
    }

    // Dịch liền

    private fun openLive() {
        liveRetries = 0
        othersGate.reset()
        liveCaptions.clear()
        liveEntries.clear()
        liveToVietnamese = makeSession(Language.vietnamese.code)
        openPartnerSession()
    }

    private fun makeSession(code: String): LiveSession? {
        val apiKey = apiKey ?: return null
        val session = LiveSession(apiKey, code, scope)
        session.onEvent = { live(it, session) }
        session.open()
        return session
    }

    /** The session that speaks the partners' language, opened again when that language changes. */
    private fun openPartnerSession() {
        val code = LiveSession.outputCode(partnerLanguage)
        if (code == null) {
            liveToPartner?.close()
            liveToPartner = null
            return
        }
        val current = liveToPartner
        if (current != null && current.language == code && current.isOpen) return
        liveToPartner?.close()
        liveToPartner = makeSession(code)
    }

    private fun closeLive() {
        liveToVietnamese?.close()
        liveToPartner?.close()
        liveToVietnamese = null
        liveToPartner = null
        if (!draining) closeSoniox()
    }

    private fun closeSoniox() {
        sonioxToVietnamese?.close()
        sonioxToPartner?.close()
        sonioxToVietnamese = null
        sonioxToPartner = null
        transcripts.clear()
        trails.clear()
        origins.clear()
        replayFrom.clear()
    }

    /**
     * Listening stopped: Soniox settles now what it heard (its last words and their translation come in),
     * and the sessions are closed once both have answered, or after two seconds.
     */
    private fun drainSoniox() {
        if (!draining) return
        drained.clear()
        settledAll.clear()
        for ((fromOthers, session) in listOf(true to sonioxToVietnamese, false to sonioxToPartner)) {
            if (session != null && session.isOpen) session.finalize() else drained.add(fromOthers)
        }
        drainJob?.cancel()
        drainJob = scope.launch {
            delay(2000)
            endDrain()
        }
        if (drained.size == 2) endDrain()
    }

    /** What is left is finished as it is known, and the sessions closed. */
    private fun endDrain() {
        if (!draining) return
        draining = false
        drainJob?.cancel()
        drainJob = null
        for (fromOthers in listOf(true, false)) transcripts[fromOthers]?.finish()?.let { finishSentence(it, fromOthers) }
        for (current in captions.values.toList()) finishCaption(current.id)
        captions.clear()
        closeSoniox()
        billLive()
        meetings.save(meeting)
        changed()
    }

    /**
     * The phone's echo cancelling, which keeps its voice out of what the microphone hears, but also
     * favours a voice close to the phone: far voices in a meeting room come through weaker, and are
     * missed or misheard. Only when the phone speaks while it listens: Dịch liền (it speaks the user's
     * words as they talk), and Soniox reading the others' words aloud. Soniox in Chỉ chữ speaks only
     * the user's words, while the room's sound is held back; Chính xác does not listen while it speaks.
     */
    private val echoCancelling: Boolean get() = engine == Engine.LIVE || (engine == Engine.SONIOX && readAloud)

    /** Đọc to or Chỉ chữ chosen while listening: the microphone is opened again with or without it. */
    private fun cancelEchoIfNeeded() {
        try {
            audio.setEchoCancelling(echoCancelling)
            log(null, if (echoCancelling) "Bật khử tiếng vọng (đọc to)" else "Tắt khử tiếng vọng: micro nghe xa hơn")
        } catch (e: Exception) {
            problem = e.message ?: "Không mở được micro."
        }
    }

    /**
     * The microphone's sound goes to the session for whoever speaks: the user's while the button is down
     * (and a moment after, for the sound still on its way), everyone else's otherwise. Dịch liền's room
     * session gets silence meanwhile, so it finishes what it was saying, and is not sent a quiet room (paid
     * by the minute sent, 2 USD an hour). Soniox's room session hears everything (0.18 USD an hour), with
     * GPT the user's turns too, without the user's voice (`myVoice`): a quiet or far voice the level meter took for
     * the room, the first syllable after a pause, a partner answering while the user speaks, are never
     * lost, and Soniox hears for itself where a sentence ends.
     */
    private fun stream(data: ByteArray, speech: Boolean, audible: Boolean) {
        if (!(running || draining) || !engine.streams) return
        val silent = ByteArray(data.size)
        val mine = talking || millis() < mineTailUntil
        if (mine) {
            sendMine(data)
        } else if (engine == Engine.LIVE && millis() < partnerTailUntil) {
            sendMine(silent)
        }
        // The phone speaking the partners' language is not someone in the room to translate; nor is its
        // Vietnamese (Đọc lại) when no echo cancelling takes it out.
        val phoneSpeaks = playingMine > 0 || (playingAloud > 0 && !echoCancelling)
        if (engine == Engine.SONIOX) {
            // Without GPT, Soniox's own translation of a sentence comes after its end, which Soniox sends only
            // after a quiet moment: the room is sent silence during the user's turn, as before.
            sendOthers(if (phoneSpeaks || (mine && !sonioxUsesGPT)) silent else data)
        } else {
            // A sound only some way above the room's noise may be what echo cancelling left of the phone's
            // own voice: sent on only while the phone is silent.
            val quiet = mine || phoneSpeaks
            val voice = speech || (audible && playingAloud == 0)
            for (chunk in othersGate.feed(if (quiet) silent else data, voice && !quiet)) sendOthers(chunk)
        }
    }

    private fun sendOthers(chunk: ByteArray) {
        if (engine == Engine.SONIOX) {
            trails.getOrPut(true) { SoundTrail() }.add(chunk)
            // While it is opened again, the sound waits in the trail.
            (sonioxToVietnamese ?: return).send(chunk)
            sonioxSent[true] = (sonioxSent[true] ?: 0.0) + PCM.seconds(chunk)
            sonioxSentAt[true] = now()
        } else {
            (liveToVietnamese ?: return).send(chunk)
        }
        unbilledLive += PCM.seconds(chunk)
    }

    private fun sendMine(chunk: ByteArray) {
        if (engine == Engine.SONIOX) {
            trails.getOrPut(false) { SoundTrail() }.add(chunk)
            (sonioxToPartner ?: return).send(chunk)
            sonioxSent[false] = (sonioxSent[false] ?: 0.0) + PCM.seconds(chunk)
            sonioxSentAt[false] = now()
        } else {
            (liveToPartner ?: return).send(chunk)
        }
        unbilledLive += PCM.seconds(chunk)
    }

    private fun live(event: LiveEvent, session: LiveSession) {
        if (session !== liveToVietnamese && session !== liveToPartner) return
        val fromOthers = session === liveToVietnamese
        when (event) {
            LiveEvent.Ready -> {
                liveRetries = 0
                if (problem?.startsWith("Mất kết nối") == true) problem = null
            }
            is LiveEvent.Source -> liveSource(event.delta, fromOthers)
            is LiveEvent.Target -> liveTarget(event.delta, fromOthers)
            is LiveEvent.Audio -> {
                if (fromOthers) {
                    // Đọc to only, never over the user, and not a Vietnamese speaker's words again.
                    if (!readAloud || talking || othersSpeakVietnamese) return
                    // Behind a room that talks on: read faster, and past six seconds behind the sound is
                    // let go (the text stays), so the voice is never far after the speaker.
                    val backlog = audio.backlog
                    if (backlog > 6) {
                        if (millis() - lastDropNote > 5000) {
                            lastDropNote = millis()
                            log(null, "Dịch liền: chậm ${SpeedLog.seconds(backlog)}, bỏ bớt tiếng đọc")
                        }
                        return
                    }
                    audio.setRate(if (backlog > 4) 1.35f else if (backlog > 2) 1.2f else 1f)
                    beginAloud(false)
                    audio.play(event.pcm) { endAloud(false) }
                } else {
                    audio.setRate(1f)
                    beginAloud(true)
                    audio.play(event.pcm) { endAloud(true) }
                }
            }
            is LiveEvent.Failed -> {
                val code = event.code
                if (code.contains("api_key") || code.contains("auth") || event.message.contains("api key", true)) {
                    problem = "API key không dùng được cho Dịch liền. Kiểm tra lại trong Cài đặt."
                    stop()
                } else if (code == "model_not_available" || code == "model_not_found") {
                    problem = "Key này chưa dùng được Dịch liền (gpt-realtime-translate). Dùng chế độ Chính xác."
                    stop()
                } else if (code == "network") {
                    problem = "Mất kết nối. Đang nối lại…"
                } else {
                    problem = event.message.ifEmpty { "Dịch liền gặp lỗi ($code)." }
                }
            }
            LiveEvent.Closed -> {
                if (!running || engine != Engine.LIVE) return
                // Dropped (network, a time limit): opened again, a few times. Let go of at once, so a
                // second word of it closing (the server's, then the socket's) opens nothing more.
                session.close()
                if (fromOthers) liveToVietnamese = null else liveToPartner = null
                // What the dropped session was still translating will not come.
                endLiveCaptions(all = true, sides = listOf(fromOthers))
                log(null, "Dịch liền mất kết nối, nối lại")
                liveRetries += 1
                if (liveRetries > 5) {
                    problem = "Không nối lại được Dịch liền. Kiểm tra mạng rồi bấm nút tròn để nghe lại."
                    stop()
                    return
                }
                scope.launch {
                    delay(1000)
                    if (!running || engine != Engine.LIVE) return@launch
                    if (fromOthers) {
                        if (liveToVietnamese == null) liveToVietnamese = makeSession(Language.vietnamese.code)
                    } else {
                        openPartnerSession()
                    }
                }
            }
        }
    }

    /** The sentence being heard live is Vietnamese (a Vietnamese speaker in the room). */
    private val othersSpeakVietnamese: Boolean
        get() {
            val text = liveCaptions[true]?.current?.text ?: return false
            if (text.length < 12) return false
            return LanguageDetector.isVietnamese(text)
        }

    /** More of what was said, live: into its caption, which may end the one before. */
    private fun liveSource(delta: String, fromOthers: Boolean) {
        val router = liveCaptions.getOrPut(fromOthers) { LiveCaptions() }
        val ended = router.heard(delta, now())
        if (ended != null) endLiveCaption(ended, fromOthers)
        router.current?.let { showLiveCaption(it, fromOthers) }
        settleLiveCaptions(fromOthers)
    }

    /**
     * More of the live translation: to the caption it belongs to (one already ended, while its
     * translation is still coming), not always to the one being heard.
     */
    private fun liveTarget(delta: String, fromOthers: Boolean) {
        val router = liveCaptions.getOrPut(fromOthers) { LiveCaptions() }
        val number = router.translated(delta, now())
        val current = router.current
        if (current != null && current.number == number) {
            showLiveCaption(current, fromOthers)
        } else {
            val caption = router.awaiting.firstOrNull { it.number == number }
            val id = liveEntries[fromOthers]?.get(number)
            val entry = id?.let { i -> entries.firstOrNull { it.id == i } }
            if (caption != null && entry != null && (entry.state == State.Hearing || entry.state == State.Translating)) {
                update(entry.id) { it.translation = caption.translation.trim() }
            }
        }
        settleLiveCaptions(fromOthers)
    }

    /** Dịch liền: the captions being heard end after a quiet moment (`all`: everything ends now). */
    private fun endLiveCaptions(all: Boolean, sides: List<Boolean> = listOf(true, false)) {
        for (fromOthers in sides) {
            val router = liveCaptions[fromOthers] ?: continue
            val ended = if (all) router.finishAll(now()) else router.tick(now())
            if (ended != null) endLiveCaption(ended, fromOthers)
            settleLiveCaptions(fromOthers)
        }
    }

    private fun showLiveCaption(caption: LiveCaptions.Caption, fromOthers: Boolean) {
        val text = caption.text.trim()
        val translation = caption.translation.trim()
        val known = liveEntries[fromOthers]?.get(caption.number)
        if (known != null && entries.any { it.id == known }) {
            update(known) {
                it.text = text
                it.translation = translation
            }
            captions[fromOthers] = Caption(known, millis())
            return
        }
        val entry = Entry(
            UUID.randomUUID().toString(), millis(), !fromOthers, if (fromOthers) "" else Language.vietnamese.code, text, translation,
            if (fromOthers) Language.vietnamese.code else partnerLanguage.code, State.Hearing,
        )
        entries.add(entry)
        services.warmUp()
        liveEntries.getOrPut(fromOthers) { HashMap() }[caption.number] = entry.id
        captions[fromOthers] = Caption(entry.id, millis())
        changed()
    }

    /**
     * A caption's words are all in (its translation may still be coming). The others' are translated
     * again by GPT, with the meeting's latest sentences and the glossary, for the text (the live voice
     * has already read them); the user's are kept as the partners heard them.
     */
    private fun endLiveCaption(caption: LiveCaptions.Caption, fromOthers: Boolean) {
        val id = liveEntries[fromOthers]?.get(caption.number) ?: return
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (captions[fromOthers]?.id == id) captions.remove(fromOthers)
        val text = caption.text.trim()
        if (!fromOthers) {
            update(id) { it.text = text }
            return
        }
        // A piece of translation of nothing heard: the end of a sentence already translated.
        if (text.isEmpty()) {
            remove(id)
            return
        }
        val language = entry.language.ifEmpty { language(text, null) }
        log(id, "Dịch liền · $language · ${TranscriptFilter.syllableCount(text)} âm tiết · câu dài ${SpeedLog.seconds(now() - caption.started)}")
        update(id) {
            it.text = text
            it.language = language
        }
        // A Vietnamese speaker in the room: shown as they said it.
        if (language == Language.vietnamese.code) {
            finishCaption(id)
            return
        }
        update(id) { it.state = State.Translating }
        noteSpoken(language, text)
        scope.launch { translateAgain(id, Language.vietnamese, speaks = false) }
    }

    /** Captions whose live translation is all in: the user's are kept now. */
    private fun settleLiveCaptions(fromOthers: Boolean) {
        val router = liveCaptions[fromOthers] ?: return
        for (caption in router.takeReleased()) {
            val id = liveEntries[fromOthers]?.remove(caption.number) ?: continue
            if (fromOthers) continue
            update(id) {
                it.text = caption.text.trim()
                it.translation = caption.translation.trim()
            }
            finishCaption(id)
        }
    }

    private fun finishCaption(id: String) {
        ahead.remove(id)?.job?.cancel()
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.state != State.Hearing) return
        val text = entry.text.trim()
        var translation = entry.translation.trim()
        if (text.isEmpty() && translation.isEmpty()) {
            remove(id)
            return
        }
        val language = entry.language.ifEmpty { language(text, null) }
        // A Vietnamese speaker in the room: the "translation" is the same sentence again.
        if (!entry.mine && language == Language.vietnamese.code) translation = ""
        update(id) {
            it.text = text
            it.translation = translation
            it.language = language
            it.state = State.Done
        }
        if (!entry.mine) noteSpoken(language, text)
        record(id)
    }

    // Soniox

    private fun openSoniox() {
        liveRetries = 0
        othersGate.reset()
        transcripts.clear()
        sonioxEntries.clear()
        trails.clear()
        origins.clear()
        replayFrom.clear()
        myVoice = MyVoiceFilter()
        sonioxLags = SonioxLags(now())
        sonioxToVietnamese = makeSoniox(Language.vietnamese.code)
        openSonioxPartner()
    }

    /**
     * Everything the others say is translated into Vietnamese; the user's words into the partners'
     * language. Soniox hears any language, so it is told which to expect only as a hint. The room's
     * session tells its speakers apart.
     */
    private fun makeSoniox(target: String): SonioxSession? {
        val key = settings.sonioxKey ?: return null
        val room = target == Language.vietnamese.code
        val partner = SonioxEvent.code(partnerLanguage)
        val hints = if (room) listOf(partner, Language.vietnamese.code) else listOf(Language.vietnamese.code)
        val session = SonioxSession(key, target, hints, settings.glossary, scope, speakers = room, about = sonioxAbout, url = sonioxUrl)
        session.onEvent = { soniox(it, session) }
        sonioxSent[room] = 0.0
        sonioxSentAt.remove(room)
        // Soniox numbers a new session's speakers anew.
        if (room) myVoice.newSession()
        session.open()
        // Opened again after a drop: sent at once what the one before had not settled, and what was said meanwhile.
        val trail = trails[room]
        val from = replayFrom.remove(room)
        if (from != null && trail != null) {
            val missed = trail.since(from)
            origins[room] = missed.firstOrNull()?.at ?: trail.end
            for (chunk in missed) {
                session.send(chunk.data)
                sonioxSent[room] = (sonioxSent[room] ?: 0.0) + PCM.seconds(chunk.data)
                unbilledLive += PCM.seconds(chunk.data)
            }
            if (missed.isNotEmpty()) {
                sonioxSentAt[room] = now()
                log(null, "Soniox nối lại: gửi lại ${SpeedLog.seconds(trail.end - (origins[room] ?: 0.0))} tiếng chưa chốt")
            }
        } else {
            origins[room] = trail?.end ?: 0.0
        }
        return session
    }

    /**
     * What Soniox is told about the talk, so it hears and translates its words right: the setting, the
     * meeting's topic and who the user is (Cài đặt › Về bạn).
     */
    private val sonioxAbout: List<Pair<String, String>>
        get() {
            val about = arrayListOf("setting" to "Business meeting or talk between a Vietnamese professional and foreign partners")
            if (topic.isNotEmpty()) about.add("topic" to topic)
            val profile = settings.profile.trim()
            if (profile.isNotEmpty()) about.add("user" to profile.take(300))
            return about
        }

    /** The session for the user's words, opened again when the partners' language changes. */
    private fun openSonioxPartner() {
        val code = SonioxEvent.code(partnerLanguage)
        val current = sonioxToPartner
        if (current != null && current.target == code && current.isOpen) return
        sonioxToPartner?.close()
        transcripts.remove(false)
        sonioxEntries.remove(false)
        sonioxToPartner = makeSoniox(code)
    }

    /**
     * Each sentence Soniox finishes is translated again by GPT (an OpenAI key is there), so sentences
     * are cut by speaker and language as they are said (see `SonioxTranscript.splits`).
     */
    private val sonioxUsesGPT: Boolean get() = !apiKey.isNullOrEmpty()

    private fun newTranscript(target: String) = SonioxTranscript(target).also { it.splits = sonioxUsesGPT }

    private fun soniox(event: SonioxEvent, session: SonioxSession) {
        if (session !== sonioxToVietnamese && session !== sonioxToPartner) return
        val fromOthers = session === sonioxToVietnamese
        when (event) {
            is SonioxEvent.Tokens -> {
                liveRetries = 0
                if (problem?.startsWith("Mất kết nối") == true) problem = null
                // Nothing new, nothing guessed to take back.
                if (event.tokens.isEmpty() && transcripts[fromOthers]?.showsGuess != true) return
                val transcript = transcripts.getOrPut(fromOthers) { newTranscript(session.target) }
                val heard = if (fromOthers) myVoice.filter(event.tokens, origins[true] ?: 0.0) else event.tokens
                val finished = transcript.take(heard)
                // How far the words Soniox settled are behind the sound sent to it.
                val lag = transcript.settledEnd?.let { (sonioxSent[fromOthers] ?: 0.0) - it }
                for (sentence in finished) finishSentence(sentence, fromOthers, lag)
                showPreviews(transcript.previews, fromOthers)
                val current = transcript.current
                // Cut by GPT's sentences, a piece of translation no sentence took is not shown on its own.
                if (if (transcript.splits) current.text.isNotEmpty() else !current.isEmpty) {
                    showSentence(current, fromOthers)
                } else {
                    // Words guessed, then taken back (or taken out as the user's): nothing of them is kept.
                    val caption = captions[fromOthers]
                    if (caption != null && entries.firstOrNull { it.id == caption.id }?.state == State.Hearing) {
                        captions.remove(fromOthers)
                        heardTimes.remove(caption.id)
                        remove(caption.id)
                    }
                }
                if (fromOthers) translateAhead()
                // Stopped: this side has answered once all it heard is settled and nothing of it is left.
                if (draining) {
                    if (event.tokens.any { it.isFin }) settledAll.add(fromOthers)
                    if (fromOthers in settledAll && transcript.current.text.isEmpty()) {
                        drained.add(fromOthers)
                        if (drained.size == 2) endDrain()
                    }
                }
            }
            is SonioxEvent.Processed -> if (fromOthers) noteProcessed(event.finalMs, event.totalMs)
            SonioxEvent.Finished -> Unit
            is SonioxEvent.Failed -> when (event.code) {
                401 -> {
                    problem = "Key Soniox không dùng được. Kiểm tra lại trong Cài đặt › Soniox."
                    stop()
                }
                402, 429 -> {
                    problem = "Tài khoản Soniox hết tiền hoặc đang bị giới hạn. Nạp thêm ở console.soniox.com."
                    stop()
                }
                // Stopped, its last words still coming in: nothing is opened again.
                -1 -> if (running) problem = "Mất kết nối. Đang nối lại…"
                else -> problem = if (event.message.isEmpty()) "Soniox gặp lỗi (${event.code})." else "Soniox: ${event.message}"
            }
            SonioxEvent.Closed -> {
                if (draining) {
                    drained.add(fromOthers)
                    if (drained.size == 2) endDrain()
                    return
                }
                if (!running || engine != Engine.SONIOX) return
                // Dropped (network, a time limit): what it settled stays, and it is opened again at once (then a
                // few more times, waiting longer), sent again what it had not settled. Let go of at once, so a
                // second word of it closing opens nothing more.
                session.close()
                replayFrom[fromOthers] = (origins[fromOthers] ?: 0.0) + (transcripts[fromOthers]?.settledEnd ?: 0.0)
                transcripts[fromOthers]?.finishSettled()?.let { finishSentence(it, fromOthers) }
                transcripts.remove(fromOthers)
                sonioxEntries.remove(fromOthers)
                if (fromOthers) sonioxToVietnamese = null else sonioxToPartner = null
                log(null, "Soniox mất kết nối, nối lại")
                liveRetries += 1
                if (liveRetries > RECONNECT_DELAYS.size) {
                    problem = "Không nối lại được Soniox. Kiểm tra mạng rồi bấm nút tròn để nghe lại."
                    stop()
                    return
                }
                val wait = (RECONNECT_DELAYS[liveRetries - 1] * 1000).toLong()
                scope.launch {
                    if (wait > 0) delay(wait)
                    if (!running || engine != Engine.SONIOX) return@launch
                    if (fromOthers) {
                        if (sonioxToVietnamese == null) sonioxToVietnamese = makeSoniox(Language.vietnamese.code)
                    } else {
                        openSonioxPartner()
                    }
                }
            }
        }
    }

    /** The sentence being said, word by word as Soniox hears and translates it. */
    private fun showSentence(sentence: SonioxTranscript.Sentence, fromOthers: Boolean): String {
        val now = millis()
        val speaker = if (fromOthers) sentence.speaker else ""
        val current = captions[fromOthers]
        val shown = current?.let { c -> entries.firstOrNull { it.id == c.id } }
        if (current != null && shown != null) {
            val grew = shown.text != sentence.text
            val translation = if (fromOthers) shownTranslation(current.id, sentence) else sentence.translation
            update(current.id) {
                it.text = sentence.text
                it.translation = translation
                if (sentence.language.isNotEmpty()) it.language = sentence.language
                if (speaker.isNotEmpty()) it.speaker = speaker
            }
            // When its words last changed: how long the speaker has said nothing more.
            captions[fromOthers] = Caption(current.id, if (grew) now else current.last)
            if (fromOthers) noteSeen(current.id, sentence)
            noteHeard(current.id, sentence, fromOthers)
            return current.id
        }
        val entry = Entry(
            UUID.randomUUID().toString(), now, !fromOthers,
            if (sentence.language.isEmpty() && !fromOthers) Language.vietnamese.code else sentence.language,
            sentence.text, sentence.translation,
            if (fromOthers) Language.vietnamese.code else partnerLanguage.code, State.Hearing, speaker,
        )
        entries.add(entry)
        // Someone started a sentence: by its end, GPT's connection is open.
        if (sonioxUsesGPT) services.warmUp()
        captions[fromOthers] = Caption(entry.id, now)
        if (fromOthers) noteSeen(entry.id, sentence)
        noteHeard(entry.id, sentence, fromOthers)
        changed()
        return entry.id
    }

    /** When the room sentence's newest words came: how long a word has waited for its translation. */
    private fun noteSeen(id: String, sentence: SonioxTranscript.Sentence) {
        if (!sonioxUsesGPT) return
        val end = sentence.endMs ?: return
        val state = ahead.getOrPut(id) { Ahead() }
        if (end > (state.seen.lastOrNull()?.first ?: Int.MIN_VALUE)) state.seen.add(end to now())
    }

    /**
     * What a room sentence shows of its translation: Soniox's, or GPT's asked ahead once it has come
     * further, and then until Soniox's has come well past it (the text does not jump between the two as
     * each comes a little further).
     */
    private fun shownTranslation(id: String, sentence: SonioxTranscript.Sentence): String {
        val state = ahead[id] ?: return sentence.translation
        state.sonioxTranslation = sentence.translation
        state.sonioxCovered = sentence.coveredMs
        return shown(state)
    }

    private fun shown(state: Ahead): String {
        val soniox = state.sonioxTranslation ?: ""
        val answer = state.answer ?: return soniox
        val reached = state.sonioxCovered ?: Int.MIN_VALUE
        if (state.showing) {
            if (reached > answer.endMs + AHEAD_MARGIN) state.showing = false
        } else if (soniox.isEmpty() || answer.endMs > reached) {
            state.showing = true
        }
        return if (state.showing) answer.translation else soniox
    }

    /**
     * The room's sentence being said is given to GPT to translate (see `ahead`): once its first word
     * without a translation has waited `AHEAD_WAIT` on the screen, as soon as more is said while GPT's
     * translation is the one shown, or when the speaker pauses (its translation is then ready if the
     * sentence ends there). One question at a time; Luna, the quick one, whatever the final's model.
     */
    private fun translateAhead() {
        if (!running || engine != Engine.SONIOX || !sonioxUsesGPT) return
        val apiKey = apiKey ?: return
        val caption = captions[true] ?: return
        val state = ahead[caption.id] ?: return
        if (state.asked != null || state.asks >= AHEAD_ASKS) return
        val transcript = transcripts[true] ?: return
        val entry = entries.firstOrNull { it.id == caption.id } ?: return
        if (entry.state != State.Hearing) return
        val sentence = transcript.current
        val text = sentence.text.trim()
        val end = sentence.endMs ?: return
        if (text.isEmpty() || text == state.answer?.text || sentence.language == Language.vietnamese.code) return
        val reached = maxOf(sentence.coveredMs ?: Int.MIN_VALUE, state.answer?.endMs ?: Int.MIN_VALUE)
        val waited = state.seen.firstOrNull { it.first > reached }?.let { now() - it.second } ?: 0.0
        // At a pause the answer is asked for the sentence's final translation (when made with Luna too).
        val paused = transcript.ended && !transcript.hasGuess && (quality.model == TranslationModel.LUNA || reached < end)
        if (!paused && waited < AHEAD_WAIT && !(state.showing && end > reached)) return
        state.asked = text
        state.asks += 1
        sonioxLags.asks += 1
        val id = caption.id
        val context = spokenContext(entry)
        val translate = services.translate
        val began = now()
        // A connection that hangs does not hold it up: the question is given up, and asked again.
        state.job = scope.launch {
            val translation = try {
                withTimeoutOrNull(AHEAD_PATIENCE) { translate(apiKey, text, Language.vietnamese, context, TranslationModel.LUNA, false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            answeredAhead(id, text, end, translation, now() - began)
        }
    }

    private fun answeredAhead(id: String, text: String, endMs: Int, translation: Translation?, seconds: Double) {
        val state = ahead[id] ?: return
        state.asked = null
        state.job = null
        if (translation == null || translation.text.isEmpty()) return
        charge { it.used(translation.usage, TranslationModel.LUNA) }
        sonioxLags.answers += 1
        sonioxLags.answering += seconds
        // Asked for fewer words than an answer already in: not kept.
        if (endMs >= (state.answer?.endMs ?: Int.MIN_VALUE)) state.answer = AheadAnswer(translation.text, text, endMs)
        val shown = shown(state)
        val entry = entries.firstOrNull { it.id == id }
        if (entry != null && (entry.state == State.Hearing || entry.state == State.Translating) && entry.translation != shown) {
            update(id) { it.translation = shown }
            if (state.showing) noteAhead(id, endMs)
        }
        translateAhead()
    }

    /**
     * GPT's translation asked while the sentence was being said, when it is of exactly its words, and by
     * the model its translation is made with: waited for when still coming.
     */
    private suspend fun aheadTranslation(entry: Entry, model: TranslationModel): String? {
        if (entry.mine || model != TranslationModel.LUNA) return null
        val state = ahead[entry.id] ?: return null
        if (state.answer?.text != entry.text && state.asked == entry.text) state.job?.join()
        val answer = ahead[entry.id]?.answer ?: return null
        return if (answer.text == entry.text) answer.translation else null
    }

    /**
     * When a Soniox sentence was said (`now()`'s clock), from where its words are in the sound sent: the
     * sound goes as it is heard, so the end of what was sent was said when it was sent (after Stop, nothing
     * more is).
     */
    private fun saidTime(sentence: SonioxTranscript.Sentence, fromOthers: Boolean): Pair<Double, Double> {
        val now = sonioxSentAt[fromOthers] ?: now()
        val sent = sonioxSent[fromOthers] ?: 0.0
        val to = sentence.endMs?.let { now - (sent - it / 1000.0) } ?: now
        val from = sentence.startMs?.let { now - (sent - it / 1000.0) } ?: (to - 3)
        return minOf(from, to) to to
    }

    /**
     * When a Soniox sentence was said, and how soon after its first word its words and its first
     * translation showed (in the session's sound: Soniox's lag and the app's together).
     */
    private fun noteHeard(id: String, sentence: SonioxTranscript.Sentence, fromOthers: Boolean) {
        val sent = sonioxSent[fromOthers] ?: 0.0
        val times = heardTimes.getOrPut(id) { HeardTime() }
        if (times.start == null) sentence.startMs?.let { times.start = it / 1000.0 }
        sentence.endMs?.let {
            val end = it / 1000.0
            // A word said after all those shown before: how late it came.
            if (end > (times.newest ?: -1.0)) {
                times.wordsLate = maxOf(times.wordsLate, sent - end)
                times.newest = end
            }
            times.end = end
        }
        sentence.coveredMs?.let {
            val covered = it / 1000.0
            if (covered > (times.covered ?: -1.0)) {
                times.sonioxLate = maxOf(times.sonioxLate, sent - covered)
                times.covered = covered
            }
        }
        val start = times.start ?: return
        if (times.shown == null && sentence.text.isNotEmpty()) times.shown = sent - start
        if (times.translated == null && sentence.translation.isNotEmpty()) times.translated = sent - start
    }

    /** GPT's translation ahead shows: how late after the words it reaches. */
    private fun noteAhead(id: String, endMs: Int) {
        val times = heardTimes[id] ?: return
        val sent = sonioxSent[true] ?: 0.0
        times.gptLate = maxOf(times.gptLate ?: 0.0, sent - endMs / 1000.0)
        if (times.translated == null) times.start?.let { times.translated = sent - it }
    }

    /** The room's session told how far it has heard and settled: every half minute, how far behind it was, in the speed log. */
    private fun noteProcessed(finalMs: Int, totalMs: Int) {
        val heard = (sonioxSent[true] ?: 0.0) - totalMs / 1000.0
        val settled = (totalMs - finalMs) / 1000.0
        val lags = sonioxLags
        lags.count += 1
        lags.heard += heard
        lags.heardMost = maxOf(lags.heardMost, heard)
        lags.settled += settled
        lags.settledMost = maxOf(lags.settledMost, settled)
        if (now() - lags.since < 30) return
        var note = "Soniox nửa phút: nghe chậm TB ${SpeedLog.seconds(lags.heard / lags.count)}, tối đa " +
            "${SpeedLog.seconds(lags.heardMost)} · chốt chậm TB ${SpeedLog.seconds(lags.settled / lags.count)}, tối đa " +
            "${SpeedLog.seconds(lags.settledMost)} · GPT dịch trước ${lags.asks} lần"
        if (lags.answers > 0) note += ", trả lời TB ${SpeedLog.seconds(lags.answering / lags.answers)}"
        log(null, note)
        sonioxLags = SonioxLags(now())
    }

    /**
     * A sentence ended. With an OpenAI key it is translated again by GPT, with the meeting's latest
     * sentences and the glossary (Soniox's own translation shows meanwhile), then kept and read aloud
     * (the user's always, the others' with Đọc to). Without one, Soniox's is kept.
     */
    private fun finishSentence(sentence: SonioxTranscript.Sentence, fromOthers: Boolean, lag: Double? = null) {
        val id = showSentence(sentence, fromOthers)
        captions.remove(fromOthers)
        // The phone's Vietnamese voice heard again through the microphone.
        val (saidFrom, saidTo) = saidTime(sentence, fromOthers)
        if (fromOthers && echo.isEcho(sentence.text, saidFrom, saidTo)) {
            log(id, "Soniox: tiếng của máy nghe lại, bỏ")
            heardTimes.remove(id)
            remove(id)
            return
        }
        var note = "Soniox"
        if (fromOthers && sentence.speaker.isNotEmpty()) note += " · Người ${sentence.speaker}"
        note += " · ${sentence.language.ifEmpty { "?" }} · ${TranscriptFilter.syllableCount(sentence.text)} âm tiết"
        if (lag != null) note += " · nhận chậm ${SpeedLog.seconds(lag)}"
        heardTimes[id]?.let { times ->
            times.shown?.let { note += " · chữ hiện sau ${SpeedLog.seconds(it)}" }
            // How late after they were said the words, and the translations reaching them, came at worst.
            note += " · chữ chậm tối đa ${SpeedLog.seconds(times.wordsLate)}"
            if (times.covered != null) note += " · bản dịch Soniox chậm tối đa ${SpeedLog.seconds(times.sonioxLate)}"
            times.gptLate?.let { note += " · GPT dịch trước chậm tối đa ${SpeedLog.seconds(it)}" }
        }
        ahead[id]?.asks?.takeIf { it > 0 }?.let { note += " · hỏi GPT dịch trước $it lần" }
        log(id, note)
        val language = if (sentence.text.isEmpty()) sentence.language else language(sentence.text, sentence.language)
        // A Vietnamese speaker in the room is shown as they said it.
        if (!sonioxUsesGPT || sentence.text.isEmpty() || (fromOthers && language == Language.vietnamese.code)) {
            heardTimes.remove(id)
            ahead.remove(id)?.job?.cancel()
            if (language.isNotEmpty()) update(id) { it.language = language }
            finishCaption(id)
            val entry = entries.firstOrNull { it.id == id } ?: return
            if (entry.state != State.Done || entry.translation.isEmpty()) return
            if (entry.mine || readAloud) sayOnce(entry)
            return
        }
        val target = if (fromOthers) Language.vietnamese else partnerLanguage
        val translation = if (fromOthers) shownTranslation(id, sentence) else sentence.translation
        update(id) {
            it.text = sentence.text
            it.translation = translation
            it.language = language
            it.target = target.code
            it.state = State.Translating
        }
        if (fromOthers) noteSpoken(language, sentence.text)
        sonioxEntries.getOrPut(fromOthers) { HashMap() }[sentence.number] = id
        scope.launch {
            translateAgain(id, target, speaks = true)
            // Soniox's pieces still coming for it go on to it (not to the next sentence), unseen.
            if (sonioxEntries[fromOthers]?.get(sentence.number) == id) sonioxEntries[fromOthers]?.remove(sentence.number)
        }
        // GPT that does not answer soon (a slow network): Soniox's translation is read meanwhile.
        if (fromOthers && !readAloud) return
        scope.launch {
            delay(5000)
            val entry = entries.firstOrNull { it.id == id } ?: return@launch
            if (entry.state != State.Translating || entry.translation.isEmpty() || id in voiced) return@launch
            log(id, "GPT chưa xong sau 5 s: đọc bản dịch của Soniox")
            sayOnce(entry)
        }
    }

    /**
     * GPT's translation of a sentence heard live (Soniox's, or a Dịch liền caption), with the meeting's
     * latest sentences: what is shown and kept, and read aloud when `speaks` (the user's always, the
     * others' with Đọc to). When it fails, the live translation stays.
     */
    private suspend fun translateAgain(id: String, target: Language, speaks: Boolean) {
        val apiKey = apiKey ?: return
        val entry = entries.firstOrNull { it.id == id } ?: return
        // What the partners hear is the user's own words: the app's translation model.
        val model = if (entry.mine) settings.model else quality.model
        val began = millis()
        val early = aheadTranslation(entry, model)
        if (early != null) {
            // GPT translated exactly these words while they were being said.
            update(id) {
                it.translation = early
                it.state = State.Done
            }
            log(id, "GPT ${name(model)} đã dịch trước, dùng ngay" + heardNote(id, entry.mine))
        } else {
            for (attempt in 1..2) {
                try {
                    val result = gptTranslation(entry, target, model, apiKey)
                    charge { it.used(result.usage, model) }
                    update(id) {
                        it.translation = result.text
                        it.state = State.Done
                    }
                    log(id, "GPT ${name(model)} ${SpeedLog.seconds((millis() - began) / 1000.0)}" + heardNote(id, entry.mine))
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Asked once more a moment later (a busy server, a network that blinked). Meanwhile the live
                    // translation goes on coming in: it was kept as it was when GPT failed, cut short.
                    if (attempt == 1) {
                        log(id, "GPT lỗi sau ${SpeedLog.seconds((millis() - began) / 1000.0)}: hỏi lại sau 1,5 s")
                        delay(1500)
                        continue
                    }
                    // The live translation that has come furthest: Soniox's once all of it is in.
                    val kept = keptTranslation(id)
                    update(id) {
                        if (kept != null) it.translation = kept
                        it.state = if (it.translation.isEmpty()) State.Failed(e.message ?: "Lỗi không rõ.") else State.Done
                    }
                    log(id, "GPT lỗi sau ${SpeedLog.seconds((millis() - began) / 1000.0)}: giữ bản dịch trực tiếp")
                    heardTimes.remove(id)
                }
            }
        }
        ahead.remove(id)?.job?.cancel()
        val done = entries.firstOrNull { it.id == id } ?: return
        if (done.state != State.Done) return
        record(id)
        if (speaks && done.translation.isNotEmpty() && (done.mine || readAloud)) sayOnce(done)
    }

    /**
     * Soniox's translation of the sentences GPT is still translating, as it comes (or GPT's asked ahead,
     * while it has come further).
     */
    private fun showPreviews(previews: List<SonioxTranscript.Sentence>, fromOthers: Boolean) {
        for (preview in previews) {
            if (preview.translation.isEmpty()) continue
            val id = sonioxEntries[fromOthers]?.get(preview.number) ?: continue
            val entry = entries.firstOrNull { it.id == id } ?: continue
            if (entry.state != State.Translating) continue
            val translation = if (fromOthers) shownTranslation(id, preview) else preview.translation
            if (entry.translation == translation) continue
            update(id) { it.translation = translation }
            noteHeard(id, preview, fromOthers)
        }
    }

    /**
     * When GPT's translation of a finished sentence failed: of the live translations, the one reaching
     * furthest into it (Soniox's, once all its pieces are in), or null to keep what shows.
     */
    private fun keptTranslation(id: String): String? {
        val state = ahead[id] ?: return null
        val soniox = state.sonioxTranslation ?: return null
        val answer = state.answer
        if (answer != null && (soniox.isEmpty() || answer.endMs > (state.sonioxCovered ?: Int.MIN_VALUE))) return answer.translation
        return soniox.ifEmpty { null }
    }

    /**
     * For the speed log, once GPT's translation of a Soniox sentence shows: how soon after its first word
     * a translation (Soniox's, or else GPT's) first showed, and GPT's after its last word.
     */
    private fun heardNote(id: String, mine: Boolean): String {
        val times = heardTimes.remove(id) ?: return ""
        val start = times.start ?: return ""
        val sent = sonioxSent[!mine] ?: 0.0
        var note = " · bản dịch đầu tiên sau ${SpeedLog.seconds(times.translated ?: (sent - start))}"
        times.end?.let { note += " · bản GPT sau ${SpeedLog.seconds(sent - it)} từ lúc ngừng nói" }
        return note
    }

    /**
     * A sentence Soniox ended but whose translation never came, one paused without a full stop (or said
     * before the user began to speak) whose speaker says nothing more, or one Soniox stopped sending words for
     * (the connection hung): finished with what is known, after a moment.
     */
    private fun expireSentences() {
        val turn = talking || millis() < mineTailUntil
        for ((fromOthers, current) in captions.entries.toList()) {
            val quiet = millis() - current.last
            val transcript = transcripts[fromOthers]
            val splits = transcript?.splits == true
            // While the user speaks, the room's Soniox hears their turn and sends no end of sentence: the
            // room's sentence ends after the quiet moment that ends a pause. Not while its words are still
            // guessed (they settle first).
            val paused = transcript?.ended == true || (fromOthers && turn && splits)
            val wait = if (!paused || transcript?.hasGuess == true) 8000L else if (splits) PAUSE_ENDS else 2000L
            if (quiet < wait) continue
            val sentence = transcripts[fromOthers]?.finish()
            if (sentence != null) {
                finishSentence(sentence, fromOthers)
            } else {
                finishCaption(current.id)
                captions.remove(fromOthers)
            }
        }
    }

    // Speed log

    /** A line of the speed log (`id`: the sentence it is about, by the start of its id). */
    private fun log(id: String?, text: String) {
        speedLog.add(if (id == null) text else "${id.take(4).uppercase()} $text")
    }

    /** For the user to send when Hội thoại feels slow: timings, languages and lengths, never words. */
    val speedLogText: String get() = "MSTrans Android $appVersion · nhật ký tốc độ Hội thoại\n" + speedLog.text

    // Summary

    fun summarizeCurrent() {
        if (meeting.lines.isEmpty() || summarizing) return
        summarizing = true
        changed()
        scope.launch {
            try {
                val (text, usage, model) = summarize(meeting, settings)
                meeting.summary = text
                charge { it.used(usage, model) }
                meetings.save(meeting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            } finally {
                summarizing = false
                changed()
            }
        }
    }
}

/** The phone's voice could not read a sentence; the message says what to do. */
class PhoneVoiceFailure(message: String) : Exception(message)
