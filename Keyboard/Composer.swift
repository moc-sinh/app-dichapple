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
    private var shown = ""
    /// The word at the cursor is one the engine cannot take up ("google", "iPhone"): letters
    /// go on it as typed until the word ends.
    private var literal = false
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
        lastSpaceAt = -10
    }

    /// The field reported its text. True when it was changed elsewhere, not just catching up.
    mutating func fieldChanged(before: String, after: String, now: Double) -> Bool {
        guard !text.explains(before: before, after: after, now: now) else { return false }
        start(before: before, after: after, now: now)
        return true
    }

    /// The cursor moved or the whole field was replaced: read the field again before the next key.
    mutating func forget() {
        text.forget()
        engine.reset()
        shown = ""
        literal = false
    }

    mutating func letter(_ c: Character, now: Double) -> [Edit] {
        lastSpaceAt = -10
        follow()
        if literal { return typeAsIs(c, now: now) }
        _ = engine.feed(c)
        // Longer than any word the engine holds (32 keys): the rest goes on as typed.
        if engine.isOverflowed {
            literal = true
            return typeAsIs(c, now: now)
        }
        return show(liveWord(), now: now)
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
        if !literal, !engine.isEmpty, Self.trailingWord(text.before ?? "") == shown {
            if shown == engine.composed {
                _ = engine.backspace()
                return show(liveWord(), now: now)
            }
            // The word shows as typed ("good", not the engine's "gôd"): its last letter goes, and the
            // rest stays as typed.
            literal = true
            let edits = record([.delete(1)], now: now)
            shown = Self.trailingWord(text.before ?? "")
            return edits
        }
        let canReopen = engine.canReopenLastCommit && !(text.before?.last?.isLetter ?? true)
        let edits = record([.delete(1)], now: now)
        let word = Self.trailingWord(text.before ?? "")
        literal = false
        if canReopen, let reopened = engine.reopenLastCommit(), reopened == word {
            shown = word
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
        shown = word
        literal = !word.isEmpty && !engine.seed(word)
    }

    /// The word is done: an English word the engine had changed goes back to the keys typed.
    private mutating func endWord(now: Double) -> [Edit] {
        defer {
            shown = ""
            literal = false
        }
        guard !literal, !engine.isEmpty, Self.trailingWord(text.before ?? "") == shown else {
            engine.reset()
            return []
        }
        // What the engine commits, from what is on screen (the engine's own edit assumes its
        // composed word is there, and it may show as typed already: `liveWord`).
        let final = engine.peekCommitText(autoRestore: true)
        _ = engine.commitBoundary(autoRestore: true)
        return show(final, now: now)
    }

    /// The word as it shows while typed: what the engine composed, unless that can no longer be
    /// Vietnamese ("gôd", "ưe"); then what the word would be if it ended now ("good", "we"). The
    /// engine alone keeps such marks until the word ends, and a message sent right after it went
    /// out with them.
    private func liveWord() -> String {
        let composed = engine.composed
        if Self.couldBeVietnamese(composed) { return composed }
        return engine.peekCommitText(autoRestore: true)
    }

    /// Whether more keys could still make `word` a Vietnamese syllable: a known onset, then the
    /// start of a known rime with the marks it has. A plain a, e, o, u or d may still get its mark
    /// ("đuo" + w is "đươ"); a marked letter must be right where it is: "ưe" fits no rime. The
    /// engine's own check is looser (it reads every marked letter as any of its marks) and let
    /// "we" show as "ưe".
    static func couldBeVietnamese(_ word: String) -> Bool {
        var letters = ""
        for c in word.lowercased() {
            if let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1,
               let (base, _) = Tables.detoneTable[scalar.value], let plain = Unicode.Scalar(base) {
                letters.append(Character(plain))
            } else {
                letters.append(c)
            }
        }
        for onset in onsetStarts.keys where letters.hasPrefix(onset) {
            let rest = String(letters.dropFirst(onset.count))
            // Still typing the onset ("q" of "qu", "ng" of "ngh"), or a rime begun after a whole one.
            if rest.isEmpty || (onsetStarts[onset] == true && rimeStarts.contains(rest)) { return true }
        }
        return false
    }

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

    private mutating func typeAsIs(_ c: Character, now: Double) -> [Edit] {
        let edits = record([.insert(String(c))], now: now)
        shown = Self.trailingWord(text.before ?? "")
        return edits
    }

    /// Only the part of the word that changed, so the field never flickers.
    static func diff(_ old: String, _ new: String) -> [Edit] {
        let a = Array(old), b = Array(new)
        var same = 0
        while same < a.count, same < b.count, a[same] == b[same] { same += 1 }
        var edits: [Edit] = []
        if same < a.count { edits.append(.delete(a.count - same)) }
        if same < b.count { edits.append(.insert(String(b[same...]))) }
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
        String(text.reversed().prefix { $0.isLetter }.reversed())
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

    /// Whether the field shows what this keyboard typed, now or a moment ago (the app has not
    /// caught up yet). If not, the text was changed elsewhere.
    mutating func explains(before field: String, after fieldAfter: String, now: Double) -> Bool {
        guard before != nil, fieldAfter == after else { return false }
        // Newest first. A text counts while it was on screen less than `lag` ago: until the next
        // edit replaced it, not since it was typed (the empty field read when the keyboard opened
        // stays current until the first key, however long that takes). Partial texts can match
        // several snapshots ("ha" and "haha"); the oldest is kept, so a later report is not
        // taken for going back.
        var match: Int?
        var replacedAt = now
        for snapshot in recent.reversed() {
            guard snapshot.n >= shown, now - replacedAt < Self.lag else { break }
            if Self.matches(snapshot.text, field) { match = snapshot.n }
            replacedAt = snapshot.at
        }
        guard let match else { return false }
        shown = match
        return true
    }

    /// The field often hands over only part of the text (the current paragraph or sentence).
    private static func matches(_ mine: String, _ field: String) -> Bool {
        if field.isEmpty { return mine.isEmpty || mine.last == "\n" }
        // Byte for byte first (the usual case, and fast); then as characters, for an app that
        // hands back the same text composed differently.
        if endsWith(mine.utf8, field.utf8) || (mine.utf8.count >= 48 && endsWith(field.utf8, mine.utf8)) { return true }
        return mine.hasSuffix(field) || (mine.count >= 16 && field.hasSuffix(mine))
    }

    private static func endsWith(_ text: String.UTF8View, _ suffix: String.UTF8View) -> Bool {
        text.count >= suffix.count && text.suffix(suffix.count).elementsEqual(suffix)
    }
}
