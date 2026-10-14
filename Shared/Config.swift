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

    static let model = "gpt-6-luna"
    static let endpoint = URL(string: "https://api.openai.com/v1/responses")!

    /// Messages sent along as context. Small on purpose: cheaper, faster requests.
    static let contextLimit = 8
    static let contextCharacterLimit = 1_500
    /// Remembered (copied or sent) messages expire after this.
    static let memoryMaxAge: TimeInterval = 30 * 60
    /// Text read from the screen counts as current for this long.
    static let screenMaxAge: TimeInterval = 2 * 60
}

struct Language: Equatable {
    let code: String
    let label: String
    let englishName: String
    /// Vision OCR language for messages written in this language.
    let ocrCode: String

    static let vietnamese = Language(code: "vi", label: "Tiếng Việt", englishName: "Vietnamese", ocrCode: "vi-VT")
    static let targets = [
        Language(code: "zh", label: "中文", englishName: "Simplified Chinese", ocrCode: "zh-Hans"),
        Language(code: "en", label: "English", englishName: "English", ocrCode: "en-US"),
        Language(code: "ko", label: "한국어", englishName: "Korean", ocrCode: "ko-KR"),
        Language(code: "ja", label: "日本語", englishName: "Japanese", ocrCode: "ja-JP"),
    ]

    static func target(_ code: String) -> Language {
        targets.first { $0.code == code } ?? targets[0]
    }
}
