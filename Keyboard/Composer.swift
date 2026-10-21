/// The keyboard's typing, without UIKit: Telex for the word at the cursor, kept in step with the
/// text before the cursor. Each call returns the edits to make in the text field.
///
/// Telex itself is VietTelex's `TelexEngine` (TelexCore/, MIT): 9,091 checked cases, tones typed
/// anywhere in the word find their vowel ("hofang" → hoàng), and a word that cannot be Vietnamese
/// is put back as typed when it ends ("gôgle " → "google ").
///
/// iOS updates `documentContextBeforeInput` only after the app has applied an edit, so while
/// someone types fast it lags a few keys behind. Reading it before every key turned "là" into
/// "lf" and "w" into "ưw". The keyboard keeps its own copy instead (`TypedText`) and takes the
/// field's text only when it was changed elsewhere: the app cleared it after sending, the user
/// moved the cursor or pasted.
struct Composer {
    enum Edit: Equatable {
        case delete(Int)
        case insert(String)
    }

    private var engine = Composer.makeEngine()
    /// The word before the cursor as the engine last left it. When the text there ends
    /// otherwise, the word was changed elsewhere (the cursor moved, an accent was picked) and
    /// the engine takes it up again before the next key.
    private var shown = "" { didSet { vietnameseShown = false } }
    /// `shown` is the engine's word and could still be Vietnamese, not the keys as typed.
    private var vietnameseShown = false
    /// The letter typed last turned a Vietnamese word into the keys typed ("không" + k shows
    /// "khoongk"): a ⌫ right after it takes that letter back.
    private var turnedAsTyped = false
    /// Letters after a Vietnamese word that spoilt it, shown after it as typed ("đưng" + d is
    /// "đưngd"): a stray key, not an English word (`isStrayLetterTypo`). The engine holds them too,
    /// so the word ends as it always did; ⌫ takes them back one by one and the word goes on.
    private var stray = 0
    /// The word at the cursor is one the engine cannot take up ("google", "iPhone"): letters
    /// go on it as typed until the word ends.
    private var literal = false
    /// The word `take` handed the engine, as it was in the field, and the keys `seed` typed for it.
    private var seeded: (word: String, keys: String)?
    private var text = TypedText()
    private var lastSpaceAt = -10.0
    private var spaceAfterWord = false

    /// Telex as MSTrans types it.
    static func makeEngine() -> TelexEngine {
        var engine = TelexEngine()
        // Marks and tones typed late still find their vowel: "hofang" → hoàng, "dduocwj" → được.
        engine.freeMarking = true
        // hoà, khoẻ, thuỷ, as MSTrans has always written them.
        engine.modernTone = true
        // A word that can no longer be Vietnamese stops changing as it is typed.
        engine.liveSpellCheck = true
        // Standard spelling only: chat spellings (w for qu, z for d) would turn English words
        // ("was", "war") into Vietnamese ones. VietTelex's app has it off too.
        engine.teencode = false
        // A word both English and Vietnamese ("last"/lát, "list"/lít) stays Vietnamese.
        engine.collisionPrefersVietnamese = true
        return engine
    }

    /// The text before the cursor as this keyboard knows it; nil when it does not.
    var before: String? { text.before }

    mutating func start(before: String, after: String, now: Double) {
        text.reset(before: before, after: after, now: now)
        engine.reset()
        engine.resetContext()
        shown = ""
        literal = false
        stray = 0
        lastSpaceAt = -10
    }

    /// The field reported its text. True when it was changed elsewhere, not just catching up.
    mutating func fieldChanged(before: String, after: String, now: Double) -> Bool {
        switch text.compare(before: before, after: after, now: now) {
        case .caughtUp:
            return false
        case .sent:
            // The word before the newline went with the message: ⌫ has nothing to re-open.
            engine.reset()
            shown = ""
            literal = false
            stray = 0
            lastSpaceAt = -10
            return true
        case .changed:
            start(before: before, after: after, now: now)
            return true
        }
    }

    /// The field reported its text while keys are going in. Nothing is taken from it then (see
    /// `KeyboardViewController.fieldReported`), but text this keyboard typed tells how far the app
    /// has caught up: a field emptied after it was sent, not a report trailing the first keys.
    mutating func fieldCaughtUp(before: String, after: String, now: Double) {
        guard !before.isEmpty || !after.isEmpty else { return }
        _ = text.compare(before: before, after: after, now: now)
    }

    /// The cursor moved `offset` characters (the space bar as a trackpad): the known text moves
    /// with it, so a key typed before the app reports the move still edits the word at the cursor.
    /// Read straight away, the app's text could still be from before the move, and Telex edited the
    /// word the cursor had left ("chao ban", cursor back to "chao|", f: "chàn ban"). Beyond what is
    /// known, the field is read again.
    mutating func moveCursor(by offset: Int, now: Double) {
        text.move(by: offset, now: now)
        engine.reset()
        shown = ""
        literal = false
        stray = 0
        lastSpaceAt = -10
    }

    /// The cursor moved or the whole field was replaced: read the field again before the next key.
    mutating func forget() {
        text.forget()
        engine.reset()
        shown = ""
        literal = false
        stray = 0
    }

    mutating func letter(_ c: Character, now: Double) -> [Edit] {
        lastSpaceAt = -10
        turnedAsTyped = false
        follow()
        // A new word: nothing the engine holds was taken from the field.
        if engine.isEmpty { seeded = nil }
        if literal { return typeAsIs(c, now: now) }
        let wasVietnamese = vietnameseShown
        let wasShown = shown
        _ = engine.feed(c)
        // Longer than any word the engine holds (32 keys): the rest goes on as typed.
        if engine.isOverflowed {
            literal = true
            return typeAsIs(c, now: now)
        }
        if stray > 0 {
            stray += 1
            return show((wasShown + String(c), false), now: now)
        }
        var live = liveWord()
        if wasVietnamese, !live.vietnamese, live.word == engine.rawKeystrokes, Self.isStrayLetterTypo(live.word) {
            stray = 1
            live = (wasShown + String(c), false)
        }
        let edits = show(live, now: now)
        turnedAsTyped = wasVietnamese && !vietnameseShown && shown == engine.rawKeystrokes
        return edits
    }

    /// Digits, punctuation, or a letter from the accent picker, typed as is. Anything but a letter
    /// ends the word first.
    mutating func insert(_ s: String, now: Double) -> [Edit] {
        lastSpaceAt = -10
        guard s.first?.isLetter ?? false else { return endWord(now: now) + record([.insert(s)], now: now) }
        // An accented letter joins the word: the engine takes the word up again at the next key.
        let edits = record([.insert(s)], now: now)
        engine.reset()
        literal = false
        stray = 0
        shown = ""
        return edits
    }

    /// Two spaces after a word make ". ", as on the system keyboard.
    mutating func space(now: Double) -> [Edit] {
        let ended = endWord(now: now)
        let before = text.before ?? ""
        if now - lastSpaceAt < 1.2, spaceAfterWord, before.hasSuffix(" ") {
            lastSpaceAt = -10
            // The space a ⌫ would have taken back to re-open the word is gone.
            engine.forgetLastCommit()
            return ended + record([.delete(1), .insert(". ")], now: now)
        }
        spaceAfterWord = before.last.map { $0.isLetter || $0.isNumber } ?? false
        lastSpaceAt = now
        return ended + record([.insert(" ")], now: now)
    }

    mutating func newline(now: Double) -> [Edit] {
        lastSpaceAt = -10
        return endWord(now: now) + record([.insert("\n")], now: now)
    }

    /// Inside the word being composed, the last letter shown goes ("khô" → "kh") and the rest keeps
    /// composing. Elsewhere one character goes, and the word now before the cursor is taken up
    /// again: ⌫ over the space after a word re-opens it ("tháy ⌫ a" → "thấy").
    mutating func deleteBackward(now: Double) -> [Edit] {
        lastSpaceAt = -10
        let turned = turnedAsTyped
        turnedAsTyped = false
        if stray > 0, Self.trailingWord(text.before ?? "") == shown {
            // The stray letter goes; with the last one gone the word is Vietnamese again and goes on.
            let edits = record([.delete(1)], now: now)
            stray -= 1
            let keys = engine.rawKeystrokes.dropLast()
            engine.reset()
            for key in keys { _ = engine.feed(key) }
            let word = Self.trailingWord(text.before ?? "")
            let live = liveWord()
            if stray == 0, live.word == word {
                shown = word
                vietnameseShown = live.vietnamese
            } else if stray == 0 {
                take(word)
            } else {
                shown = word
            }
            return edits
        }
        if !literal, !engine.isEmpty, Self.trailingWord(text.before ?? "") == shown {
            if turned, case let keys = engine.rawKeystrokes, !Self.isKnownEnglish(keys) {
                // The letter just typed turned the word into its keys ("không" + k showed
                // "khoongk"): it is taken back, and the word shows as it did before it.
                engine.reset()
                for key in keys.dropLast() { _ = engine.feed(key) }
                return show(liveWord(), now: now)
            }
            if shown == engine.composed, seeded == nil {
                let rest = String(shown.dropLast())
                _ = engine.backspace()
                // Only the last letter goes: re-reading the shorter word, the engine may compose it
                // otherwise ("are" ⌫ showed "ả", "after" ⌫⌫ "at").
                let live = liveWord()
                if live.word == rest { return show(live, now: now) }
            }
            // The word shows as typed ("good", not the engine's "gôd"), or the engine would change
            // more than its last letter: that letter goes, and the rest stays as it is.
            let edits = record([.delete(1)], now: now)
            take(Self.trailingWord(text.before ?? ""))
            return edits
        }
        let canReopen = engine.canReopenLastCommit && !(text.before?.last?.isLetter ?? true)
        let edits = record([.delete(1)], now: now)
        let word = Self.trailingWord(text.before ?? "")
        literal = false
        if canReopen, let reopened = engine.reopenLastCommit(), reopened == word {
            shown = word
            seeded = nil
            vietnameseShown = Self.couldBeVietnamese(word)
        } else {
            take(word)
        }
        return edits
    }

    /// Deletes the word before the cursor and the spaces after it.
    mutating func deleteWord(now: Double) -> [Edit] {
        let before = Array(text.before ?? "")
        var i = before.count
        while i > 0, before[i - 1].isWhitespace { i -= 1 }
        while i > 0, !before[i - 1].isWhitespace { i -= 1 }
        lastSpaceAt = -10
        engine.reset()
        literal = false
        stray = 0
        let edits = record([.delete(max(1, before.count - i))], now: now)
        shown = ""
        return edits
    }

    /// The word at the cursor, when it is not the one the engine left there.
    private mutating func follow() {
        let current = Self.trailingWord(text.before ?? "")
        guard current != shown else { return }
        take(current)
    }

    /// Makes `word`, already in the field, the one being composed: the engine takes it up when it
    /// would type it exactly the same way (`seed`), so "toan" + s gives "toán"; otherwise letters
    /// go on as typed.
    private mutating func take(_ word: String) {
        engine.reset()
        stray = 0
        shown = word
        literal = !word.isEmpty && !engine.seed(word)
        seeded = literal || word.isEmpty ? nil : (word, engine.rawKeystrokes)
        vietnameseShown = !literal && !word.isEmpty && Self.couldBeVietnamese(word)
    }

    /// An English word the engine knows ("good", "are", "wifi"): ⌫ right after its last letter
    /// takes that letter as typed.
    private static func isKnownEnglish(_ keys: String) -> Bool {
        let word = keys.lowercased()
        return EnglishContextWords.words.contains(word) || EnglishCollisions.words.contains(word)
            || EnglishContextWords.neutralLoanwords.contains(word) || EnglishContextWords.restoreOnly.contains(word)
    }

    /// The word is done: an English word the engine had changed goes back to the keys typed.
    private mutating func endWord(now: Double) -> [Edit] {
        defer {
            shown = ""
            literal = false
            stray = 0
        }
        guard !literal, !engine.isEmpty, Self.trailingWord(text.before ?? "") == shown else {
            engine.reset()
            return []
        }
        // What the engine commits, from what is on screen (the engine's own edit assumes its
        // composed word is there, and it may show as typed already: `liveWord`).
        let final = ending()
        _ = engine.commitBoundary(autoRestore: true)
        return show(final, now: now)
    }

    /// What the word would be if it ended now. Put back as typed, a word taken up from the field
    /// keeps the letters it had there: "việt" + k is "việtk", not the keys `seed` typed ("vieetjk").
    private func ending() -> String {
        let final = engine.peekCommitText(autoRestore: true)
        guard let seeded, final == engine.rawKeystrokes, final.hasPrefix(seeded.keys) else { return final }
        return seeded.word + final.dropFirst(seeded.keys.count)
    }

    /// The word as it shows while typed: what the engine composed, unless that can no longer be
    /// Vietnamese ("gôd", "ưe"); then what the word would be if it ended now ("good", "we"). The
    /// engine alone keeps such marks until the word ends, and a message sent right after it went
    /// out with them.
    private func liveWord() -> (word: String, vietnamese: Bool) {
        let composed = engine.composed
        if Self.couldBeVietnamese(composed) { return (composed, true) }
        return (ending(), false)
    }

    /// Keys that spell no English word: a Vietnamese word with a stray letter (d next to f, q next
    /// to w), which stays as it was with the letter after it ("đưngd", one ⌫ from "đưng") rather
    /// than turning into its keys ("dduwngd", which had to be deleted whole and typed again). Only
    /// marks Telex alone types say so: đ as dd at the start, the horn as uw, â as aa, a tone's j
    /// before a consonant; "ee", "oo", "ow" or an s are English too (good, new), and so is any
    /// beginning of an English word the engine knows ("majo" is "major" on its way).
    static func isStrayLetterTypo(_ keys: String) -> Bool {
        let lower = keys.lowercased()
        guard lower.contains(where: { "aeiouy".contains($0) }), !englishStarts.contains(lower) else { return false }
        if lower.hasPrefix("dd") || lower.contains("uw") || lower.contains("aa") { return true }
        var previous: Character = " "
        for c in lower {
            if previous == "j", !"aeiouy".contains(c) { return true }
            previous = c
        }
        return false
    }

    /// Every beginning of an English word the engine knows, so "majo" is still "major" on its way.
    private static let englishStarts: Set<String> = {
        var starts: Set<String> = []
        for list in [EnglishContextWords.words, EnglishCollisions.words, EnglishContextWords.neutralLoanwords,
                     EnglishContextWords.restoreOnly] {
            for word in list {
                var s = ""
                for c in word {
                    s.append(c)
                    starts.insert(s)
                }
            }
        }
        return starts
    }()

    /// Whether more keys could still make `word` a Vietnamese syllable: a known onset, then the
    /// start of a known rime with the marks it has. A plain a, e, o, u or d may still get its mark
    /// ("đuo" + w is "đươ"); a marked letter must be right where it is: "ưe" fits no rime. The
    /// engine's own check is looser (it reads every marked letter as any of its marks) and let
    /// "we" show as "ưe".
    static func couldBeVietnamese(_ word: String) -> Bool {
        // The engine's words (a–z and the Vietnamese letters, at most 32) go through the same
        // tables compiled to tries, without building strings; anything else the long way.
        let scalars = word.unicodeScalars
        if scalars.count <= 32, let fast = withUnsafeTemporaryAllocation(of: UInt8.self, capacity: 32, { buffer -> Bool? in
            var n = 0
            for scalar in scalars {
                guard let letter = letterClasses[scalar.value] else { return nil }
                buffer[n] = letter
                n += 1
            }
            var node: Int32 = 0
            for k in 0...min(n, longestOnset) {
                if k > 0 {
                    node = onsetTrie.step(node, buffer[k - 1])
                    if node < 0 { return false }
                }
                let mask = onsetTrie.mask(node)
                if mask == 0 { continue }
                if k == n { return true }
                guard mask == 2 else { continue }
                var rime: Int32 = 0
                for i in k..<n {
                    rime = rimeTrie.step(rime, buffer[i])
                    if rime < 0 { break }
                }
                if rime >= 0 { return true }
            }
            return false
        }) { return fast }
        var letters = ""
        for c in word.lowercased() {
            if let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1,
               let (base, _) = Tables.detoneTable[scalar.value], let plain = Unicode.Scalar(base) {
                letters.append(Character(plain))
            } else {
                letters.append(c)
            }
        }
        // Each beginning of the word that is an onset (or the start of one): a few lookups, not a
        // walk over every onset.
        var end = letters.startIndex
        var taken = 0
        while true {
            if let whole = onsetStarts[String(letters[..<end])] {
                let rest = letters[end...]
                // Still typing the onset ("q" of "qu", "ng" of "ngh"), or a rime begun after a whole one.
                if rest.isEmpty || (whole && rimeStarts.contains(String(rest))) { return true }
            }
            guard taken < longestOnset, end < letters.endIndex else { return false }
            letters.formIndex(after: &end)
            taken += 1
        }
    }

    private static let longestOnset = onsetStarts.keys.map(\.count).max() ?? 0

    /// `onsetStarts` and `rimeStarts` as tries over the engine's letter classes: an onset start
    /// has mask 1, a whole onset 2.
    private static let onsetTrie = ClassTrie(onsetStarts.map { ($0.key, $0.value ? 2 : 1) })
    private static let rimeTrie = ClassTrie(rimeStarts.map { ($0, 1) })

    /// Letter class of each letter the engine writes, lowercased and without its tone: "Ấ" is â.
    private static let letterClasses: [UInt32: UInt8] = {
        var classes: [UInt32: UInt8] = [:]
        for letter in "abcdefghijklmnopqrstuvwxyzđ" {
            for form in [letter, Character(letter.uppercased())] {
                classes[form.unicodeScalars.first!.value] = Tables.charClass[letter]
            }
        }
        for (toned, (base, _)) in Tables.detoneTable {
            guard let plain = Unicode.Scalar(base), let lower = String(plain).lowercased().first,
                  let letter = Tables.charClass[lower] else { continue }
            classes[toned] = letter
        }
        return classes
    }()

    /// Every beginning of a standard onset; true when it is a whole onset.
    private static let onsetStarts: [String: Bool] = {
        var starts: [String: Bool] = [:]
        for onset in SyllableValidator.onsets.subtracting(SyllableValidator.teencodeOnsets) {
            var s = ""
            for c in onset {
                s.append(c)
                if starts[s] == nil { starts[s] = false }
            }
            starts[onset] = true
        }
        // A plain d may still become đ.
        starts["d"] = true
        return starts
    }()

    /// Every beginning of a standard rime, also with any of its marks still missing (they come
    /// with later keys): "iê", "ie", "ươ", "uơ", "uo"…
    private static let rimeStarts: Set<String> = {
        let plain: [Character: Character] = ["ă": "a", "â": "a", "ê": "e", "ô": "o", "ơ": "o", "ư": "u"]
        var starts: Set<String> = []
        for rime in SyllableValidator.rimes.subtracting(SyllableValidator.teencodeRimes) {
            var variants: [String] = [""]
            for c in rime {
                variants = variants.flatMap { v -> [String] in
                    plain[c].map { [v + String(c), v + String($0)] } ?? [v + String(c)]
                }
                starts.formUnion(variants)
            }
        }
        return starts
    }()

    /// Puts `word` on screen in place of the word shown: only the part that changed.
    private mutating func show(_ word: String, now: Double) -> [Edit] {
        let edits = record(Self.diff(shown, word), now: now)
        shown = word
        return edits
    }

    private mutating func show(_ live: (word: String, vietnamese: Bool), now: Double) -> [Edit] {
        let edits = show(live.word, now: now)
        vietnameseShown = live.vietnamese
        return edits
    }

    private mutating func typeAsIs(_ c: Character, now: Double) -> [Edit] {
        let edits = record([.insert(String(c))], now: now)
        shown = Self.trailingWord(text.before ?? "")
        return edits
    }

    /// Only the part of the word that changed, so the field never flickers.
    static func diff(_ old: String, _ new: String) -> [Edit] {
        var i = old.startIndex, j = new.startIndex
        while i < old.endIndex, j < new.endIndex, old[i] == new[j] {
            old.formIndex(after: &i)
            new.formIndex(after: &j)
        }
        var edits: [Edit] = []
        if i < old.endIndex { edits.append(.delete(old.distance(from: i, to: old.endIndex))) }
        if j < new.endIndex { edits.append(.insert(String(new[j...]))) }
        return edits
    }

    private mutating func record(_ edits: [Edit], now: Double) -> [Edit] {
        for edit in edits {
            switch edit {
            case .delete(let count):
                for _ in 0..<count { text.deleteBackward(now: now) }
            case .insert(let s):
                text.insert(s, now: now)
            }
        }
        return edits
    }

    /// The edits that take a key back, given the text before the cursor as it was before the key:
    /// what it inserted goes, what it deleted comes back. Nil when what it deleted is not known.
    static func inverse(of edits: [Edit], before: String?) -> [Edit]? {
        var deleted = 0, inserted = 0
        for edit in edits {
            switch edit {
            case .delete(let count): deleted += count
            case .insert(let s): inserted += s.count
            }
        }
        // A key's edits delete first, then insert.
        var undo: [Edit] = inserted > 0 ? [.delete(inserted)] : []
        if deleted > 0 {
            guard let gone = before?.suffix(deleted), gone.count == deleted else { return nil }
            undo.append(.insert(String(gone)))
        }
        return undo
    }

    static func trailingWord(_ text: String) -> String {
        // Walked back by index: `reversed().prefix().reversed()` went through generic code that
        // took most of the time of a key.
        var start = text.endIndex
        while start > text.startIndex {
            let previous = text.index(before: start)
            guard text[previous].isLetter else { break }
            start = previous
        }
        return String(text[start...])
    }
}

/// What this keyboard knows is before the cursor, and what it was a moment ago.
struct TypedText {
    private struct Snapshot {
        let text: String
        let at: Double
        /// Counts up with every snapshot.
        let n: Int
    }

    /// Nil when unknown: the field's text is read again.
    private(set) var before: String?
    /// Characters in `before`, kept as it changes: counting a String walks all of it.
    private var length = 0
    /// The text after the cursor when this was last read; typing never changes it.
    private var after = ""
    private var recent: [Snapshot] = []
    private var count = 0
    /// The oldest snapshot the field can still be showing. An app shows edits in order and never
    /// goes back, so once it has shown a text, older ones are not lag any more: an empty field
    /// after the message appeared means it was sent.
    private var shown = 0

    private static let keep = 300
    private static let history = 64
    /// How long an app may take to show an edit.
    static let lag = 1.5
    /// An empty field reported once typing paused: the app emptied it (the message was sent), even
    /// if it never reported the message itself, unless the keys typed into the empty field are
    /// that recent. Only the first keys into an empty field can trail a report of it empty, and
    /// those reach the app within a moment. With `lag`, a short message sent quickly ("ok") stayed
    /// in this copy, and the next one went on from it ("okchaof").
    static let emptiedLag = 0.5
    /// How long after the last key a report counts as made once typing paused (a thumb going to the
    /// app's Send button), not between keys.
    static let paused = 0.2

    mutating func reset(before: String, after: String, now: Double) {
        let kept = String(before.suffix(Self.keep))
        self.before = kept
        length = kept.count
        self.after = after
        count += 1
        shown = count
        recent = [Snapshot(text: kept, at: now, n: count)]
    }

    mutating func forget() {
        before = nil
        recent.removeAll()
    }

    /// The cursor moved over `offset` UTF-16 units of the text (left when negative), as
    /// `adjustTextPosition` counts them. Unknown when that leaves what is known, or splits a letter.
    mutating func move(by offset: Int, now: Double) {
        guard var b = before, offset != 0 else { return }
        var a = after
        if offset < 0 {
            let units = b.utf16
            guard let cut = units.index(units.endIndex, offsetBy: offset, limitedBy: units.startIndex),
                  let i = cut.samePosition(in: b) else { return forget() }
            a = String(b[i...]) + a
            b = String(b[..<i])
        } else {
            let units = a.utf16
            guard let cut = units.index(units.startIndex, offsetBy: offset, limitedBy: units.endIndex),
                  let i = cut.samePosition(in: a) else { return forget() }
            b += a[..<i]
            a = String(a[i...])
        }
        reset(before: b, after: a, now: now)
    }

    mutating func insert(_ s: String, now: Double) {
        guard var b = before else { return }
        b += s
        length += s.count
        // Trimmed now and then, not on every key.
        if length > Self.keep + 100 {
            b = String(b.suffix(Self.keep))
            length = Self.keep
        }
        record(b, now)
    }

    mutating func deleteBackward(now: Double) {
        // Deleting past what is known: read the field again.
        guard var b = before, !b.isEmpty else { return forget() }
        b.removeLast()
        length = max(0, length - 1)
        record(b, now)
    }

    private mutating func record(_ b: String, _ now: Double) {
        before = b
        count += 1
        recent.append(Snapshot(text: b, at: now, n: count))
        if recent.count > Self.history { recent.removeFirst(recent.count - Self.history) }
    }

    enum Report {
        /// The field shows what this keyboard typed, now or a moment ago (the app has not caught up).
        case caughtUp
        /// Emptied right after a newline: the app took ⏎ as Send. The text up to the newline is gone,
        /// what was typed after it stays. Kept, the sent message came back under ⌫ ("ok" ⏎ ⌫ "ddi"
        /// gave "ddi", the keys going on from "ok").
        case sent
        /// Changed elsewhere.
        case changed
    }

    mutating func compare(before field: String, after fieldAfter: String, now: Double) -> Report {
        guard before != nil, fieldAfter == after else { return .changed }
        let emptied = field.isEmpty && fieldAfter.isEmpty
        // Newest first. A text counts while it was on screen less than `lag` ago: until the next
        // edit replaced it, not since it was typed (the empty field read when the keyboard opened
        // stays current until the first key, however long that takes). Partial texts can match
        // several snapshots ("ha" and "haha"); the oldest is kept, so a later report is not
        // taken for going back. The text on screen now always counts.
        let window = emptied && now - (recent.last?.at ?? now) >= Self.paused ? Self.emptiedLag : Self.lag
        var match: Snapshot?
        // Emptied, and the text on screen then ended in a newline. Taken before an older empty
        // text the report could also be trailing: wrongly taken, it loses nothing after a newline.
        var sentAt: Snapshot?
        var replacedAt = now
        var current = true
        for snapshot in recent.reversed() {
            guard snapshot.n >= shown, current || now - replacedAt < window else { break }
            if Self.matches(snapshot.text, field) {
                match = snapshot
                if emptied, sentAt == nil, snapshot.text.last == "\n" { sentAt = snapshot }
            }
            replacedAt = snapshot.at
            current = false
        }
        if let sentAt {
            shown = sentAt.n
            // What was typed since the newline is all the field holds.
            let newer = recent.filter { $0.n >= sentAt.n }
            guard newer.allSatisfy({ $0.text.hasPrefix(sentAt.text) }) else {
                // The newline was deleted since: read the field again.
                forget()
                return .sent
            }
            let cut = sentAt.text.count
            recent = newer.map { Snapshot(text: String($0.text.dropFirst(cut)), at: $0.at, n: $0.n) }
            before = recent.last?.text ?? ""
            length = before?.count ?? 0
            return .sent
        }
        guard let match else { return .changed }
        shown = match.n
        return .caughtUp
    }

    /// The field often hands over only part of the text (the current paragraph or sentence).
    private static func matches(_ mine: String, _ field: String) -> Bool {
        if field.isEmpty { return mine.isEmpty || mine.last == "\n" }
        // Byte for byte first (the usual case, and fast); then as characters, for an app that
        // hands back the same text composed differently. Never when the last letters already differ,
        // as they do for most of the texts compared: those cost next to nothing.
        if endsWith(mine.utf8, field.utf8) || (mine.utf8.count >= 48 && endsWith(field.utf8, mine.utf8)) { return true }
        guard mine.last == field.last else { return false }
        return mine.hasSuffix(field) || (mine.count >= 16 && field.hasSuffix(mine))
    }

    private static func endsWith(_ text: String.UTF8View, _ suffix: String.UTF8View) -> Bool {
        text.count >= suffix.count && text.suffix(suffix.count).elementsEqual(suffix)
    }
}
