/// The keyboard's typing, without UIKit: Telex for the word at the cursor, kept in step with the
/// text before the cursor. Each call returns the edits to make in the text field.
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

    private(set) var word = TelexWord()
    private var text = TypedText()
    private var lastSpaceAt = -10.0
    private var spaceAfterWord = false

    /// The text before the cursor as this keyboard knows it; nil when it does not.
    var before: String? { text.before }

    mutating func start(before: String, after: String, now: Double) {
        text.reset(before: before, after: after, now: now)
        word = TelexWord()
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
        word = TelexWord()
    }

    mutating func letter(_ c: Character, now: Double) -> [Edit] {
        let current = Self.trailingWord(text.before ?? "")
        if current != word.shown { word = TelexWord(resuming: current) }
        let old = word.shown
        word.type(c)
        lastSpaceAt = -10
        return apply(Self.diff(old, word.shown), now: now)
    }

    /// Digits, punctuation, or a letter from the accent picker, typed as is.
    mutating func insert(_ s: String, now: Double) -> [Edit] {
        let edits = apply([.insert(s)], now: now)
        word = !s.isEmpty && s.allSatisfy(\.isLetter) ? TelexWord(resuming: Self.trailingWord(text.before ?? "")) : TelexWord()
        lastSpaceAt = -10
        return edits
    }

    /// Two spaces after a word make ". ", as on the system keyboard.
    mutating func space(now: Double) -> [Edit] {
        let before = text.before ?? ""
        word = TelexWord()
        if now - lastSpaceAt < 1.2, spaceAfterWord, before.hasSuffix(" ") {
            lastSpaceAt = -10
            return apply([.delete(1), .insert(". ")], now: now)
        }
        spaceAfterWord = before.last.map { $0.isLetter || $0.isNumber } ?? false
        lastSpaceAt = now
        return apply([.insert(" ")], now: now)
    }

    mutating func newline(now: Double) -> [Edit] {
        word = TelexWord()
        lastSpaceAt = -10
        return apply([.insert("\n")], now: now)
    }

    /// Deleting a letter keeps composing the rest of the word, so its marks can still change.
    mutating func deleteBackward(now: Double) -> [Edit] {
        lastSpaceAt = -10
        let edits = apply([.delete(1)], now: now)
        word = TelexWord(resuming: Self.trailingWord(text.before ?? ""))
        return edits
    }

    /// Deletes the word before the cursor and the spaces after it.
    mutating func deleteWord(now: Double) -> [Edit] {
        let before = Array(text.before ?? "")
        var i = before.count
        while i > 0, before[i - 1].isWhitespace { i -= 1 }
        while i > 0, !before[i - 1].isWhitespace { i -= 1 }
        lastSpaceAt = -10
        let edits = apply([.delete(max(1, before.count - i))], now: now)
        word = TelexWord()
        return edits
    }

    private mutating func apply(_ edits: [Edit], now: Double) -> [Edit] {
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
