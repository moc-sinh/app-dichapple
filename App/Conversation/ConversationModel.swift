import Foundation
import Observation
import UIKit

/// Runs on the audio queue: cuts what the others say into sentences, keeps what the user says
/// while the Tôi nói button is down, and in live mode passes the sound on a tenth of a second at a
/// time. Everything it reports goes to the main queue. Sendable because it is only ever used on
/// that one queue (its callbacks are set before listening starts).
final class Listener: @unchecked Sendable {
    private var segmenter: SpeechSegmenter
    /// The last moments of sound, so the user's first syllable is kept even when the button goes
    /// down as they start speaking.
    private var recent: [Int16] = []
    private var mine: [Int16]?
    /// Pieces of the user's turn already given (a long turn goes piece by piece).
    private var minePieces = 0
    private var chunk: [Int16] = []
    private var sinceLevel = 0
    /// Live mode: the sound itself is wanted.
    let streams: Bool
    var onUtterance: ((SpeechSegmenter.Utterance) -> Void)?
    /// What the user said while holding the button: the whole turn, or a long turn piece by piece
    /// (`first` and `last` piece of the turn).
    var onMine: ((_ samples: [Int16], _ first: Bool, _ last: Bool) -> Void)?
    var onLevel: ((Double, Bool) -> Void)?
    /// A tenth of a second of sound, and whether someone is speaking in it.
    var onChunk: ((Data, Bool) -> Void)?

    private static let recentSamples = PCM.rate * 3 / 10
    /// A turn longer than this goes on as it is said, a piece at a time (cut at a breath), so
    /// its translation is ready soon after the button goes up.
    private static let longestPiece = PCM.rate * 20

    init(silence: Double, streams: Bool) {
        // Ten seconds at most: a room that talks on is translated as it goes, not 15 s late.
        segmenter = SpeechSegmenter(silence: silence, maxLength: 10)
        self.streams = streams
    }

    func take(_ samples: [Int16]) {
        if mine != nil {
            mine?.append(contentsOf: samples)
            if (mine?.count ?? 0) >= Self.longestPiece { giveMine(last: false) }
        } else {
            for utterance in segmenter.push(samples) { deliver(utterance) }
        }
        recent.append(contentsOf: samples)
        if recent.count > Self.recentSamples { recent.removeFirst(recent.count - Self.recentSamples) }
        if streams {
            chunk.append(contentsOf: samples)
            let speech = mine != nil || segmenter.speaking
            while chunk.count >= PCM.rate / 10 {
                let data = PCM.data(chunk[..<(PCM.rate / 10)])
                chunk.removeFirst(PCM.rate / 10)
                DispatchQueue.main.async { [weak self] in self?.onChunk?(data, speech) }
            }
        }
        sinceLevel += samples.count
        if sinceLevel >= PCM.rate / 10 {
            sinceLevel = 0
            let level = PCM.dbfs(samples.suffix(PCM.rate / 10))
            let speaking = mine != nil || segmenter.speaking
            DispatchQueue.main.async { [weak self] in self?.onLevel?(level, speaking) }
        }
    }

    /// The button went down: what the others were saying goes out, and from now on (and the
    /// moment before, unless that was the phone's own voice) the sound is the user's.
    func beginMine() {
        guard mine == nil else { return }
        let phoneSpoke = segmenter.muted
        if let utterance = segmenter.flush() { deliver(utterance) }
        mine = phoneSpoke ? [] : recent
        minePieces = 0
    }

    func endMine() {
        giveMine(last: true)
    }

    /// The turn so far: all of it at the end, otherwise up to its last breath (the rest stays
    /// for the next piece).
    private func giveMine(last: Bool) {
        guard let held = mine else { return }
        let split = last ? held.count : SpeechSegmenter.split(held)
        let piece = Array(held[..<split])
        mine = last ? nil : Array(held[split...])
        let first = minePieces == 0
        minePieces += 1
        DispatchQueue.main.async { [weak self] in self?.onMine?(piece, first, last) }
    }

    func mute() {
        if let utterance = segmenter.mute() { deliver(utterance) }
    }

    func unmute() { segmenter.unmute() }

    func setSilence(_ seconds: Double) { segmenter.silence = seconds }

    func flush() {
        endMine()
        if let utterance = segmenter.flush() { deliver(utterance) }
        chunk = []
    }

    private func deliver(_ utterance: SpeechSegmenter.Utterance) {
        DispatchQueue.main.async { [weak self] in self?.onUtterance?(utterance) }
    }

    /// Made outside the main actor: the audio queue calls it.
    nonisolated static func sink(_ listener: Listener) -> ([Int16]) -> Void {
        { samples in listener.take(samples) }
    }
}

/// The calls to OpenAI (and the iPhone's and Google's voices), replaceable in a test.
struct ConversationServices {
    var transcribe: (_ apiKey: String, _ wav: Data, _ prompt: String) async throws -> SpeechServices.Transcript
    var translate: (_ apiKey: String, _ text: String, _ target: Language, _ context: [ChatLine],
                    _ model: TranslationModel) async throws -> Translation
    /// 24 kHz mono PCM16 of `text`, in `language`. `heard` gets it as it is made (OpenAI's and the
    /// iPhone's voices; Google's comes whole), so reading begins before the end is made.
    var speak: (_ apiKey: String, _ text: String, _ voice: String, _ language: Language,
                _ heard: @escaping @Sendable (Data) -> Void) async throws -> Data

    static let openAI = ConversationServices(
        transcribe: { try await SpeechServices(apiKey: $0).transcribe(wav: $1, prompt: $2) },
        translate: { try await Translator(apiKey: $0, model: $4).translate($1, to: $2, context: $3, spoken: true) },
        speak: { apiKey, text, voice, language, heard in
            if let google = GoogleVoice.name(of: voice), let googleKey = SharedStore.googleKey {
                do {
                    return try await GoogleVoice(apiKey: googleKey).speak(text, voice: google, language: language.code)
                } catch GoogleVoice.Failure.unsupported {
                    return try await AppleVoice.pcm(text, language: language.code, heard: heard)
                }
            }
            // A Google voice without its key reads with the iPhone's until the key is there.
            if !ConversationModel.isOpenAIVoice(voice) { return try await AppleVoice.pcm(text, language: language.code, heard: heard) }
            return try await SpeechServices(apiKey: apiKey).speak(text, voice: voice, language: language, heard: heard)
        })
}

/// The Hội thoại tab, for a meeting or a conversation with foreign partners.
///
/// While it runs, everything the others say comes up as Vietnamese text (Vietnamese speakers as
/// they spoke), and with Đọc to it is also read aloud through the speaker; Chỉ chữ only shows it.
/// When the user wants to speak, they hold the Tôi nói button (or tap it, and tap again at the
/// end): what they say is translated into the partners' language and read aloud in the voice chosen.
///
/// How it translates (Cách dịch):
/// - Chính xác: each sentence is recognised and translated once it ends (2–3 s), with the
///   glossary and the meeting's context; several at once, shown in the order they were said.
/// - Dịch liền: OpenAI's live speech translation, text (and voice) as the others speak, cut into
///   sentences, each translated again by GPT for the text; the user's words come out in the
///   partners' language while they speak.
/// - Soniox: Soniox's live recognition and translation, text word by word as people speak (a
///   fraction of Dịch liền's price). With an OpenAI key, sentences are cut speaker by speaker and
///   each is translated again by GPT with the meeting's context before it is read aloud.
///
/// A room that talks on is not let to run ahead of the voice: it reads faster while behind, and an
/// old sentence with newer ones waiting is left on the screen unread. The speed log (menu ⋯) says
/// how long each step of each sentence took, for a real meeting that felt slow.
@MainActor @Observable
final class ConversationModel {
    enum Engine: String, CaseIterable, Identifiable {
        case accurate, live, soniox
        var id: String { rawValue }
        var title: String {
            switch self {
            case .accurate: return "Chính xác (sau mỗi câu)"
            case .live: return "Dịch liền (ngay khi nói)"
            case .soniox: return "Soniox: dịch liền, rẻ nhất"
            }
        }

        /// The sound goes to a live session as it is heard (not sentence by sentence).
        var streams: Bool { self != .accurate }
    }

    /// How the others' words are translated (the user's own always use the app's translation model).
    enum Quality: String, CaseIterable, Identifiable {
        case economy, best
        var id: String { rawValue }
        var title: String { self == .economy ? "Tiết kiệm · GPT-6 Luna" : "Chuẩn nhất · GPT-6.1 Sol" }
        var model: TranslationModel { self == .economy ? .luna : .sol }
    }

    /// How large the translations are written, for a phone lying on the table.
    enum TextSize: Int, CaseIterable, Identifiable {
        case medium, large, extraLarge
        var id: Int { rawValue }
        var title: String {
            switch self {
            case .medium: return "Vừa"
            case .large: return "Lớn"
            case .extraLarge: return "Rất lớn"
            }
        }
    }

    struct Entry: Identifiable, Equatable {
        enum State: Equatable {
            /// Live captions still coming in.
            case hearing
            case recognizing
            case translating
            case done
            case failed(String)
        }

        let id: UUID
        let at: Date
        /// Said by the user with the Tôi nói button (translated and read aloud).
        let mine: Bool
        /// What it was spoken in ("vi", "zh"…), empty until known.
        var language: String
        var text: String
        var translation: String
        /// What it is translated into.
        var target: String
        var state: State
        /// Who said it, as Soniox tells the room's speakers apart ("1", "2"…); empty when not told.
        var speaker = ""

        var isVietnamese: Bool { language == Language.vietnamese.code }
    }

    private enum Keys {
        static let readAloud = "conversation.readAloud"
        static let engine = "conversation.engine"
        static let partner = "conversation.partner"
        static let silence = "conversation.silence"
        static let voice = "conversation.voice"
        static let quality = "conversation.quality"
        static let textSize = "conversation.textSize"
    }

    // MARK: Settings, kept between launches (this app's own defaults: the keyboard never needs them)

    /// Đọc to: the others' words are read aloud in Vietnamese too. Chỉ chữ: only shown.
    var readAloud: Bool {
        didSet {
            defaults.set(readAloud, forKey: Keys.readAloud)
            if running, readAloud != oldValue { cancelEchoIfNeeded() }
        }
    }
    var engine: Engine { didSet { defaults.set(engine.rawValue, forKey: Keys.engine) } }
    /// `Language.autoCode`: the language the others spoke last.
    var partnerCode: String { didSet { defaults.set(partnerCode, forKey: Keys.partner) } }
    /// The pause that ends someone's sentence.
    var silence: Double {
        didSet {
            defaults.set(silence, forKey: Keys.silence)
            let seconds = silence
            if let listener { audio.queue.async { listener.setSilence(seconds) } }
        }
    }
    var voice: String { didSet { defaults.set(voice, forKey: Keys.voice) } }
    var quality: Quality { didSet { defaults.set(quality.rawValue, forKey: Keys.quality) } }
    var textSize: TextSize { didSet { defaults.set(textSize.rawValue, forKey: Keys.textSize) } }

    static let silenceChoices: [Double] = [0.4, 0.6, 0.8, 1.0, 1.2, 1.4]
    /// The iPhone's own voice: free, made on the phone, less natural than OpenAI's.
    nonisolated static let iPhoneVoice = "iphone"
    static let voices: [(id: String, name: String)] = [
        ("marin", "Marin (nữ, OpenAI)"), ("cedar", "Cedar (nam, OpenAI)"),
        (GoogleVoice.id(GoogleVoice.female), "Aoede (nữ, Google)"), (GoogleVoice.id(GoogleVoice.male), "Charon (nam, Google)"),
        (iPhoneVoice, "Giọng iPhone (miễn phí)"),
    ]

    /// Made by OpenAI with the OpenAI key, and paid by the minute (the iPhone's and Google's are not).
    nonisolated static func isOpenAIVoice(_ voice: String) -> Bool { voice != iPhoneVoice && GoogleVoice.name(of: voice) == nil }

    // MARK: State

    private(set) var entries: [Entry] = []
    private(set) var running = false
    private(set) var starting = false
    /// 0…1, for the meter.
    private(set) var level: Double = 0
    private(set) var hearsSpeech = false
    private(set) var startedAt: Date?
    var problem: String?
    private(set) var micDenied = false
    /// The Tôi nói button is down: what the microphone hears is the user.
    private(set) var talking = false
    /// The phone is reading a translation aloud; `speakingMine`: the user's.
    private(set) var speaking = false
    private(set) var speakingMine = false
    private(set) var interrupted = false
    private(set) var summarizing = false
    /// The language the others spoke last (for "Tự động").
    private(set) var detectedPartner: Language?
    private(set) var meeting = Meeting()

    @ObservationIgnored var services = ConversationServices.openAI
    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private let audio = AudioIO()
    @ObservationIgnored private var listener: Listener?
    @ObservationIgnored private var apiKey: String?
    @ObservationIgnored private var wantsToTalk = false
    /// Something loud enough was said in the user's current turn.
    @ObservationIgnored private var heardThisTurn = false
    @ObservationIgnored private var waiting: [(id: UUID, samples: [Int16], mine: Bool)] = []
    @ObservationIgnored private var inFlight = 0
    /// Sound of sentences that could not be recognised (no network), to try again.
    @ObservationIgnored private var unrecognised: [UUID: [Int16]] = [:]
    @ObservationIgnored private var echo = EchoGuard()
    @ObservationIgnored private var speechChain: Task<Void, Never>?
    @ObservationIgnored private var speechGeneration = 0
    @ObservationIgnored private var playingAloud = 0
    @ObservationIgnored private var playingMine = 0
    @ObservationIgnored private var liveToVietnamese: LiveSession?
    @ObservationIgnored private var liveToPartner: LiveSession?
    /// The entry being heard live, per side (true: the others'), and when it last grew.
    @ObservationIgnored private var captions: [Bool: (id: UUID, last: Date)] = [:]
    /// Dịch liền's captions cut into sentences (true: the others'), and each caption's entry.
    @ObservationIgnored private var liveCaptions: [Bool: LiveCaptions] = [:]
    @ObservationIgnored private var liveEntries: [Bool: [Int: UUID]] = [:]
    @ObservationIgnored private var partnerTailUntil = Date.distantPast
    @ObservationIgnored private var liveRetries = 0
    @ObservationIgnored private var sonioxToVietnamese: SonioxSession?
    @ObservationIgnored private var sonioxToPartner: SonioxSession?
    /// What each Soniox session has heard (true: the others'), cut into sentences.
    @ObservationIgnored private var transcripts: [Bool: SonioxTranscript] = [:]
    /// Dịch liền: the others' sound is sent while someone speaks, not while the room is quiet.
    @ObservationIgnored private var othersGate = LiveGate()
    /// Seconds sent to Dịch liền and not yet in the meeting's cost (added a sentence at a time,
    /// not ten times a second).
    @ObservationIgnored private var unbilledLive = 0.0
    /// Voices already made: Đọc lại plays them at once, without paying again.
    @ObservationIgnored private var voiceCache = VoiceCache()
    @ObservationIgnored private var aloneTask: Task<Void, Never>?
    /// Soniox sentences GPT is translating, by their number in the transcript (true: the others').
    @ObservationIgnored private var sonioxEntries: [Bool: [Int: UUID]] = [:]
    /// Seconds of sound sent to each Soniox session (true: the others'), for how far it lags.
    @ObservationIgnored private var sonioxSent: [Bool: Double] = [:]
    /// Entries already read aloud: one read from Soniox's translation is not read again for GPT's.
    @ObservationIgnored private var voiced: Set<UUID> = []
    /// Counts the others' sentences queued to be read, so one can tell how many wait behind it.
    @ObservationIgnored private var theirsQueued = 0
    @ObservationIgnored private var lastDropNote = Date.distantPast
    @ObservationIgnored private var speedLog = SpeedLog()

    /// Sentences recognised and translated at once: a busy meeting does not fall behind.
    private static let parallel = 4
    /// The others' sentence whose voice is this late (seconds after its translation), with newer
    /// ones waiting, is left on the screen unread: the voice keeps up with the room.
    private static let stale = 6.0

    init() {
        let defaults = UserDefaults.standard
        readAloud = defaults.object(forKey: Keys.readAloud) as? Bool ?? true
        engine = Engine(rawValue: defaults.string(forKey: Keys.engine) ?? "") ?? .accurate
        partnerCode = defaults.string(forKey: Keys.partner) ?? Language.autoCode
        let savedSilence = defaults.double(forKey: Keys.silence)
        silence = Self.silenceChoices.contains(savedSilence) ? savedSilence : 0.8
        let savedVoice = defaults.string(forKey: Keys.voice) ?? ""
        voice = Self.voices.contains { $0.id == savedVoice } ? savedVoice : "marin"
        quality = Quality(rawValue: defaults.string(forKey: Keys.quality) ?? "") ?? .economy
        textSize = TextSize(rawValue: defaults.integer(forKey: Keys.textSize)) ?? .medium
        audio.onInterruption = { [weak self] began in self?.interrupted = began }
        if defaults.bool(forKey: "conversationDemo") { fillDemo() }
    }

    /// The language the user's words are translated into: the one chosen, or with "Tự động" the
    /// one the others spoke last.
    var partnerLanguage: Language {
        if partnerCode != Language.autoCode, let chosen = Language.named(partnerCode) { return chosen }
        return detectedPartner ?? Language.named(SharedStore.lastPartnerCode) ?? Language.all[0]
    }

    /// OpenAI's live translation speaks 13 languages; Thai, Malay and Arabic are not among them.
    var liveSpeaksPartner: Bool { LiveSession.outputCode(for: partnerLanguage) != nil }

    // MARK: Start and stop

    func start() async {
        guard !running, !starting else { return }
        problem = nil
        // Soniox hears and translates by itself: the OpenAI key is only for its voices and summaries.
        let key: String
        if engine == .soniox {
            guard SharedStore.sonioxKey != nil else {
                problem = "Chưa có key Soniox. Dán key trong Cài đặt › Soniox, hoặc đổi Cách dịch (menu ⋯)."
                return
            }
            key = SharedStore.apiKey ?? ""
            if key.isEmpty, Self.isOpenAIVoice(voice) { voice = Self.iPhoneVoice }
        } else {
            guard let openAIKey = SharedStore.apiKey else {
                problem = TranslateError.noKey.errorDescription
                return
            }
            key = openAIKey
        }
        starting = true
        defer { starting = false }
        guard await AudioIO.requestPermission() else {
            micDenied = true
            problem = "MSTrans chưa được dùng micro. Bật trong Cài đặt › MSTrans › Micro."
            return
        }
        micDenied = false
        apiKey = key
        let listener = Listener(silence: silence, streams: engine.streams)
        listener.onUtterance = { [weak self] utterance in self?.heard(utterance) }
        listener.onMine = { [weak self] samples, first, last in self?.said(samples, first: first, last: last) }
        listener.onLevel = { [weak self] level, speaking in self?.heard(level: level, speaking: speaking) }
        listener.onChunk = { [weak self] data, speech in self?.stream(data, speech: speech) }
        self.listener = listener
        audio.onSamples = Listener.sink(listener)
        do {
            try audio.start(echoCancelling: echoCancelling)
        } catch {
            problem = error.localizedDescription
            self.listener = nil
            return
        }
        running = true
        interrupted = false
        startedAt = Date()
        if meeting.lines.isEmpty { meeting.started = Date() }
        UIApplication.shared.isIdleTimerDisabled = true
        if engine == .live { openLive() }
        if engine == .soniox { openSoniox() }
        if wantsToTalk { talk(true) }
        warmUpGoogle()
        log(nil, "Bắt đầu · \(engine.rawValue) · \(readAloud ? "đọc to" : "chỉ chữ") · dịch đối phương \(Self.name(of: quality.model))"
            + " · lời tôi \(Self.name(of: SharedStore.model)) · giọng \(voice)")
    }

    /// With a Google voice, the connection is opened before the first sentence needs it, and a key
    /// that does not work is told at once rather than at the first sentence.
    private func warmUpGoogle() {
        guard GoogleVoice.name(of: voice) != nil, let googleKey = SharedStore.googleKey else { return }
        Task { [weak self] in
            guard let failure = await GoogleVoice(apiKey: googleKey).warmUp() else { return }
            self?.problem = failure.errorDescription
        }
    }

    func stop() {
        guard running else { return }
        if talking { talk(false) }
        wantsToTalk = false
        running = false
        level = 0
        hearsSpeech = false
        stopSpeaking()
        audio.stop()
        // After the sound already queued: the sentence being said still goes in.
        if let listener { audio.queue.async { listener.flush() } }
        // The sentences being said are translated as they are.
        for fromOthers in [true, false] {
            if let sentence = transcripts[fromOthers]?.finish() { finishSentence(sentence, fromOthers: fromOthers) }
        }
        closeLive()
        endLiveCaptions(all: true)
        for current in captions.values { finishCaption(current.id) }
        captions = [:]
        billLive()
        UIApplication.shared.isIdleTimerDisabled = false
        MeetingStore.save(meeting)
        log(nil, "Dừng")
    }

    /// Sentences still being recognised or translated.
    private var settling: Bool { entries.contains { $0.state == .recognizing || $0.state == .translating } }

    /// A new meeting can start once listening has stopped and the last sentences (and a summary
    /// being written) are in this one.
    var canStartNewMeeting: Bool { !running && !summarizing && !settling && !entries.isEmpty }

    /// The meeting so far goes to the saved ones; the next starts empty.
    func newMeeting() {
        guard !running, !summarizing, !settling else { return }
        billLive()
        MeetingStore.save(meeting)
        meeting = Meeting()
        entries = []
        unrecognised = [:]
        detectedPartner = nil
        problem = nil
    }

    private func heard(level: Double, speaking: Bool) {
        guard running else { return }
        // -60 dBFS (a quiet room) to -15 (a voice close by).
        self.level = max(0, min(1, (level + 60) / 45))
        hearsSpeech = speaking
        if engine == .live { endLiveCaptions(all: false) }
        if engine == .soniox { expireSentences() }
    }

    // MARK: The Tôi nói button

    /// The button went down (true) or up. Pressed before listening started, listening starts.
    func talk(_ down: Bool) {
        wantsToTalk = down
        guard running else {
            if down { Task { await start() } }
            return
        }
        guard talking != down else { return }
        if down {
            // The user speaks now: the phone stops reading what it was reading.
            stopSpeaking()
            if engine == .live {
                openPartnerSession()
                guard liveToPartner != nil else {
                    problem = "Dịch liền chưa nói được \(partnerLanguage.name). Đổi Cách dịch sang Chính xác (menu ⋯)."
                    wantsToTalk = false
                    return
                }
            }
            if engine == .soniox { openSonioxPartner() }
        } else if engine == .live {
            // A moment of silence lets the last words through.
            partnerTailUntil = Date().addingTimeInterval(1.5)
        } else if engine == .soniox {
            // The user's last words are settled and translated now, not after a pause.
            sonioxToPartner?.finalize()
        }
        talking = down
        if engine == .accurate, let listener {
            audio.queue.async { down ? listener.beginMine() : listener.endMine() }
        }
    }

    // MARK: Chính xác: sentence by sentence

    /// Someone else finished a sentence.
    private func heard(_ utterance: SpeechSegmenter.Utterance) {
        guard engine == .accurate, apiKey != nil else { return }
        enqueue(utterance.samples, mine: false, at: Date().addingTimeInterval(-utterance.duration))
    }

    /// The user let go of the button (`last`), or a long turn goes on piece by piece.
    private func said(_ samples: [Int16], first: Bool, last: Bool) {
        guard apiKey != nil else { return }
        if first { heardThisTurn = false }
        let seconds = Double(samples.count) / Double(PCM.rate)
        // Nothing louder than the room: the button was tapped, nobody spoke (or not in this piece).
        let loudest = stride(from: 0, to: samples.count - SpeechSegmenter.frame, by: SpeechSegmenter.frame)
            .map { PCM.dbfs(samples[$0..<$0 + SpeechSegmenter.frame]) }.max() ?? -100
        guard seconds >= 0.5, loudest > -50 else {
            if last, !heardThisTurn { problem = "Chưa nghe thấy bạn nói. Giữ nút Tôi nói trong lúc nói nhé." }
            return
        }
        heardThisTurn = true
        enqueue(samples, mine: true, at: Date().addingTimeInterval(-seconds))
    }

    private func enqueue(_ samples: [Int16], mine: Bool, at: Date) {
        let entry = Entry(id: UUID(), at: at, mine: mine, language: mine ? Language.vietnamese.code : "", text: "",
                          translation: "", target: mine ? partnerLanguage.code : Language.vietnamese.code,
                          state: .recognizing)
        entries.append(entry)
        // The user's own words go first (in the order said): the room is waiting for them.
        let place = mine ? (waiting.firstIndex(where: { !$0.mine }) ?? waiting.count) : waiting.count
        waiting.insert((entry.id, samples, mine), at: place)
        pump()
    }

    private func pump() {
        while inFlight < Self.parallel, !waiting.isEmpty {
            let next = waiting.removeFirst()
            inFlight += 1
            Task {
                await self.recognise(next.id, next.samples)
                self.inFlight -= 1
                self.pump()
            }
        }
    }

    private func recognise(_ id: UUID, _ samples: [Int16]) async {
        guard let apiKey else { return }
        let seconds = Double(samples.count) / Double(PCM.rate)
        let wav = await Task.detached(priority: .userInitiated) { PCM.wav(samples) }.value
        let transcript: SpeechServices.Transcript
        let began = Date()
        do {
            transcript = try await services.transcribe(apiKey, wav, recognitionPrompt())
            log(id, "nhận \(SpeedLog.seconds(Date().timeIntervalSince(began))) · câu dài \(SpeedLog.seconds(seconds))")
        } catch is CancellationError {
            return remove(id)
        } catch {
            unrecognised[id] = samples
            return update(id) { $0.state = .failed(error.localizedDescription) }
        }
        unrecognised[id] = nil
        charge { $0.transcribed(seconds: seconds) }
        guard let entry = entries.first(where: { $0.id == id }) else { return }
        let text = transcript.text
        // Noise the recognizer wrote words for, or the phone's own voice heard again.
        if TranscriptFilter.isNoise(text, seconds: seconds) || (!entry.mine && echo.isEcho(text, at: Self.now)) {
            if entry.mine { problem = "Chưa nghe rõ bạn nói. Thử lại, nói gần điện thoại hơn." }
            return remove(id)
        }
        let language = Self.language(of: text, heard: transcript.language)
        update(id) {
            $0.text = text
            $0.language = language
        }
        await translate(id)
    }

    private func translate(_ id: UUID) async {
        guard let apiKey, let entry = entries.first(where: { $0.id == id }) else { return }
        // A Vietnamese speaker in the room: shown as they said it.
        if !entry.mine, entry.isVietnamese {
            update(id) {
                $0.target = Language.vietnamese.code
                $0.state = .done
            }
            return record(id)
        }
        if !entry.mine { noteSpoken(entry.language, text: entry.text) }
        let target = entry.mine ? partnerLanguage : Language.vietnamese
        update(id) {
            $0.target = target.code
            $0.state = .translating
        }
        // What the partners hear is the user's own words: the app's translation model. The others'
        // words are only read by the user: the cheaper model by default.
        let model = entry.mine ? SharedStore.model : quality.model
        let began = Date()
        do {
            let result = try await services.translate(apiKey, entry.text, target, context(before: entry.at), model)
            charge { $0.used(result.usage, by: model) }
            log(id, "GPT \(Self.name(of: model)) \(SpeedLog.seconds(Date().timeIntervalSince(began)))")
            let translation = result.text
            update(id) {
                $0.translation = translation
                $0.state = .done
            }
            record(id)
            if entry.mine || readAloud { say(translation, mine: entry.mine, language: target, entry: id) }
        } catch is CancellationError {
        } catch {
            update(id) { $0.state = .failed(error.localizedDescription) }
        }
    }

    func retry(_ id: UUID) {
        guard let entry = entries.first(where: { $0.id == id }), case .failed = entry.state else { return }
        if apiKey == nil { apiKey = SharedStore.apiKey }
        if let samples = unrecognised[id] {
            update(id) { $0.state = .recognizing }
            Task { await recognise(id, samples) }
        } else if !entry.text.isEmpty {
            Task { await translate(id) }
        }
    }

    /// Reads a translation aloud again: at once, in Chỉ chữ too, and after listening has stopped.
    func repeatAloud(_ id: UUID) {
        guard let entry = entries.first(where: { $0.id == id }), !entry.translation.isEmpty else { return }
        if apiKey == nil { apiKey = SharedStore.apiKey }
        let language = Self.language(code: entry.target)
        if running {
            say(entry.translation, mine: entry.mine, language: language, requested: true)
        } else {
            sayAlone(entry.translation, language: language)
        }
    }

    static func language(code: String) -> Language {
        code == Language.vietnamese.code ? .vietnamese : Language.named(code) ?? .vietnamese
    }

    /// The language a sentence was spoken in: what the recognizer heard, checked against the text
    /// (Vietnamese written with its marks is Vietnamese, whatever was heard).
    static func language(of text: String, heard: String?) -> String {
        if LanguageDetector.isVietnamese(text) { return Language.vietnamese.code }
        if let heard, !heard.isEmpty {
            if heard == "zh", LanguageDetector.language(of: text)?.code == "zh-Hant" { return "zh-Hant" }
            return heard
        }
        return LanguageDetector.language(of: text)?.code ?? "en"
    }

    /// A foreign sentence long enough to tell: "Tự động" now means its language. A word of English
    /// in a meeting held in Chinese ("OK") does not change it.
    private func noteSpoken(_ code: String, text: String) {
        guard code != Language.vietnamese.code, TranscriptFilter.syllableCount(text) >= 4 else { return }
        guard let language = Language.named(code) ?? LanguageDetector.language(of: text) else { return }
        detectedPartner = language
        if Language.named(language.code) != nil { SharedStore.lastPartnerCode = language.code }
    }

    /// The meeting's latest sentences, as the translator's context: those still being translated
    /// too (sentences are translated several at once), with who said them when known.
    private func context(before at: Date) -> [ChatLine] {
        entries.filter { $0.at < at && !$0.text.isEmpty }.suffix(8).map {
            ChatLine(side: $0.mine ? .me : .partner, text: $0.speaker.isEmpty ? $0.text : "(Người \($0.speaker)) \($0.text)",
                     date: $0.at)
        }
    }

    /// Names and terms the recognizer should spell right: the glossary only. The last sentences
    /// went with it once, and a prompt in Chinese had a Vietnamese sentence written in Chinese.
    private func recognitionPrompt() -> String {
        var terms: [String] = []
        for line in SharedStore.glossary.split(whereSeparator: \.isNewline) {
            for side in line.split(separator: "=") {
                let term = side.trimmingCharacters(in: .whitespaces)
                if !term.isEmpty, term.count <= 40 { terms.append(term) }
            }
        }
        return terms.isEmpty ? "" : "Thuật ngữ / Terms: " + terms.prefix(40).joined(separator: ", ")
    }

    /// Into the minutes, in the order things were said (sentences are translated several at once).
    private func record(_ id: UUID) {
        guard let entry = entries.first(where: { $0.id == id }), !entry.text.isEmpty else { return }
        let line = Meeting.Line(at: entry.at, mine: entry.mine, language: entry.language, text: entry.text,
                                translation: entry.translation, target: entry.target,
                                speaker: entry.speaker.isEmpty ? nil : entry.speaker)
        let index = meeting.lines.lastIndex { $0.at <= line.at }.map { $0 + 1 } ?? 0
        meeting.lines.insert(line, at: index)
        billLive()
        MeetingStore.save(meeting)
    }

    /// Into the meeting's cost (kept with it).
    private func charge(_ change: (inout CostMeter) -> Void) {
        var cost = meeting.cost ?? CostMeter()
        change(&cost)
        meeting.cost = cost
    }

    private func billLive() {
        guard unbilledLive > 0 else { return }
        let seconds = unbilledLive
        let soniox = engine == .soniox
        unbilledLive = 0
        charge { $0.streamed(seconds: seconds, soniox: soniox) }
    }

    /// What this meeting has cost so far, estimated.
    var spent: Double {
        let rate = engine == .soniox ? CostMeter.Rate.sonioxPerMinute : CostMeter.Rate.livePerMinute
        return (meeting.cost?.dollars ?? 0) + unbilledLive / 60 * rate
    }

    private func update(_ id: UUID, _ change: (inout Entry) -> Void) {
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        change(&entries[index])
    }

    private func remove(_ id: UUID) {
        entries.removeAll { $0.id == id }
        unrecognised[id] = nil
    }

    private static var now: Double { Date.timeIntervalSinceReferenceDate }

    // MARK: Reading aloud

    /// Each translation is fetched as soon as it is ready and read after the one before, in the
    /// order the sentences were said (`entry`: one translated sooner waits a little for those said
    /// before it on the same side). `mine`: the user's words, in the partners' language; otherwise
    /// the others', in Vietnamese. `requested`: Đọc lại, read at once (after what is being read),
    /// even in Chỉ chữ and without waiting for the room to pause.
    private func say(_ text: String, mine: Bool, language: Language, requested: Bool = false, entry id: UUID? = nil) {
        guard let apiKey, running else { return }
        let generation = speechGeneration
        let ready = Date()
        let sound = voiceTask(text, voice: voice, language: language, apiKey: apiKey)
        guard let id else {
            return queueSpeech(sound, text: text, mine: mine, requested: requested, generation: generation, ready: ready, entry: nil)
        }
        Task {
            await waitForEarlier(than: id)
            queueSpeech(sound, text: text, mine: mine, requested: requested, generation: generation, ready: ready, entry: id)
        }
    }

    /// Read aloud once: a sentence read from Soniox's translation is not read again when GPT's comes.
    private func sayOnce(_ entry: Entry) {
        guard voiced.insert(entry.id).inserted else { return }
        say(entry.translation, mine: entry.mine, language: Self.language(code: entry.target), entry: entry.id)
    }

    /// Read after what is already queued (unless the queue was dropped meanwhile).
    private func queueSpeech(_ sound: VoiceStream, text: String, mine: Bool, requested: Bool, generation: Int, ready: Date,
                             entry id: UUID?) {
        guard generation == speechGeneration else { return }
        var serial = 0
        if !mine, !requested {
            theirsQueued += 1
            serial = theirsQueued
        }
        let previous = speechChain
        speechChain = Task { [weak self] in
            await previous?.value
            guard let self, generation == self.speechGeneration else { return }
            await self.play(sound, text: text, generation: generation, mine: mine, requested: requested, ready: ready,
                            serial: serial, entry: id)
        }
    }

    /// Until the sentences said shortly before `id` on its side (the user's, or the others') are
    /// recognised and translated, eight seconds at most.
    private func waitForEarlier(than id: UUID) async {
        for _ in 0..<80 {
            guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
            let entry = entries[index]
            let earlierBusy = entries[..<index].contains {
                $0.mine == entry.mine && entry.at.timeIntervalSince($0.at) < 60
                    && ($0.state == .recognizing || $0.state == .translating)
            }
            guard earlierBusy else { return }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    /// Read again after listening stopped: the speaker only.
    private func sayAlone(_ text: String, language: Language) {
        let voice = voice
        guard !Self.isOpenAIVoice(voice) || apiKey != nil else {
            problem = TranslateError.noKey.errorDescription
            return
        }
        aloneTask?.cancel()
        audio.stopAlone()
        let sound = voiceTask(text, voice: voice, language: language, apiKey: apiKey ?? "")
        aloneTask = Task { [weak self] in
            let result = await sound.whole()
            guard let self, !Task.isCancelled, !self.running else { return }
            switch result {
            case .success(let pcm):
                self.audio.playAlone(pcm) {}
            case .failure(let error):
                if !(error is CancellationError) { self.problem = Self.voiceProblem(error) }
            }
            MeetingStore.save(self.meeting)
        }
    }

    /// The voice for `text`: the one made before, or a new one (paid for, if OpenAI's), its sound
    /// coming in as it is made.
    private func voiceTask(_ text: String, voice: String, language: Language, apiKey: String) -> VoiceStream {
        let key = VoiceCache.key(text: text, voice: voice, language: language.code)
        if let cached = voiceCache.get(key) { return VoiceStream(made: cached) }
        let sound = VoiceStream()
        let speak = services.speak
        Task { [weak self] in
            do {
                let pcm = try await speak(apiKey, text, voice, language) { sound.append($0) }
                sound.finish(pcm)
                self?.voiceCache.put(key, pcm)
                if Self.isOpenAIVoice(voice) { self?.charge { $0.voiced(seconds: PCM.seconds(pcm)) } }
            } catch {
                sound.finish(nil, failure: error)
            }
        }
        return sound
    }

    private static func voiceProblem(_ error: Error) -> String {
        if let apple = error as? AppleVoice.Failure, let text = apple.errorDescription { return text }
        return "Không đọc to được: \(error.localizedDescription) Bản dịch vẫn hiện trên màn hình."
    }

    /// Sound enough to begin reading without running dry while the rest is made: 0.4 s.
    private static let startingSound = Int(Double(PCM.rate * 2) * 0.4)

    /// `serial`: the others' sentence's place in the queue (0 for the user's and Đọc lại).
    private func play(_ sound: VoiceStream, text: String, generation: Int, mine: Bool, requested: Bool, ready: Date,
                      serial: Int, entry id: UUID?) async {
        // Never over the user: a translation ready while they speak waits until they let go.
        while talking, running { try? await Task.sleep(nanoseconds: 100_000_000) }
        // Like an interpreter, the others' words are read once the room pauses: read over someone
        // still talking, Chính xác would stop listening to them. A sentence that finds no pause
        // within ten seconds of being translated gets the text only (so the voice never falls
        // far behind a room that talks on).
        if !mine, !requested, engine == .accurate {
            let latest = ready.addingTimeInterval(10)
            while hearsSpeech, running, Date() < latest, generation == speechGeneration {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            if hearsSpeech { return }
        }
        guard running, generation == speechGeneration, mine || requested || readAloud else { return }
        // Read as soon as there is enough to go on with (OpenAI's and the iPhone's voices come as they
        // are made), not once all of it is made: the rest is played on as it comes.
        var next = sound.sound(after: 0)
        while !next.finished, next.pcm.count < Self.startingSound, running, generation == speechGeneration {
            try? await Task.sleep(nanoseconds: 20_000_000)
            next = sound.sound(after: 0)
        }
        guard running, generation == speechGeneration else { return }
        guard next.pcm.count > 1 else {
            if let error = next.failure, !(error is CancellationError) { problem = Self.voiceProblem(error) }
            return
        }
        // A room that talks on: an old sentence is left on the screen while newer ones wait to be
        // read, and the voice reads faster while it is behind (the pitch stays).
        let behind = Date().timeIntervalSince(ready)
        let newer = serial > 0 ? theirsQueued - serial : 0
        if newer > 0, behind > Self.stale {
            log(id, "bỏ đọc: chậm \(SpeedLog.seconds(behind)), \(newer) câu mới hơn đang chờ")
            return
        }
        let rate: Float = serial == 0 ? 1 : newer >= 2 || behind > 5 ? 1.35 : newer >= 1 || behind > 2.5 ? 1.2 : 1
        audio.setRate(rate)
        if serial > 0 {
            let faster = String(format: " · nhanh %.2f×", rate).replacingOccurrences(of: ".", with: ",")
            log(id, "đọc sau \(SpeedLog.seconds(behind))" + (rate > 1 ? faster : "")
                + (newer > 0 ? " · \(newer) câu chờ" : ""))
        }
        beginAloud(mine: mine)
        echo.spoke(text, at: Self.now)
        var played = 0
        var playing = 0
        while running, generation == speechGeneration {
            // Whole samples only: the odd byte goes with the next part.
            let whole = next.pcm.count / 2 * 2
            if whole > 0 {
                played += whole
                playing += 1
                audio.play(next.pcm.prefix(whole)) { playing -= 1 }
            }
            if next.finished { break }
            try? await Task.sleep(nanoseconds: 20_000_000)
            next = sound.sound(after: played)
        }
        // Until the last part has been heard (or playback was stopped).
        let heardBy = Date().addingTimeInterval(PCM.seconds(Data(count: played)) + 2)
        while playing > 0, Date() < heardBy { try? await Task.sleep(nanoseconds: 20_000_000) }
        endAloud(mine: mine)
        if let error = next.failure, !(error is CancellationError), generation == speechGeneration {
            problem = Self.voiceProblem(error)
        }
    }

    /// The button went down, or listening stopped: what is being read, or waiting to be, stops.
    private func stopSpeaking() {
        speechGeneration += 1
        speechChain = nil
        audio.stopPlayback()
    }

    /// The microphone hears the phone's speaker. Chính xác listens to nobody meanwhile (it would
    /// translate its own voice); Dịch liền cancels the echo and only stops sending the others' sound
    /// while the user's words are spoken.
    private func beginAloud(mine: Bool) {
        playingAloud += 1
        if mine { playingMine += 1 }
        speaking = true
        speakingMine = playingMine > 0
        if engine == .accurate, playingAloud == 1, let listener { audio.queue.async { listener.mute() } }
    }

    private func endAloud(mine: Bool) {
        // The room's echo dies down a moment after the voice.
        Task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            playingAloud = max(0, playingAloud - 1)
            if mine { playingMine = max(0, playingMine - 1) }
            speakingMine = playingMine > 0
            if playingAloud == 0 {
                speaking = false
                if let listener { audio.queue.async { listener.unmute() } }
            }
        }
    }

    // MARK: Dịch liền

    private func openLive() {
        liveRetries = 0
        othersGate.reset()
        liveCaptions = [:]
        liveEntries = [:]
        liveToVietnamese = makeSession(Language.vietnamese.code)
        openPartnerSession()
    }

    private func makeSession(_ code: String) -> LiveSession? {
        guard let apiKey else { return nil }
        let session = LiveSession(apiKey: apiKey, language: code)
        session.onEvent = { [weak self, weak session] event in
            guard let self, let session else { return }
            self.live(event, from: session)
        }
        session.open()
        return session
    }

    /// The session that speaks the partners' language, opened again when that language changes.
    private func openPartnerSession() {
        guard let code = LiveSession.outputCode(for: partnerLanguage) else {
            liveToPartner?.close()
            liveToPartner = nil
            return
        }
        if let current = liveToPartner, current.language == code, current.isOpen { return }
        liveToPartner?.close()
        liveToPartner = makeSession(code)
    }

    private func closeLive() {
        liveToVietnamese?.close()
        liveToPartner?.close()
        liveToVietnamese = nil
        liveToPartner = nil
        sonioxToVietnamese?.close()
        sonioxToPartner?.close()
        sonioxToVietnamese = nil
        sonioxToPartner = nil
        transcripts = [:]
    }

    /// Apple's echo cancelling, which keeps the phone's voice out of what the microphone hears, but
    /// also favours a voice close to the phone: far voices in a meeting room come through weaker, and
    /// are missed or misheard. Only when the phone speaks while it listens: Dịch liền (it speaks the
    /// user's words as they talk), and Soniox reading the others' words aloud. Soniox in Chỉ chữ
    /// speaks only the user's words, while the room's sound is held back; Chính xác does not
    /// listen while the phone speaks.
    private var echoCancelling: Bool { engine == .live || (engine == .soniox && readAloud) }

    /// Đọc to or Chỉ chữ chosen while listening: the microphone is opened again with or without it.
    private func cancelEchoIfNeeded() {
        do {
            try audio.setEchoCancelling(echoCancelling)
            log(nil, echoCancelling ? "Bật khử tiếng vọng (đọc to)" : "Tắt khử tiếng vọng: micro nghe xa hơn")
        } catch {
            problem = error.localizedDescription
        }
    }

    /// The microphone's sound goes to the session for whoever speaks: the user's while the button is
    /// down, everyone else's otherwise. The other session gets silence for a moment, so it finishes
    /// what it was saying. Dịch liền is not sent a quiet room (it is paid by the minute sent, 2 USD an
    /// hour); Soniox is sent all of it (0.18 USD an hour): a quiet or far voice the level meter took
    /// for the room, or the first syllable after a pause, is never lost, and Soniox hears for itself
    /// where a sentence ends.
    private func stream(_ data: Data, speech: Bool) {
        guard running, engine.streams else { return }
        let silent = Data(count: data.count)
        if talking {
            sendMine(data)
            if engine == .soniox {
                sendOthers(silent)
            } else {
                for chunk in othersGate.feed(silent, speech: false) { sendOthers(chunk) }
            }
        } else {
            // The phone speaking the partners' language is not someone in the room to translate; nor
            // is its Vietnamese (Đọc lại) when no echo cancelling takes it out.
            let phoneSpeaks = playingMine > 0 || (playingAloud > 0 && !echoCancelling)
            if engine == .soniox {
                sendOthers(phoneSpeaks ? silent : data)
            } else {
                let chunks = othersGate.feed(phoneSpeaks ? silent : data, speech: speech && !phoneSpeaks)
                for chunk in chunks { sendOthers(chunk) }
            }
            if Date() < partnerTailUntil { sendMine(silent) }
        }
    }

    private func sendOthers(_ chunk: Data) {
        if engine == .soniox {
            guard let session = sonioxToVietnamese else { return }
            session.send(audio: chunk)
            sonioxSent[true, default: 0] += PCM.seconds(chunk)
        } else {
            guard let session = liveToVietnamese else { return }
            session.send(audio: chunk)
        }
        unbilledLive += PCM.seconds(chunk)
    }

    private func sendMine(_ chunk: Data) {
        if engine == .soniox {
            guard let session = sonioxToPartner else { return }
            session.send(audio: chunk)
            sonioxSent[false, default: 0] += PCM.seconds(chunk)
        } else {
            guard let session = liveToPartner else { return }
            session.send(audio: chunk)
        }
        unbilledLive += PCM.seconds(chunk)
    }

    private func live(_ event: LiveEvent, from session: LiveSession) {
        guard session === liveToVietnamese || session === liveToPartner else { return }
        let fromOthers = session === liveToVietnamese
        switch event {
        case .ready:
            liveRetries = 0
            if problem?.hasPrefix("Mất kết nối") == true { problem = nil }
        case .source(let delta):
            liveSource(delta, fromOthers: fromOthers)
        case .target(let delta):
            liveTarget(delta, fromOthers: fromOthers)
        case .audio(let pcm):
            if fromOthers {
                // Đọc to only, never over the user, and not a Vietnamese speaker's words again.
                guard readAloud, !talking, !othersSpeakVietnamese else { return }
                // Behind a room that talks on: read faster, and past six seconds behind the sound
                // is let go (the text stays), so the voice is never far after the speaker.
                let backlog = audio.backlog
                if backlog > 6 {
                    if Date().timeIntervalSince(lastDropNote) > 5 {
                        lastDropNote = Date()
                        log(nil, "Dịch liền: chậm \(SpeedLog.seconds(backlog)), bỏ bớt tiếng đọc")
                    }
                    return
                }
                audio.setRate(backlog > 4 ? 1.35 : backlog > 2 ? 1.2 : 1)
                beginAloud(mine: false)
                audio.play(pcm) { [weak self] in self?.endAloud(mine: false) }
            } else {
                audio.setRate(1)
                beginAloud(mine: true)
                audio.play(pcm) { [weak self] in self?.endAloud(mine: true) }
            }
        case .failed(let code, let message):
            if code.contains("api_key") || code.contains("auth") || message.localizedCaseInsensitiveContains("api key") {
                problem = "API key không dùng được cho Dịch liền. Kiểm tra lại trong Cài đặt."
                stop()
            } else if code == "model_not_available" || code == "model_not_found" {
                problem = "Key này chưa dùng được Dịch liền (gpt-realtime-translate). Dùng chế độ Chính xác."
                stop()
            } else if code == "network" {
                problem = "Mất kết nối. Đang nối lại…"
            } else {
                problem = message.isEmpty ? "Dịch liền gặp lỗi (\(code))." : message
            }
        case .closed:
            guard running, engine == .live else { return }
            // Dropped (network, a time limit): opened again, a few times. Let go of at once, so
            // a second word of it closing (the server's, then the socket's) opens nothing more.
            session.close()
            if fromOthers { liveToVietnamese = nil } else { liveToPartner = nil }
            // What the dropped session was still translating will not come.
            endLiveCaptions(all: true, sides: [fromOthers])
            log(nil, "Dịch liền mất kết nối, nối lại")
            liveRetries += 1
            guard liveRetries <= 5 else {
                problem = "Không nối lại được Dịch liền. Kiểm tra mạng rồi bấm nút tròn để nghe lại."
                return stop()
            }
            Task {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard self.running, self.engine == .live else { return }
                if fromOthers {
                    if self.liveToVietnamese == nil { self.liveToVietnamese = self.makeSession(Language.vietnamese.code) }
                } else {
                    self.openPartnerSession()
                }
            }
        }
    }

    /// The sentence being heard live is Vietnamese (a Vietnamese speaker in the room).
    private var othersSpeakVietnamese: Bool {
        guard let text = liveCaptions[true]?.current?.text, text.count >= 12 else { return false }
        return LanguageDetector.isVietnamese(text)
    }

    /// More of what was said, live: into its caption, which may end the one before.
    private func liveSource(_ delta: String, fromOthers: Bool) {
        var router = liveCaptions[fromOthers] ?? LiveCaptions()
        let ended = router.heard(delta, now: Self.now)
        liveCaptions[fromOthers] = router
        if let ended { endLiveCaption(ended, fromOthers: fromOthers) }
        if let current = router.current { showLiveCaption(current, fromOthers: fromOthers) }
        settleLiveCaptions(fromOthers)
    }

    /// More of the live translation: to the caption it belongs to (one already ended, while its
    /// translation is still coming), not always to the one being heard.
    private func liveTarget(_ delta: String, fromOthers: Bool) {
        var router = liveCaptions[fromOthers] ?? LiveCaptions()
        let number = router.translated(delta, now: Self.now)
        liveCaptions[fromOthers] = router
        if let current = router.current, current.number == number {
            showLiveCaption(current, fromOthers: fromOthers)
        } else if let caption = router.awaiting.first(where: { $0.number == number }),
                  let id = liveEntries[fromOthers]?[number], let entry = entries.first(where: { $0.id == id }),
                  entry.state == .hearing || entry.state == .translating {
            update(id) { $0.translation = caption.translation.trimmingCharacters(in: .whitespacesAndNewlines) }
        }
        settleLiveCaptions(fromOthers)
    }

    /// Dịch liền: the captions being heard end after a quiet moment (`all`: everything ends now).
    private func endLiveCaptions(all: Bool, sides: [Bool] = [true, false]) {
        for fromOthers in sides {
            guard var router = liveCaptions[fromOthers] else { continue }
            let ended = all ? router.finishAll(now: Self.now) : router.tick(now: Self.now)
            liveCaptions[fromOthers] = router
            if let ended { endLiveCaption(ended, fromOthers: fromOthers) }
            settleLiveCaptions(fromOthers)
        }
    }

    private func showLiveCaption(_ caption: LiveCaptions.Caption, fromOthers: Bool) {
        let text = caption.text.trimmingCharacters(in: .whitespacesAndNewlines)
        let translation = caption.translation.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id = liveEntries[fromOthers]?[caption.number], entries.contains(where: { $0.id == id }) {
            update(id) {
                $0.text = text
                $0.translation = translation
            }
            captions[fromOthers] = (id, Date())
            return
        }
        let entry = Entry(id: UUID(), at: Date(), mine: !fromOthers, language: fromOthers ? "" : Language.vietnamese.code,
                          text: text, translation: translation,
                          target: fromOthers ? Language.vietnamese.code : partnerLanguage.code, state: .hearing)
        entries.append(entry)
        liveEntries[fromOthers, default: [:]][caption.number] = entry.id
        captions[fromOthers] = (entry.id, Date())
    }

    /// A caption's words are all in (its translation may still be coming). The others' are
    /// translated again by GPT, with the meeting's latest sentences and the glossary, for the text
    /// (the live voice has already read them); the user's are kept as the partners heard them.
    private func endLiveCaption(_ caption: LiveCaptions.Caption, fromOthers: Bool) {
        guard let id = liveEntries[fromOthers]?[caption.number], let entry = entries.first(where: { $0.id == id }) else { return }
        if captions[fromOthers]?.id == id { captions[fromOthers] = nil }
        let text = caption.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard fromOthers else { return update(id) { $0.text = text } }
        // A piece of translation of nothing heard: the end of a sentence already translated.
        guard !text.isEmpty else { return remove(id) }
        let language = entry.language.isEmpty ? Self.language(of: text, heard: nil) : entry.language
        log(id, "Dịch liền · \(language) · \(TranscriptFilter.syllableCount(text)) âm tiết · câu dài \(SpeedLog.seconds(Self.now - caption.started))")
        update(id) {
            $0.text = text
            $0.language = language
        }
        // A Vietnamese speaker in the room: shown as they said it.
        if language == Language.vietnamese.code { return finishCaption(id) }
        update(id) { $0.state = .translating }
        noteSpoken(language, text: text)
        Task { await self.translateAgain(id, target: .vietnamese, speaks: false) }
    }

    /// Captions whose live translation is all in: the user's are kept now.
    private func settleLiveCaptions(_ fromOthers: Bool) {
        guard var router = liveCaptions[fromOthers] else { return }
        let released = router.takeReleased()
        liveCaptions[fromOthers] = router
        for caption in released {
            guard let id = liveEntries[fromOthers]?.removeValue(forKey: caption.number), !fromOthers else { continue }
            update(id) {
                $0.text = caption.text.trimmingCharacters(in: .whitespacesAndNewlines)
                $0.translation = caption.translation.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            finishCaption(id)
        }
    }

    private func finishCaption(_ id: UUID) {
        guard let entry = entries.first(where: { $0.id == id }), entry.state == .hearing else { return }
        let text = entry.text.trimmingCharacters(in: .whitespacesAndNewlines)
        var translation = entry.translation.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !translation.isEmpty else { return remove(id) }
        let language = entry.language.isEmpty ? Self.language(of: text, heard: nil) : entry.language
        // A Vietnamese speaker in the room: the "translation" is the same sentence again.
        if !entry.mine, language == Language.vietnamese.code { translation = "" }
        update(id) {
            $0.text = text
            $0.translation = translation
            $0.language = language
            $0.state = .done
        }
        if !entry.mine { noteSpoken(language, text: text) }
        record(id)
    }

    // MARK: Soniox

    private func openSoniox() {
        liveRetries = 0
        othersGate.reset()
        transcripts = [:]
        sonioxEntries = [:]
        sonioxToVietnamese = makeSoniox(target: Language.vietnamese.code)
        openSonioxPartner()
    }

    /// Everything the others say is translated into Vietnamese; the user's words into the partners'
    /// language. Soniox hears any language, so it is told which to expect only as a hint. The room's
    /// session tells its speakers apart.
    private func makeSoniox(target: String) -> SonioxSession? {
        guard let key = SharedStore.sonioxKey else { return nil }
        let room = target == Language.vietnamese.code
        let partner = SonioxEvent.code(for: partnerLanguage)
        let hints = room ? [partner, Language.vietnamese.code] : [Language.vietnamese.code]
        let session = SonioxSession(apiKey: key, target: target, hints: hints, glossary: SharedStore.glossary, speakers: room)
        session.onEvent = { [weak self, weak session] event in
            guard let self, let session else { return }
            self.soniox(event, from: session)
        }
        sonioxSent[room] = 0
        session.open()
        return session
    }

    /// The session for the user's words, opened again when the partners' language changes.
    private func openSonioxPartner() {
        let code = SonioxEvent.code(for: partnerLanguage)
        if let current = sonioxToPartner, current.target == code, current.isOpen { return }
        sonioxToPartner?.close()
        transcripts[false] = nil
        sonioxEntries[false] = nil
        sonioxToPartner = makeSoniox(target: code)
    }

    /// Each sentence Soniox finishes is translated again by GPT (an OpenAI key is there), so
    /// sentences are cut by speaker and language as they are said (see `SonioxTranscript.splits`).
    private var sonioxUsesGPT: Bool { !(apiKey ?? "").isEmpty }

    private func newTranscript(target: String) -> SonioxTranscript {
        var transcript = SonioxTranscript(target: target)
        transcript.splits = sonioxUsesGPT
        return transcript
    }

    private func soniox(_ event: SonioxEvent, from session: SonioxSession) {
        guard session === sonioxToVietnamese || session === sonioxToPartner else { return }
        let fromOthers = session === sonioxToVietnamese
        switch event {
        case .tokens(let tokens):
            liveRetries = 0
            if problem?.hasPrefix("Mất kết nối") == true { problem = nil }
            var transcript = transcripts[fromOthers] ?? newTranscript(target: session.target)
            let finished = transcript.take(tokens, now: Self.now)
            transcripts[fromOthers] = transcript
            // How far the words Soniox settled are behind the sound sent to it.
            let lag = transcript.settledEnd.map { (sonioxSent[fromOthers] ?? 0) - $0 }
            for sentence in finished { finishSentence(sentence, fromOthers: fromOthers, lag: lag) }
            showPreviews(transcript.previews, fromOthers: fromOthers)
            let current = transcript.current
            // Cut by GPT's sentences, a piece of translation no sentence took is not shown on its own.
            if transcript.splits ? !current.text.isEmpty : !current.isEmpty { showSentence(current, fromOthers: fromOthers) }
        case .finished:
            break
        case .failed(let code, let message):
            switch code {
            case 401:
                problem = "Key Soniox không dùng được. Kiểm tra lại trong Cài đặt › Soniox."
                stop()
            case 402, 429:
                problem = "Tài khoản Soniox hết tiền hoặc đang bị giới hạn. Nạp thêm ở console.soniox.com."
                stop()
            case -1:
                problem = "Mất kết nối. Đang nối lại…"
            default:
                problem = message.isEmpty ? "Soniox gặp lỗi (\(code))." : "Soniox: \(message)"
            }
        case .closed:
            guard running, engine == .soniox else { return }
            // Dropped (network, a time limit): what was heard stays, and it is opened again, a few
            // times. Let go of at once, so a second word of it closing opens nothing more.
            session.close()
            if let sentence = transcripts[fromOthers]?.finish() { finishSentence(sentence, fromOthers: fromOthers) }
            transcripts[fromOthers] = nil
            sonioxEntries[fromOthers] = nil
            if fromOthers { sonioxToVietnamese = nil } else { sonioxToPartner = nil }
            log(nil, "Soniox mất kết nối, nối lại")
            liveRetries += 1
            guard liveRetries <= 5 else {
                problem = "Không nối lại được Soniox. Kiểm tra mạng rồi bấm nút tròn để nghe lại."
                return stop()
            }
            Task {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard self.running, self.engine == .soniox else { return }
                if fromOthers {
                    if self.sonioxToVietnamese == nil { self.sonioxToVietnamese = self.makeSoniox(target: Language.vietnamese.code) }
                } else {
                    self.openSonioxPartner()
                }
            }
        }
    }

    /// The sentence being said, word by word as Soniox hears and translates it.
    @discardableResult
    private func showSentence(_ sentence: SonioxTranscript.Sentence, fromOthers: Bool) -> UUID {
        let now = Date()
        let speaker = fromOthers ? sentence.speaker : ""
        if let current = captions[fromOthers], entries.contains(where: { $0.id == current.id }) {
            update(current.id) {
                $0.text = sentence.text
                $0.translation = sentence.translation
                if !sentence.language.isEmpty { $0.language = sentence.language }
                if !speaker.isEmpty { $0.speaker = speaker }
            }
            captions[fromOthers] = (current.id, now)
            return current.id
        }
        let entry = Entry(id: UUID(), at: now, mine: !fromOthers,
                          language: sentence.language.isEmpty && !fromOthers ? Language.vietnamese.code : sentence.language,
                          text: sentence.text, translation: sentence.translation,
                          target: fromOthers ? Language.vietnamese.code : partnerLanguage.code, state: .hearing,
                          speaker: speaker)
        entries.append(entry)
        captions[fromOthers] = (entry.id, now)
        return entry.id
    }

    /// A sentence ended. With an OpenAI key it is translated again by GPT, with the meeting's
    /// latest sentences and the glossary (Soniox's own translation shows meanwhile), then kept and
    /// read aloud (the user's always, the others' with Đọc to). Without one, Soniox's is kept.
    private func finishSentence(_ sentence: SonioxTranscript.Sentence, fromOthers: Bool, lag: Double? = nil) {
        let id = showSentence(sentence, fromOthers: fromOthers)
        captions[fromOthers] = nil
        // The phone's Vietnamese voice heard again through the microphone.
        if fromOthers, echo.isEcho(sentence.text, at: Self.now) {
            log(id, "Soniox: tiếng của máy nghe lại, bỏ")
            return remove(id)
        }
        var note = "Soniox"
        if fromOthers, !sentence.speaker.isEmpty { note += " · Người \(sentence.speaker)" }
        note += " · \(sentence.language.isEmpty ? "?" : sentence.language) · \(TranscriptFilter.syllableCount(sentence.text)) âm tiết"
        if let lag { note += " · nhận chậm \(SpeedLog.seconds(lag))" }
        log(id, note)
        let language = sentence.text.isEmpty ? sentence.language : Self.language(of: sentence.text, heard: sentence.language)
        // A Vietnamese speaker in the room is shown as they said it.
        guard sonioxUsesGPT, !sentence.text.isEmpty, !fromOthers || language != Language.vietnamese.code else {
            if !language.isEmpty { update(id) { $0.language = language } }
            finishCaption(id)
            guard let entry = entries.first(where: { $0.id == id }), entry.state == .done, !entry.translation.isEmpty else { return }
            if entry.mine || readAloud { sayOnce(entry) }
            return
        }
        let target = fromOthers ? Language.vietnamese : partnerLanguage
        update(id) {
            $0.text = sentence.text
            $0.translation = sentence.translation
            $0.language = language
            $0.target = target.code
            $0.state = .translating
        }
        if fromOthers { noteSpoken(language, text: sentence.text) }
        sonioxEntries[fromOthers, default: [:]][sentence.number] = id
        Task {
            await self.translateAgain(id, target: target, speaks: true)
            // Soniox's pieces still coming for it go on to it (not to the next sentence), unseen.
            if self.sonioxEntries[fromOthers]?[sentence.number] == id { self.sonioxEntries[fromOthers]?[sentence.number] = nil }
        }
        // GPT that does not answer soon (a slow network): Soniox's translation is read meanwhile.
        guard !fromOthers || readAloud else { return }
        Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard let entry = self.entries.first(where: { $0.id == id }), entry.state == .translating,
                  !entry.translation.isEmpty, !self.voiced.contains(id) else { return }
            self.log(id, "GPT chưa xong sau 5 s: đọc bản dịch của Soniox")
            self.sayOnce(entry)
        }
    }

    /// GPT's translation of a sentence heard live (Soniox's, or a Dịch liền caption), with the
    /// meeting's latest sentences: what is shown and kept, and read aloud when `speaks` (the user's
    /// always, the others' with Đọc to). When it fails, the live translation stays.
    private func translateAgain(_ id: UUID, target: Language, speaks: Bool) async {
        guard let apiKey, let entry = entries.first(where: { $0.id == id }) else { return }
        // What the partners hear is the user's own words: the app's translation model.
        let model = entry.mine ? SharedStore.model : quality.model
        let began = Date()
        do {
            let result = try await services.translate(apiKey, entry.text, target, context(before: entry.at), model)
            charge { $0.used(result.usage, by: model) }
            let translation = result.text
            update(id) {
                $0.translation = translation
                $0.state = .done
            }
            log(id, "GPT \(Self.name(of: model)) \(SpeedLog.seconds(Date().timeIntervalSince(began)))")
        } catch {
            update(id) { $0.state = $0.translation.isEmpty ? .failed(error.localizedDescription) : .done }
            log(id, "GPT lỗi sau \(SpeedLog.seconds(Date().timeIntervalSince(began))): giữ bản dịch trực tiếp")
        }
        guard let done = entries.first(where: { $0.id == id }), done.state == .done else { return }
        record(id)
        if speaks, !done.translation.isEmpty, done.mine || readAloud { sayOnce(done) }
    }

    /// Soniox's translation of the sentences GPT is still translating, as it comes.
    private func showPreviews(_ previews: [SonioxTranscript.Sentence], fromOthers: Bool) {
        for preview in previews where !preview.translation.isEmpty {
            guard let id = sonioxEntries[fromOthers]?[preview.number],
                  let entry = entries.first(where: { $0.id == id }), entry.state == .translating,
                  entry.translation != preview.translation else { continue }
            update(id) { $0.translation = preview.translation }
        }
    }

    /// A sentence Soniox ended but whose translation never came, or one it stopped sending words
    /// for (the connection hung): finished with what is known, after a moment.
    private func expireSentences() {
        for (fromOthers, current) in captions {
            let quiet = Date().timeIntervalSince(current.last)
            guard quiet >= 2, transcripts[fromOthers]?.ended == true || quiet >= 8 else { continue }
            if let sentence = transcripts[fromOthers]?.finish() {
                finishSentence(sentence, fromOthers: fromOthers)
            } else {
                finishCaption(current.id)
                captions[fromOthers] = nil
            }
        }
    }

    // MARK: Speed log

    /// A line of the speed log (`id`: the sentence it is about, by the start of its id).
    private func log(_ id: UUID?, _ text: String) {
        speedLog.add(id.map { "\($0.uuidString.prefix(4)) \(text)" } ?? text)
    }

    /// For the user to send when Hội thoại feels slow: timings, languages and lengths, never words.
    var speedLogText: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        return "MSTrans \(version) · nhật ký tốc độ Hội thoại\n" + speedLog.text
    }

    static func name(of model: TranslationModel) -> String { model == .luna ? "Luna" : "Sol" }

    // MARK: Summary

    /// Minutes of the meeting in Vietnamese (what was discussed, agreed and left to do), with
    /// what writing them cost.
    static func summarize(_ meeting: Meeting) async throws -> (text: String, usage: TokenUsage?, model: TranslationModel) {
        guard let apiKey = SharedStore.apiKey else { throw TranslateError.noKey }
        let model = SharedStore.model
        let result = try await Translator(apiKey: apiKey, model: model).write(instructions: summaryInstructions,
                                                                             input: meeting.transcript)
        return (result.text, result.usage, model)
    }

    func summarizeCurrent() async {
        guard !meeting.lines.isEmpty, !summarizing else { return }
        summarizing = true
        defer { summarizing = false }
        do {
            let summary = try await Self.summarize(meeting)
            meeting.summary = summary.text
            charge { $0.used(summary.usage, by: summary.model) }
            MeetingStore.save(meeting)
        } catch {
            problem = error.localizedDescription
        }
    }

    private static let summaryInstructions = """
    You write the minutes of a business meeting for a Vietnamese professional. The input is its transcript: each \
    sentence with its time, who spoke ("Tôi" is the user, otherwise the language spoken, after "Người 1", "Người 2"… \
    when the speakers were told apart, which may now and then be wrong), what was said (recognised \
    automatically, so it may hold recognition mistakes) and its translation. Write in Vietnamese, short and plain, \
    under these headings: "Nội dung chính" (bullets), "Đã thống nhất" (decisions; numbers, prices, quantities and \
    dates exactly as said), "Việc cần làm" (who does what, by when, when said), "Còn bỏ ngỏ" (open questions). \
    Leave out a heading with nothing under it. Never add anything the transcript does not say.
    """

    // MARK: Demo (CI screenshots)

    private func fillDemo() {
        let lines: [(Bool, String, String, String, String)] = [
            (false, "zh", "这个型号的价格是多少？如果订五百台，可以便宜一点吗？", "Mẫu này giá bao nhiêu? Nếu đặt 500 chiếc thì có bớt được không?", "vi"),
            (true, "vi", "Nếu anh đặt năm trăm chiếc, bên em giảm năm phần trăm và giao trong hai tuần.",
             "如果您订五百台，我们可以优惠百分之五，两周内交货。", "zh"),
            (false, "zh", "好的，那我们下周一确认订单。", "Được, vậy thứ Hai tuần sau chúng tôi xác nhận đơn hàng.", "vi"),
        ]
        var at = Date().addingTimeInterval(-90)
        for (mine, language, text, translation, target) in lines {
            entries.append(Entry(id: UUID(), at: at, mine: mine, language: language, text: text, translation: translation,
                                 target: target, state: .done))
            meeting.lines.append(Meeting.Line(at: at, mine: mine, language: language, text: text, translation: translation,
                                              target: target))
            at.addTimeInterval(25)
        }
        entries.append(Entry(id: UUID(), at: at, mine: false, language: "", text: "", translation: "", target: "vi",
                             state: .recognizing))
        detectedPartner = Language.named("zh")
    }
}
