// Language detection checks, run in CI on macOS (same NaturalLanguage models as iOS):
// swiftc Shared/Config.swift Shared/SharedStore.swift Shared/History.swift Shared/LanguageDetector.swift \
//     Tests/Language/main.swift -o language-tests && ./language-tests

import Foundation

var failed = 0
func check(_ ok: Bool, _ what: String) {
    if !ok {
        failed += 1
        print("FAIL \(what)")
    }
}

// Copied partner messages must never be taken for Vietnamese (the keyboard would refuse them).
let foreign = [
    "Can you send me the price list?", "OK, 300 pcs", "Thanks!", "ok", "Hi", "No problem",
    "你好，请问这个型号的价格是多少？", "好的", "Terima kasih banyak", "À bientôt, ça va?",
    "¿Cuánto cuesta?", "감사합니다", "ありがとうございます", "1200 USD", "OK 👍",
]
for text in foreign {
    check(!LanguageDetector.isVietnamese(text), "\"\(text)\" taken for Vietnamese")
}

// Vietnamese with its letters is still recognised.
for text in ["Bên em đã gửi báo giá rồi nhé", "Mẫu này giá bao nhiêu vậy anh?", "Dạ vâng, em cảm ơn anh ạ"] {
    check(LanguageDetector.isVietnamese(text), "\"\(text)\" not recognised as Vietnamese")
}

// The partner's language, for "Tự động".
let expected: [(String, String)] = [
    ("Can you send me the price list for the four containers?", "en"),
    ("你好，请问这个型号的价格是多少？", "zh"),
    ("請問這個型號的價格是多少？", "zh-Hant"),
    ("이 모델의 가격은 얼마입니까?", "ko"),
    ("この型番の価格はいくらですか？", "ja"),
]
for (text, code) in expected {
    let got = LanguageDetector.language(of: text)?.code ?? "nil"
    check(got == code, "\"\(text)\" -> \(got), want \(code)")
}

print(failed == 0 ? "Language: all checks passed" : "Language: \(failed) failed")
fflush(stdout)
if failed > 0 { fatalError("Language tests failed") }
