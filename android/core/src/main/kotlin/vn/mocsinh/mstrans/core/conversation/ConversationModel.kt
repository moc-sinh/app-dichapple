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
    val translate: suspend (apiKey: String, text: String, target: Language, context: SpokenContext, model: TranslationModel) -> Translation,
    /** 24 kHz mono PCM16 of `text`; `heard` gets it as it is made (OpenAI's and the phone's voices). */
    val speak: suspend (apiKey: String, text: String, voice: String, language: Language, heard: (ByteArray) -> Unit) -> ByteArray,
    /** Opens the connection to OpenAI before a sentence needs it (every 45 s at most), so no translation waits for it. */
    val warmUp: () -> Unit = { Translator.warmUp() },
) {
    companion object {
        fun live(settings: Settings, phone: PhoneVoice) = ConversationServices(
            transcribe = { key, wav, prompt -> SpeechServices(key).transcribe(wav, prompt) },
            translate = { key, text, target, context, model ->
                Translator(key, model, settings).translateSpoken(text, target, context)
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
 * the Tôi nói button is down, and in live mode passes the sound on a tenth of a second at a time.
 * Everything it reports goes to the main thread through `post`.
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
    /** A tenth of a second of sound, and whether someone is speaking in it. */
    var onChunk: ((ByteArray, Boolean) -> Unit)? = null

    companion object {
        private const val RECENT_SAMPLES = PCM.RATE * 3 / 10
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
            val speech = mine != null || segmenter.speaking
            val tenth = PCM.RATE / 10
            while (chunk.size >= tenth) {
                val data = PCM.data(chunk.array, 0, tenth)
                chunk.removeFirst(tenth)
                post { onChunk?.invoke(data, speech) }
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
    enum class TextSize(val title: String) { MEDIUM("Vừa"), LARGE("Lớn"), EXTRA_LARGE("Rất lớn") }

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
        const val READ_ALOUD = "conversation.readAloud"
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
    var readAloud: Boolean = defaults.get(Keys.READ_ALOUD)?.toBooleanStrictOrNull() ?: true
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
    var textSize: TextSize = TextSize.entries.getOrNull(defaults.get(Keys.TEXT_SIZE)?.toIntOrNull() ?: 0) ?: TextSize.MEDIUM
        set(value) {
            field = value
            defaults.set(Keys.TEXT_SIZE, value.ordinal.toString())
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
    private var liveRetries = 0
    private var sonioxToVietnamese: SonioxSession? = null
    private var sonioxToPartner: SonioxSession? = null
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
    /** Entries already read aloud: one read from Soniox's translation is not read again for GPT's. */
    private val voiced = HashSet<String>()
    /** Counts the others' sentences queued to be read, so one can tell how many wait behind it. */
    private var theirsQueued = 0
    private var lastDropNote = 0L
    private val speedLog = SpeedLog()

    /**
     * Seconds into a Soniox session's sound: where a sentence's words begin and end, and how much had
     * been sent when its words, then its first translation, showed (for the speed log).
     */
    private class HeardTime(var start: Double? = null, var end: Double? = null, var shown: Double? = null, var translated: Double? = null)
    private val heardTimes = HashMap<String, HeardTime>()

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
            listener.onChunk = { data, speech -> stream(data, speech) }
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
        // After the sound already queued: the sentence being said still goes in.
        listener?.let { l -> audio.onAudioThread { l.flush() } }
        // The sentences being said are translated as they are.
        for (fromOthers in listOf(true, false)) transcripts[fromOthers]?.finish()?.let { finishSentence(it, fromOthers) }
        closeLive()
        endLiveCaptions(all = true)
        for (current in captions.values.toList()) finishCaption(current.id)
        captions.clear()
        billLive()
        keepScreenOn?.invoke(false)
        meetings.save(meeting)
        log(null, "Dừng")
        changed()
    }

    /** Sentences still being recognised or translated. */
    private val settling: Boolean get() = entries.any { it.state == State.Recognizing || it.state == State.Translating }

    /** A new meeting can start once listening has stopped and the last sentences are in this one. */
    val canStartNewMeeting: Boolean get() = !running && !summarizing && !settling && entries.isNotEmpty()

    /** The meeting so far goes to the saved ones; the next starts empty. */
    fun newMeeting() {
        if (running || summarizing || settling) return
        billLive()
        meetings.save(meeting)
        meeting = Meeting(topic = topic.ifEmpty { null })
        entries.clear()
        heardTimes.clear()
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
        if (engine == Engine.SONIOX) expireSentences()
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
            if (engine == Engine.SONIOX) openSonioxPartner()
        } else if (engine == Engine.LIVE) {
            // A moment of silence lets the last words through.
            partnerTailUntil = millis() + 1500
        } else if (engine == Engine.SONIOX) {
            // The user's last words are settled and translated now, not after a pause.
            sonioxToPartner?.finalize()
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
        if (TranscriptFilter.isNoise(text, seconds) || (!entry.mine && echo.isEcho(text, now()))) {
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
            asks.add(launch {
                try {
                    answers.trySend(Result.success(translate(key, text, target, context, model)))
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

    private fun update(id: String, change: (Entry) -> Unit) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        change(entry)
        changed()
    }

    private fun remove(id: String) {
        entries.removeAll { it.id == id }
        unrecognised.remove(id)
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
        if (!mine && !requested) {
            theirsQueued += 1
            serial = theirsQueued
        }
        val previous = speechChain
        speechChain = scope.launch {
            previous?.join()
            if (generation != speechGeneration) return@launch
            play(sound, text, generation, mine, requested, ready, serial, entry)
        }
    }

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

    /** `serial`: the others' sentence's place in the queue (0 for the user's and Đọc lại). */
    private suspend fun play(
        sound: VoiceStream, text: String, generation: Int, mine: Boolean, requested: Boolean, ready: Long, serial: Int, entry: String?,
    ) {
        // Never over the user: a translation ready while they speak waits until they let go.
        while (talking && running) delay(100)
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
        echo.spoke(text, now())
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
        sonioxToVietnamese?.close()
        sonioxToPartner?.close()
        sonioxToVietnamese = null
        sonioxToPartner = null
        transcripts.clear()
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
     * The microphone's sound goes to the session for whoever speaks: the user's while the button is
     * down, everyone else's otherwise. The other session gets silence for a moment, so it finishes what
     * it was saying. Dịch liền is not sent a quiet room (paid by the minute sent, 2 USD an hour); Soniox
     * is sent all of it (0.18 USD an hour): a quiet or far voice the level meter took for the room, or
     * the first syllable after a pause, is never lost, and Soniox hears for itself where a sentence ends.
     */
    private fun stream(data: ByteArray, speech: Boolean) {
        if (!running || !engine.streams) return
        val silent = ByteArray(data.size)
        if (talking) {
            sendMine(data)
            if (engine == Engine.SONIOX) {
                sendOthers(silent)
            } else {
                for (chunk in othersGate.feed(silent, false)) sendOthers(chunk)
            }
        } else {
            // The phone speaking the partners' language is not someone in the room to translate; nor is
            // its Vietnamese (Đọc lại) when no echo cancelling takes it out.
            val phoneSpeaks = playingMine > 0 || (playingAloud > 0 && !echoCancelling)
            if (engine == Engine.SONIOX) {
                sendOthers(if (phoneSpeaks) silent else data)
            } else {
                for (chunk in othersGate.feed(if (phoneSpeaks) silent else data, speech && !phoneSpeaks)) sendOthers(chunk)
            }
            if (millis() < partnerTailUntil) sendMine(silent)
        }
    }

    private fun sendOthers(chunk: ByteArray) {
        if (engine == Engine.SONIOX) {
            (sonioxToVietnamese ?: return).send(chunk)
            sonioxSent[true] = (sonioxSent[true] ?: 0.0) + PCM.seconds(chunk)
        } else {
            (liveToVietnamese ?: return).send(chunk)
        }
        unbilledLive += PCM.seconds(chunk)
    }

    private fun sendMine(chunk: ByteArray) {
        if (engine == Engine.SONIOX) {
            (sonioxToPartner ?: return).send(chunk)
            sonioxSent[false] = (sonioxSent[false] ?: 0.0) + PCM.seconds(chunk)
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
        session.open()
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
                val transcript = transcripts.getOrPut(fromOthers) { newTranscript(session.target) }
                val finished = transcript.take(event.tokens)
                // How far the words Soniox settled are behind the sound sent to it.
                val lag = transcript.settledEnd?.let { (sonioxSent[fromOthers] ?: 0.0) - it }
                for (sentence in finished) finishSentence(sentence, fromOthers, lag)
                showPreviews(transcript.previews, fromOthers)
                val current = transcript.current
                // Cut by GPT's sentences, a piece of translation no sentence took is not shown on its own.
                if (if (transcript.splits) current.text.isNotEmpty() else !current.isEmpty) showSentence(current, fromOthers)
            }
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
                -1 -> problem = "Mất kết nối. Đang nối lại…"
                else -> problem = if (event.message.isEmpty()) "Soniox gặp lỗi (${event.code})." else "Soniox: ${event.message}"
            }
            SonioxEvent.Closed -> {
                if (!running || engine != Engine.SONIOX) return
                // Dropped (network, a time limit): what was heard stays, and it is opened again, a few
                // times. Let go of at once, so a second word of it closing opens nothing more.
                session.close()
                transcripts[fromOthers]?.finish()?.let { finishSentence(it, fromOthers) }
                transcripts.remove(fromOthers)
                sonioxEntries.remove(fromOthers)
                if (fromOthers) sonioxToVietnamese = null else sonioxToPartner = null
                log(null, "Soniox mất kết nối, nối lại")
                liveRetries += 1
                if (liveRetries > 5) {
                    problem = "Không nối lại được Soniox. Kiểm tra mạng rồi bấm nút tròn để nghe lại."
                    stop()
                    return
                }
                scope.launch {
                    delay(1000)
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
        if (current != null && entries.any { it.id == current.id }) {
            update(current.id) {
                it.text = sentence.text
                it.translation = sentence.translation
                if (sentence.language.isNotEmpty()) it.language = sentence.language
                if (speaker.isNotEmpty()) it.speaker = speaker
            }
            captions[fromOthers] = Caption(current.id, now)
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
        noteHeard(entry.id, sentence, fromOthers)
        changed()
        return entry.id
    }

    /**
     * When a Soniox sentence was said, and how soon after its first word its words and its first
     * translation showed (in the session's sound: Soniox's lag and the app's together).
     */
    private fun noteHeard(id: String, sentence: SonioxTranscript.Sentence, fromOthers: Boolean) {
        val sent = sonioxSent[fromOthers] ?: 0.0
        val times = heardTimes.getOrPut(id) { HeardTime() }
        if (times.start == null) sentence.startMs?.let { times.start = it / 1000.0 }
        sentence.endMs?.let { times.end = it / 1000.0 }
        val start = times.start ?: return
        if (times.shown == null && sentence.text.isNotEmpty()) times.shown = sent - start
        if (times.translated == null && sentence.translation.isNotEmpty()) times.translated = sent - start
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
        if (fromOthers && echo.isEcho(sentence.text, now())) {
            log(id, "Soniox: tiếng của máy nghe lại, bỏ")
            heardTimes.remove(id)
            remove(id)
            return
        }
        var note = "Soniox"
        if (fromOthers && sentence.speaker.isNotEmpty()) note += " · Người ${sentence.speaker}"
        note += " · ${sentence.language.ifEmpty { "?" }} · ${TranscriptFilter.syllableCount(sentence.text)} âm tiết"
        if (lag != null) note += " · nhận chậm ${SpeedLog.seconds(lag)}"
        heardTimes[id]?.shown?.let { note += " · chữ hiện sau ${SpeedLog.seconds(it)}" }
        log(id, note)
        val language = if (sentence.text.isEmpty()) sentence.language else language(sentence.text, sentence.language)
        // A Vietnamese speaker in the room is shown as they said it.
        if (!sonioxUsesGPT || sentence.text.isEmpty() || (fromOthers && language == Language.vietnamese.code)) {
            heardTimes.remove(id)
            if (language.isNotEmpty()) update(id) { it.language = language }
            finishCaption(id)
            val entry = entries.firstOrNull { it.id == id } ?: return
            if (entry.state != State.Done || entry.translation.isEmpty()) return
            if (entry.mine || readAloud) sayOnce(entry)
            return
        }
        val target = if (fromOthers) Language.vietnamese else partnerLanguage
        update(id) {
            it.text = sentence.text
            it.translation = sentence.translation
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
        try {
            val result = gptTranslation(entry, target, model, apiKey)
            charge { it.used(result.usage, model) }
            update(id) {
                it.translation = result.text
                it.state = State.Done
            }
            log(id, "GPT ${name(model)} ${SpeedLog.seconds((millis() - began) / 1000.0)}" + heardNote(id, entry.mine))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(id) { it.state = if (it.translation.isEmpty()) State.Failed(e.message ?: "Lỗi không rõ.") else State.Done }
            log(id, "GPT lỗi sau ${SpeedLog.seconds((millis() - began) / 1000.0)}: giữ bản dịch trực tiếp")
            heardTimes.remove(id)
        }
        val done = entries.firstOrNull { it.id == id } ?: return
        if (done.state != State.Done) return
        record(id)
        if (speaks && done.translation.isNotEmpty() && (done.mine || readAloud)) sayOnce(done)
    }

    /** Soniox's translation of the sentences GPT is still translating, as it comes. */
    private fun showPreviews(previews: List<SonioxTranscript.Sentence>, fromOthers: Boolean) {
        for (preview in previews) {
            if (preview.translation.isEmpty()) continue
            val id = sonioxEntries[fromOthers]?.get(preview.number) ?: continue
            val entry = entries.firstOrNull { it.id == id } ?: continue
            if (entry.state != State.Translating || entry.translation == preview.translation) continue
            update(id) { it.translation = preview.translation }
            noteHeard(id, preview, fromOthers)
        }
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
     * A sentence Soniox ended but whose translation never came, or one it stopped sending words for (the
     * connection hung): finished with what is known, after a moment.
     */
    private fun expireSentences() {
        for ((fromOthers, current) in captions.entries.toList()) {
            val quiet = millis() - current.last
            if (quiet < 2000 || !(transcripts[fromOthers]?.ended == true || quiet >= 8000)) continue
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
