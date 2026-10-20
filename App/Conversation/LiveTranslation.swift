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
            guard let error, let self, !self.closing else { return }
            self.deliver(.failed(code: "network", message: error.localizedDescription))
        }
    }

    private func receive(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self, task === self.task || self.closing else { return }
            switch result {
            case .success(let message):
                let text: String?
                switch message {
                case .string(let string): text = string
                case .data(let data): text = String(data: data, encoding: .utf8)
                @unknown default: text = nil
                }
                if let text, let event = LiveEvent.parse(text) { self.deliver(event) }
                self.receive(task)
            case .failure(let error):
                let wasClosing = self.closing
                if task === self.task { self.task = nil }
                self.isOpen = false
                if !wasClosing {
                    // Refused at the start: a wrong key, or one without live translation.
                    switch (task.response as? HTTPURLResponse)?.statusCode ?? 0 {
                    case 401: self.deliver(.failed(code: "invalid_api_key", message: ""))
                    case 403, 404: self.deliver(.failed(code: "model_not_available", message: ""))
                    default: self.deliver(.failed(code: "network", message: error.localizedDescription))
                    }
                }
                self.deliver(.closed)
            }
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

    var isOpen: Bool { left > 0 }

    mutating func reset() {
        buffered = []
        left = 0
    }
}
