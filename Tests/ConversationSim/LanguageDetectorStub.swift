import Foundation
/// Linux stand-in for Shared/LanguageDetector.swift (NaturalLanguage is Apple-only): the script decides.
enum LanguageDetector {
    static func language(of text: String) -> Language? {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2, !isVietnamese(text) else { return nil }
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x3040...0x30FF: return Language.named("ja")
            case 0xAC00...0xD7AF: return Language.named("ko")
            case 0x0E00...0x0E7F: return Language.named("th")
            case 0x4E00...0x9FFF: return Language.named("zh")
            default: continue
            }
        }
        return text.unicodeScalars.contains { $0.properties.isAlphabetic } ? Language.named("en") : nil
    }
    static func isVietnamese(_ text: String) -> Bool { hasVietnameseMarks(text) && !text.unicodeScalars.contains { (0x3040...0x9FFF).contains($0.value) } }
    static func hasVietnameseMarks(_ text: String) -> Bool {
        var letters = 0, marked = 0
        for scalar in text.unicodeScalars where scalar.properties.isAlphabetic {
            let value = scalar.value
            if value < 0x80 { letters += 1 } else if (0xC0...0x1EFF).contains(value) { letters += 1; marked += 1 }
        }
        return letters > 0 && Double(marked) / Double(letters) >= 0.1
    }
}
