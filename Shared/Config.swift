import Foundation

enum Config {
    // Keep in sync with project.yml.
    static let appGroup = "group.com.mocsinh.dichapple"
    static let keychainGroupSuffix = "com.mocsinh.dichapple.shared"
    static let screenExtension = "com.mocsinh.dichapple.screen"

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
