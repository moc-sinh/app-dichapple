import Foundation
import NaturalLanguage

/// On-device language detection, so "Tự động" can answer in the partner's language.
enum LanguageDetector {
    /// The language the partner writes in, from their latest messages.
    static func partnerLanguage(in lines: [ChatLine]) -> Language? {
        let text = lines.filter { $0.side == .partner }.suffix(4).map(\.text).joined(separator: "\n")
        return language(of: text)
    }

    /// The language of `text`, unless it is Vietnamese or too short to tell. A foreign message that
    /// names a Vietnamese person or place ("@Đào Ngọc Minh we cannot use this address…") is still foreign.
    static func language(of text: String) -> Language? {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 else { return nil }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        var detected = recognizer.dominantLanguage
        if detected == .vietnamese, !hasVietnameseMarks(text) {
            detected = recognizer.languageHypotheses(withMaximum: 3)
                .filter { $0.key != .vietnamese }
                .max { $0.value < $1.value }?.key
        }
        guard let detected, detected != .vietnamese, detected != .undetermined else { return nil }
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

    /// Vietnamese as people write it: recognised as Vietnamese and with marks on many letters.
    static func isVietnamese(_ text: String) -> Bool {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        return recognizer.dominantLanguage == .vietnamese && hasVietnameseMarks(text)
    }

    /// About a quarter of the letters in Vietnamese carry a mark (ă â đ ê ô ơ ư or a tone); an
    /// English message with a Vietnamese name in it has only a few.
    static func hasVietnameseMarks(_ text: String) -> Bool {
        var letters = 0
        var marked = 0
        for scalar in text.unicodeScalars where scalar.properties.isAlphabetic {
            let value = scalar.value
            if value < 0x80 {
                letters += 1
            } else if (0xC0...0x1EFF).contains(value) {
                letters += 1
                marked += 1
            }
        }
        return letters > 0 && Double(marked) / Double(letters) >= 0.1
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
