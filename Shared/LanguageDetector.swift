import Foundation
import NaturalLanguage

/// On-device language detection, so "Tự động" can answer in the partner's language.
enum LanguageDetector {
    /// The language the partner writes in, from their latest messages.
    static func partnerLanguage(in lines: [ChatLine]) -> Language? {
        let text = lines.filter { $0.side == .partner }.suffix(4).map(\.text).joined(separator: "\n")
        return language(of: text)
    }

    /// The language of `text`, unless it is Vietnamese or too short to tell.
    static func language(of text: String) -> Language? {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2,
              let detected = dominant(text), detected != .vietnamese, detected != .undetermined else { return nil }
        let code: String
        switch detected {
        case .simplifiedChinese: code = "zh"
        case .traditionalChinese: code = "zh-Hant"
        default: code = detected.rawValue
        }
        if let known = Language.named(code) { return known }
        let name = Locale(identifier: "vi").localizedString(forLanguageCode: code) ?? code
        let englishName = Locale(identifier: "en").localizedString(forLanguageCode: code) ?? code
        return Language(code: code, label: name.capitalized, name: name, englishName: englishName)
    }

    static func isVietnamese(_ text: String) -> Bool { dominant(text) == .vietnamese }

    private static func dominant(_ text: String) -> NLLanguage? {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        return recognizer.dominantLanguage
    }
}

extension Language {
    /// The language to write to the partner in: the one chosen, or with "Tự động" the partner's
    /// own (from the conversation, else the one seen last).
    static func target(code: String, context: [ChatLine]) -> Language {
        guard code == autoCode else { return named(code) ?? all[0] }
        if let detected = LanguageDetector.partnerLanguage(in: context) {
            if named(detected.code) != nil { SharedStore.lastPartnerCode = detected.code }
            return detected
        }
        return named(SharedStore.lastPartnerCode) ?? all[0]
    }
}
