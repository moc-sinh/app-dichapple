import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// OpenAI's speech recognition and voices, with the user's key.
struct SpeechServices {
    let apiKey: String

    struct Transcript: Equatable {
        let text: String
        /// What OpenAI heard ("vi", "zh", "en"…), when it could tell.
        let language: String?
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 45
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    /// The newest recognizer first; an account that cannot use it yet gets the one before
    /// (kept for the rest of the run, so only the first sentence pays for the try). Several
    /// sentences are recognised at once, hence the lock.
    private static let transcribeModels = ["gpt-transcribe", "gpt-4o-transcribe"]
    private static let lock = NSLock()
    private static var preferredModel = 0

    private static var currentModel: Int {
        lock.lock()
        defer { lock.unlock() }
        return preferredModel
    }

    /// Moves on from the model at `index`; false when there is none after it.
    private static func fallBack(from index: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if preferredModel == index, index + 1 < transcribeModels.count { preferredModel = index + 1 }
        return preferredModel > index
    }

    /// One utterance (a WAV file) to text. `prompt`: the meeting's latest sentences and the
    /// glossary's terms, so names and products come out spelt right.
    func transcribe(wav: Data, prompt: String) async throws -> Transcript {
        while true {
            let index = Self.currentModel
            do {
                return try await transcribe(wav: wav, prompt: prompt, model: Self.transcribeModels[index])
            } catch let unavailable as ModelUnavailable {
                guard Self.fallBack(from: index) else { throw TranslateError.server(unavailable.message) }
            }
        }
    }

    private struct ModelUnavailable: Error {
        let message: String
    }

    private func transcribe(wav: Data, prompt: String, model: String) async throws -> Transcript {
        var form = MultipartForm()
        form.add("model", model)
        form.add("response_format", "json")
        if !prompt.isEmpty { form.add("prompt", String(prompt.suffix(800))) }
        form.addFile("file", filename: "speech.wav", contentType: "audio/wav", data: wav)

        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/audio/transcriptions")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(form.boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = form.finished
        let json = try await send(request, model: model)
        let text = (json["text"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let languages = json["languages"] as? [[String: Any]] ?? []
        return Transcript(text: text, language: (languages.first?["code"] as? String).map(Self.normalized))
    }

    /// "zh-TW" and "zh-Hant" are Traditional Chinese; other codes keep only their language.
    static func normalized(_ code: String) -> String {
        let lower = code.lowercased()
        if lower.hasPrefix("zh-hant") || lower == "zh-tw" || lower == "zh-hk" { return "zh-Hant" }
        return String(lower.split(separator: "-").first ?? Substring(lower))
    }

    /// The translation read aloud: 24 kHz mono PCM16. `marin` and `cedar` are OpenAI's most
    /// natural voices.
    func speak(_ text: String, voice: String) async throws -> Data {
        let body: [String: Any] = [
            "model": "gpt-4o-mini-tts",
            "voice": voice,
            "input": String(text.prefix(3_000)),
            "instructions": "Speak like a professional interpreter: clear, natural and warm, at a brisk conversational pace.",
            "response_format": "pcm",
        ]
        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/audio/speech")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, status) = try await load(request)
        guard status == 200 else { throw failure(status: status, data: data, model: "gpt-4o-mini-tts") }
        return data
    }

    private func send(_ request: URLRequest, model: String) async throws -> [String: Any] {
        let (data, status) = try await load(request)
        guard status == 200 else {
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            let error = json["error"] as? [String: Any]
            let code = error?["code"] as? String ?? ""
            let message = error?["message"] as? String ?? ""
            if code == "model_not_found" || status == 404
                || (status == 400 && message.localizedCaseInsensitiveContains("model")) {
                throw ModelUnavailable(message: Translator.explain(status: status, code: code, message: message, model: model))
            }
            throw failure(status: status, data: data, model: model)
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private func load(_ request: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, response) = try await Self.session.data(for: request)
            return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            let code = (error as? URLError)?.code
            if code == .cancelled || error is CancellationError { throw CancellationError() }
            throw TranslateError.network
        }
    }

    private func failure(status: Int, data: Data, model: String) -> Error {
        if (500...599).contains(status) { return TranslateError.busy }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let error = json["error"] as? [String: Any]
        return TranslateError.server(Translator.explain(status: status, code: error?["code"] as? String ?? "",
                                                        message: error?["message"] as? String ?? "", model: model))
    }
}

/// A multipart/form-data body (the transcription upload).
struct MultipartForm {
    let boundary = "MSTrans-\(UUID().uuidString)"
    private var body = Data()

    mutating func add(_ name: String, _ value: String) {
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
    }

    mutating func addFile(_ name: String, filename: String, contentType: String, data: Data) {
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\nContent-Type: \(contentType)\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n".utf8))
    }

    /// The body with its closing boundary.
    var finished: Data { body + Data("--\(boundary)--\r\n".utf8) }
}
