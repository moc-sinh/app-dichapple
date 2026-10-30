import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Google's Chirp 3 HD voices, with the user's Google Cloud key: natural in Vietnamese and in most of
/// the partners' languages, a sentence in about half a second, the first million characters of a
/// month free (then 30 USD a million). Asked for at 24 kHz like OpenAI's and the iPhone's, so they
/// are queued, played and kept out of the microphone the same way.
struct GoogleVoice {
    enum Failure: LocalizedError, Equatable {
        case badKey, notEnabled, billing, quota, busy, network
        /// No Chirp 3 HD voice for the language: the iPhone's reads it instead.
        case unsupported
        case refused(String)

        var errorDescription: String? {
            switch self {
            case .badKey: return "Key Google không đúng. Kiểm tra lại trong Cài đặt › Giọng Google."
            case .notEnabled:
                return "Dự án Google của key chưa bật Cloud Text-to-Speech API. Bật ở console.cloud.google.com rồi thử lại."
            case .billing: return "Dự án Google chưa gắn thanh toán. Google cần thẻ, dù chỉ dùng trong phần miễn phí."
            case .quota: return "Google báo đọc quá nhiều trong một lúc. Đợi một chút rồi thử lại."
            case .busy: return "Máy chủ Google đang bận. Thử lại."
            case .network: return "Không kết nối được Google. Kiểm tra mạng rồi thử lại."
            case .unsupported: return "Google chưa có giọng cho ngôn ngữ này."
            case .refused(let message): return "Google không đọc được câu này: \(message)"
            }
        }
    }

    let apiKey: String

    /// The voices offered, by Google's name for them (the same in every language).
    static let female = "Aoede"
    static let male = "Charon"
    private static let prefix = "google-"

    /// A Google voice as the voice setting keeps it ("google-Aoede"), and back.
    static func id(_ name: String) -> String { prefix + name }
    static func name(of id: String) -> String? { id.hasPrefix(prefix) ? String(id.dropFirst(prefix.count)) : nil }

    /// Google's locale for an app language; nil where Chirp 3 HD has no voice. Traditional Chinese
    /// is read by the Mandarin voice, which reads either script.
    static func locale(for code: String) -> String? {
        switch code {
        case "vi": return "vi-VN"
        case "zh", "zh-Hant": return "cmn-CN"
        case "en": return "en-US"
        case "ko": return "ko-KR"
        case "ja": return "ja-JP"
        case "th": return "th-TH"
        case "id": return "id-ID"
        case "ru": return "ru-RU"
        case "fr": return "fr-FR"
        case "de": return "de-DE"
        case "es": return "es-ES"
        case "hi": return "hi-IN"
        case "ar": return "ar-XA"
        default: return nil
        }
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    /// Languages Google turned down this run: read by the iPhone's voice without asking again.
    private static let lock = NSLock()
    private static var refusedLocales: Set<String> = []

    static func request(_ text: String, voice: String, locale: String, apiKey: String) -> URLRequest {
        var request = URLRequest(url: URL(string: "https://texttospeech.googleapis.com/v1/text:synthesize")!)
        request.httpMethod = "POST"
        request.setValue(apiKey, forHTTPHeaderField: "X-Goog-Api-Key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Google takes 5,000 bytes: a sentence is far shorter.
        let body: [String: Any] = [
            "input": ["text": String(text.prefix(1_500))],
            "voice": ["languageCode": locale, "name": "\(locale)-Chirp3-HD-\(voice)"],
            "audioConfig": ["audioEncoding": "LINEAR16", "sampleRateHertz": PCM.rate],
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        return request
    }

    /// The voice in Google's answer: `audioContent`, a WAV file for LINEAR16.
    static func pcm(fromAnswer data: Data) -> Data? {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let content = json["audioContent"] as? String, let file = Data(base64Encoded: content) else { return nil }
        return PCM.body(ofWAV: file)
    }

    static func failure(status: Int, data: Data) -> Failure {
        if (500...599).contains(status) { return .busy }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let error = json["error"] as? [String: Any] ?? [:]
        let message = error["message"] as? String ?? ""
        let reasons = (error["details"] as? [[String: Any]] ?? []).compactMap { $0["reason"] as? String }
        let lower = message.lowercased()
        if reasons.contains("API_KEY_INVALID") || status == 401 { return .badKey }
        if reasons.contains("BILLING_DISABLED") || lower.contains("billing") { return .billing }
        if reasons.contains("SERVICE_DISABLED") || lower.contains("has not been used") { return .notEnabled }
        if status == 429 || reasons.contains("RATE_LIMIT_EXCEEDED") { return .quota }
        if status == 400, lower.contains("voice") || lower.contains("language") { return .unsupported }
        return .refused(message.isEmpty ? "HTTP \(status)" : message)
    }

    /// `text` read in the language `code` by the voice `name` ("Aoede").
    func speak(_ text: String, voice name: String, language code: String) async throws -> Data {
        guard let locale = Self.locale(for: code), !Self.isRefused(locale) else { throw Failure.unsupported }
        let (data, status) = try await Self.load(Self.request(text, voice: name, locale: locale, apiKey: apiKey))
        guard status == 200 else {
            let failure = Self.failure(status: status, data: data)
            if failure == .unsupported { Self.markRefused(locale) }
            throw failure
        }
        guard let pcm = Self.pcm(fromAnswer: data), !pcm.isEmpty else { throw Failure.refused("không có tiếng") }
        return pcm
    }

    /// Opens the connection before the first sentence (so it does not wait for it), and tells at once
    /// when the key does not work. nil: it works, or the network did not answer (a sentence will say).
    func warmUp() async -> Failure? {
        var request = URLRequest(url: URL(string: "https://texttospeech.googleapis.com/v1/voices?languageCode=vi-VN")!)
        request.setValue(apiKey, forHTTPHeaderField: "X-Goog-Api-Key")
        guard let (data, status) = try? await Self.load(request), status != 200 else { return nil }
        let failure = Self.failure(status: status, data: data)
        return failure == .busy ? nil : failure
    }

    private static func load(_ request: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, response) = try await session.data(for: request)
            return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            if (error as? URLError)?.code == .cancelled || error is CancellationError { throw CancellationError() }
            throw Failure.network
        }
    }

    private static func isRefused(_ locale: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return refusedLocales.contains(locale)
    }

    private static func markRefused(_ locale: String) {
        lock.lock()
        refusedLocales.insert(locale)
        lock.unlock()
    }
}
