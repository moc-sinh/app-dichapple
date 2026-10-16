// Typing checks, run in CI with:
// swiftc -O Keyboard/TelexCore/*.swift Keyboard/Composer.swift Tests/main.swift -o telex-tests && ./telex-tests
import Foundation

/// What typing `keys` and then a space leaves in an empty field, without that space: the word as
/// it stays once it is done (an English word the engine had changed is back as typed).
func typed(_ keys: String) -> String {
    var composer = Composer()
    composer.start(before: "", after: "", now: 0)
    var text: [Character] = []
    func apply(_ edits: [Composer.Edit]) {
        for edit in edits {
            switch edit {
            case .delete(let count): text.removeLast(count)
            case .insert(let s): text.append(contentsOf: s)
            }
        }
    }
    for key in keys { apply(composer.letter(key, now: 0)) }
    apply(composer.space(now: 0))
    return String(text.dropLast())
}

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
    // w: on its own it is ư, twice it is w (as UniKey); the horn goes where it makes a syllable.
    // Three or more stay as typed: "www" for web addresses.
    ("ww", "w"), ("Ww", "W"), ("WW", "W"), ("tww", "tw"), ("www", "www"), ("chuaw", "chưa"), ("muaw", "mưa"),
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
    // Twenty a's give nineteen: the third takes the hat off "â" (aa -> â, aaa -> aa), as in Unikey;
    // seven d's give six the same way (dd -> đ, ddd -> dd).
    ("aaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaa"), ("hahahahahahahahahahahahahahahahahahahaha", "hahahahahahahahahahahahahahahahahahahaha"),
    ("kkkkkkkkkkkkkkkk", "kkkkkkkkkkkkkkkk"), ("ddddddd", "dddddd"), ("facebookfacebookfacebook", "facebookfacebookfacebook"),
    ("nghieengs", "nghiếng"), ("khuyeenr", "khuyển"),
    // A tone typed before the end of the word moves to its vowel (the old Telex left "hòang").
    ("hofang", "hoàng"), ("tosan", "toán"), ("lojai", "loại"), ("gisa", "giá"), ("qusy", "quý"),
    // English that took a mark while typed is back as typed once the word ends.
    ("google", "google"), ("github", "github"), ("enterprise", "enterprise"),
]

var failed = 0
for (keys, want) in cases {
    let got = typed(keys)
    if got != want {
        failed += 1
        print("FAIL \(keys) -> \(got), want \(want)")
    }
}

// Words picked up from the text field must keep editing correctly: the engine takes each up as is.
let words = ["người", "được", "tiếng", "Việt", "khuyên", "giữa", "quốc", "Đây", "tuần", "hoà", "gửi",
             "trước", "nhé", "không", "chào", "thứ", "cảm", "ơn", "hàng", "báo", "phương", "nghiêng", "ngoài"]
for w in words {
    var engine = Composer.makeEngine()
    if !engine.seed(w) || engine.composed != w {
        failed += 1
        print("FAIL taking up \(w) -> \(engine.composed)")
    }
}

// What a message holds if it is sent right after its last word, with no space: an English word
// shows as typed while it is typed, not with the marks the engine gave it on the way ("gôd").
let live: [(String, String)] = [
    ("good", "good"), ("we", "we"), ("meeting", "meeting"), ("WeChat", "WeChat"), ("add", "add"),
    ("hello", "hello"), ("price", "price"), ("ok", "ok"), ("thanks", "thanks"), ("Deffault", "Default"),
    ("vieejt", "việt"), ("nguwowif", "người"), ("dduocwj", "được"), ("ddc", "đc"), ("w", "ư"),
]
for (keys, want) in live {
    var composer = Composer()
    composer.start(before: "", after: "", now: 0)
    var text: [Character] = []
    for key in keys {
        for edit in composer.letter(key, now: 0) {
            switch edit {
            case .delete(let count): text.removeLast(count)
            case .insert(let s): text.append(contentsOf: s)
            }
        }
    }
    if String(text) != want {
        failed += 1
        print("FAIL while typing \(keys) -> \(String(text)), want \(want)")
    }
}

// Keys, accents picked from the row and ⌫ in a row, into a field that starts with `before`.
func sequence(_ before: String, _ steps: [String]) -> String {
    var composer = Composer()
    composer.start(before: before, after: "", now: 0)
    var text = Array(before)
    var now = 0.0
    for step in steps {
        now += 0.2
        let edits: [Composer.Edit]
        switch step {
        case "⌫": edits = composer.deleteBackward(now: now)
        case " ": edits = composer.space(now: now)
        case let s where s.count > 1 || !(s.first?.isASCII ?? true): edits = composer.insert(String(s.dropFirst(s.hasPrefix("+") ? 1 : 0)), now: now)
        default: edits = composer.letter(step.first!, now: now)
        }
        for edit in edits {
            switch edit {
            case .delete(let count): text.removeLast(count)
            case .insert(let s): text.append(contentsOf: s)
            }
        }
    }
    return String(text)
}
let sequences: [(before: String, steps: [String], want: String)] = [
    // ⌫ over the space after a word opens it again: the next mark still lands on it.
    ("", ["t", "h", "a", "y", "s", " ", "⌫", "a"], "thấy"),
    // A word already in the field takes the next tone.
    ("Chào toan", ["s"], "Chào toán"),
    // An accent picked from the row, then the word goes on.
    ("", ["c", "+á", "n"], "cán"),
    // A word the engine cannot take up goes on as typed.
    ("google", ["s"], "googles"),
    // ⌫ in an English word shown as typed takes its last letter as typed.
    ("", ["g", "o", "o", "d", "⌫", "s"], "goos"),
    // Inside a word ⌫ takes the last letter shown and the word keeps composing.
    ("", ["k", "h", "o", "o", "⌫", "o", "o", "n", "g"], "không"),
]
for c in sequences {
    let got = sequence(c.before, c.steps)
    if got != c.want {
        failed += 1
        print("FAIL \(c.before)|\(c.steps.joined()) -> \(got), want \(c.want)")
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
    composer.start(before: "ok ", after: "", now: now)
    _ = composer.letter("d", now: now)
    now += 0.06
    let edits = composer.letter("d", now: now)  // delete "d", insert "đ"; the app drops the insert
    let waiting = composer.fieldChanged(before: "ok ", after: "", now: now + 0.6)
    let caughtUp = composer.fieldChanged(before: "ok ", after: "", now: now + 1.6)
    if edits != [.delete(1), .insert("đ")] || waiting || !caughtUp || composer.before != "ok " {
        failed += 1
        print("FAIL dropped edit: \(edits), waiting \(waiting), caught up \(caughtUp), before \(composer.before ?? "nil")")
    }
}

// A short message sent within a moment of its last key, before the app reported any of it (while
// keys go in the keyboard does not look at reports, see `KeyboardViewController.fieldReported`):
// the emptied field may still trail the first key when it is reported, but half a second after the
// last key it is taken, and the next message starts afresh.
do {
    fastChecks += 1
    var field = LaggingField()
    var composer = Composer()
    var now = 0.0
    composer.start(before: "", after: "", now: now)
    for key in "ok" {
        field.apply(composer.letter(key, now: now))
        now += 0.1
    }
    now += 0.2
    field.replace(with: "")
    let atOnce = composer.fieldChanged(before: "", after: "", now: now)
    now = 0.1 + 0.55
    let afterPause = composer.fieldChanged(before: "", after: "", now: now)
    now += 0.3
    typeFast("chaof", lag: 2, into: &field, composer: &composer, now: &now)
    if atOnce || !afterPause || field.text != "chào" {
        failed += 1
        print("FAIL sent quickly: at once \(atOnce), after the pause \(afterPause), then \(field.text), want chào")
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
        let want = keys.split(separator: " ").map { typed(String($0)) }.joined(separator: " ")
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

// Keys type as the finger lands; holding one takes it back: an accent chosen instead, or the
// space bar turned trackpad. Everything must then be as if the key had never been typed: the
// field, and the typing that goes on from it.
do {
    func type(_ keys: String, into field: inout LaggingField, composer: inout Composer, now: inout Double) {
        for key in keys {
            field.apply(key == " " ? composer.space(now: now) : composer.letter(key, now: now))
            now += 0.1
        }
    }
    func result(_ keys: String, held: Character?, accent: String?, then more: String) -> String {
        var field = LaggingField()
        var composer = Composer()
        composer.start(before: "", after: "", now: 0)
        var now = 0.0
        type(keys, into: &field, composer: &composer, now: &now)
        if let held {
            let saved = composer
            let edits = held == " " ? composer.space(now: now) : composer.letter(held, now: now)
            field.apply(edits)
            guard let undo = Composer.inverse(of: edits, before: saved.before) else { return "no undo" }
            field.apply(undo)
            composer = saved
        }
        if let accent { field.apply(composer.insert(accent, now: now)) }
        now += 0.5
        type(more, into: &field, composer: &composer, now: &now)
        return field.text
    }
    let cases: [(keys: String, held: Character, accent: String?, more: String)] = [
        ("c", "a", "á", "n"), ("ca", "a", "à", "s"), ("d", "d", "đ", "i"), ("Vieej", "e", "ê", "t"),
        ("ok ", " ", nil, "ok"), ("ok", " ", nil, "s"), ("nguwowi", "f", nil, "s"), ("ww", "w", nil, "w"),
        ("chuaw", "w", nil, " ok"), ("ass", "s", nil, "ss"), ("aa", "a", "ă", "n"),
    ]
    for c in cases {
        fastChecks += 1
        let got = result(c.keys, held: c.held, accent: c.accent, then: c.more)
        let want = result(c.keys, held: nil, accent: c.accent, then: c.more)
        if got != want {
            failed += 1
            print("FAIL holding \(c.held) after \(c.keys): \(got), want \(want)")
        }
    }
}

// The space bar as a trackpad: the cursor moves, and a key typed before the app reports the move
// edits the word now at the cursor. Moved past what is known, the field is read again.
do {
    struct CursorField {
        var before: String
        var after: String
        mutating func apply(_ edits: [Composer.Edit]) {
            for edit in edits {
                switch edit {
                case .delete(let count): before = String(before.dropLast(count))
                case .insert(let s): before += s
                }
            }
        }
        mutating func move(by offset: Int) {
            let text = before + after
            let at = max(0, min(text.count, before.count + offset))
            before = String(text.prefix(at))
            after = String(text.dropFirst(at))
        }
    }
    func moveThenType(before: String, after: String, by offset: Int, _ keys: String) -> String {
        var field = CursorField(before: before, after: after)
        var composer = Composer()
        composer.start(before: before, after: after, now: 0)
        composer.moveCursor(by: offset, now: 1)
        field.move(by: offset)
        for key in keys {
            // As the keyboard does when it no longer knows the text: it reads the field.
            if composer.before == nil { composer.start(before: field.before, after: field.after, now: 1) }
            field.apply(composer.letter(key, now: 1.1))
        }
        return field.before + "|" + field.after
    }
    for (before, after, offset, keys, want) in [("chao ban", "", -4, "f", "chào| ban"), ("chao", " ban", 4, "f", "chao bàn|"),
                                                ("ok", "", -5, "a", "a|ok"), ("viêt", "", 1, "j", "việt|"),
                                                ("tôi đi", "", -3, "s", "tối| đi")] {
        fastChecks += 1
        let got = moveThenType(before: before, after: after, by: offset, keys)
        if got != want {
            failed += 1
            print("FAIL cursor moved \(offset) in \(before)|\(after), then \(keys): \(got), want \(want)")
        }
    }
}

// ⏎ taken as Send: the app empties the field. ⌫ on the empty field then has nothing to re-open,
// and keys typed before the app said so stay.
do {
    func sendWithReturn(_ keys: String, then more: String, typedBeforeReport: String = "") -> String {
        var field = LaggingField()
        var composer = Composer()
        var now = 0.0
        composer.start(before: "", after: "", now: now)
        for key in keys { field.apply(composer.letter(key, now: now)); now += 0.1 }
        _ = composer.newline(now: now)
        field.replace(with: "")
        now += 0.1
        for key in typedBeforeReport { field.apply(composer.letter(key, now: now)); now += 0.1 }
        _ = composer.fieldChanged(before: "", after: "", now: now)
        for key in more {
            // As the keyboard does when it no longer knows the text: it reads the field.
            if composer.before == nil { composer.start(before: field.text, after: "", now: now) }
            field.apply(key == "\u{8}" ? composer.deleteBackward(now: now) : key == " " ? composer.space(now: now) : composer.letter(key, now: now))
            now += 0.1
        }
        return field.text
    }
    for (keys, more, early, want) in [("ok", "\u{8}ddi", "", "đi"), ("ok", "\u{8}\u{8}chaof", "", "chào"),
                                      ("vaang", "nhs", "a", "ánh"), ("ok", "nh ddi", "a", "anh đi")] {
        fastChecks += 1
        let got = sendWithReturn(keys, then: more, typedBeforeReport: early)
        if got != want {
            failed += 1
            print("FAIL after ⏎ sent \(keys), then \(early)|\(more): \(got), want \(want)")
        }
    }
}

// Chatting: messages typed fast and sent, with the app's Send button or the return key, into apps
// that apply each edit late, report the field now and then (some only once typing stops) and
// empty it on sending. The keyboard's side is `KeyboardViewController`'s: reports while keys go in
// only tell how far the app has caught up, an emptied field is looked at at once, the field is
// read again on the first key after a pause and half a second and 1.6 s after the last key. Every
// message must go out exactly as typed. Before the emptied-field rule, one chat in forty here had a
// message go on from the one sent before it ("okchaof").
do {
    final class Random {
        var seed: UInt64 = 7
        func next() -> Double {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Double(seed >> 11) / Double(1 << 53)
        }
        func next(_ low: Double, _ high: Double) -> Double { low + (high - low) * next() }
    }
    let random = Random()

    enum Event { case key(Character), sendTap, apply([Composer.Edit]), send, report(String), look }
    final class Chat {
        var events: [(at: Double, event: Event)] = []
        func at(_ time: Double, _ event: Event) {
            let i = events.firstIndex { $0.at > time } ?? events.count
            events.insert((time, event), at: i)
        }
        // The app
        var field = ""
        var sent: [String] = []
        var appliedUntil = 0.0
        var reportedUntil = 0.0
        let reports: Double
        let lag: ClosedRange<Double>
        let random: Random
        // The keyboard: the field as last reported, and its own state.
        var seen = ""
        var composer = Composer()
        var lastKeyAt = -10.0
        var lookPending = false

        init(reports: Double, lag: ClosedRange<Double>, random: Random) {
            self.reports = reports
            self.lag = lag
            self.random = random
            composer.start(before: "", after: "", now: 0)
        }

        func report(_ now: Double) {
            // One connection: reports arrive in order.
            reportedUntil = max(reportedUntil + 0.0001, now + random.next(0.002, 0.02))
            at(reportedUntil, .report(field))
        }

        func reconcile(_ now: Double) {
            // An app that hands over nothing although the field has text is not believed.
            if seen.isEmpty, !field.isEmpty { return }
            _ = composer.fieldChanged(before: seen, after: "", now: now)
        }

        func scheduleLook(_ now: Double) {
            guard !lookPending else { return }
            lookPending = true
            at(now + max(0.05, lastKeyAt + 0.5 - now + 0.001), .look)
        }

        func run() {
            while !events.isEmpty {
                let (now, event) = events.removeFirst()
                switch event {
                case .key(let key):
                    if now - lastKeyAt >= 0.5 { reconcile(now) }
                    lastKeyAt = now
                    let edits = key == " " ? composer.space(now: now) : key == "\n" ? composer.newline(now: now) : composer.letter(key, now: now)
                    if !edits.isEmpty {
                        appliedUntil = max(appliedUntil, now + random.next(lag.lowerBound, lag.upperBound))
                        at(appliedUntil, .apply(edits))
                    }
                    scheduleLook(now)
                case .sendTap:
                    appliedUntil = max(appliedUntil, now)
                    at(appliedUntil + 0.0001, .send)
                case .send:
                    sent.append(field)
                    field = ""
                    report(now)
                case .apply(let edits):
                    var sending = false
                    for edit in edits {
                        switch edit {
                        case .delete(let count): field = String(field.dropLast(count))
                        case .insert("\n"): sending = true
                        case .insert(let s): field += s
                        }
                    }
                    if sending {
                        sent.append(field)
                        field = ""
                        report(now)
                    } else if random.next() < reports {
                        report(now)
                    }
                case .report(let text):
                    seen = text
                    if now - lastKeyAt < 0.5, !text.isEmpty {
                        composer.fieldCaughtUp(before: text, after: "", now: now)
                        scheduleLook(now)
                    } else {
                        reconcile(now)
                    }
                case .look:
                    lookPending = false
                    let idle = now - lastKeyAt
                    if idle < 0.5 {
                        scheduleLook(now)
                    } else {
                        reconcile(now)
                        if idle < TypedText.lag + 0.1 {
                            lookPending = true
                            at(now + max(0.01, TypedText.lag + 0.1 - idle), .look)
                        }
                    }
                }
            }
        }
    }

    let words = ["ok", "dda", "vaang", "chaof", "anh", "em", "guwir", "baos", "gias", "nhes", "khoong", "dduwowcj",
                 "nguwowif", "muaw", "roofi", "xem", "giusp", "vowis", "aj", "hello", "google", "ww", "w", "tesst",
                 "Tooi", "bieets", "laf", "naof", "camr", "own", "hofang", "tosan", "ddi", "ddaau"]
    // Apps that report every change, half of them, none while typing, and a slow one.
    let apps: [(reports: Double, lag: ClosedRange<Double>)] = [(1, 0.005...0.05), (0.5, 0.01...0.15), (0, 0.01...0.1), (0.2, 0.02...0.4)]
    var chatsFailed = 0
    for round in 0..<400 {
        let app = apps[round % apps.count]
        let chat = Chat(reports: app.reports, lag: app.lag, random: random)
        var now = random.next(0.2, 1.5)
        var messages: [String] = []
        for _ in 0..<Int(random.next(2, 6)) {
            let keys = (0..<Int(random.next(1, 4))).map { _ in words[Int(random.next() * Double(words.count))] }.joined(separator: " ")
            messages.append(keys)
            for key in keys {
                chat.at(now, .key(key))
                now += random.next(0.04, 0.2)
            }
            // A thumb going to Send and back to the keys takes a moment.
            if random.next() < 0.5 {
                now += random.next(0.25, 0.9)
                chat.at(now, .sendTap)
            } else {
                chat.at(now, .key("\n"))
            }
            now += random.next(0.3, 2)
        }
        chat.run()
        let want = messages.map { $0.split(separator: " ").map { typed(String($0)) }.joined(separator: " ") }
        fastChecks += 1
        if chat.sent != want {
            chatsFailed += 1
            failed += 1
            if chatsFailed <= 3 { print("FAIL chat \(round): sent \(chat.sent), want \(want)") }
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

let total = cases.count + words.count + live.count + sequences.count + fastChecks
print(failed == 0 ? "Telex: \(total) checks passed" : "Telex: \(failed) of \(total) failed")
fflush(stdout)
if failed > 0 { exit(1) }
