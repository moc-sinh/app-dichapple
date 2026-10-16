// VietTelex's 9,091 typing cases, typed through MSTrans's Composer exactly as the keyboard sends
// keys: letters compose, a space or punctuation ends the word; then every Vietnamese syllable.
// Run in CI with:
// swiftc -O Keyboard/TelexCore/*.swift Keyboard/Composer.swift Tests/Suite/main.swift -o telex-suite \
//     && ./telex-suite Tests/Suite/telex_suite.tsv
import Foundation

/// What the field holds after typing `keys` into an empty one, the last word ended as by a space.
func typed(_ keys: String) -> String {
    var composer = Composer()
    composer.start(before: "", after: "", now: 0)
    var text: [Character] = []
    func apply(_ edits: [Composer.Edit]) {
        for edit in edits {
            switch edit {
            case .delete(let count): text.removeLast(min(count, text.count))
            case .insert(let s): text.append(contentsOf: s)
            }
        }
    }
    // Two seconds a key: two spaces never make ". " here.
    var now = 0.0
    for key in keys {
        now += 2
        if key == " " {
            apply(composer.space(now: now))
        } else if key == "\n" {
            apply(composer.newline(now: now))
        } else if let a = key.asciiValue, (a >= 65 && a <= 90) || (a >= 97 && a <= 122) {
            apply(composer.letter(key, now: now))
        } else {
            apply(composer.insert(String(key), now: now))
        }
    }
    apply(composer.space(now: now + 2))
    return String(text.dropLast())
}

let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Tests/Suite/telex_suite.tsv"
guard let file = try? String(contentsOfFile: path, encoding: .utf8) else {
    print("FAIL cannot read \(path)")
    exit(1)
}
var pass: [String: Int] = [:], total: [String: Int] = [:], misses: [String: [String]] = [:]
for line in file.split(separator: "\n") where !line.hasPrefix("#") {
    let f = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    guard f.count == 3 else { continue }
    let got = typed(f[0])
    total[f[2], default: 0] += 1
    if got == f[1] || f[1].components(separatedBy: " / ").contains(got) {
        pass[f[2], default: 0] += 1
    } else {
        misses[f[2], default: []].append("\(f[0]) -> \(got), want \(f[1])")
    }
}
for kind in total.keys.sorted() { print("\(kind): \(pass[kind] ?? 0)/\(total[kind]!)") }

// Vietnamese must always come out right, and a trailing double key (the "no mark" gesture) keeps
// what the screen showed. The rest are floors, measured, that must never drop:
// - English typed without a change stays as typed, all but "momo" and "mono": with marks typed
//   late finding their vowel (on, so "dduocwj" gives "được"), the second o reaches back (môm, môn),
//   as with the Telex MSTrans had before;
// - English words that did change come back as typed at the end of the word.
var failed = 0
func require(_ kind: String, atLeast floor: Int? = nil) {
    let got = pass[kind] ?? 0, all = total[kind] ?? 0
    let want = floor ?? all
    guard got < want else { return }
    failed += 1
    print("FAIL \(kind): \(got)/\(all), must be at least \(want)")
    for miss in (misses[kind] ?? []).prefix(20) { print("   \(miss)") }
}
require("transform")
require("keep_as_typed", atLeast: 360)
require("cancel_keep_composed")
require("restore_raw", atLeast: 7041)
// Every syllable the rules allow (onset × rime × tone, standard spelling), typed as Telex keys with
// the marks doubled and the tone last: it must end as the engine composes it, and while it is typed
// the field must show exactly what the engine composed, never the keys (no flicker mid-word).
let marks: [Character: String] = ["â": "aa", "ă": "aw", "ê": "ee", "ô": "oo", "ơ": "ow", "ư": "uw", "đ": "dd"]
var syllables = 0, endWrong = 0, flicker = 0
for onset in SyllableValidator.onsets.subtracting(SyllableValidator.teencodeOnsets).sorted() {
    for rime in SyllableValidator.rimes.subtracting(SyllableValidator.teencodeRimes).sorted() {
        let word = onset + rime
        guard SyllableValidator.isValidSyllable(word, teencode: false) else { continue }
        for toneKey in ["", "s", "f", "r", "x", "j"] {
            let keys = String(word.flatMap { marks[$0].map(Array.init) ?? [$0] }) + toneKey
            var engine = Composer.makeEngine()
            var composer = Composer()
            composer.start(before: "", after: "", now: 0)
            var text: [Character] = []
            var steady = true
            func apply(_ edits: [Composer.Edit]) {
                for edit in edits {
                    switch edit {
                    case .delete(let count): text.removeLast(count)
                    case .insert(let s): text.append(contentsOf: s)
                    }
                }
            }
            for key in keys {
                apply(composer.letter(key, now: 0))
                _ = engine.feed(key)
                if String(text) != engine.composed { steady = false }
            }
            let composed = engine.composed
            // Only tones the syllable takes (a stop coda takes sắc or nặng only).
            guard SyllableValidator.isValidSyllable(composed, teencode: false) else { continue }
            syllables += 1
            apply(composer.space(now: 0))
            if String(text.dropLast()) != composed {
                endWrong += 1
                if endWrong <= 10 { print("   \(keys) -> \(String(text.dropLast())), want \(composed)") }
            }
            if !steady {
                flicker += 1
                if flicker <= 10 { print("   \(keys) flickers while typed") }
            }
        }
    }
}
print("syllables: \(syllables), \(endWrong) end wrong, \(flicker) flicker while typed")
if syllables < 19_000 || endWrong > 0 || flicker > 0 {
    failed += 1
    print("FAIL syllables")
}

print(failed == 0 ? "Suite passed" : "Suite: \(failed) failed")
exit(failed == 0 ? 0 : 1)
