import Foundation

enum Config {
    /// The App Group shared by the app and its extensions. Sideloading tools that
    /// sign with a free Apple ID may rename it, so prefer the one the embedded
    /// provisioning profile actually grants; fall back to the name in project.yml.
    static let appGroup: String = {
        let groups = provisionedAppGroups()
        return groups.first { $0.hasPrefix("group.com.mocsinh.dichapple") } ?? groups.first ?? "group.com.mocsinh.dichapple"
    }()

    private static func provisionedAppGroups() -> [String] {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(
                  from: data.subdata(in: start.lowerBound..<end.upperBound), format: nil) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any] else { return [] }
        return entitlements["com.apple.security.application-groups"] as? [String] ?? []
    }

    static let endpoint = URL(string: "https://api.openai.com/v1/responses")!

    /// Messages sent along as context. Enough for pronouns and references, small enough to stay fast.
    static let contextLimit = 10
    static let contextCharacterLimit = 2_500
    /// Remembered (copied or sent) messages expire after this.
    static let memoryMaxAge: TimeInterval = 45 * 60
    /// Text read from the screen counts as current for this long.
    static let screenMaxAge: TimeInterval = 2 * 60
}

/// Which OpenAI model translates.
enum TranslationModel: String, CaseIterable {
    /// Best quality: near the top model at a fraction of its cost.
    case sol = "gpt-6.1-sol"
    /// Fastest and cheapest.
    case luna = "gpt-6-luna"

    var label: String {
        switch self {
        case .sol: return "Chuẩn nhất (GPT-6.1 Sol)"
        case .luna: return "Nhanh (GPT-6 Luna)"
        }
    }

    /// Sol does not accept "none"; a little reasoning helps nuance without slowing replies much.
    var reasoningEffort: String {
        switch self {
        case .sol: return "low"
        case .luna: return "none"
        }
    }
}

/// How formal outgoing messages sound.
enum Tone: Int, CaseIterable {
    case polite, formal, friendly

    var label: String {
        switch self {
        case .polite: return "Lịch sự"
        case .formal: return "Trang trọng"
        case .friendly: return "Thân mật"
        }
    }

    var instruction: String {
        switch self {
        case .polite: return "polite and warm, the way professionals write in a business chat"
        case .formal: return "formal and respectful, as in correspondence with an important client or a superior"
        case .friendly: return "friendly and casual, as between partners who know each other well"
        }
    }
}

struct Language: Equatable {
    let code: String
    /// Name in the language itself, shown on keys.
    let label: String
    /// Vietnamese name, shown in sentences.
    let name: String
    /// English name, used in prompts.
    let englishName: String
    /// Vision OCR language for messages written in this language.
    let ocrCode: String?

    /// Translate into whatever language the partner writes in.
    static let autoCode = "auto"

    static let vietnamese = Language(code: "vi", label: "Tiếng Việt", name: "tiếng Việt", englishName: "Vietnamese", ocrCode: "vi-VT")

    static let all = [
        Language(code: "zh", label: "中文", name: "tiếng Trung", englishName: "Simplified Chinese (Mainland China)", ocrCode: "zh-Hans"),
        Language(code: "zh-Hant", label: "繁體中文", name: "tiếng Trung phồn thể", englishName: "Traditional Chinese (Taiwan)", ocrCode: "zh-Hant"),
        Language(code: "en", label: "English", name: "tiếng Anh", englishName: "English", ocrCode: "en-US"),
        Language(code: "ko", label: "한국어", name: "tiếng Hàn", englishName: "Korean", ocrCode: "ko-KR"),
        Language(code: "ja", label: "日本語", name: "tiếng Nhật", englishName: "Japanese", ocrCode: "ja-JP"),
        Language(code: "th", label: "ไทย", name: "tiếng Thái", englishName: "Thai", ocrCode: "th-TH"),
        Language(code: "id", label: "Indonesia", name: "tiếng Indonesia", englishName: "Indonesian", ocrCode: "id-ID"),
        Language(code: "ms", label: "Melayu", name: "tiếng Mã Lai", englishName: "Malay", ocrCode: "ms-MY"),
        Language(code: "ru", label: "Русский", name: "tiếng Nga", englishName: "Russian", ocrCode: "ru-RU"),
        Language(code: "fr", label: "Français", name: "tiếng Pháp", englishName: "French", ocrCode: "fr-FR"),
        Language(code: "de", label: "Deutsch", name: "tiếng Đức", englishName: "German", ocrCode: "de-DE"),
        Language(code: "es", label: "Español", name: "tiếng Tây Ban Nha", englishName: "Spanish", ocrCode: "es-ES"),
        Language(code: "hi", label: "हिन्दी", name: "tiếng Hindi", englishName: "Hindi", ocrCode: nil),
        Language(code: "ar", label: "العربية", name: "tiếng Ả Rập", englishName: "Arabic", ocrCode: "ar-SA"),
    ]

    static func named(_ code: String) -> Language? { all.first { $0.code == code } }
}
