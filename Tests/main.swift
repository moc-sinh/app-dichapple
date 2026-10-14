// Telex checks, run in CI with: swiftc Keyboard/Telex.swift Tests/main.swift -o telex-tests && ./telex-tests

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

let total = cases.count + words.count
print(failed == 0 ? "Telex: \(total) checks passed" : "Telex: \(failed) of \(total) failed")
if failed > 0 { fatalError("Telex tests failed") }
