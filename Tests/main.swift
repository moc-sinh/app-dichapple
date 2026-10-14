// Telex checks, run in CI with: swiftc Keyboard/Telex.swift Tests/main.swift -o telex-tests && ./telex-tests

func typed(_ keys: String) -> String {
    var word = ""
    for k in keys { word = Telex.apply(word, k) }
    return word
}

let cases: [(String, String)] = [
    ("guwir", "gửi"), ("giups", "giúp"), ("Saus", "Sáu"), ("nhes", "nhé"),
    ("truwowcs", "trước"), ("thuws", "thứ"), ("ddi", "đi"), ("camr", "cảm"),
    ("own", "ơn"), ("quas", "quá"), ("hoaf", "hoà"), ("vieetj", "việt"),
    ("Ddaay", "Đây"), ("nguwowif", "người"), ("chaof", "chào"), ("khoong", "không"),
    ("dduwowcj", "được"), ("tieesng", "tiếng"), ("ass", "as"), ("aaa", "aa"),
    ("tuaanf", "tuần"), ("giaa", "giâ"), ("hangf", "hàng"), ("baos", "báo"),
]

var failed = 0
for (keys, want) in cases {
    let got = typed(keys)
    if got != want {
        failed += 1
        print("FAIL \(keys) -> \(got), want \(want)")
    }
}
print(failed == 0 ? "Telex: \(cases.count) cases passed" : "Telex: \(failed) of \(cases.count) failed")
if failed > 0 { fatalError("Telex tests failed") }
