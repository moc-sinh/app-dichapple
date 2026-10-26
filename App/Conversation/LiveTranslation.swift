import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// What OpenAI's live speech translation (`gpt-realtime-translate`) sends back while people talk.
enum LiveEvent: Equatable {
    case ready
    /// More of what was said, as recognised.
    case source(String)
    /// More of its translation.
    case target(String)
    /// More of the translation spoken: 24 kHz mono PCM16.
    case audio(Data)
    case failed(code: String, message: String)
    case closed

    static func parse(_ text: String) -> LiveEvent? {
        guard let data = text.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = object["type"] as? String else { return nil }
        switch type {
        case "session.created":
            return .ready
        case "session.closed":
            return .closed
        case "session.input_transcript.delta":
            return (object["delta"] as? String).map(LiveEvent.source)
        case "session.output_transcript.delta":
            return (object["delta"] as? String).map(LiveEvent.target)
        case "session.output_audio.delta":
            guard let delta = object["delta"] as? String, let audio = Data(base64Encoded: delta) else { return nil }
            return .audio(audio)
        case "error":
            let error = object["error"] as? [String: Any] ?? object
            return .failed(code: error["code"] as? String ?? "error", message: error["message"] as? String ?? "")
        default:
            return nil
        }
    }

    /// Sound for the session (24 kHz mono PCM16). Base64 needs no escaping in JSON, and this goes
    /// ten times a second.
    static func appendMessage(_ pcm: Data) -> String {
        #"{"type":"session.input_audio_buffer.append","audio":""# + pcm.base64EncodedString() + #""}"#
    }

    static func languageMessage(_ code: String) -> String {
        #"{"type":"session.update","session":{"audio":{"output":{"language":""# + code + #""}}}}"#
    }

    static let closeMessage = #"{"type":"session.close"}"#
}

/// One live translation session: sound goes in, and its translation into one language comes back
/// as text and voice while the speaker is still talking. Two-way talk needs one per direction.
/// Used on the main queue only: what the socket says is handled there too.
final class LiveSession {
    /// The languages OpenAI's live translation can speak (any of 70 can be heard).
    static let outputLanguages: Set<String> = ["vi", "en", "zh", "ja", "ko", "es", "pt", "fr", "de", "ru", "it", "hi", "id"]

    static func outputCode(for language: Language) -> String? {
        let code = language.code == "zh-Hant" ? "zh" : language.code
        return outputLanguages.contains(code) ? code : nil
    }

    let language: String
    /// Called on the main queue.
    var onEvent: ((LiveEvent) -> Void)?
    private(set) var isOpen = false
    private let apiKey: String
    private var task: URLSessionWebSocketTask?
    private var closing = false

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        return URLSession(configuration: config)
    }()

    init(apiKey: String, language: String) {
        self.apiKey = apiKey
        self.language = language
    }

    func open() {
        guard task == nil, let url = URL(string: "wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate") else { return }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let task = Self.session.webSocketTask(with: request)
        self.task = task
        closing = false
        isOpen = true
        task.resume()
        send(LiveEvent.languageMessage(language))
        receive(task)
    }

    /// 24 kHz mono PCM16, about 100 ms at a time.
    func send(audio pcm: Data) {
        send(LiveEvent.appendMessage(pcm))
    }

    /// Asks for what is still on its way, then ends the session.
    func close() {
        guard let task else { return }
        closing = true
        isOpen = false
        task.send(.string(LiveEvent.closeMessage)) { _ in }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak task] in task?.cancel(with: .normalClosure, reason: nil) }
        self.task = nil
    }

    private func send(_ message: String) {
        task?.send(.string(message)) { [weak self] error in
            guard let error else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.closing else { return }
                self.deliver(.failed(code: "network", message: error.localizedDescription))
            }
        }
    }

    private func receive(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async { [weak self] in self?.received(result, from: task) }
        }
    }

    private func received(_ result: Result<URLSessionWebSocketTask.Message, Error>, from task: URLSessionWebSocketTask) {
        guard task === self.task || closing else { return }
        switch result {
        case .success(let message):
            let text: String?
            switch message {
            case .string(let string): text = string
            case .data(let data): text = String(data: data, encoding: .utf8)
            @unknown default: text = nil
            }
            if let text, let event = LiveEvent.parse(text) { deliver(event) }
            receive(task)
        case .failure(let error):
            let wasClosing = closing
            if task === self.task { self.task = nil }
            isOpen = false
            if !wasClosing {
                // Refused at the start: a wrong key, or one without live translation.
                switch (task.response as? HTTPURLResponse)?.statusCode ?? 0 {
                case 401: deliver(.failed(code: "invalid_api_key", message: ""))
                case 403, 404: deliver(.failed(code: "model_not_available", message: ""))
                default: deliver(.failed(code: "network", message: error.localizedDescription))
                }
            }
            deliver(.closed)
        }
    }

    private func deliver(_ event: LiveEvent) {
        DispatchQueue.main.async { [weak self] in self?.onEvent?(event) }
    }
}

/// Dịch liền is paid by the minute of sound sent, so a quiet room is not sent: sound goes to a
/// session while someone speaks, with the half second before (the first syllable) and the three
/// seconds after (so it finishes the sentence), and nothing while nobody speaks.
struct LiveGate {
    /// In chunks of a tenth of a second.
    static let preroll = 5
    static let hold = 30

    private var buffered: [Data] = []
    private var left = 0

    /// The next tenth of a second, and whether someone is speaking in it; returns what to send now.
    mutating func feed(_ chunk: Data, speech: Bool) -> [Data] {
        if speech {
            left = Self.hold
            let out = buffered + [chunk]
            buffered = []
            return out
        }
        if left > 0 {
            left -= 1
            return [chunk]
        }
        buffered.append(chunk)
        if buffered.count > Self.preroll { buffered.removeFirst(buffered.count - Self.preroll) }
        return []
    }

    mutating func reset() {
        buffered = []
        left = 0
    }
}

/// Dịch liền's captions, cut into sentences. OpenAI's live translation sends what it heard and its
/// translation as two streams of pieces, saying neither where a sentence ends nor which sentence a
/// piece of translation belongs to; and a room that talks on never goes quiet for long, so one
/// caption ran on for minutes, several people in it. A caption is cut after a full stop once it is
/// a few seconds long (after a comma once it is long, anywhere once it is very long), and the
/// translation still coming for it goes to it (`awaiting`) until it has as many sentence ends as
/// what was said, or none has come for a while.
struct LiveCaptions {
    struct Caption: Equatable {
        let number: Int
        var text = ""
        var translation = ""
        /// When its first piece came (seconds, any clock).
        let started: Double
    }

    /// Nothing heard this long: the caption ends.
    static let quiet = 2.5
    /// A caption is cut after a full stop once it is this long; after a comma, and anywhere.
    static let shortest = 3.0
    static let long = 10.0
    static let longest = 20.0
    /// No piece of its translation this long: an ended caption's translation is done.
    static let translationQuiet = 3.0

    /// The caption being heard.
    private(set) var current: Caption?
    /// Ended captions whose translation may still be coming, oldest first.
    private(set) var awaiting: [Caption] = []
    private var released: [Caption] = []
    private var last: Double = 0
    private var lastPiece: [Int: Double] = [:]
    private var nextNumber = 1

    /// More of what was said; returns the caption it ended, if any.
    mutating func heard(_ delta: String, now: Double) -> Caption? {
        var ended: Caption?
        if let caption = current, cuts(caption, before: delta, now: now) { ended = finishCurrent(now: now) }
        begin(now: now)
        current?.text += delta
        last = now
        return ended
    }

    /// More of the translation; returns the number of the caption it went to.
    mutating func translated(_ delta: String, now: Double) -> Int {
        while let first = awaiting.first {
            if Self.isComplete(first) || now - (lastPiece[first.number] ?? now) > Self.translationQuiet {
                release(first.number)
                continue
            }
            awaiting[0].translation += delta
            lastPiece[first.number] = now
            return first.number
        }
        begin(now: now)
        current?.translation += delta
        last = now
        return current?.number ?? 0
    }

    /// Time passing: the caption being heard ends after a quiet moment; returns it.
    mutating func tick(now: Double) -> Caption? {
        for caption in awaiting where now - (lastPiece[caption.number] ?? now) > Self.translationQuiet {
            release(caption.number)
        }
        guard current != nil, now - last >= Self.quiet else { return nil }
        return finishCurrent(now: now)
    }

    /// Listening stops, or the session is gone: everything ends as it is; returns the caption heard.
    mutating func finishAll(now: Double) -> Caption? {
        let ended = current == nil ? nil : finishCurrent(now: now)
        while let first = awaiting.first { release(first.number) }
        return ended
    }

    /// Captions whose translation is in (or stopped coming), since this was last asked.
    mutating func takeReleased() -> [Caption] {
        defer { released = [] }
        return released
    }

    private mutating func begin(now: Double) {
        guard current == nil else { return }
        current = Caption(number: nextNumber, started: now)
        nextNumber += 1
    }

    private mutating func finishCurrent(now: Double) -> Caption? {
        guard let caption = current else { return nil }
        current = nil
        if caption.text.contains(where: { $0.isLetter || $0.isNumber }), !Self.isComplete(caption) {
            awaiting.append(caption)
            lastPiece[caption.number] = now
            while awaiting.count > 2 { release(awaiting[0].number) }
        } else {
            released.append(caption)
        }
        return caption
    }

    private mutating func release(_ number: Int) {
        guard let index = awaiting.firstIndex(where: { $0.number == number }) else { return }
        released.append(awaiting.remove(at: index))
        lastPiece[number] = nil
    }

    /// A piece of speech after the caption's end of sentence (or clause, or anything, as it grows).
    private func cuts(_ caption: Caption, before delta: String, now: Double) -> Bool {
        guard delta.contains(where: { $0.isLetter || $0.isNumber }),
              let end = caption.text.trimmingCharacters(in: .whitespacesAndNewlines).last else { return false }
        let age = now - caption.started
        if Self.sentenceEnds.contains(end) { return age >= Self.shortest }
        if ",，、;；:：".contains(end) { return age >= Self.long }
        return age >= Self.longest
    }

    private static let sentenceEnds = ".?!。？！…"

    /// Sentence ends in `text` ("..." is one).
    static func ends(_ text: String) -> Int {
        var count = 0
        var previous = false
        for character in text {
            let end = sentenceEnds.contains(character)
            if end, !previous { count += 1 }
            previous = end
        }
        return count
    }

    /// Its translation has as many sentence ends as what was said.
    static func isComplete(_ caption: Caption) -> Bool {
        let said = ends(caption.text)
        return said > 0 && ends(caption.translation) >= said
    }
}
