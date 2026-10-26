package vn.mocsinh.mstrans.core.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.core.ChatLine
import vn.mocsinh.mstrans.core.KeyValueStore
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.LanguageDetector
import vn.mocsinh.mstrans.core.Settings
import vn.mocsinh.mstrans.core.TokenUsage
import vn.mocsinh.mstrans.core.TranslateError
import vn.mocsinh.mstrans.core.Translation
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.Translator
import java.util.UUID

/** The microphone and the speaker, as the phone gives them. */
interface AudioPort {
    /** 24 kHz mono samples, on the audio thread. */
    var onSamples: ((ShortArray) -> Unit)?
    /** A phone call or another app took the sound (true) or gave it back; on the main thread. */
    var onInterruption: ((Boolean) -> Unit)?

    /** Starts listening; throws with a message the user can read. */
    fun start(echoCancelling: Boolean)
    fun stop()
    /** Runs `block` on the audio thread, after the sound already taken. */
    fun onAudioThread(block: () -> Unit)
    /** Plays 24 kHz mono PCM16 after what is already playing; `done` on the main thread once heard. */
    fun play(pcm: ByteArray, done: () -> Unit)
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
    val translate: suspend (apiKey: String, text: String, target: Language, context: List<ChatLine>, model: TranslationModel) -> Translation,
    /** 24 kHz mono PCM16 of `text`; `heard` gets it as it is made (OpenAI's and the phone's voices). */
    val speak: suspend (apiKey: String, text: String, voice: String, language: Language, heard: (ByteArray) -> Unit) -> ByteArray,
) {
    companion object {
        fun live(settings: Settings, phone: PhoneVoice) = ConversationServices(
            transcribe = { key, wav, prompt -> SpeechServices(key).transcribe(wav, prompt) },
            translate = { key, text, target, context, model ->
                Translator(key, model, settings).translate(text, target, context, spoken = true)
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
    private val segmenter = SpeechSegmenter(silence)
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
 * While it runs, everything the others say comes up as Vietnamese text, and with Đọc to it is also
 * read aloud through the speaker; Chỉ chữ only shows it. When the user wants to speak, they hold the
 * Tôi nói button: what they say is translated into the partners' language and read aloud.
 *
 * How it translates (Cách dịch):
 * - Chính xác: each sentence is recognised and translated once it ends, with the glossary and the
 *   meeting's context; several at once, shown in the order they were said.
 * - Dịch liền: OpenAI's live speech translation, text (and voice) as the others speak.
 * - Soniox: Soniox's live recognition and translation, word by word, a fraction of Dịch liền's price.
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
        private const val PARALLEL = 3

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
                "sentence with its time, who spoke (\"Tôi\" is the user, otherwise the language spoken), what was said (recognised " +
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

        private fun now(): Double = System.currentTimeMillis() / 1000.0
        private fun millis(): Long = System.currentTimeMillis()
    }

    /** Called on the main thread after anything shown changes. */
    var onChange: (() -> Unit)? = null
    /** Keeps the screen on while listening. */
    var keepScreenOn: ((Boolean) -> Unit)? = null
    /** Asks for the microphone; true when allowed. */
    var requestPermission: (suspend () -> Boolean)? = null

    private fun changed() {
        onChange?.invoke()
    }

    // Settings, kept between launches

    /** Đọc to: the others' words are read aloud in Vietnamese too. Chỉ chữ: only shown. */
    var readAloud: Boolean = defaults.get(Keys.READ_ALOUD)?.toBooleanStrictOrNull() ?: true
        set(value) {
            field = value
            defaults.set(Keys.READ_ALOUD, value.toString())
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
    var meeting = Meeting()
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
    private class Caption(val id: String, val last: Long)
    private val captions = HashMap<Boolean, Caption>()
    private var partnerTailUntil = 0L
    private var liveRetries = 0
    private var sonioxToVietnamese: SonioxSession? = null
    private var sonioxToPartner: SonioxSession? = null
    /** What each Soniox session has heard (true: the others'), cut into sentences. */
    private val transcripts = HashMap<Boolean, SonioxTranscript>()
    /** Dịch liền and Soniox: the others' sound is sent while someone speaks, not while the room is quiet. */
    private val othersGate = LiveGate()
    private var othersSending = false
    /** Seconds sent to a live session and not yet in the meeting's cost. */
    private var unbilledLive = 0.0
    /** Voices already made: Đọc lại plays them at once, without paying again. */
    private val voiceCache = VoiceCache()
    private var aloneTask: Job? = null

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
            val listener = Listener(silence, engine.streams) { block -> scope.launch { block() } }
            listener.onUtterance = { heard(it) }
            listener.onMine = { samples, first, last -> said(samples, first, last) }
            listener.onLevel = { level, speaking -> heard(level, speaking) }
            listener.onChunk = { data, speech -> stream(data, speech) }
            this.listener = listener
            audio.onSamples = { listener.take(it) }
            try {
                // Dịch liền and Soniox speak while they listen: echo cancelling keeps the phone's voice out.
                audio.start(echoCancelling = engine.streams)
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
        closeLive()
        finishCaptions(all = true)
        billLive()
        keepScreenOn?.invoke(false)
        meetings.save(meeting)
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
        meeting = Meeting()
        entries.clear()
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
        if (engine == Engine.LIVE) finishCaptions(all = false)
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
        try {
            val result = services.translate(apiKey, entry.text, target, context(entry.at), model)
            charge { it.used(result.usage, model) }
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

    /** The meeting's latest sentences, as the translator's context. */
    private fun context(before: Long): List<ChatLine> =
        meeting.lines.filter { it.at < before }.takeLast(8).map {
            ChatLine(if (it.mine) ChatLine.Side.ME else ChatLine.Side.PARTNER, it.text, it.at)
        }

    /** Names and terms the recognizer should spell right: the glossary, and the last sentences. */
    private fun recognitionPrompt(): String {
        val terms = ArrayList<String>()
        for (line in settings.glossary.lines()) {
            for (side in line.split("=")) {
                val term = side.trim()
                if (term.isNotEmpty() && term.length <= 40) terms.add(term)
            }
        }
        var prompt = if (terms.isEmpty()) "" else "Thuật ngữ / Terms: " + terms.take(40).joinToString(", ") + "\n"
        prompt += meeting.lines.takeLast(2).joinToString(" ") { it.text }
        return prompt
    }

    /** Into the minutes, in the order things were said. */
    private fun record(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.text.isEmpty()) return
        val line = Meeting.Line(entry.at, entry.mine, entry.language, entry.text, entry.translation, entry.target)
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
     * Each translation is fetched as soon as it is ready and read after the one before, in the order
     * the sentences were said. `mine`: the user's words, in the partners' language; otherwise the
     * others', in Vietnamese. `requested`: Đọc lại, read at once, even in Chỉ chữ.
     */
    private fun say(text: String, mine: Boolean, language: Language, requested: Boolean = false, entry: String? = null) {
        val apiKey = apiKey ?: return
        if (!running) return
        val generation = speechGeneration
        val ready = millis()
        val sound = voiceTask(text, voice, language, apiKey)
        if (entry == null) {
            queueSpeech(sound, text, mine, requested, generation, ready)
            return
        }
        scope.launch {
            waitForEarlier(entry)
            queueSpeech(sound, text, mine, requested, generation, ready)
        }
    }

    /** Read after what is already queued (unless the queue was dropped meanwhile). */
    private fun queueSpeech(sound: VoiceStream, text: String, mine: Boolean, requested: Boolean, generation: Int, ready: Long) {
        if (generation != speechGeneration) return
        val previous = speechChain
        speechChain = scope.launch {
            previous?.join()
            if (generation != speechGeneration) return@launch
            play(sound, text, generation, mine, requested, ready)
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

    private suspend fun play(sound: VoiceStream, text: String, generation: Int, mine: Boolean, requested: Boolean, ready: Long) {
        // Never over the user: a translation ready while they speak waits until they let go.
        while (talking && running) delay(100)
        // Like an interpreter, the others' words are read once the room pauses. A sentence that finds
        // no pause within ten seconds of being translated gets the text only.
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
     * translate its own voice); Dịch liền cancels the echo.
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
     * The microphone's sound goes to the session for whoever speaks: the user's while the button is
     * down, everyone else's otherwise. A quiet room is not sent at all (paid by the minute sent).
     */
    private fun stream(data: ByteArray, speech: Boolean) {
        if (!running || !engine.streams) return
        val silent = ByteArray(data.size)
        if (talking) {
            sendMine(data)
            for (chunk in othersGate.feed(silent, false)) sendOthers(chunk)
        } else {
            // The phone speaking the partners' language is not someone in the room to translate.
            val phoneSpeaksMine = playingMine > 0
            val chunks = othersGate.feed(if (phoneSpeaksMine) silent else data, speech && !phoneSpeaksMine)
            for (chunk in chunks) sendOthers(chunk)
            // The room went quiet: Soniox settles what it still guesses now.
            if (engine == Engine.SONIOX && othersSending && chunks.isEmpty()) sonioxToVietnamese?.finalize()
            othersSending = chunks.isNotEmpty()
            if (millis() < partnerTailUntil) sendMine(silent)
        }
    }

    private fun sendOthers(chunk: ByteArray) {
        if (engine == Engine.SONIOX) {
            (sonioxToVietnamese ?: return).send(chunk)
        } else {
            (liveToVietnamese ?: return).send(chunk)
        }
        unbilledLive += PCM.seconds(chunk)
    }

    private fun sendMine(chunk: ByteArray) {
        if (engine == Engine.SONIOX) {
            (sonioxToPartner ?: return).send(chunk)
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
            is LiveEvent.Source -> caption(fromOthers) { it.text += event.delta }
            is LiveEvent.Target -> caption(fromOthers) { it.translation += event.delta }
            is LiveEvent.Audio -> {
                if (fromOthers) {
                    // Đọc to only, never over the user, and not a Vietnamese speaker's words again.
                    if (!readAloud || talking || othersSpeakVietnamese) return
                    beginAloud(false)
                    audio.play(event.pcm) { endAloud(false) }
                } else {
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
                // Dropped (network, a time limit): opened again, a few times.
                session.close()
                if (fromOthers) liveToVietnamese = null else liveToPartner = null
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
            val id = captions[true]?.id ?: return false
            val entry = entries.firstOrNull { it.id == id } ?: return false
            if (entry.text.length < 12) return false
            return LanguageDetector.isVietnamese(entry.text)
        }

    /** Live captions: what comes within 2.5 s of the last piece belongs to the same sentence. */
    private fun caption(fromOthers: Boolean, change: (Entry) -> Unit) {
        val now = millis()
        val current = captions[fromOthers]
        if (current != null && now - current.last < 2500 && entries.any { it.id == current.id }) {
            update(current.id, change)
            captions[fromOthers] = Caption(current.id, now)
            return
        }
        if (current != null) finishCaption(current.id)
        val entry = Entry(
            UUID.randomUUID().toString(), now, !fromOthers, if (fromOthers) "" else Language.vietnamese.code, "", "",
            if (fromOthers) Language.vietnamese.code else partnerLanguage.code, State.Hearing,
        )
        change(entry)
        entries.add(entry)
        captions[fromOthers] = Caption(entry.id, now)
        changed()
    }

    private fun finishCaptions(all: Boolean) {
        for ((key, current) in captions.entries.toList()) {
            if (all || millis() - current.last >= 2500) {
                finishCaption(current.id)
                captions.remove(key)
            }
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
        othersSending = false
        transcripts.clear()
        sonioxToVietnamese = makeSoniox(Language.vietnamese.code)
        openSonioxPartner()
    }

    /**
     * Everything the others say is translated into Vietnamese; the user's words into the partners'
     * language. Soniox hears any language, so it is told which to expect only as a hint.
     */
    private fun makeSoniox(target: String): SonioxSession? {
        val key = settings.sonioxKey ?: return null
        val partner = SonioxEvent.code(partnerLanguage)
        val hints = if (target == Language.vietnamese.code) listOf(partner, Language.vietnamese.code) else listOf(Language.vietnamese.code)
        val session = SonioxSession(key, target, hints, settings.glossary, scope)
        session.onEvent = { soniox(it, session) }
        session.open()
        return session
    }

    /** The session for the user's words, opened again when the partners' language changes. */
    private fun openSonioxPartner() {
        val code = SonioxEvent.code(partnerLanguage)
        val current = sonioxToPartner
        if (current != null && current.target == code && current.isOpen) return
        sonioxToPartner?.close()
        transcripts.remove(false)
        sonioxToPartner = makeSoniox(code)
    }

    private fun soniox(event: SonioxEvent, session: SonioxSession) {
        if (session !== sonioxToVietnamese && session !== sonioxToPartner) return
        val fromOthers = session === sonioxToVietnamese
        when (event) {
            is SonioxEvent.Tokens -> {
                liveRetries = 0
                if (problem?.startsWith("Mất kết nối") == true) problem = null
                val transcript = transcripts.getOrPut(fromOthers) { SonioxTranscript(session.target) }
                val finished = transcript.take(event.tokens)
                for (sentence in finished) finishSentence(sentence, fromOthers)
                val current = transcript.current
                if (!current.isEmpty) showSentence(current, fromOthers)
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
                // Dropped: what was heard stays, and it is opened again, a few times.
                session.close()
                transcripts[fromOthers]?.finish()?.let { finishSentence(it, fromOthers) }
                transcripts.remove(fromOthers)
                if (fromOthers) sonioxToVietnamese = null else sonioxToPartner = null
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
        val current = captions[fromOthers]
        if (current != null && entries.any { it.id == current.id }) {
            update(current.id) {
                it.text = sentence.text
                it.translation = sentence.translation
                if (sentence.language.isNotEmpty()) it.language = sentence.language
            }
            captions[fromOthers] = Caption(current.id, now)
            return current.id
        }
        val entry = Entry(
            UUID.randomUUID().toString(), now, !fromOthers,
            if (sentence.language.isEmpty() && !fromOthers) Language.vietnamese.code else sentence.language,
            sentence.text, sentence.translation,
            if (fromOthers) Language.vietnamese.code else partnerLanguage.code, State.Hearing,
        )
        entries.add(entry)
        captions[fromOthers] = Caption(entry.id, now)
        changed()
        return entry.id
    }

    /** A sentence ended: into the minutes, and read aloud at once (the user's always, the others' with Đọc to). */
    private fun finishSentence(sentence: SonioxTranscript.Sentence, fromOthers: Boolean) {
        val id = showSentence(sentence, fromOthers)
        captions.remove(fromOthers)
        // The phone's Vietnamese voice heard again through the microphone.
        if (fromOthers && echo.isEcho(sentence.text, now())) {
            remove(id)
            return
        }
        finishCaption(id)
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.state != State.Done || entry.translation.isEmpty()) return
        if (entry.mine || readAloud) say(entry.translation, entry.mine, language(entry.target))
    }

    /** A sentence Soniox ended but whose translation never came, or one it stopped sending words for. */
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
