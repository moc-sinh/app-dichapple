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
    private var chunk: [Int16] = []
    private var sinceLevel = 0
    /// Live mode: the sound itself is wanted.
    let streams: Bool
    var onUtterance: ((SpeechSegmenter.Utterance) -> Void)?
    /// What the user said while holding the button.
    var onMine: (([Int16]) -> Void)?
    var onLevel: ((Double, Bool) -> Void)?
    var onChunk: ((Data) -> Void)?

    private static let recentSamples = PCM.rate * 3 / 10
    /// The button held longer than this ends by itself.
    private static let longestMine = PCM.rate * 120

    init(silence: Double, streams: Bool) {
        segmenter = SpeechSegmenter(silence: silence)
        self.streams = streams
    }

    func take(_ samples: [Int16]) {
        if mine != nil {
            mine?.append(contentsOf: samples)
            if (mine?.count ?? 0) >= Self.longestMine { endMine() }
        } else {
            for utterance in segmenter.push(samples) { deliver(utterance) }
        }
        recent.append(contentsOf: samples)
        if recent.count > Self.recentSamples { recent.removeFirst(recent.count - Self.recentSamples) }
        if streams {
            chunk.append(contentsOf: samples)
            while chunk.count >= PCM.rate / 10 {
                let data = PCM.data(chunk[..<(PCM.rate / 10)])
                chunk.removeFirst(PCM.rate / 10)
                DispatchQueue.main.async { [weak self] in self?.onChunk?(data) }
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
    /// moment before) the sound is the user's.
    func beginMine() {
        guard mine == nil else { return }
        if let utterance = segmenter.flush() { deliver(utterance) }
        mine = recent
    }

    func endMine() {
        guard let samples = mine else { return }
        mine = nil
        DispatchQueue.main.async { [weak self] in self?.onMine?(samples) }
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

/// The calls to OpenAI, replaceable in a test.
struct ConversationServices {
    var transcribe: (_ apiKey: String, _ wav: Data, _ prompt: String) async throws -> SpeechServices.Transcript
    var translate: (_ apiKey: String, _ text: String, _ target: Language, _ context: [ChatLine]) async throws -> String
    var speak: (_ apiKey: String, _ text: String, _ voice: String) async throws -> Data

    static let openAI = ConversationServices(
        transcribe: { try await SpeechServices(apiKey: $0).transcribe(wav: $1, prompt: $2) },
        translate: { try await Translator(apiKey: $0).translate($1, to: $2, context: $3, spoken: true).text },
        speak: { try await SpeechServices(apiKey: $0).speak($1, voice: $2) })
}

/// The Hội thoại tab, for a meeting or a conversation with foreign partners.
///
/// While it runs, everything the others say comes up as Vietnamese text (Vietnamese speakers as
/// they spoke), and with Đọc to it is also read aloud through the speaker; Chỉ chữ only shows it.
/// When the user wants to speak, they hold the Tôi nói button (or tap it, and tap again at the
/// end): what they say is translated into the partners' language and read aloud in OpenAI's voice.
///
/// How it translates (Cách dịch):
/// - Chính xác: each sentence is recognised and translated once it ends (2–3 s), with the
///   glossary and the meeting's context; several at once, shown in the order they were said.
/// - Dịch liền: OpenAI's live speech translation, text (and voice) as the others speak; the user's
///   words come out in the partners' language while they speak.
@MainActor @Observable
final class ConversationModel {
    enum Engine: String, CaseIterable, Identifiable {
        case accurate, live
        var id: String { rawValue }
        var title: String { self == .accurate ? "Chính xác (sau mỗi câu)" : "Dịch liền (ngay khi nói)" }
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

        var isVietnamese: Bool { language == Language.vietnamese.code }
    }

    private enum Keys {
        static let readAloud = "conversation.readAloud"
        static let engine = "conversation.engine"
        static let partner = "conversation.partner"
        static let silence = "conversation.silence"
        static let voice = "conversation.voice"
    }

    // MARK: Settings, kept between launches (this app's own defaults: the keyboard never needs them)

    /// Đọc to: the others' words are read aloud in Vietnamese too. Chỉ chữ: only shown.
    var readAloud: Bool { didSet { defaults.set(readAloud, forKey: Keys.readAloud) } }
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

    static let silenceChoices: [Double] = [0.4, 0.6, 0.8, 1.0, 1.2, 1.4]
    static let voices: [(id: String, name: String)] = [("marin", "Marin (nữ)"), ("cedar", "Cedar (nam)")]

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
    @ObservationIgnored private var waiting: [(id: UUID, samples: [Int16])] = []
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
    @ObservationIgnored private var captions: [Bool: (id: UUID, last: Date)] = [:]
    @ObservationIgnored private var partnerTailUntil = Date.distantPast
    @ObservationIgnored private var liveRetries = 0

    /// Sentences recognised and translated at once: a busy meeting does not fall behind.
    private static let parallel = 3

    init() {
        let defaults = UserDefaults.standard
        readAloud = defaults.object(forKey: Keys.readAloud) as? Bool ?? true
        engine = Engine(rawValue: defaults.string(forKey: Keys.engine) ?? "") ?? .accurate
        partnerCode = defaults.string(forKey: Keys.partner) ?? Language.autoCode
        let savedSilence = defaults.double(forKey: Keys.silence)
        silence = Self.silenceChoices.contains(savedSilence) ? savedSilence : 0.8
        voice = defaults.string(forKey: Keys.voice) ?? "marin"
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
        guard let key = SharedStore.apiKey else {
            problem = TranslateError.noKey.errorDescription
            return
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
        let listener = Listener(silence: silence, streams: engine == .live)
        listener.onUtterance = { [weak self] utterance in self?.heard(utterance) }
        listener.onMine = { [weak self] samples in self?.said(samples) }
        listener.onLevel = { [weak self] level, speaking in self?.heard(level: level, speaking: speaking) }
        listener.onChunk = { [weak self] data in self?.stream(data) }
        self.listener = listener
        audio.onSamples = Listener.sink(listener)
        do {
            // Dịch liền speaks while it listens: Apple's echo cancelling keeps its own voice out.
            // Chính xác does not listen while the phone speaks, and hears far voices better without it.
            try audio.start(echoCancelling: engine == .live)
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
        if wantsToTalk { talk(true) }
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
        closeLive()
        finishCaptions(all: true)
        UIApplication.shared.isIdleTimerDisabled = false
        MeetingStore.save(meeting)
    }

    /// The meeting so far goes to the saved ones; the next starts empty.
    func newMeeting() {
        guard !running else { return }
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
        if engine == .live { finishCaptions(all: false) }
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
        } else if engine == .live {
            // A moment of silence lets the last words through.
            partnerTailUntil = Date().addingTimeInterval(1.5)
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

    /// The user let go of the button.
    private func said(_ samples: [Int16]) {
        guard apiKey != nil else { return }
        let seconds = Double(samples.count) / Double(PCM.rate)
        // Nothing louder than the room: the button was tapped, nobody spoke.
        let loudest = stride(from: 0, to: samples.count - SpeechSegmenter.frame, by: SpeechSegmenter.frame)
            .map { PCM.dbfs(samples[$0..<$0 + SpeechSegmenter.frame]) }.max() ?? -100
        guard seconds >= 0.5, loudest > -50 else {
            problem = "Chưa nghe thấy bạn nói. Giữ nút Tôi nói trong lúc nói nhé."
            return
        }
        enqueue(samples, mine: true, at: Date().addingTimeInterval(-seconds))
    }

    private func enqueue(_ samples: [Int16], mine: Bool, at: Date) {
        let entry = Entry(id: UUID(), at: at, mine: mine, language: mine ? Language.vietnamese.code : "", text: "",
                          translation: "", target: mine ? partnerLanguage.code : Language.vietnamese.code,
                          state: .recognizing)
        entries.append(entry)
        // The user's own words go first: the room is waiting for them.
        if mine { waiting.insert((entry.id, samples), at: 0) } else { waiting.append((entry.id, samples)) }
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
        do {
            transcript = try await services.transcribe(apiKey, wav, recognitionPrompt())
        } catch is CancellationError {
            return remove(id)
        } catch {
            unrecognised[id] = samples
            return update(id) { $0.state = .failed(error.localizedDescription) }
        }
        unrecognised[id] = nil
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
        do {
            let translation = try await services.translate(apiKey, entry.text, target, context(before: entry.at))
            update(id) {
                $0.translation = translation
                $0.state = .done
            }
            record(id)
            if entry.mine || readAloud { say(translation, mine: entry.mine) }
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

    /// Reads a translation aloud again.
    func repeatAloud(_ id: UUID) {
        guard let entry = entries.first(where: { $0.id == id }), !entry.translation.isEmpty else { return }
        if apiKey == nil { apiKey = SharedStore.apiKey }
        say(entry.translation, mine: entry.mine)
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

    /// The meeting's latest sentences, as the translator's context.
    private func context(before at: Date) -> [ChatLine] {
        meeting.lines.filter { $0.at < at }.suffix(8).map {
            ChatLine(side: $0.mine ? .me : .partner, text: $0.text, date: $0.at)
        }
    }

    /// Names and terms the recognizer should spell right: the glossary, and the last sentences.
    private func recognitionPrompt() -> String {
        var terms: [String] = []
        for line in SharedStore.glossary.split(whereSeparator: \.isNewline) {
            for side in line.split(separator: "=") {
                let term = side.trimmingCharacters(in: .whitespaces)
                if !term.isEmpty, term.count <= 40 { terms.append(term) }
            }
        }
        var prompt = terms.isEmpty ? "" : "Thuật ngữ / Terms: " + terms.prefix(40).joined(separator: ", ") + "\n"
        prompt += meeting.lines.suffix(2).map(\.text).joined(separator: " ")
        return prompt
    }

    /// Into the minutes, in the order things were said (sentences are translated several at once).
    private func record(_ id: UUID) {
        guard let entry = entries.first(where: { $0.id == id }), !entry.text.isEmpty else { return }
        let line = Meeting.Line(at: entry.at, mine: entry.mine, language: entry.language, text: entry.text,
                                translation: entry.translation, target: entry.target)
        let index = meeting.lines.lastIndex { $0.at <= line.at }.map { $0 + 1 } ?? 0
        meeting.lines.insert(line, at: index)
        MeetingStore.save(meeting)
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

    /// Each translation is fetched as soon as it is ready and read after the one before.
    /// `mine`: the user's words, in the partners' language; otherwise the others', in Vietnamese.
    private func say(_ text: String, mine: Bool) {
        guard let apiKey, running else { return }
        let voice = voice
        let generation = speechGeneration
        let speak = services.speak
        let fetch = Task { try? await speak(apiKey, text, voice) }
        let previous = speechChain
        speechChain = Task { [weak self] in
            await previous?.value
            guard let pcm = await fetch.value else {
                self?.problem = "Không đọc to được (mạng hoặc API key). Bản dịch vẫn hiện trên màn hình."
                return
            }
            guard let self, generation == self.speechGeneration else { return }
            await self.play(pcm, text: text, generation: generation, mine: mine)
        }
    }

    private func play(_ pcm: Data, text: String, generation: Int, mine: Bool) async {
        // Never over the user: a translation ready while they speak waits until they let go.
        while talking, running { try? await Task.sleep(nanoseconds: 100_000_000) }
        // Like an interpreter, the others' words are read once the room pauses: read over someone
        // still talking, Chính xác would stop listening to them. Someone who talks on for ten
        // seconds without a pause gets the text only.
        if !mine, engine == .accurate {
            var waited = 0
            while hearsSpeech, running, waited < 100, generation == speechGeneration {
                try? await Task.sleep(nanoseconds: 100_000_000)
                waited += 1
            }
            if hearsSpeech { return }
        }
        guard running, generation == speechGeneration, mine || readAloud else { return }
        beginAloud(mine: mine)
        echo.spoke(text, at: Self.now)
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            audio.play(pcm) { done.resume() }
        }
        endAloud(mine: mine)
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
    }

    /// The microphone's sound goes to the session for whoever speaks: the user's while the button is
    /// down, everyone else's otherwise. The other session gets silence, so it finishes what it was
    /// saying.
    private func stream(_ data: Data) {
        guard running, engine == .live else { return }
        let silent = Data(count: data.count)
        if talking {
            liveToPartner?.send(audio: data)
            liveToVietnamese?.send(audio: silent)
        } else {
            // The phone speaking the partners' language is not someone in the room to translate.
            liveToVietnamese?.send(audio: playingMine > 0 ? silent : data)
            if Date() < partnerTailUntil { liveToPartner?.send(audio: silent) }
        }
    }

    private func live(_ event: LiveEvent, from session: LiveSession) {
        guard session === liveToVietnamese || session === liveToPartner else { return }
        let fromOthers = session === liveToVietnamese
        switch event {
        case .ready:
            liveRetries = 0
            if problem?.hasPrefix("Mất kết nối") == true { problem = nil }
        case .source(let delta):
            caption(fromOthers) { $0.text += delta }
        case .target(let delta):
            caption(fromOthers) { $0.translation += delta }
        case .audio(let pcm):
            if fromOthers {
                // Đọc to only, never over the user, and not a Vietnamese speaker's words again.
                guard readAloud, !talking, !othersSpeakVietnamese else { return }
                beginAloud(mine: false)
                audio.play(pcm) { [weak self] in self?.endAloud(mine: false) }
            } else {
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
            // Dropped (network, a time limit): opened again, a few times.
            liveRetries += 1
            guard liveRetries <= 5 else {
                problem = "Không nối lại được Dịch liền. Kiểm tra mạng rồi bấm Bắt đầu lại."
                return stop()
            }
            Task {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard self.running, self.engine == .live else { return }
                if fromOthers {
                    self.liveToVietnamese = self.makeSession(Language.vietnamese.code)
                } else {
                    self.liveToPartner = nil
                    self.openPartnerSession()
                }
            }
        }
    }

    /// The sentence being heard live is Vietnamese (a Vietnamese speaker in the room).
    private var othersSpeakVietnamese: Bool {
        guard let id = captions[true]?.id, let entry = entries.first(where: { $0.id == id }), entry.text.count >= 12
        else { return false }
        return LanguageDetector.isVietnamese(entry.text)
    }

    /// Live captions: what comes within 2.5 s of the last piece belongs to the same sentence.
    private func caption(_ fromOthers: Bool, _ change: (inout Entry) -> Void) {
        let now = Date()
        if let current = captions[fromOthers], now.timeIntervalSince(current.last) < 2.5,
           entries.contains(where: { $0.id == current.id }) {
            update(current.id, change)
            captions[fromOthers] = (current.id, now)
            return
        }
        if let previous = captions[fromOthers] { finishCaption(previous.id) }
        var entry = Entry(id: UUID(), at: now, mine: !fromOthers, language: fromOthers ? "" : Language.vietnamese.code,
                          text: "", translation: "",
                          target: fromOthers ? Language.vietnamese.code : partnerLanguage.code, state: .hearing)
        change(&entry)
        entries.append(entry)
        captions[fromOthers] = (entry.id, now)
    }

    private func finishCaptions(all: Bool) {
        for (key, current) in captions where all || Date().timeIntervalSince(current.last) >= 2.5 {
            finishCaption(current.id)
            captions[key] = nil
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

    // MARK: Summary

    /// Minutes of the meeting in Vietnamese: what was discussed, agreed and left to do.
    static func summarize(_ meeting: Meeting) async throws -> String {
        guard let apiKey = SharedStore.apiKey else { throw TranslateError.noKey }
        return try await Translator(apiKey: apiKey).write(instructions: summaryInstructions, input: meeting.transcript)
    }

    func summarizeCurrent() async {
        guard !meeting.lines.isEmpty, !summarizing else { return }
        summarizing = true
        defer { summarizing = false }
        do {
            meeting.summary = try await Self.summarize(meeting)
            MeetingStore.save(meeting)
        } catch {
            problem = error.localizedDescription
        }
    }

    private static let summaryInstructions = """
    You write the minutes of a business meeting for a Vietnamese professional. The input is its transcript: each \
    sentence with its time, who spoke ("Tôi" is the user, otherwise the language spoken), what was said (recognised \
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
