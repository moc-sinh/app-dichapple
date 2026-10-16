/// Telex input for Vietnamese: `aa`→â, `aw`→ă, `ow`→ơ, `uw`→ư, `w`→ư, `dd`→đ, and
/// `s f r x j` for sắc, huyền, hỏi, ngã, nặng (`z` removes the tone).
/// Typing the same modifier twice undoes it (`as` + `s` → `as`).
/// Words that cannot be Vietnamese keep the keys as typed, so English words
/// such as "facebook", "order" or "thanks" come out unchanged.
enum Telex {
    private static let forms: [Character: [Character]] = [
        "a": Array("aáàảãạ"), "ă": Array("ăắằẳẵặ"), "â": Array("âấầẩẫậ"),
        "e": Array("eéèẻẽẹ"), "ê": Array("êếềểễệ"), "i": Array("iíìỉĩị"),
        "o": Array("oóòỏõọ"), "ô": Array("ôốồổỗộ"), "ơ": Array("ơớờởỡợ"),
        "u": Array("uúùủũụ"), "ư": Array("ưứừửữự"), "y": Array("yýỳỷỹỵ"),
    ]

    private struct Vowel { let base: Character; let tone: Int }

    private static let vowels: [Character: Vowel] = {
        var map: [Character: Vowel] = [:]
        for (base, chars) in forms {
            for (tone, c) in chars.enumerated() { map[c] = Vowel(base: base, tone: tone) }
        }
        return map
    }()

    private static let toneKeys: [Character] = ["s", "f", "r", "x", "j"]
    private static let hats: [Character: Character] = ["a": "â", "e": "ê", "o": "ô"]
    private static let horns: [Character: Character] = ["a": "ă", "o": "ơ", "u": "ư"]
    private static let unhorns: [Character: Character] = ["ă": "a", "ơ": "o", "ư": "u"]
    private static let marked: Set<Character> = ["ă", "â", "ê", "ô", "ơ", "ư"]

    // MARK: Public

    /// The text shown for a word typed as `raw` keys from scratch.
    static func output(for raw: String) -> String {
        var word = TelexWord()
        for key in raw { word.type(key) }
        return word.shown
    }

    /// Runs the keys through the engine; also says whether the last key undid a mark.
    static func process(_ raw: String) -> (text: String, lastWasUndo: Bool) {
        var word = ""
        var undo = false
        for key in raw {
            let step = self.step(word, key)
            word = step.text
            undo = step.undo
        }
        return (word, undo)
    }

    /// Keys that type `word` again, so editing can continue after the word was changed elsewhere.
    static func rawKeys(for word: String) -> String {
        var out = ""
        var toneKey: Character?
        for c in word {
            let upper = c.isUppercase
            if let v = vowel(c) {
                let keys: String
                switch v.base {
                case "â": keys = "aa"
                case "ă": keys = "aw"
                case "ê": keys = "ee"
                case "ô": keys = "oo"
                case "ơ": keys = "ow"
                case "ư": keys = "uw"
                default: keys = String(v.base)
                }
                out += upper ? keys.prefix(1).uppercased() + keys.dropFirst() : keys
                if v.tone > 0 { toneKey = toneKeys[v.tone - 1] }
            } else if lower(c) == "đ" {
                out += upper ? "Dd" : "dd"
            } else {
                out.append(c)
            }
        }
        if let toneKey { out.append(toneKey) }
        return out
    }

    /// Returns the word after typing `key` at its end.
    static func apply(_ word: String, _ key: Character) -> String { step(word, key).text }

    // MARK: Engine

    private static func step(_ word: String, _ key: Character) -> (text: String, undo: Bool) {
        let k = lower(key)
        var ch = Array(word)
        let plain = (word + String(key), false)
        let hasVowel = ch.contains { vowel($0) != nil }

        if k == "w" && !hasVowel {
            return (word + (key.isUppercase ? "Ư" : "ư"), false)
        }
        guard !ch.isEmpty else { return (String(key), false) }

        if let t = toneKeys.firstIndex(of: k) { return tone(ch, t + 1, key, word) }
        if k == "z" { return tone(ch, 0, key, word) }

        if k == "d", lower(ch[0]) == "d" {
            ch[0] = ch[0].isUppercase ? "Đ" : "đ"
            return (String(ch), false)
        }

        if let hat = hats[k] {
            for i in ch.indices.reversed() {
                guard let v = vowel(ch[i]) else { continue }
                if v.base == k { ch[i] = make(ch[i], hat, v.tone); return (retone(&ch), false) }
                if v.base == hat { ch[i] = make(ch[i], k, v.tone); return (retone(&ch) + String(key), true) }
            }
            return plain
        }

        if k == "w" {
            for i in 0..<(ch.count - 1) {
                if let a = vowel(ch[i]), let b = vowel(ch[i + 1]), a.base == "u", b.base == "o" {
                    ch[i] = make(ch[i], "ư", a.tone)
                    ch[i + 1] = make(ch[i + 1], "ơ", b.tone)
                    return (retone(&ch), false)
                }
            }
            for i in ch.indices.reversed() {
                guard let v = vowel(ch[i]) else { continue }
                if let h = horns[v.base] { ch[i] = make(ch[i], h, v.tone); return (retone(&ch), false) }
                if let p = unhorns[v.base] { ch[i] = make(ch[i], p, v.tone); return (retone(&ch) + String(key), true) }
            }
            return plain
        }

        return plain
    }

    private static func tone(_ original: [Character], _ tone: Int, _ key: Character, _ word: String) -> (text: String, undo: Bool) {
        var ch = original
        let current = ch.firstIndex { (vowel($0)?.tone ?? 0) > 0 }
        guard let target = toneTarget(ch) else { return (word + String(key), false) }
        if tone == 0 && current == nil { return (word + String(key), false) }
        if let cur = current, let v = vowel(ch[cur]) {
            ch[cur] = make(ch[cur], v.base, 0)
            if v.tone == tone { return (String(ch) + String(key), true) }
        }
        if tone > 0, let v = vowel(ch[target]) { ch[target] = make(ch[target], v.base, tone) }
        return (String(ch), false)
    }

    /// Where the tone mark goes, following the modern placement rules.
    private static func toneTarget(_ ch: [Character]) -> Int? {
        var positions: [Int] = []
        for i in ch.indices {
            guard let v = vowel(ch[i]) else { continue }
            let prev: Character? = i > 0 ? lower(ch[i - 1]) : nil
            let nextIsVowel = i + 1 < ch.count && vowel(ch[i + 1]) != nil
            if (v.base == "u" && prev == "q") || (v.base == "i" && prev == "g" && nextIsVowel) { continue }
            positions.append(i)
        }
        guard let last = positions.last else { return nil }
        var cluster = [last]
        var j = positions.count - 2
        while j >= 0 && positions[j] == cluster[0] - 1 {
            cluster.insert(positions[j], at: 0)
            j -= 1
        }
        for i in cluster.reversed() where marked.contains(vowel(ch[i])!.base) { return i }
        if cluster.count == 1 { return cluster[0] }
        let end = cluster[cluster.count - 1]
        if end < ch.count - 1 { return end }
        let pair = String([vowel(ch[end - 1])!.base, vowel(ch[end])!.base])
        if cluster.count == 2 && (pair == "oa" || pair == "oe" || pair == "uy") { return end }
        return cluster.count >= 3 ? cluster[1] : cluster[0]
    }

    /// Moves an existing tone mark to where it belongs after a vowel changed.
    private static func retone(_ ch: inout [Character]) -> String {
        var tone = 0
        for i in ch.indices {
            if let v = vowel(ch[i]), v.tone > 0 { tone = v.tone; ch[i] = make(ch[i], v.base, 0) }
        }
        if tone > 0, let t = toneTarget(ch), let v = vowel(ch[t]) { ch[t] = make(ch[t], v.base, tone) }
        return String(ch)
    }

    // MARK: Vietnamese syllables

    private static let initials = [
        "ngh", "ng", "nh", "ch", "gh", "gi", "kh", "ph", "qu", "th", "tr",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x",
    ]
    private static let finalPrefixes: Set<String> = ["c", "ch", "m", "n", "ng", "nh", "p", "t"]
    private static let stopFinals: Set<String> = ["c", "ch", "p", "t"]

    private static let nuclei: [[Character]] = [
        "a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y",
        "ai", "ao", "au", "ay", "âu", "ây", "eo", "êu", "ia", "iê", "iu", "oa", "oă", "oe",
        "oi", "ôi", "ơi", "oo", "ua", "uâ", "uê", "ui", "uô", "uơ", "uy", "ưa", "ưi", "ươ", "ưu",
        "yê", "iêu", "yêu", "oai", "oay", "oeo", "uây", "uôi", "ươi", "ươu", "uya", "uyê", "uyu",
    ].map { Array($0) }

    /// Whether these vowels are a nucleus, or the start of one when `complete` is false.
    /// A plain letter may still get its hat or horn later ("tuan" before "tuân"), a marked one may not.
    private static func fitsNucleus(_ v: [Character], complete: Bool) -> Bool {
        nuclei.contains { n in
            (complete ? n.count == v.count : n.count >= v.count)
                && zip(v, n).allSatisfy { a, b in a == b || unmarked(b) == a }
        }
    }

    /// Whether `word` is a Vietnamese syllable, or the start of one.
    static func isPlausible(_ word: String) -> Bool {
        var base = ""
        var tone = 0
        for c in word.lowercased() {
            if let v = vowels[c] {
                base.append(v.base)
                if v.tone > 0 { tone = v.tone }
            } else {
                base.append(c)
            }
        }
        var rest = Substring(base)
        var initial = initials.first { rest.hasPrefix($0) } ?? ""
        rest = rest.dropFirst(initial.count)
        if initial == "gi" && !rest.contains(where: isVowelBase) {
            initial = "g"
            rest = "i" + rest
        }
        let vowelPart = Array(rest.prefix(while: isVowelBase))
        let final = String(rest.dropFirst(vowelPart.count))
        if vowelPart.isEmpty { return final.isEmpty }
        if final.isEmpty { return fitsNucleus(vowelPart, complete: false) }
        guard fitsNucleus(vowelPart, complete: true), finalPrefixes.contains(final) else { return false }
        if stopFinals.contains(final) && (2...4).contains(tone) { return false }
        return true
    }

    private static func isVowelBase(_ c: Character) -> Bool { "aăâeêioôơuưy".contains(c) }

    private static func unmarked(_ c: Character) -> Character {
        switch c {
        case "ă", "â": return "a"
        case "ê": return "e"
        case "ô", "ơ": return "o"
        case "ư": return "u"
        default: return c
        }
    }

    // MARK: Characters

    private static func vowel(_ c: Character) -> Vowel? { vowels[lower(c)] }

    /// Upper-case Vietnamese letters to lower case, built once.
    private static let lowercase: [Character: Character] = {
        var map: [Character: Character] = ["Đ": "đ"]
        for chars in forms.values {
            for c in chars { map[Character(c.uppercased())] = c }
        }
        return map
    }()

    /// Lower-cases a letter without building a string: runs several times per key press.
    private static func lower(_ c: Character) -> Character {
        if let ascii = c.asciiValue {
            return (65...90).contains(ascii) ? Character(Unicode.Scalar(ascii + 32)) : c
        }
        return lowercase[c] ?? c
    }

    private static func make(_ source: Character, _ base: Character, _ tone: Int) -> Character {
        let c = forms[base]![tone]
        return source.isUppercase ? Character(c.uppercased()) : c
    }
}

/// The word at the cursor while it is being typed.
struct TelexWord {
    private(set) var raw = ""
    private(set) var shown = ""
    private var literal = false

    init() {}

    /// Continues a word that is already in the text field.
    init(resuming word: String) {
        raw = Telex.rawKeys(for: word)
        shown = word
    }

    mutating func type(_ key: Character) {
        let rawBefore = raw
        let shownBefore = shown
        raw.append(key)
        if literal {
            shown.append(key)
            return
        }
        // No Vietnamese syllable takes this many keys. A word still shown as typed stays so,
        // and from here each key costs nothing however long it gets (hahahaha…).
        if raw.count > 12, shownBefore == rawBefore {
            literal = true
            shown.append(key)
            return
        }
        let (candidate, lastWasUndo) = Telex.process(raw)
        if lastWasUndo && shownBefore != rawBefore {
            // Repeating a modifier right after it took effect types that letter as is (as -> á -> as),
            // and the rest of the word stays as typed.
            literal = true
            shown = candidate
        } else if candidate == raw || Telex.isPlausible(candidate) {
            shown = candidate
        } else {
            // Not Vietnamese: keep the keys as typed.
            shown = raw
        }
    }
}
