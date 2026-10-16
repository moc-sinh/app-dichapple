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

    /// Vietnamese for sure: it has letters only Vietnamese writes and the recognizer is confident.
    /// A wrong "yes" refuses to translate the partner's message; a wrong "no" only costs one
    /// request that comes back unchanged, so doubt counts as "no".
    static func isVietnamese(_ text: String) -> Bool {
        guard hasVietnameseLetters(text) else { return false }
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        return (recognizer.languageHypotheses(withMaximum: 2)[.vietnamese] ?? 0) >= 0.8
    }

    /// The recognizer's best guess, except that text without a single Vietnamese letter is not
    /// Vietnamese (short English or Malay often gets that guess): then the next best.
    private static func dominant(_ text: String) -> NLLanguage? {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        guard let best = recognizer.dominantLanguage else { return nil }
        guard best == .vietnamese, !hasVietnameseLetters(text) else { return best }
        return recognizer.languageHypotheses(withMaximum: 3)
            .filter { $0.key != .vietnamese }
            .max { $0.value < $1.value }?.key
    }

    /// At least one letter in twenty is one only Vietnamese uses: ă đ ơ ư and the vowels with a
    /// hook, a dot below or two marks (ả ạ ấ ồ ữ…). á, à, â, é, ê, ô… are French or Spanish too.
    private static func hasVietnameseLetters(_ text: String) -> Bool {
        var letters = 0
        var vietnamese = 0
        for scalar in text.precomposedStringWithCanonicalMapping.unicodeScalars where CharacterSet.letters.contains(scalar) {
            letters += 1
            switch scalar.value {
            case 0x0102, 0x0103, 0x0110, 0x0111, 0x01A0, 0x01A1, 0x01AF, 0x01B0, 0x1EA0...0x1EF9: vietnamese += 1
            default: break
            }
        }
        return vietnamese > 0 && vietnamese * 20 >= letters
    }
}
