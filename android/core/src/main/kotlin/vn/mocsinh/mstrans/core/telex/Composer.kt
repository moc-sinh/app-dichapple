// Composer.kt
// Port of MSTrans's Keyboard/Composer.swift: the keyboard's typing, without the platform. Telex
// for the word at the cursor, kept in step with the text before the cursor. Each call returns the
// edits to make in the text field.
//
// Telex itself is VietTelex's TelexEngine (MIT): 9,091 checked cases, tones typed anywhere in the
// word find their vowel ("hofang" → hoàng), and a word that cannot be Vietnamese is put back as
// typed when it ends ("gôgle " → "google ").
//
// The keyboard keeps its own copy of the text before the cursor (TypedText) and takes the field's
// text only when it was changed elsewhere: the app cleared it after sending, the user moved the
// cursor or pasted. Field reports trail the keys while someone types fast.
package vn.mocsinh.mstrans.core.telex

import java.text.BreakIterator
import java.text.Normalizer

class Composer {
    /** `Delete` counts characters as a person sees them (grapheme clusters). */
    sealed class Edit {
        data class Delete(val count: Int) : Edit()
        data class Insert(val text: String) : Edit()
    }

    private val engine = makeEngine()
    /**
     * The word before the cursor as the engine last left it. When the text there ends otherwise,
     * the word was changed elsewhere (the cursor moved, an accent was picked) and the engine takes
     * it up again before the next key.
     */
    private var shown = ""
        set(value) {
            field = value
            vietnameseShown = false
        }
    /** `shown` is the engine's word and could still be Vietnamese, not the keys as typed. */
    private var vietnameseShown = false
    /** The letter typed last turned a Vietnamese word into the keys typed: ⌫ takes it back. */
    private var turnedAsTyped = false
    /** Letters after a Vietnamese word that spoilt it, shown after it as typed ("đưngd"). */
    private var stray = 0
    /** The word at the cursor is one the engine cannot take up ("google"): letters go on as typed. */
    private var literal = false
    /** The word `take` handed the engine, as it was in the field, and the keys `seed` typed for it. */
    private var seeded: Pair<String, String>? = null
    private val text = TypedText()
    private var lastSpaceAt = -10.0
    private var spaceAfterWord = false

    /** The text before the cursor as this keyboard knows it; null when it does not. */
    val before: String? get() = text.before

    fun start(before: String, after: String, now: Double) {
        text.reset(before, after, now)
        engine.reset()
        engine.resetContext()
        shown = ""
        literal = false
        stray = 0
        lastSpaceAt = -10.0
    }

    /** The field reported its text. True when it was changed elsewhere, not just catching up. */
    fun fieldChanged(before: String, after: String, now: Double): Boolean {
        return when (text.compare(before, after, now)) {
            TypedText.Report.CAUGHT_UP -> false
            TypedText.Report.SENT -> {
                // The word before the newline went with the message: ⌫ has nothing to re-open.
                engine.reset()
                shown = ""
                literal = false
                stray = 0
                lastSpaceAt = -10.0
                true
            }
            TypedText.Report.CHANGED -> {
                start(before, after, now)
                true
            }
        }
    }

    /** The field reported its text while keys are going in: only tells how far the app caught up. */
    fun fieldCaughtUp(before: String, after: String, now: Double) {
        if (before.isEmpty() && after.isEmpty()) return
        text.compare(before, after, now)
    }

    /** The cursor moved `offset` UTF-16 units (left when negative): the known text moves with it. */
    fun moveCursor(offset: Int, now: Double) {
        text.move(offset, now)
        engine.reset()
        shown = ""
        literal = false
        stray = 0
        lastSpaceAt = -10.0
    }

    /** The cursor moved or the whole field was replaced: read the field again before the next key. */
    fun forget() {
        text.forget()
        engine.reset()
        shown = ""
        literal = false
        stray = 0
    }

    fun letter(c: Char, now: Double): List<Edit> {
        lastSpaceAt = -10.0
        turnedAsTyped = false
        follow()
        // A new word: nothing the engine holds was taken from the field.
        if (engine.isEmpty) seeded = null
        if (literal) return typeAsIs(c, now)
        val wasVietnamese = vietnameseShown
        val wasShown = shown
        engine.feed(c)
        // Longer than any word the engine holds (32 keys): the rest goes on as typed.
        if (engine.isOverflowed) {
            literal = true
            return typeAsIs(c, now)
        }
        if (stray > 0) {
            stray += 1
            return show(Live(wasShown + c, false), now)
        }
        var live = liveWord()
        if (wasVietnamese && !live.vietnamese && live.word == engine.rawKeystrokes && isStrayLetterTypo(live.word)) {
            stray = 1
            live = Live(wasShown + c, false)
        }
        val edits = show(live, now)
        turnedAsTyped = wasVietnamese && !vietnameseShown && shown == engine.rawKeystrokes
        return edits
    }

    /** Digits, punctuation, or an accented letter, typed as is. Anything but a letter ends the word. */
    fun insert(s: String, now: Double): List<Edit> {
        lastSpaceAt = -10.0
        if (s.firstOrNull()?.isLetter() != true) return endWord(now) + record(listOf(Edit.Insert(s)), now)
        // An accented letter joins the word: the engine takes the word up again at the next key.
        val edits = record(listOf(Edit.Insert(s)), now)
        engine.reset()
        literal = false
        stray = 0
        shown = ""
        return edits
    }

    /** Two spaces after a word make ". ", as on the system keyboard. */
    fun space(now: Double): List<Edit> {
        val ended = endWord(now)
        val before = text.before ?: ""
        if (now - lastSpaceAt < 1.2 && spaceAfterWord && before.endsWith(" ")) {
            lastSpaceAt = -10.0
            // The space a ⌫ would have taken back to re-open the word is gone.
            engine.forgetLastCommit()
            return ended + record(listOf(Edit.Delete(1), Edit.Insert(". ")), now)
        }
        spaceAfterWord = before.lastOrNull()?.isLetterOrDigit() ?: false
        lastSpaceAt = now
        return ended + record(listOf(Edit.Insert(" ")), now)
    }

    fun newline(now: Double): List<Edit> {
        lastSpaceAt = -10.0
        return endWord(now) + record(listOf(Edit.Insert("\n")), now)
    }

    /** The word ends without a key that types: the app's Send, Go or Search key (Android). */
    fun finishWord(now: Double): List<Edit> {
        lastSpaceAt = -10.0
        return endWord(now)
    }

    /**
     * Inside the word being composed, the last letter shown goes ("khô" → "kh") and the rest keeps
     * composing. Elsewhere one character goes, and the word now before the cursor is taken up
     * again: ⌫ over the space after a word re-opens it ("tháy ⌫ a" → "thấy").
     */
    fun deleteBackward(now: Double): List<Edit> {
        lastSpaceAt = -10.0
        val turned = turnedAsTyped
        turnedAsTyped = false
        if (stray > 0 && trailingWord(text.before ?: "") == shown) {
            // The stray letter goes; with the last one gone the word is Vietnamese again and goes on.
            val edits = record(listOf(Edit.Delete(1)), now)
            stray -= 1
            val keys = engine.rawKeystrokes.dropLast(1)
            engine.reset()
            for (key in keys) engine.feed(key)
            val word = trailingWord(text.before ?: "")
            val live = liveWord()
            if (stray == 0 && live.word == word) {
                shown = word
                vietnameseShown = live.vietnamese
            } else if (stray == 0) {
                take(word)
            } else {
                shown = word
            }
            return edits
        }
        if (!literal && !engine.isEmpty && trailingWord(text.before ?: "") == shown) {
            val keys = engine.rawKeystrokes
            if (turned && !isKnownEnglish(keys)) {
                // The letter just typed turned the word into its keys ("không" + k showed
                // "khoongk"): it is taken back, and the word shows as it did before it.
                engine.reset()
                for (key in keys.dropLast(1)) engine.feed(key)
                return show(liveWord(), now)
            }
            if (shown == engine.composed && seeded == null) {
                val rest = shown.dropLast(1)
                engine.backspace()
                // Only the last letter goes: re-reading the shorter word, the engine may compose it
                // otherwise ("are" ⌫ showed "ả", "after" ⌫⌫ "at").
                val live = liveWord()
                if (live.word == rest) return show(live, now)
            }
            // The word shows as typed, or the engine would change more than its last letter: that
            // letter goes, and the rest stays as it is.
            val edits = record(listOf(Edit.Delete(1)), now)
            take(trailingWord(text.before ?: ""))
            return edits
        }
        val canReopen = engine.canReopenLastCommit && !(text.before?.lastOrNull()?.isLetter() ?: true)
        val edits = record(listOf(Edit.Delete(1)), now)
        val word = trailingWord(text.before ?: "")
        literal = false
        val reopened = if (canReopen) engine.reopenLastCommit() else null
        if (reopened != null && reopened == word) {
            shown = word
            seeded = null
            vietnameseShown = couldBeVietnamese(word)
        } else {
            take(word)
        }
        return edits
    }

    /** Deletes the word before the cursor and the spaces after it. */
    fun deleteWord(now: Double): List<Edit> {
        val before = graphemes(text.before ?: "")
        var i = before.size
        while (i > 0 && before[i - 1].isBlank()) i--
        while (i > 0 && !before[i - 1].isBlank()) i--
        lastSpaceAt = -10.0
        engine.reset()
        literal = false
        stray = 0
        val edits = record(listOf(Edit.Delete(maxOf(1, before.size - i))), now)
        shown = ""
        return edits
    }

    /** The word at the cursor, when it is not the one the engine left there. */
    private fun follow() {
        val current = trailingWord(text.before ?: "")
        if (current == shown) return
        take(current)
    }

    /**
     * Makes `word`, already in the field, the one being composed: the engine takes it up when it
     * would type it exactly the same way (`seed`), so "toan" + s gives "toán"; otherwise letters go
     * on as typed.
     */
    private fun take(word: String) {
        engine.reset()
        stray = 0
        shown = word
        literal = word.isNotEmpty() && !engine.seed(word)
        seeded = if (literal || word.isEmpty()) null else word to engine.rawKeystrokes
        vietnameseShown = !literal && word.isNotEmpty() && couldBeVietnamese(word)
    }

    /** The word is done: an English word the engine had changed goes back to the keys typed. */
    private fun endWord(now: Double): List<Edit> {
        try {
            if (literal || engine.isEmpty || trailingWord(text.before ?: "") != shown) {
                engine.reset()
                return emptyList()
            }
            // What the engine commits, from what is on screen.
            val final = ending()
            engine.commitBoundary(autoRestore = true)
            return show(final, now)
        } finally {
            shown = ""
            literal = false
            stray = 0
        }
    }

    /** What the word would be if it ended now; a word taken up from the field keeps its letters. */
    private fun ending(): String {
        val final = engine.peekCommitText(autoRestore = true)
        val seeded = seeded ?: return final
        if (final != engine.rawKeystrokes || !final.startsWith(seeded.second)) return final
        return seeded.first + final.substring(seeded.second.length)
    }

    private data class Live(val word: String, val vietnamese: Boolean)

    /**
     * The word as it shows while typed: what the engine composed, unless that can no longer be
     * Vietnamese ("gôd", "ưe"); then what the word would be if it ended now ("good", "we").
     */
    private fun liveWord(): Live {
        val composed = engine.composed
        if (couldBeVietnamese(composed)) return Live(composed, true)
        return Live(ending(), false)
    }

    /** Puts `word` on screen in place of the word shown: only the part that changed. */
    private fun show(word: String, now: Double): List<Edit> {
        val edits = record(diff(shown, word), now)
        shown = word
        return edits
    }

    private fun show(live: Live, now: Double): List<Edit> {
        val edits = show(live.word, now)
        vietnameseShown = live.vietnamese
        return edits
    }

    private fun typeAsIs(c: Char, now: Double): List<Edit> {
        val edits = record(listOf(Edit.Insert(c.toString())), now)
        shown = trailingWord(text.before ?: "")
        return edits
    }

    private fun record(edits: List<Edit>, now: Double): List<Edit> {
        for (edit in edits) {
            when (edit) {
                is Edit.Delete -> repeat(edit.count) { text.deleteBackward(now) }
                is Edit.Insert -> text.insert(edit.text, now)
            }
        }
        return edits
    }

    companion object {
        /** Telex as MSTrans types it. */
        fun makeEngine(): TelexEngine = TelexEngine().apply {
            // Marks and tones typed late still find their vowel: "hofang" → hoàng, "dduocwj" → được.
            freeMarking = true
            // hoà, khoẻ, thuỷ, as MSTrans has always written them.
            modernTone = true
            // A word that can no longer be Vietnamese stops changing as it is typed.
            liveSpellCheck = true
            // Standard spelling only: chat spellings would turn English words into Vietnamese ones.
            teencode = false
            // A word both English and Vietnamese ("last"/lát) stays Vietnamese.
            collisionPrefersVietnamese = true
        }

        /** An English word the engine knows: ⌫ right after its last letter takes that letter as typed. */
        private fun isKnownEnglish(keys: String): Boolean {
            val word = keys.lowercase()
            return word in EnglishContextWords.words || word in EnglishCollisions.words ||
                word in EnglishContextWords.neutralLoanwords || word in EnglishContextWords.restoreOnly
        }

        /**
         * Keys that spell no English word: a Vietnamese word with a stray letter, which stays as it
         * was with the letter after it ("đưngd", one ⌫ from "đưng") rather than turning into its keys.
         */
        fun isStrayLetterTypo(keys: String): Boolean {
            val lower = keys.lowercase()
            if (lower.none { it in "aeiouy" } || lower in englishStarts) return false
            if (lower.startsWith("dd") || lower.contains("uw") || lower.contains("aa")) return true
            var previous = ' '
            for (c in lower) {
                if (previous == 'j' && c !in "aeiouy") return true
                previous = c
            }
            return false
        }

        /** Every beginning of an English word the engine knows, so "majo" is still "major" on its way. */
        private val englishStarts: Set<String> by lazy {
            val starts = HashSet<String>()
            for (list in listOf(
                EnglishContextWords.words, EnglishCollisions.words, EnglishContextWords.neutralLoanwords,
                EnglishContextWords.restoreOnly,
            )) {
                for (word in list) for (i in 1..word.length) starts.add(word.substring(0, i))
            }
            starts
        }

        /** Every beginning of a standard onset; true when it is a whole onset. */
        private val onsetStarts: Map<String, Boolean> by lazy {
            val starts = HashMap<String, Boolean>()
            for (onset in SyllableValidator.onsets - SyllableValidator.teencodeOnsets) {
                for (i in 1..onset.length) starts.putIfAbsent(onset.substring(0, i), false)
                starts[onset] = true
            }
            // A plain d may still become đ.
            starts["d"] = true
            starts
        }

        /** Every beginning of a standard rime, also with any of its marks still missing. */
        private val rimeStarts: Set<String> by lazy {
            val plain = mapOf('ă' to 'a', 'â' to 'a', 'ê' to 'e', 'ô' to 'o', 'ơ' to 'o', 'ư' to 'u')
            val starts = HashSet<String>()
            for (rime in SyllableValidator.rimes - SyllableValidator.teencodeRimes) {
                var variants = listOf("")
                for (c in rime) {
                    variants = variants.flatMap { v ->
                        val p = plain[c]
                        if (p != null) listOf(v + c, v + p) else listOf(v + c)
                    }
                    starts.addAll(variants)
                }
            }
            starts
        }

        private val longestOnset: Int by lazy { onsetStarts.keys.maxOf { it.length } }
        private val onsetTrie: ClassTrie by lazy { ClassTrie(onsetStarts.map { it.key to if (it.value) 2 else 1 }) }
        private val rimeTrie: ClassTrie by lazy { ClassTrie(rimeStarts.map { it to 1 }) }

        /** Letter class of each letter the engine writes, lowercased and without its tone: "Ấ" is â. */
        private val letterClasses: Map<Int, Int> by lazy {
            val classes = HashMap<Int, Int>()
            for (letter in "abcdefghijklmnopqrstuvwxyzđ") {
                val cls = Tables.charClass.getValue(letter)
                classes[letter.code] = cls
                classes[letter.uppercaseChar().code] = cls
            }
            for ((toned, entry) in Tables.detoneTable) {
                val lower = entry.first.toChar().lowercaseChar()
                val cls = Tables.charClass[lower] ?: continue
                classes[toned] = cls
            }
            classes
        }

        /**
         * Whether more keys could still make `word` a Vietnamese syllable: a known onset, then the
         * start of a known rime with the marks it has. A plain a, e, o, u or d may still get its
         * mark; a marked letter must be right where it is: "ưe" fits no rime.
         */
        fun couldBeVietnamese(word: String): Boolean {
            if (word.length <= 32) {
                val buffer = IntArray(32)
                var n = 0
                var fast = true
                for (ch in word) {
                    val cls = letterClasses[ch.code]
                    if (cls == null) {
                        fast = false
                        break
                    }
                    buffer[n++] = cls
                }
                if (fast) {
                    var node = 0
                    for (k in 0..minOf(n, longestOnset)) {
                        if (k > 0) {
                            node = onsetTrie.step(node, buffer[k - 1])
                            if (node < 0) return false
                        }
                        val mask = onsetTrie.mask(node)
                        if (mask == 0) continue
                        if (k == n) return true
                        if (mask != 2) continue
                        var rime = 0
                        for (i in k until n) {
                            rime = rimeTrie.step(rime, buffer[i])
                            if (rime < 0) break
                        }
                        if (rime >= 0) return true
                    }
                    return false
                }
            }
            val letters = StringBuilder()
            for (c in word.lowercase()) {
                val entry = Tables.detoneTable[c.code]
                letters.append(if (entry != null) entry.first.toChar() else c)
            }
            val s = letters.toString()
            var end = 0
            while (true) {
                val whole = onsetStarts[s.substring(0, end)]
                if (whole != null) {
                    val rest = s.substring(end)
                    // Still typing the onset ("q" of "qu"), or a rime begun after a whole one.
                    if (rest.isEmpty() || (whole && rest in rimeStarts)) return true
                }
                if (end >= longestOnset || end >= s.length) return false
                end++
            }
        }

        /** Only the part of the word that changed, so the field never flickers. */
        fun diff(old: String, new: String): List<Edit> {
            var i = 0
            while (i < old.length && i < new.length && old[i] == new[i]) i++
            val edits = ArrayList<Edit>(2)
            if (i < old.length) edits.add(Edit.Delete(graphemeCount(old.substring(i))))
            if (i < new.length) edits.add(Edit.Insert(new.substring(i)))
            return edits
        }

        /**
         * The edits that take a key back, given the text before the cursor as it was before the key:
         * what it inserted goes, what it deleted comes back. Null when what it deleted is not known.
         */
        fun inverse(edits: List<Edit>, before: String?): List<Edit>? {
            var deleted = 0
            var inserted = 0
            for (edit in edits) {
                when (edit) {
                    is Edit.Delete -> deleted += edit.count
                    is Edit.Insert -> inserted += graphemeCount(edit.text)
                }
            }
            // A key's edits delete first, then insert.
            val undo = ArrayList<Edit>()
            if (inserted > 0) undo.add(Edit.Delete(inserted))
            if (deleted > 0) {
                val parts = graphemes(before ?: return null)
                if (parts.size < deleted) return null
                undo.add(Edit.Insert(parts.takeLast(deleted).joinToString("")))
            }
            return undo
        }

        fun trailingWord(text: String): String {
            var start = text.length
            while (start > 0 && text[start - 1].isLetter()) start--
            return text.substring(start)
        }

        /**
         * The edits as UTF-16 operations on a text whose part before the cursor is `before`:
         * each delete becomes the number of UTF-16 units its characters take there.
         */
        fun toUnits(edits: List<Edit>, before: String): List<Edit> {
            val sim = StringBuilder(before)
            val out = ArrayList<Edit>()
            for (edit in edits) {
                when (edit) {
                    is Edit.Delete -> {
                        var units = 0
                        repeat(edit.count) {
                            val cut = lastGraphemeStart(sim)
                            units += sim.length - cut
                            sim.setLength(cut)
                        }
                        out.add(Edit.Delete(units))
                    }
                    is Edit.Insert -> {
                        sim.append(edit.text)
                        out.add(edit)
                    }
                }
            }
            return out
        }

        /** Where the last character (grapheme cluster) of `s` starts; 0 for empty text. */
        fun lastGraphemeStart(s: CharSequence): Int {
            if (s.isEmpty()) return 0
            val last = s[s.length - 1]
            // Plain BMP letters, digits and punctuation: the usual case, without a BreakIterator.
            if (!Character.isSurrogate(last) && last.code < 0x300 && (s.length < 2 || s[s.length - 2].code < 0x300)) {
                return s.length - 1
            }
            val it = BreakIterator.getCharacterInstance()
            it.setText(s.toString())
            val start = it.preceding(s.length)
            return if (start == BreakIterator.DONE) 0 else start
        }

        fun graphemes(s: String): List<String> {
            if (s.isEmpty()) return emptyList()
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            val out = ArrayList<String>()
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                out.add(s.substring(start, end))
                start = end
                end = it.next()
            }
            return out
        }

        fun graphemeCount(s: String): Int {
            if (s.all { it.code < 0x300 }) return s.length
            return graphemes(s).size
        }

        internal fun nfc(s: String): String =
            if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)
    }
}

/** What this keyboard knows is before the cursor, and what it was a moment ago. */
class TypedText {
    private class Snapshot(val text: String, val at: Double, val n: Int)

    /** Null when unknown: the field's text is read again. */
    var before: String? = null
        private set
    /** The text after the cursor when this was last read; typing never changes it. */
    private var after = ""
    private val recent = ArrayList<Snapshot>()
    private var count = 0
    /** The oldest snapshot the field can still be showing. */
    private var shown = 0

    companion object {
        private const val KEEP = 300
        private const val HISTORY = 64
        /** How long an app may take to show an edit. */
        const val LAG = 1.5
        /** An empty field reported once typing paused: the app emptied it (the message was sent). */
        const val EMPTIED_LAG = 0.5
        /** How long after the last key a report counts as made once typing paused. */
        const val PAUSED = 0.2

        private fun keepTail(s: String): String {
            if (s.length <= KEEP) return s
            var start = s.length - KEEP
            if (Character.isLowSurrogate(s[start])) start++
            return s.substring(start)
        }

        /** The field often hands over only part of the text (the current paragraph or sentence). */
        private fun matches(mine: String, field: String): Boolean {
            if (field.isEmpty()) return mine.isEmpty() || mine.last() == '\n'
            if (mine.endsWith(field) || (mine.length >= 48 && field.endsWith(mine))) return true
            // As characters, for an app that hands back the same text composed differently.
            val m = Composer.nfc(mine)
            val f = Composer.nfc(field)
            if (m.isEmpty() || m.last() != f.last()) return false
            return m.endsWith(f) || (m.length >= 16 && f.endsWith(m))
        }
    }

    enum class Report {
        /** The field shows what this keyboard typed, now or a moment ago. */
        CAUGHT_UP,
        /** Emptied right after a newline: the app took ⏎ as Send. */
        SENT,
        /** Changed elsewhere. */
        CHANGED,
    }

    fun reset(before: String, after: String, now: Double) {
        val kept = keepTail(before)
        this.before = kept
        this.after = after
        count += 1
        shown = count
        recent.clear()
        recent.add(Snapshot(kept, now, count))
    }

    fun forget() {
        before = null
        recent.clear()
    }

    /** The cursor moved over `offset` UTF-16 units (left when negative). Unknown beyond what is known. */
    fun move(offset: Int, now: Double) {
        var b = before ?: return
        if (offset == 0) return
        var a = after
        if (offset < 0) {
            val cut = b.length + offset
            if (cut < 0 || (cut > 0 && Character.isLowSurrogate(b[cut]))) return forget()
            a = b.substring(cut) + a
            b = b.substring(0, cut)
        } else {
            if (offset > a.length || (offset < a.length && Character.isLowSurrogate(a[offset]))) return forget()
            b += a.substring(0, offset)
            a = a.substring(offset)
        }
        reset(b, a, now)
    }

    fun insert(s: String, now: Double) {
        var b = before ?: return
        b += s
        // Trimmed now and then, not on every key.
        if (b.length > KEEP + 100) b = keepTail(b)
        record(b, now)
    }

    fun deleteBackward(now: Double) {
        // Deleting past what is known: read the field again.
        val b = before
        if (b.isNullOrEmpty()) return forget()
        record(b.substring(0, Composer.lastGraphemeStart(b)), now)
    }

    private fun record(b: String, now: Double) {
        before = b
        count += 1
        recent.add(Snapshot(b, now, count))
        if (recent.size > HISTORY) recent.subList(0, recent.size - HISTORY).clear()
    }

    fun compare(field: String, fieldAfter: String, now: Double): Report {
        if (before == null || fieldAfter != after) return Report.CHANGED
        val emptied = field.isEmpty() && fieldAfter.isEmpty()
        val lastAt = recent.lastOrNull()?.at ?: now
        val window = if (emptied && now - lastAt >= PAUSED) EMPTIED_LAG else LAG
        var match: Snapshot? = null
        var sentAt: Snapshot? = null
        var replacedAt = now
        var current = true
        for (snapshot in recent.asReversed()) {
            if (snapshot.n < shown || !(current || now - replacedAt < window)) break
            if (matches(snapshot.text, field)) {
                match = snapshot
                if (emptied && sentAt == null && snapshot.text.lastOrNull() == '\n') sentAt = snapshot
            }
            replacedAt = snapshot.at
            current = false
        }
        if (sentAt != null) {
            shown = sentAt.n
            // What was typed since the newline is all the field holds.
            val newer = recent.filter { it.n >= sentAt.n }
            if (!newer.all { it.text.startsWith(sentAt.text) }) {
                // The newline was deleted since: read the field again.
                forget()
                return Report.SENT
            }
            val cut = sentAt.text.length
            val trimmed = newer.map { Snapshot(it.text.substring(cut), it.at, it.n) }
            recent.clear()
            recent.addAll(trimmed)
            before = recent.lastOrNull()?.text ?: ""
            return Report.SENT
        }
        if (match == null) return Report.CHANGED
        shown = match.n
        return Report.CAUGHT_UP
    }
}
