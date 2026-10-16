// Typing checks, run in CI with:
// swiftc Keyboard/Telex.swift Keyboard/Composer.swift Tests/main.swift -o telex-tests && ./telex-tests
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
    // w: on its own it is ư, twice it is w (as UniKey); the horn goes where it makes a syllable
    ("ww", "w"), ("Ww", "W"), ("WW", "W"), ("tww", "tw"), ("www", "ww"), ("chuaw", "chưa"), ("muaw", "mưa"),
    ("ngwoif", "người"), ("nguwoif", "người"), ("twowng", "tương"), ("hoaw", "hoă"), ("quaw", "quă"),
    ("tuw", "tư"), ("uwow", "ươ"), ("Uw", "Ư"),
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

// Typing fast into an app that shows each edit a few keys late, as iOS apps do.
struct LaggingField {
    var text = ""
    var states = [""]
    /// Reports never go back past one already made.
    var reported = 0

    mutating func apply(_ edits: [Composer.Edit]) {
        for edit in edits {
            switch edit {
            case .delete(let count): text = String(text.dropLast(count))
            case .insert(let s): text += s
            }
            states.append(text)
        }
    }

    /// What the keyboard is told, `lag` edits behind.
    mutating func seen(lag: Int) -> String {
        reported = max(reported, states.count - 1 - lag)
        return states[reported]
    }

    /// The app changes the text itself (clears it after sending) and says so at once.
    mutating func replace(with new: String) {
        text = new
        states.append(new)
        reported = states.count - 1
    }
}

func typeFast(_ keys: String, lag: Int, into field: inout LaggingField, composer: inout Composer, now: inout Double) {
    for key in keys {
        // The app reports its (late) text before most keys.
        _ = composer.fieldChanged(before: field.seen(lag: lag), after: "", now: now)
        field.apply(key == " " ? composer.space(now: now) : composer.letter(key, now: now))
        now += 0.06
    }
}

var fastChecks = 0
let sentences = [
    ("Tooi khoong bieets laf banj thees naof", "Tôi không biết là bạn thế nào"),
    ("ww we will work wow", "w we will work ươ"),
    ("nguwowif nuwowcs ngoaif muaw chuaw", "người nước ngoài mưa chưa"),
]
for (keys, want) in sentences {
    for lag in 0...4 {
        fastChecks += 1
        var field = LaggingField()
        var composer = Composer()
        var now = 0.0
        composer.start(before: "", after: "", now: now)
        typeFast(keys, lag: lag, into: &field, composer: &composer, now: &now)
        if field.text != want {
            failed += 1
            print("FAIL fast typing (lag \(lag)): \(keys) -> \(field.text), want \(want)")
        }
    }
}

// The app clears the field after sending; the next message starts afresh.
do {
    fastChecks += 1
    var field = LaggingField()
    var composer = Composer()
    var now = 0.0
    composer.start(before: "", after: "", now: now)
    typeFast("ok", lag: 2, into: &field, composer: &composer, now: &now)
    now += 2
    field.replace(with: "")
    _ = composer.fieldChanged(before: "", after: "", now: now)
    typeFast("chaof banj", lag: 2, into: &field, composer: &composer, now: &now)
    if field.text != "chào bạn" {
        failed += 1
        print("FAIL after sending: \(field.text), want chào bạn")
    }
}

// Two spaces make ". ", even while the app is behind.
do {
    fastChecks += 1
    var field = LaggingField()
    var composer = Composer()
    var now = 0.0
    composer.start(before: "", after: "", now: now)
    typeFast("ok  vaang", lag: 3, into: &field, composer: &composer, now: &now)
    if field.text != "ok. vâng" {
        failed += 1
        print("FAIL double space: \(field.text), want ok. vâng")
    }
}

// Typing fast long after the field was read (reading the partner's reply first): the app's first
// reports still show the empty field, which must not undo the first keys.
for lag in 1...4 {
    fastChecks += 1
    var field = LaggingField()
    var composer = Composer()
    var now = 0.0
    composer.start(before: "", after: "", now: now)
    now = 5
    typeFast("ddi nguwowif", lag: lag, into: &field, composer: &composer, now: &now)
    if field.text != "đi người" {
        failed += 1
        print("FAIL fast typing after a pause (lag \(lag)): \(field.text), want đi người")
    }
}

// Sent right after typing: once the app has shown the message, an empty field means it was sent,
// even a moment later, and the next message starts afresh.
do {
    fastChecks += 1
    var field = LaggingField()
    var composer = Composer()
    var now = 0.0
    composer.start(before: "", after: "", now: now)
    typeFast("ok", lag: 2, into: &field, composer: &composer, now: &now)
    _ = composer.fieldChanged(before: field.seen(lag: 0), after: "", now: now)
    now += 0.3
    field.replace(with: "")
    _ = composer.fieldChanged(before: "", after: "", now: now)
    now += 0.3
    typeFast("chaof", lag: 2, into: &field, composer: &composer, now: &now)
    if field.text != "chào" {
        failed += 1
        print("FAIL sent right after typing: \(field.text), want chào")
    }
}

// An app that drops an edit (seen in Spotlight): while it may still be catching up the keyboard
// waits; once it must have caught up, the field's text is taken.
do {
    fastChecks += 1
    var composer = Composer()
    var now = 0.0
    composer.start(before: "", after: "", now: now)
    _ = composer.letter("d", now: now)
    now += 0.06
    let edits = composer.letter("d", now: now)  // delete "d", insert "đ"; the app drops the insert
    let waiting = composer.fieldChanged(before: "", after: "", now: now + 0.6)
    let caughtUp = composer.fieldChanged(before: "", after: "", now: now + 1.6)
    if edits != [.delete(1), .insert("đ")] || waiting || !caughtUp || composer.before != "" {
        failed += 1
        print("FAIL dropped edit: \(edits), waiting \(waiting), caught up \(caughtUp), before \(composer.before ?? "nil")")
    }
}

// Many messages typed fast into an app whose reports come late and unevenly, some not at all, with
// pauses between bursts: every message comes out exactly as typed.
do {
    var seed: UInt64 = 42
    func random(_ n: Int) -> Int {
        seed = seed &* 6364136223846793005 &+ 1442695040888963407
        return Int((seed >> 33) % UInt64(n))
    }
    let messages = ["hahaha haha ddi ddaau", "Tooi khoong bieets", "ww nguwowif muaw", "dd ddd dddd", "aa aaa uwow"]
    for round in 0..<200 {
        fastChecks += 1
        let keys = messages[round % messages.count]
        let want = keys.split(separator: " ").map { Telex.output(for: String($0)) }.joined(separator: " ")
        var field = LaggingField()
        var composer = Composer()
        var now = Double(random(10))
        composer.start(before: "", after: "", now: 0)
        for key in keys {
            if random(20) == 0 {
                // A pause: the app catches up and says so.
                now += 0.6 + Double(random(20)) / 10
                _ = composer.fieldChanged(before: field.seen(lag: 0), after: "", now: now)
            } else if random(3) != 0 {
                _ = composer.fieldChanged(before: field.seen(lag: random(5)), after: "", now: now)
            }
            field.apply(key == " " ? composer.space(now: now) : composer.letter(key, now: now))
            now += 0.03 + Double(random(12)) / 100
        }
        if field.text != want {
            failed += 1
            print("FAIL uneven reports (round \(round)): \(keys) -> \(field.text), want \(want)")
        }
    }
}

// Speed: keys through the engine and the composer as the keyboard sends them, one at a time.
let sample = ["nguwowif", "dduwowcj", "tieesng", "vieetj", "khoong", "aaaaaaaaaaaaaaaaaaaa",
              "hahahahahahahahahahahahahahahahahahahaha", "facebook", "guwir", "baos", "gias", "chuaw", "ngwoif"]
var keyCount = 0
let started = Date()
for _ in 0..<500 {
    var composer = Composer()
    composer.start(before: "", after: "", now: 0)
    var now = 0.0
    for w in sample {
        for key in w { _ = composer.letter(key, now: now); now += 0.06; keyCount += 1 }
        _ = composer.space(now: now)
    }
}
let perKey = Date().timeIntervalSince(started) / Double(keyCount) * 1_000_000
print(String(format: "Typing: %.2f µs per key over %d keys", perKey, keyCount))
// A frame on a 120 Hz iPhone is 8,300 µs; typing must be a rounding error of that.
if perKey > 100 {
    failed += 1
    print("FAIL typing is too slow: \(perKey) µs per key")
}

let total = cases.count + words.count + fastChecks
print(failed == 0 ? "Telex: \(total) checks passed" : "Telex: \(failed) of \(total) failed")
fflush(stdout)
if failed > 0 { exit(1) }
