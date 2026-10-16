// Telex checks, run in CI with: swiftc Keyboard/Telex.swift Tests/main.swift -o telex-tests && ./telex-tests

import Foundation

let cases: [(String, String)] = [
    // Vietnamese
    ("guwir", "gửi"), ("giups", "giúp"), ("Saus", "Sáu"), ("nhes", "nhé"),
    ("truwowcs", "trước"), ("thuws", "thứ"), ("ddi", "đi"), ("camr", "cảm"),
    ("own", "ơn"), ("quas", "quá"), ("hoaf", "hoà"), ("vieetj", "việt"),
    ("Ddaay", "Đây"), ("nguwowif", "người"), ("chaof", "chào"), ("khoong", "không"),
    ("dduwowcj", "được"), ("tieesng", "tiếng"), ("tuaanf", "tuần"), ("hangf", "hàng"),
    ("baos", "báo"), ("nhw", "như"), ("w", "ư"), ("tw", "tư"), ("gif", "gì"),
    ("quoocs", "quốc"), ("dduocwj", "được"), ("nguoiwf", "người"), ("khuyeen", "khuyên"),
    ("giuwax", "giữa"), ("Vieejt", "Việt"), ("NAM", "NAM"), ("DDAAY", "ĐÂY"), ("xooong", "xoong"),
    // Undo by repeating a modifier
    ("ass", "as"), ("aaa", "aa"), ("tesst", "test"), ("sorrry", "sorry"), ("passs", "pass"),
    // English and brand names stay as typed
    ("facebook", "facebook"), ("good", "good"), ("thanks", "thanks"), ("Shopee", "Shopee"),
    ("meeting", "meeting"), ("we", "we"), ("hello", "hello"), ("ok", "ok"), ("Zalo", "Zalo"),
    ("price", "price"), ("order", "order"), ("email", "email"), ("iPhone", "iPhone"),
    ("WeChat", "WeChat"), ("class", "class"), ("access", "access"), ("process", "process"),
    ("staff", "staff"), ("check", "check"), ("file", "file"),
    // One key again and again, long words: still exactly as typed
    // Twenty a's give nineteen: the third takes the hat off "â" (aa -> â, aaa -> aa), as in Unikey.
    ("aaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaa"), ("hahahahahahahahahahahahahahahahahahahaha", "hahahahahahahahahahahahahahahahahahahaha"),
    ("kkkkkkkkkkkkkkkk", "kkkkkkkkkkkkkkkk"), ("ddddddd", "ddddddd"), ("facebookfacebookfacebook", "facebookfacebookfacebook"),
    ("nghieengs", "nghiếng"), ("khuyeenr", "khuyển"),
]

var failed = 0
for (keys, want) in cases {
    let got = Telex.output(for: keys)
    if got != want {
        failed += 1
        print("FAIL \(keys) -> \(got), want \(want)")
    }
}

// Words picked up from the text field must keep editing correctly.
let words = ["người", "được", "tiếng", "Việt", "khuyên", "giữa", "quốc", "Đây", "tuần", "hoà", "gửi",
             "trước", "nhé", "không", "chào", "thứ", "cảm", "ơn", "hàng", "báo", "phương", "nghiêng", "ngoài"]
for w in words {
    let again = Telex.output(for: Telex.rawKeys(for: w))
    if again != w {
        failed += 1
        print("FAIL round trip \(w) -> \(Telex.rawKeys(for: w)) -> \(again)")
    }
}

// Speed: keys through the engine as the keyboard sends them, one at a time.
let sample = ["nguwowif", "dduwowcj", "tieesng", "vieetj", "khoong", "aaaaaaaaaaaaaaaaaaaa",
              "hahahahahahahahahahahahahahahahahahahaha", "facebook", "guwir", "baos", "gias"]
var keyCount = 0
let started = Date()
for _ in 0..<500 {
    for w in sample {
        var word = TelexWord()
        for key in w { word.type(key); keyCount += 1 }
    }
}
let perKey = Date().timeIntervalSince(started) / Double(keyCount) * 1_000_000
print(String(format: "Telex: %.2f µs per key over %d keys", perKey, keyCount))
// A frame on a 120 Hz iPhone is 8,300 µs; the engine must be a rounding error of that.
if perKey > 100 {
    failed += 1
    print("FAIL Telex is too slow: \(perKey) µs per key")
}

let total = cases.count + words.count
print(failed == 0 ? "Telex: \(total) checks passed" : "Telex: \(failed) of \(total) failed")
fflush(stdout)
if failed > 0 { fatalError("Telex tests failed") }
