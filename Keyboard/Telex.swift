/// Telex input for Vietnamese: `aa`→â, `aw`→ă, `ow`→ơ, `uw`→ư, `dd`→đ, and
/// `s f r x j` for sắc, huyền, hỏi, ngã, nặng (`z` removes the tone).
/// Typing the same modifier twice undoes it (`as` + `s` → `ass`).
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

    /// Returns the word after typing `key` at its end.
    static func apply(_ word: String, _ key: Character) -> String {
        let k = lower(key)
        var ch = Array(word)
        let plain = word + String(key)
        guard !ch.isEmpty else { return String(key) }

        if let t = toneKeys.firstIndex(of: k) { return tone(ch, t + 1, plain) }
        if k == "z" { return tone(ch, 0, plain) }

        if k == "d", lower(ch[0]) == "d" {
            ch[0] = isUpper(ch[0]) ? "Đ" : "đ"
            return String(ch)
        }

        if let hat = hats[k] {
            for i in ch.indices.reversed() {
                guard let v = vowel(ch[i]) else { continue }
                if v.base == k { ch[i] = make(ch[i], hat, v.tone); return retone(&ch) }
                if v.base == hat { ch[i] = make(ch[i], k, v.tone); return retone(&ch) + String(key) }
            }
            return plain
        }

        if k == "w" {
            for i in 0..<(ch.count - 1) {
                if let a = vowel(ch[i]), let b = vowel(ch[i + 1]), a.base == "u", b.base == "o" {
                    ch[i] = make(ch[i], "ư", a.tone)
                    ch[i + 1] = make(ch[i + 1], "ơ", b.tone)
                    return retone(&ch)
                }
            }
            for i in ch.indices.reversed() {
                guard let v = vowel(ch[i]) else { continue }
                if let h = horns[v.base] { ch[i] = make(ch[i], h, v.tone); return retone(&ch) }
                if let p = unhorns[v.base] { ch[i] = make(ch[i], p, v.tone); return retone(&ch) + String(key) }
            }
            return plain
        }

        return plain
    }

    private static func tone(_ original: [Character], _ tone: Int, _ plain: String) -> String {
        var ch = original
        let current = ch.firstIndex { (vowel($0)?.tone ?? 0) > 0 }
        guard let target = toneTarget(ch) else { return plain }
        if tone == 0 && current == nil { return plain }
        if let cur = current, let v = vowel(ch[cur]) {
            ch[cur] = make(ch[cur], v.base, 0)
            if v.tone == tone { return String(ch) + String(plain.last!) }
        }
        if tone > 0, let v = vowel(ch[target]) { ch[target] = make(ch[target], v.base, tone) }
        return String(ch)
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

    private static func vowel(_ c: Character) -> Vowel? { vowels[lower(c)] }
    private static func lower(_ c: Character) -> Character { Character(c.lowercased()) }
    private static func isUpper(_ c: Character) -> Bool { c.isUppercase }

    private static func make(_ source: Character, _ base: Character, _ tone: Int) -> Character {
        let c = forms[base]![tone]
        return isUpper(source) ? Character(c.uppercased()) : c
    }
}
