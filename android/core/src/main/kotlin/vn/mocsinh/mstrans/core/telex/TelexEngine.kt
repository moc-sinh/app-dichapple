// TelexEngine.kt
// Port of VietTelex's TelexCore TelexEngine.swift (MIT, © 2026 Phil Trinh; see
// Keyboard/TelexCore/LICENSE-VietTelex.txt). The Swift file holds the full commentary on every
// rule; this port keeps the same structure, names and order so the two can be read side by side.
//
// Strategy: keep the raw keystroke buffer (letters only) plus an incremental parse state: each
// new key is folded into the persistent (letters + tone) state by one `parseStep`. Rendering
// copies the letters, applies the post-passes (ươ propagation, tone placement) on the copy, and
// diffs against the previous render to yield the minimal (backspaces, insert) edit.
package vn.mocsinh.mstrans.core.telex

sealed class TelexAction {
    /** Not handled by the engine; let the system insert the character. */
    data object Passthrough : TelexAction()
    /** Replace `backspaces` trailing characters already on screen with `insert`. */
    data class Replace(val backspaces: Int, val insert: String) : TelexAction()
    /** Nothing to do. */
    data object None : TelexAction()
}

class TelexEngine {
    /** "Bỏ dấu tự do": modifier keys reach back over consonants to the target vowel. */
    var freeMarking = false
    /** Modern tone placement for open oa/oe/uy (hoà, khoẻ, thuý). */
    var modernTone = false
    /** Stop transforming once the word can no longer be Vietnamese. */
    var liveSpellCheck = false
    /** A standalone w never becomes ư. */
    var simpleTelex = false
    /** Informal chat spellings (w→qu, z→d, ie, ik…). */
    var teencode = true
    /** Doubled onset consonant expands to its digraph (cc→ch…). */
    var quickTelex = false
    /** `[` types ơ, `]` types ư. */
    var bracketVowels = false
    /** VNI input instead of Telex. */
    var vniMode = false
    /** Context-based English decision (experimental). */
    var contextualEnglish = false
    var previousWordEnglish = false
        private set
    private var englishCandidate = false
    private var previousGateBits = 0
    var englishWordRestore = true
    var collisionPrefersVietnamese = false

    companion object {
        const val CAPACITY = 32
        private val telexMarkExpansion = mapOf(
            'â' to "aa", 'ă' to "aw", 'ê' to "ee", 'ô' to "oo", 'ơ' to "ow", 'ư' to "uw", 'đ' to "dd",
        )
        private val vniMarkExpansion = mapOf(
            'â' to "a6", 'ê' to "e6", 'ô' to "o6", 'ơ' to "o7", 'ư' to "u7", 'ă' to "a8", 'đ' to "d9",
        )
        private val englishExceptions = listOf("was", "wow", "yes").map { w -> IntArray(w.length) { w[it].code } }
        private val onsetsAllowingStandaloneU = ClassTrie(
            listOf(
                "", "b", "c", "ch", "d", "g", "h", "kh", "l", "m", "n", "ng", "nh",
                "ph", "r", "s", "t", "th", "tr", "v", "x", "gi",
            ).map { it to 1 },
        )

        private fun telexToneKey(t: TelexTone): Char? = when (t) {
            TelexTone.ACUTE -> 's'; TelexTone.GRAVE -> 'f'; TelexTone.HOOK -> 'r'
            TelexTone.TILDE -> 'x'; TelexTone.DOT -> 'j'; TelexTone.NONE -> null
        }

        private fun vniToneKey(t: TelexTone): Char? = when (t) {
            TelexTone.ACUTE -> '1'; TelexTone.GRAVE -> '2'; TelexTone.HOOK -> '3'
            TelexTone.TILDE -> '4'; TelexTone.DOT -> '5'; TelexTone.NONE -> null
        }

        private fun upperFirst(s: String): String = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

        /** Keystrokes that would compose `word`, or null if some character can't be typed. */
        private fun seedKeystrokes(word: String, vni: Boolean): String? {
            val out = StringBuilder()
            var toneKey: Char? = null
            for (ch in word) {
                val isUpper = ch.isUpperCase()
                val lower = ch.lowercaseChar()
                var toneless = lower
                val entry = Tables.detoneTable[lower.code]
                if (entry != null) {
                    if (entry.second != TelexTone.NONE) {
                        if (toneKey != null) return null
                        toneKey = if (vni) vniToneKey(entry.second) else telexToneKey(entry.second)
                    }
                    toneless = entry.first.toChar()
                }
                val expanded: String = (if (vni) vniMarkExpansion else telexMarkExpansion)[toneless]
                    ?: run {
                        if (toneless.code >= 128 || !toneless.isLetter()) return null
                        toneless.toString()
                    }
                out.append(if (isUpper) (if (vni) upperFirst(expanded) else expanded.uppercase()) else expanded)
            }
            toneKey?.let { out.append(it) }
            return out.toString()
        }

        fun bracketVowel(key: Int): Pair<Int, Boolean>? = when (key) {
            '['.code -> 'o'.code to false
            ']'.code -> 'u'.code to false
            '{'.code -> 'o'.code to true
            '}'.code -> 'u'.code to true
            else -> null
        }

        private fun vniTone(d: Int): TelexTone? = when (d) {
            '1'.code -> TelexTone.ACUTE
            '2'.code -> TelexTone.GRAVE
            '3'.code -> TelexTone.HOOK
            '4'.code -> TelexTone.TILDE
            '5'.code -> TelexTone.DOT
            else -> null
        }

        private fun vniMark(d: Int): Mark? = when (d) {
            '6'.code -> Mark.CIRCUMFLEX
            '7'.code -> Mark.HORN
            '8'.code -> Mark.BREVE
            '9'.code -> Mark.BAR
            else -> null
        }

        private fun vniMarkAccepts(base: Int, mark: Mark): Boolean = when (mark) {
            Mark.CIRCUMFLEX -> base == 'a'.code || base == 'e'.code || base == 'o'.code
            Mark.HORN -> base == 'o'.code || base == 'u'.code
            Mark.BREVE -> base == 'a'.code
            Mark.BAR -> base == 'd'.code
            Mark.NONE -> false
        }

        private fun quickTelexSecond(c: Int): Int? = when (c) {
            'c'.code, 'k'.code, 'p'.code, 't'.code -> 'h'.code
            'g'.code -> 'i'.code
            'n'.code -> 'g'.code
            'q'.code -> 'u'.code
            else -> null
        }
    }

    // Raw keystrokes of the current word (ascii, case preserved).
    private val raw = IntArray(CAPACITY)
    private var rawCount = 0
    private var overflowed = false

    // Current on-screen composition (code points).
    private val out = IntArray(CAPACITY)
    private var outCount = 0

    private val scratch = IntArray(CAPACITY)
    // Render-time copy of the letters.
    private val rb = IntArray(CAPACITY)
    private val rm = Array(CAPACITY) { Mark.NONE }
    private val ru = BooleanArray(CAPACITY)
    private val basesScratch = IntArray(CAPACITY)
    private val rawLetter = IntArray(CAPACITY) { -1 }
    private val toneKeys = IntArray(CAPACITY)
    private val vowelIdx = IntArray(CAPACITY)

    // Incremental parse state: letters (base, mark, upper).
    private val lb = IntArray(CAPACITY)
    private val lm = Array(CAPACITY) { Mark.NONE }
    private val lu = BooleanArray(CAPACITY)
    private var pCount = 0
    private var pTone = TelexTone.NONE
    private var pToneKeyCount = 0
    private var pCancelled = false
    private var pToneCancelSpan = 0
    private var pToneCancelAt = -1
    private var pProcessed = 0
    private var pFreeMarking = false
    private var pSimpleTelex = false
    private var pQuickTelex = false
    private var pVniMode = false
    private var pBracketVowels = false
    private var pLiveSpellCheck = false

    private var disabledAtCount = Int.MAX_VALUE
    private var markCancelled = false
    private var toneCancelAt = -1
    private var toneCancelSpan = 0
    private var pFoldTones = false
    private var tonesFolded = false
    private var lastEffTone = TelexTone.NONE
    private var upperToneKey = false

    // Re-open snapshot.
    private val reopenRaw = IntArray(CAPACITY)
    private val reopenOut = IntArray(CAPACITY)
    private var reopenRawCount = 0
    private var reopenOutCount = 0
    private var reopenPrevEnglish = false
    private var reopenCandidate = false
    private var reopenGateBits = 0

    val isEmpty: Boolean get() = rawCount == 0

    // MARK: Public entry points

    /** Re-edit a committed word: true only when the word round-trips exactly. */
    fun seed(word: String): Boolean {
        reset()
        if (word.isEmpty() || word.codePointCount(0, word.length) > CAPACITY / 2) return false
        val keys = seedKeystrokes(word, vniMode) ?: return false
        if (keys.length > CAPACITY) { reset(); return false }
        for (ch in keys) feed(ch)
        if (composed != word) { reset(); return false }
        return true
    }

    /** Feed one typed character. Only ascii letters compose. */
    fun feed(ch: Char): TelexAction {
        val ascii = ch.code
        if (ascii >= 128 || !(isAsciiLetter(ascii) || (vniMode && isAsciiDigit(ascii)) ||
                (bracketVowels && bracketVowel(ascii) != null))
        ) return TelexAction.Passthrough
        if (rawCount >= CAPACITY) { overflowed = true; return TelexAction.Passthrough }

        if (rawCount == 0) { reopenRawCount = 0; reopenOutCount = 0 }

        raw[rawCount] = ascii
        rawCount += 1

        if (pLiveSpellCheck != liveSpellCheck) {
            recomputeFreeze()
            rebuildFrozenAware()
        } else if (pProcessed != rawCount - 1 ||
            pFreeMarking != freeMarking || pSimpleTelex != simpleTelex ||
            pQuickTelex != quickTelex || pVniMode != vniMode ||
            pBracketVowels != bracketVowels
        ) {
            rebuildFrozenAware()
        } else {
            parseStep(rawCount - 1)
            pProcessed = rawCount
        }

        var newCount = render()
        snapshotCancel()

        if (disabledAtCount == Int.MAX_VALUE && forceRestoreUpperTone) {
            disabledAtCount = 0
            rebuildParseState()
            tonesFolded = false
            newCount = render()
            snapshotCancel()
        }

        if (liveSpellCheck && disabledAtCount == Int.MAX_VALUE && !prefixIsValid(newCount) &&
            elongationHeadCount(newCount, pTone) <= 0
        ) {
            disabledAtCount = rawCount
            if (pTone != TelexTone.NONE) {
                pFoldTones = true
                rebuildParseState()
                pFoldTones = false
                tonesFolded = true
                newCount = render()
                snapshotCancel()
            }
        }

        if (liveSpellCheck && disabledAtCount != Int.MAX_VALUE && !forceRestoreUpperTone &&
            rawCount >= 2 && raw[rawCount - 1] == raw[rawCount - 2]
        ) {
            recomputeFreeze()
            rebuildFrozenAware()
            newCount = render()
            snapshotCancel()
        }

        if (newCount == outCount + 1 && scratch[newCount - 1] == ascii &&
            commonPrefixLength(outCount) == outCount
        ) {
            copyOut(newCount)
            return TelexAction.Passthrough
        }

        val action = diff(newCount)
        copyOut(newCount)
        return action
    }

    private fun snapshotCancel() {
        markCancelled = pCancelled
        toneCancelAt = pToneCancelAt
        toneCancelSpan = pToneCancelSpan
    }

    /** Backspace: delete the whole last displayed character, then reconcile the screen. */
    fun backspace(): TelexAction {
        if (rawCount <= 0) return TelexAction.Passthrough
        if (overflowed) return TelexAction.Passthrough

        rebuildFrozenAware()
        render()

        if (pCount == 0) {
            rawCount -= 1
        } else {
            val last = pCount - 1
            var w = 0
            for (r in 0 until rawCount) {
                if (rawLetter[r] != last) { raw[w] = raw[r]; w += 1 }
            }
            rawCount = w
        }

        recomputeFreeze()
        rebuildFrozenAware()
        val newCount = render()
        snapshotCancel()
        val action = diff(newCount)
        copyOut(newCount)
        return action
    }

    private fun needsStandaloneWRunRestore(): Boolean {
        if (rawCount < 3 || !compositionDiffersFromRaw()) return false
        for (i in 0 until rawCount) if (raw[i] or 0x20 != 'w'.code) return false
        return true
    }

    /** Word boundary reached: optionally auto-restore the raw keys. Resets the engine. */
    fun commitBoundary(autoRestore: Boolean): TelexAction {
        try {
            if (rawCount <= 0) { reopenRawCount = 0; reopenOutCount = 0; return TelexAction.None }
            if (overflowed) {
                if (contextualEnglish) clearEnglishContext()
                reopenRawCount = 0; reopenOutCount = 0
                return TelexAction.None
            }
            val wantsRestore = outCount > 0 && (needsStandaloneWRunRestore() || (autoRestore && shouldRestoreRaw()))
            var action: TelexAction = TelexAction.None
            if (wantsRestore && compositionDiffersFromRaw()) {
                val limit = minOf(rawCount, outCount)
                var lcp = 0
                while (lcp < limit && raw[lcp] == out[lcp]) lcp++
                val backspaces = outCount - lcp
                val s = StringBuilder()
                for (i in lcp until rawCount) s.appendCodePoint(raw[i])
                action = TelexAction.Replace(backspaces, s.toString())
            }
            captureReopen(wantsRestore)
            updateContext(wantsRestore)
            return action
        } finally {
            resetWord()
        }
    }

    private fun captureReopen(restored: Boolean) {
        if (restored || rawCount <= 0) { reopenRawCount = 0; reopenOutCount = 0; return }
        for (i in 0 until rawCount) reopenRaw[i] = raw[i]
        for (i in 0 until outCount) reopenOut[i] = out[i]
        reopenRawCount = rawCount
        reopenOutCount = outCount
        reopenPrevEnglish = previousWordEnglish
        reopenCandidate = englishCandidate
        reopenGateBits = previousGateBits
    }

    fun forgetLastCommit() {
        reopenRawCount = 0
        reopenOutCount = 0
    }

    val canReopenLastCommit: Boolean get() = rawCount == 0 && reopenRawCount > 0

    /** Re-open the word the last boundary committed; the composed word, or null. */
    fun reopenLastCommit(): String? {
        if (!canReopenLastCommit) return null
        val n = reopenRawCount
        val expected = reopenOutCount
        val prevEnglish = reopenPrevEnglish
        val prevCandidate = reopenCandidate
        val prevGateBits = reopenGateBits
        val keys = reopenRaw.copyOf(n)
        val outs = reopenOut.copyOf(expected)
        reset()
        previousWordEnglish = prevEnglish
        englishCandidate = prevCandidate
        previousGateBits = prevGateBits
        for (i in 0 until n) feed(keys[i].toChar())
        if (rawCount != n || outCount != expected) { reset(); return null }
        for (i in 0 until outCount) if (out[i] != outs[i]) { reset(); return null }
        return composed
    }

    /** Boundary restore decision; order is the policy (see the Swift original). */
    private fun shouldRestoreRaw(): Boolean {
        if (markCancelled) {
            if (composedIsValidSyllable()) return false
            if (toneCancelSpan > 1) {
                return !composedIsRecognizedEnglish() || rawIsEnglishContextWord()
            }
            if (toneCancelAt >= 0 && toneCancelAt < rawCount - 1 && rawIsEnglishCollision()) return true
            if (isTeencodeKeep()) return false
            if (toneCancelAt < 0 && tonesFolded) return true
            return composedHasDiacritic()
        }
        if (rawIsEnglishCollision() && !(collisionPrefersVietnamese && composedIsValidSyllable())) return true
        if (forceRestoreUpperTone || rawIsEnglishException() ||
            (!composedIsValidSyllable() && !isTeencodeKeep())
        ) return true
        if (contextualEnglish && previousWordEnglish &&
            (rawIsEnglishContextWord(includingRestoreOnly = true) ||
                (collisionPrefersVietnamese && rawIsEnglishCollision())) &&
            predecessorGateAllows()
        ) return true
        return false
    }

    private fun predecessorGateAllows(): Boolean {
        val w = rawLowerWord() ?: return true
        val i = EnglishContextWords.gateIndex(w) ?: return true
        return previousGateBits and (1 shl i) != 0
    }

    private fun rawLowerWord(): String? {
        if (rawCount <= 0 || rawCount > EnglishContextWords.MAX_LENGTH) return null
        val v = StringBuilder()
        for (i in 0 until rawCount) {
            var b = raw[i]
            if (b in 'A'.code..'Z'.code) b = b or 0x20
            if (b !in 'a'.code..'z'.code) return null
            v.append(b.toChar())
        }
        return v.toString()
    }

    private fun composedHasDiacritic(): Boolean {
        if (pTone != TelexTone.NONE) return true
        for (k in 0 until pCount) if (lm[k] != Mark.NONE) return true
        return false
    }

    private fun composedIsRecognizedEnglish(): Boolean {
        if (outCount < 2 || outCount > 12) return false
        val v = StringBuilder()
        for (i in 0 until outCount) {
            var b = out[i]
            if (b in 0x41..0x5A) b = b or 0x20
            if (b !in 0x61..0x7A) return false
            v.append(b.toChar())
        }
        val w = v.toString()
        return w in EnglishCollisions.words || w in EnglishContextWords.words
    }

    private enum class WordContext { ENGLISH_FUNCTION, ENGLISH, VIETNAMESE, NEUTRAL }

    private fun isRecognizedEnglish(): Boolean =
        rawIsEnglishContextWord() || rawIsEnglishCollision() || rawIsEnglishException()

    private fun classifyWordContext(restored: Boolean): WordContext {
        if (!restored && compositionHasDiacritic()) return WordContext.VIETNAMESE
        if (isRecognizedEnglish()) {
            return if (rawIsFunctionWord()) WordContext.ENGLISH_FUNCTION else WordContext.ENGLISH
        }
        val word = composedAsciiWord()
        if (word != null && (word in EnglishContextWords.words || word in EnglishCollisions.words ||
                word in EnglishContextWords.neutralLoanwords)
        ) {
            if (word in EnglishContextWords.neutralLoanwords) return WordContext.NEUTRAL
            return if (word in EnglishContextWords.functionWords) WordContext.ENGLISH_FUNCTION else WordContext.ENGLISH
        }
        if (rawIsNeutralLoanword() || rawIsEnglishContextWord(includingRestoreOnly = true) || rawIsContractionTail()) {
            return WordContext.NEUTRAL
        }
        if (!restored && SyllableValidator.isValidSyllable(composed.lowercase(), teencode)) return WordContext.VIETNAMESE
        if (rawCount <= 2) return WordContext.NEUTRAL
        return WordContext.ENGLISH
    }

    private fun rawIsNeutralLoanword(): Boolean {
        val w = rawLowerWord() ?: return false
        return w in EnglishContextWords.neutralLoanwords
    }

    private fun rawIsFunctionWord(): Boolean {
        val w = rawLowerWord() ?: return false
        return w in EnglishContextWords.functionWords
    }

    private fun rawIsContractionTail(): Boolean {
        if (rawCount > 2) return false
        val w = rawLowerWord() ?: return false
        return w in EnglishContextWords.contractionTails
    }

    private fun updateContext(restored: Boolean) {
        if (!contextualEnglish || rawCount <= 0) return
        when (classifyWordContext(restored)) {
            WordContext.ENGLISH_FUNCTION -> { previousWordEnglish = true; englishCandidate = false }
            WordContext.ENGLISH -> foldEnglishContentWord()
            WordContext.VIETNAMESE -> { clearEnglishContext(); return }
            WordContext.NEUTRAL -> {}
        }
        previousGateBits = rawLowerWord()?.let { EnglishContextWords.predecessorBits(it) } ?: 0
    }

    private fun foldEnglishContentWord() {
        if (previousWordEnglish) return
        if (englishCandidate) { previousWordEnglish = true; englishCandidate = false } else englishCandidate = true
    }

    private fun rawIsEnglishContextWord(includingRestoreOnly: Boolean = false): Boolean {
        val w = rawLowerWord() ?: return false
        if (w in EnglishContextWords.words) return true
        return includingRestoreOnly && w in EnglishContextWords.restoreOnly
    }

    fun resetContext() = clearEnglishContext()

    private fun clearEnglishContext() {
        previousWordEnglish = false
        englishCandidate = false
        previousGateBits = 0
    }

    fun noteExternalWord(word: String?, english: Boolean) {
        if (!contextualEnglish) return
        if (!english) { clearEnglishContext(); return }
        val w = word?.lowercase()
        if (w != null && w in EnglishContextWords.functionWords) {
            previousWordEnglish = true; englishCandidate = false
        } else {
            foldEnglishContentWord()
        }
        previousGateBits = w?.let { EnglishContextWords.predecessorBits(it) } ?: 0
    }

    /** Final text to commit at a word boundary, with auto-restore applied. Resets the engine. */
    fun commitText(autoRestore: Boolean): String {
        try {
            if (overflowed) {
                if (contextualEnglish) clearEnglishContext()
                reopenRawCount = 0; reopenOutCount = 0
                return composed
            }
            val wantsRestore = outCount > 0 && (needsStandaloneWRunRestore() || (autoRestore && shouldRestoreRaw()))
            captureReopen(wantsRestore)
            updateContext(wantsRestore)
            return if (wantsRestore) rawKeystrokes else composed
        } finally {
            resetWord()
        }
    }

    /** What the boundary will commit, without changing anything. */
    fun peekCommitText(autoRestore: Boolean): String {
        if (overflowed) return composed
        if (outCount > 0 && needsStandaloneWRunRestore()) return rawKeystrokes
        if (autoRestore && outCount > 0 && shouldRestoreRaw()) return rawKeystrokes
        return composed
    }

    private fun rawIsEnglishCollision(): Boolean {
        if (!englishWordRestore) return false
        if (rawCount < 2 || rawCount > 12) return false
        if (!compositionDiffersFromRaw()) return false
        if ((raw[0] or 0x20) == 'w'.code) {
            val wTransformed = pCount > 0 && rb[0] == 'u'.code && rm[0] == Mark.HORN
            if (!wTransformed) return false
        }
        val v = StringBuilder()
        for (i in 0 until rawCount) {
            var b = raw[i]
            if (b in 'A'.code..'Z'.code) b = b or 0x20
            if (b !in 'a'.code..'z'.code) return false
            v.append(b.toChar())
        }
        return v.toString() in EnglishCollisions.words
    }

    private fun rawIsEnglishException(): Boolean {
        if (pCount <= 0) return false
        val wTransformed = rb[0] == 'u'.code && rm[0] == Mark.HORN
        outer@ for (word in englishExceptions) {
            if (word.size != rawCount) continue
            if (word[0] == 'w'.code && !wTransformed) continue
            for (i in 0 until rawCount) if (lowercasedAscii(raw[i]) != word[i]) continue@outer
            return true
        }
        return false
    }

    private fun teencodeOnset(): Pair<IntArray, Int>? {
        if (!teencode || pCount < 2 || rm[0] != Mark.NONE) return null
        return when (rb[0]) {
            'w'.code -> intArrayOf('q'.code, 'u'.code) to 1
            'z'.code -> intArrayOf('d'.code) to 1
            'd'.code -> if (pCount >= 3 && rb[1] == 'z'.code && rm[1] == Mark.NONE) intArrayOf('d'.code) to 2 else null
            else -> null
        }
    }

    private fun isAbbreviationPrefix(upper: Boolean): Boolean {
        if (pCount <= 0) return false
        for (k in 0 until pCount) {
            if (isVowelAscii(lb[k]) || lu[k] != upper) return false
        }
        return true
    }

    private fun abbreviationDoublerException(lower: Int, upper: Boolean): Boolean =
        !pVniMode && lower == 'd'.code && isAbbreviationPrefix(upper)

    private fun composedIsValidSyllable(): Boolean {
        if (pCount >= 1 && lastEffTone == TelexTone.NONE && rb[0] == 'd'.code && rm[0] == Mark.BAR) {
            var bareConsonantsOnly = true
            for (k in 1 until pCount) {
                if (rm[k] != Mark.NONE || isVowelAscii(rb[k])) { bareConsonantsOnly = false; break }
            }
            if (bareConsonantsOnly) return true
        }
        if (lastEffTone == TelexTone.NONE && rawCount >= 2) {
            var sameCase = true
            val firstIsUpper = isUpperAscii(raw[0])
            for (i in 0 until rawCount) if (isUpperAscii(raw[i]) != firstIsUpper) { sameCase = false; break }
            if (sameCase) {
                var hasBar = false
                var onlyBar = true
                for (k in 0 until pCount) {
                    if (isVowelAscii(rb[k])) { onlyBar = false; break }
                    if (rb[k] == 'd'.code && rm[k] == Mark.BAR) hasBar = true
                    else if (rm[k] != Mark.NONE) { onlyBar = false; break }
                }
                if (hasBar && onlyBar) return true
            }
        }
        val buf = IntArray(CAPACITY + 1)
        val onset = teencodeOnset()
        val stacksTeencodeRime = pCount >= 3 &&
            rb[pCount - 2] == 'i'.code && rm[pCount - 2] == Mark.NONE &&
            rb[pCount - 1] == 'e'.code && rm[pCount - 1] == Mark.NONE &&
            (onset?.let { it.second == pCount - 2 } ?: false)
        if (!stacksTeencodeRime && pCount < CAPACITY - 1 && onset != null) {
            val (canon, skip) = onset
            var n = 0
            for (c in canon) buf[n++] = Tables.letterClass(c, Mark.NONE)
            for (k in skip until pCount) buf[n++] = Tables.letterClass(rb[k], rm[k])
            if (SyllableValidator.isValidSyllable(buf, n, lastEffTone, teencode)) return true
        }
        for (k in 0 until pCount) buf[k] = Tables.letterClass(rb[k], rm[k])
        return SyllableValidator.isValidSyllable(buf, pCount, lastEffTone, teencode)
    }

    // MARK: Teencode elongation

    private fun elongationHeadCount(count: Int, tone: TelexTone): Int {
        val minRun = 3
        if (count < minRun + 1) return -1
        val lastB = rb[count - 1]
        val lastM = rm[count - 1]
        for (j in (count - minRun) until (count - 1)) {
            if (rb[j] != lastB || rm[j] != lastM) return -1
        }
        var runStart = count - minRun
        while (runStart > 0 && rb[runStart - 1] == lastB && rm[runStart - 1] == lastM) runStart--
        if (runStart < 1) runStart = 1
        var k = runStart
        while (k <= count - minRun) {
            if (isValidHead(k, tone)) return k
            k++
        }
        return -1
    }

    private fun isValidHead(k: Int, tone: TelexTone): Boolean {
        if (k < 1 || k > CAPACITY) return false
        var t = tone
        if ((t == TelexTone.GRAVE || t == TelexTone.HOOK || t == TelexTone.TILDE) && hasStopCoda(k) &&
            !(t == TelexTone.GRAVE && isUkRime(k))
        ) t = TelexTone.NONE
        val buf = IntArray(CAPACITY + 1)
        val onset = teencodeOnset()
        if (onset != null && onset.second < k) {
            val (canon, skip) = onset
            var n = 0
            for (c in canon) buf[n++] = Tables.letterClass(c, Mark.NONE)
            for (j in skip until k) buf[n++] = Tables.letterClass(rb[j], rm[j])
            if (SyllableValidator.isValidSyllable(buf, n, t, teencode)) return true
        }
        for (j in 0 until k) buf[j] = Tables.letterClass(rb[j], rm[j])
        return SyllableValidator.isValidSyllable(buf, k, t, teencode)
    }

    private fun isTeencodeKeep(): Boolean = elongationHeadCount(pCount, lastEffTone) > 0

    private fun compositionDiffersFromRaw(): Boolean {
        if (outCount != rawCount) return true
        for (i in 0 until outCount) if (out[i] != raw[i]) return true
        return false
    }

    private fun compositionHasDiacritic(): Boolean {
        for (i in 0 until outCount) if (out[i] > 127) return true
        return false
    }

    private fun composedAsciiWord(): String? {
        if (outCount <= 0 || outCount > EnglishContextWords.MAX_LENGTH) return null
        val v = StringBuilder()
        for (i in 0 until outCount) {
            var c = out[i]
            if (c > 127) return null
            if (c in 'A'.code..'Z'.code) c = c or 0x20
            if (c !in 'a'.code..'z'.code) return null
            v.append(c.toChar())
        }
        return v.toString()
    }

    private val forceRestoreUpperTone: Boolean get() = upperToneKey

    private fun letterCreatedByW(idx: Int): Boolean {
        for (i in 0 until rawCount) {
            if (rawLetter[i] == idx) return raw[i] == 'w'.code || raw[i] == 'W'.code
        }
        return false
    }

    private fun uaUuPredecessorAllowsRetarget(pred: Int): Boolean {
        val m = lm[pred]
        return m == Mark.NONE || (m == Mark.HORN && !letterCreatedByW(pred))
    }

    private fun hasLowercaseBefore(at: Int): Boolean {
        for (i in 0 until at) if (raw[i] in 'a'.code..'z'.code) return true
        return false
    }

    /** Drop the current word and the re-open snapshot. */
    fun reset() {
        if (rawCount > 0) clearEnglishContext()
        resetWord()
        reopenRawCount = 0
        reopenOutCount = 0
    }

    private fun resetWord() {
        rawCount = 0
        outCount = 0
        markCancelled = false
        toneCancelAt = -1
        toneCancelSpan = 0
        upperToneKey = false
        overflowed = false
        disabledAtCount = Int.MAX_VALUE
        tonesFolded = false
        pCount = 0
        pTone = TelexTone.NONE
        pToneKeyCount = 0
        pCancelled = false
        pToneCancelAt = -1
        pToneCancelSpan = 0
        pProcessed = 0
    }

    private fun prefixIsValid(n: Int): Boolean {
        var o = 0
        var start = 0
        if (teencode && n >= 1 && lm[0] == Mark.NONE && n < CAPACITY - 1) {
            when (lb[0]) {
                'w'.code -> { basesScratch[0] = 'q'.code; basesScratch[1] = 'u'.code; o = 2; start = 1 }
                'z'.code -> { basesScratch[0] = 'd'.code; o = 1; start = 1 }
                'd'.code -> if (n >= 2 && lb[1] == 'z'.code && lm[1] == Mark.NONE) {
                    basesScratch[0] = 'd'.code; o = 1; start = 2
                }
            }
        }
        for (k in start until n) {
            basesScratch[o] = lb[k] or (if (lm[k] != Mark.NONE) 0x80 else 0)
            o++
        }
        return SyllableValidator.isValidPrefix(basesScratch, o, teencode)
    }

    val isOverflowed: Boolean get() = overflowed

    fun hasSpellingError(autoRestore: Boolean): Boolean {
        if (rawCount == 0 || overflowed || outCount == 0) return false
        val shown = composed
        if (shown == rawKeystrokes) return false
        if (peekCommitText(autoRestore) != shown) return false
        return SyllableValidator.isSpellingError(shown, teencode)
    }

    /** Current composed word. */
    val composed: String
        get() {
            val s = StringBuilder(outCount)
            for (i in 0 until outCount) s.appendCodePoint(out[i])
            return s.toString()
        }

    /** The raw keystrokes typed for the current word. */
    val rawKeystrokes: String
        get() {
            val s = StringBuilder(rawCount)
            for (i in 0 until rawCount) s.append(raw[i].toChar())
            return s.toString()
        }

    // MARK: Rendering

    private fun copyOut(n: Int) {
        for (i in 0 until n) out[i] = scratch[i]
        outCount = n
    }

    private fun render(): Int {
        val count = pCount
        for (k in 0 until count) { rb[k] = lb[k]; rm[k] = lm[k]; ru[k] = lu[k] }

        for (k in 1 until maxOf(1, count)) {
            if (rb[k - 1] != 'u'.code || rb[k] != 'o'.code) continue
            val prevHorn = rm[k - 1] == Mark.HORN
            val curHorn = rm[k] == Mark.HORN
            if (prevHorn == curHorn) continue
            val oIsLast = k == count - 1
            val isQuGlide = k >= 2 && rb[k - 2] == 'q'.code
            if (!oIsLast && !isQuGlide) {
                rm[k - 1] = Mark.HORN
                rm[k] = Mark.HORN
            }
        }

        var effTone = pTone
        var toneScope = count
        if (pTone != TelexTone.NONE) {
            val head = elongationHeadCount(count, pTone)
            if (head > 0) toneScope = head
        }
        var toneIdx = if (pTone == TelexTone.NONE) -1 else toneVowelIndex(toneScope)
        if (toneIdx >= 0 && (effTone == TelexTone.GRAVE || effTone == TelexTone.HOOK || effTone == TelexTone.TILDE) &&
            hasStopCoda(toneScope) && !(effTone == TelexTone.GRAVE && isUkRime(toneScope))
        ) {
            effTone = TelexTone.NONE
            toneIdx = -1
        }
        val target = if (toneIdx >= 0) toneIdx else maxOf(0, count - 1)
        for (j in 0 until pToneKeyCount) rawLetter[toneKeys[j]] = target
        lastEffTone = effTone

        for (k in 0 until count) {
            var scalar = Tables.markedScalar(rb[k], rm[k], ru[k])
            if (k == toneIdx) scalar = Tables.applyTone(scalar, effTone)
            scratch[k] = scalar
        }
        return count
    }

    // MARK: Incremental parse

    private fun rebuildFrozenAware() {
        rebuildParseState()
        tonesFolded = false
        if (disabledAtCount != Int.MAX_VALUE && pTone != TelexTone.NONE) {
            pFoldTones = true
            rebuildParseState()
            pFoldTones = false
            tonesFolded = true
        }
    }

    private fun setCircumflex(k: Int) {
        lm[k] = Mark.CIRCUMFLEX
        if (lb[k] == 'o'.code && k >= 1 && lb[k - 1] == 'u'.code && lm[k - 1] == Mark.HORN) {
            lm[k - 1] = Mark.NONE
        }
    }

    private fun recomputeFreeze() {
        if (disabledAtCount == 0 && forceRestoreUpperTone && !liveSpellCheck) return
        val full = rawCount
        disabledAtCount = Int.MAX_VALUE
        if (!liveSpellCheck) return
        var r = 1
        while (r <= full) {
            rawCount = r
            rebuildParseState()
            if (disabledAtCount == Int.MAX_VALUE && pCount > 0 && !prefixIsValid(pCount)) {
                render()
                if (elongationHeadCount(pCount, pTone) <= 0) disabledAtCount = r
            }
            r++
        }
        rawCount = full
        if (disabledAtCount != Int.MAX_VALUE) {
            val frozenAt = disabledAtCount
            disabledAtCount = Int.MAX_VALUE
            rebuildParseState()
            render()
            if (elongationHeadCount(pCount, pTone) <= 0) disabledAtCount = frozenAt
        }
    }

    private fun rebuildParseState() {
        pCount = 0
        pTone = TelexTone.NONE
        pToneKeyCount = 0
        pCancelled = false
        pToneCancelAt = -1
        pToneCancelSpan = 0
        upperToneKey = false
        pFreeMarking = freeMarking
        pSimpleTelex = simpleTelex
        pQuickTelex = quickTelex
        pVniMode = vniMode
        pBracketVowels = bracketVowels
        pLiveSpellCheck = liveSpellCheck
        for (i in 0 until rawCount) rawLetter[i] = -1
        for (i in 0 until rawCount) parseStep(i)
        pProcessed = rawCount
    }

    private fun literal(at: Int, base: Int, upper: Boolean) {
        appendLetter(base, Mark.NONE, upper)
        rawLetter[at] = pCount - 1
    }

    private fun parseStep(at: Int) {
        val key = raw[at]
        val lower = lowercasedAscii(key)
        val upper = isUpperAscii(key)

        if (pCancelled || (at >= disabledAtCount && !abbreviationDoublerException(lower, upper))) {
            literal(at, lower, upper)
            return
        }

        if (pBracketVowels) {
            val bracket = bracketVowel(key)
            if (bracket != null) {
                appendLetter(bracket.first, Mark.HORN, bracket.second)
                rawLetter[at] = pCount - 1
                return
            }
        }

        if (pVniMode) {
            parseStepVNI(at, key, lower, upper)
            return
        }

        val t = toneForKey(lower)
        if (t != null) {
            if (pFoldTones) { literal(at, lower, upper); return }
            if (hasVowel(pCount)) {
                if (pTone == t) {
                    pTone = TelexTone.NONE
                    pCancelled = true; pToneCancelAt = at
                    pToneCancelSpan = if (pToneKeyCount > 0) at - toneKeys[pToneKeyCount - 1] else 1
                    literal(at, lower, upper)
                    for (j in 0 until pToneKeyCount) {
                        if (rawLetter[toneKeys[j]] == -1) rawLetter[toneKeys[j]] = pCount - 1
                    }
                    pToneKeyCount = 0
                } else if (stopCodaRejectsTone(t)) {
                    literal(at, lower, upper)
                } else {
                    pTone = t
                    if (upper && hasLowercaseBefore(at)) upperToneKey = true
                    rawLetter[at] = -1
                    toneKeys[pToneKeyCount] = at; pToneKeyCount++
                }
            } else {
                literal(at, lower, upper)
            }
            return
        }

        if (lower == 'z'.code) {
            if (pTone != TelexTone.NONE) {
                pToneCancelAt = at
                pToneCancelSpan = if (pToneKeyCount > 0) at - toneKeys[pToneKeyCount - 1] else 1
                pTone = TelexTone.NONE
                if (upper && hasLowercaseBefore(at)) upperToneKey = true
                rawLetter[at] = -1
                toneKeys[pToneKeyCount] = at; pToneKeyCount++
            } else {
                literal(at, lower, upper)
            }
            return
        }

        if (lower == 'w'.code) {
            var tIdx = -1
            var k = pCount - 1
            while (k >= 0) {
                val b = lb[k]
                if (b == 'a'.code || b == 'o'.code || b == 'u'.code) { tIdx = k; break }
                if (!freeMarking && !isVowelAscii(b)) break
                k--
            }
            if (tIdx >= 1 && lb[tIdx] == 'a'.code && lm[tIdx] == Mark.NONE &&
                lb[tIdx - 1] == 'u'.code && uaUuPredecessorAllowsRetarget(tIdx - 1) &&
                !(tIdx >= 2 && lb[tIdx - 2] == 'q'.code)
            ) tIdx--
            if (tIdx >= 1 && lb[tIdx] == 'u'.code && lm[tIdx] == Mark.NONE &&
                lb[tIdx - 1] == 'u'.code && uaUuPredecessorAllowsRetarget(tIdx - 1) &&
                !(tIdx >= 2 && lb[tIdx - 2] == 'q'.code)
            ) tIdx--
            if (tIdx >= 2 && lb[tIdx] == 'u'.code && lm[tIdx] == Mark.NONE &&
                lb[tIdx - 1] == 'o'.code && (lm[tIdx - 1] == Mark.NONE || lm[tIdx - 1] == Mark.HORN) &&
                lb[tIdx - 2] == 'u'.code
            ) tIdx--
            if (tIdx >= 0) {
                val pb = lb[tIdx]
                val pm = lm[tIdx]
                if (pm == Mark.NONE && pb == 'a'.code) {
                    lm[tIdx] = Mark.BREVE; rawLetter[at] = tIdx; return
                }
                if (pm == Mark.NONE && (pb == 'o'.code || pb == 'u'.code)) {
                    lm[tIdx] = Mark.HORN; rawLetter[at] = tIdx; return
                }
                if (pm == Mark.BREVE && pb == 'a'.code) {
                    lm[tIdx] = Mark.NONE
                    pCancelled = true
                    literal(at, 'w'.code, upper); return
                }
                if (pm == Mark.HORN && (pb == 'o'.code || pb == 'u'.code)) {
                    lm[tIdx] = Mark.NONE
                    pCancelled = true
                    if (pb == 'u'.code && letterCreatedByW(tIdx)) {
                        lb[tIdx] = 'w'.code
                        rawLetter[at] = tIdx
                        return
                    }
                    literal(at, 'w'.code, upper); return
                }
            }
            if (!simpleTelex && standaloneHornUAllowed(pCount)) {
                appendLetter('u'.code, Mark.HORN, upper)
            } else {
                appendLetter('w'.code, Mark.NONE, upper)
            }
            rawLetter[at] = pCount - 1
            return
        }

        if (lower == 'a'.code || lower == 'e'.code || lower == 'o'.code) {
            if (pCount > 0) {
                val pIdx = pCount - 1
                val pb = lb[pIdx]
                val pm = lm[pIdx]
                if (pb == lower && pm == Mark.NONE) { setCircumflex(pIdx); rawLetter[at] = pIdx; return }
                if (lower == 'o'.code && pb == lower && pm == Mark.HORN) { setCircumflex(pIdx); rawLetter[at] = pIdx; return }
                if (pb == lower && pm == Mark.CIRCUMFLEX) {
                    lm[pIdx] = Mark.NONE
                    pCancelled = true
                    literal(at, lower, upper); return
                }
            }
            if (freeMarking) {
                var k = pCount - 1
                while (k >= 0 && !isVowelAscii(lb[k])) k--
                val nucleusEnd = k
                while (k >= 0 && isVowelAscii(lb[k])) {
                    if (lb[k] == lower && (lm[k] == Mark.NONE || (lower == 'o'.code && lm[k] == Mark.HORN))) {
                        if (lower == 'o'.code && nucleusEnd == pCount - 1 && k == pCount - 2 &&
                            lm[k + 1] == Mark.NONE && (lb[k + 1] == 'e'.code || lb[k + 1] == 'a'.code)
                        ) break
                        setCircumflex(k); rawLetter[at] = k; return
                    }
                    if (lb[k] == lower && lm[k] == Mark.CIRCUMFLEX) {
                        lm[k] = Mark.NONE
                        pCancelled = true
                        literal(at, lower, upper); return
                    }
                    k--
                }
            }
            literal(at, lower, upper)
            return
        }

        if (lower == 'd'.code) {
            if (pCount > 0) {
                val pIdx = pCount - 1
                if (lb[pIdx] == 'd'.code && lm[pIdx] == Mark.NONE) { lm[pIdx] = Mark.BAR; rawLetter[at] = pIdx; return }
                if (lb[pIdx] == 'd'.code && lm[pIdx] == Mark.BAR) {
                    lm[pIdx] = Mark.NONE
                    pCancelled = true
                    literal(at, 'd'.code, upper); return
                }
            }
            if (freeMarking && pCount > 1 && lb[0] == 'd'.code && lm[0] == Mark.NONE) {
                lm[0] = Mark.BAR; rawLetter[at] = 0; return
            }
            if (freeMarking && pCount > 1 && lb[0] == 'd'.code && lm[0] == Mark.BAR && lb[pCount - 1] != 'd'.code) {
                lm[0] = Mark.NONE
                pCancelled = true
                literal(at, 'd'.code, upper); return
            }
            literal(at, 'd'.code, upper)
            return
        }

        if (quickTelex && pCount == 1 && lb[0] == lower && lm[0] == Mark.NONE) {
            val second = quickTelexSecond(lower)
            if (second != null) {
                literal(at, second, upper)
                return
            }
        }

        literal(at, lower, upper)
    }

    private fun parseStepVNI(at: Int, key: Int, lower: Int, upper: Boolean) {
        if (!isAsciiDigit(key)) { literal(at, lower, upper); return }
        if (pFoldTones && vniTone(key) != null) { literal(at, key, false); return }
        val t = vniTone(key)
        if (t != null) {
            if (hasVowel(pCount)) {
                if (pTone == t) {
                    pTone = TelexTone.NONE
                    pCancelled = true; pToneCancelAt = at
                    pToneCancelSpan = if (pToneKeyCount > 0) at - toneKeys[pToneKeyCount - 1] else 1
                    literal(at, key, false)
                    for (j in 0 until pToneKeyCount) {
                        if (rawLetter[toneKeys[j]] == -1) rawLetter[toneKeys[j]] = pCount - 1
                    }
                    pToneKeyCount = 0
                } else if (stopCodaRejectsTone(t)) {
                    literal(at, key, false)
                } else {
                    pTone = t
                    rawLetter[at] = -1
                    toneKeys[pToneKeyCount] = at; pToneKeyCount++
                }
            } else {
                literal(at, key, false)
            }
            return
        }
        if (key == '0'.code) {
            if (pTone != TelexTone.NONE) {
                pToneCancelAt = at
                pToneCancelSpan = if (pToneKeyCount > 0) at - toneKeys[pToneKeyCount - 1] else 1
                pTone = TelexTone.NONE
                rawLetter[at] = -1
                toneKeys[pToneKeyCount] = at; pToneKeyCount++
            } else {
                literal(at, key, false)
            }
            return
        }
        val mark = vniMark(key)
        if (mark != null) {
            var k = pCount - 1
            while (k >= 0) {
                if (vniMarkAccepts(lb[k], mark)) {
                    var target = k
                    if (mark == Mark.HORN && target >= 1 && lb[target] == 'u'.code && lm[target] == Mark.NONE &&
                        lb[target - 1] == 'u'.code && !(target >= 2 && lb[target - 2] == 'q'.code)
                    ) target--
                    if (lm[target] == Mark.NONE) {
                        lm[target] = mark
                        rawLetter[at] = target
                        return
                    }
                    if (lm[target] == mark) {
                        lm[target] = Mark.NONE
                        pCancelled = true
                        literal(at, key, false)
                        return
                    }
                    break
                }
                k--
            }
            literal(at, key, false)
            return
        }
        literal(at, key, false)
    }

    // MARK: Tone placement

    private fun toneVowelIndex(count: Int): Int {
        var vcount = 0
        var start = 0
        if (count >= 2 && rb[0] == 'q'.code && rb[1] == 'u'.code && rm[1] == Mark.NONE) {
            start = 2
        } else if (count >= 3 && rb[0] == 'g'.code && rb[1] == 'i'.code && rm[1] == Mark.NONE && isVowelAscii(rb[2])) {
            start = 2
        }
        for (k in start until count) if (isVowelAscii(rb[k])) { vowelIdx[vcount] = k; vcount++ }
        if (vcount == 0) {
            for (k in 0 until count) if (isVowelAscii(rb[k])) return k
            return count - 1
        }
        var lastMarked = -1
        for (j in 0 until vcount) if (rm[vowelIdx[j]] != Mark.NONE) lastMarked = vowelIdx[j]
        if (lastMarked >= 0) return lastMarked
        if (vcount == 1) return vowelIdx[0]
        val hasCoda = vowelIdx[vcount - 1] < count - 1
        if (vcount == 2) {
            if (hasCoda) return vowelIdx[1]
            if (modernTone) {
                val a = rb[vowelIdx[0]]
                val b = rb[vowelIdx[1]]
                val glideInitial = (a == 'o'.code && (b == 'a'.code || b == 'e'.code)) || (a == 'u'.code && b == 'y'.code)
                if (glideInitial) return vowelIdx[1]
            }
            return vowelIdx[0]
        }
        return vowelIdx[1]
    }

    // MARK: Diffing

    private fun commonPrefixLength(limit: Int): Int {
        var i = 0
        while (i < limit && scratch[i] == out[i]) i++
        return i
    }

    private fun diff(newCount: Int): TelexAction {
        val lcp = commonPrefixLength(minOf(newCount, outCount))
        val backspaces = outCount - lcp
        if (backspaces == 0 && lcp == newCount) return TelexAction.Replace(0, "")
        val s = StringBuilder()
        for (i in lcp until newCount) s.appendCodePoint(scratch[i])
        return TelexAction.Replace(backspaces, s.toString())
    }

    // MARK: Small helpers

    private fun standaloneHornUAllowed(count: Int): Boolean {
        var node = 0
        for (k in 0 until count) {
            node = onsetsAllowingStandaloneU.step(node, lb[k] - 'a'.code)
            if (node < 0) return false
        }
        return onsetsAllowingStandaloneU.mask(node) != 0
    }

    private fun hasVowel(count: Int): Boolean {
        for (k in 0 until count) if (isVowelAscii(lb[k])) return true
        return false
    }

    private fun isUkRime(count: Int): Boolean =
        count >= 2 && rb[count - 1] == 'k'.code && rb[count - 2] == 'u'.code && rm[count - 2] == Mark.HORN

    private fun hasStopCoda(count: Int): Boolean {
        if (count <= 0) return false
        val last = rb[count - 1]
        if (last == 'p'.code || last == 't'.code || last == 'c'.code || last == 'k'.code) return true
        return last == 'h'.code && count >= 2 && rb[count - 2] == 'c'.code
    }

    private fun lettersHaveStopCoda(count: Int): Boolean {
        if (count <= 0) return false
        val last = lb[count - 1]
        if (last == 'p'.code || last == 't'.code || last == 'c'.code || last == 'k'.code) return true
        return last == 'h'.code && count >= 2 && lb[count - 2] == 'c'.code
    }

    private fun lettersAreUkRime(count: Int): Boolean =
        count >= 2 && lb[count - 1] == 'k'.code && lb[count - 2] == 'u'.code && lm[count - 2] == Mark.HORN

    private fun stopCodaRejectsTone(tone: TelexTone): Boolean {
        if (tone != TelexTone.GRAVE && tone != TelexTone.HOOK && tone != TelexTone.TILDE) return false
        if (!lettersHaveStopCoda(pCount)) return false
        if (tone == TelexTone.GRAVE && lettersAreUkRime(pCount)) return false
        return true
    }

    private fun appendLetter(base: Int, mark: Mark, upper: Boolean) {
        if (pCount >= CAPACITY) return
        lb[pCount] = base
        lm[pCount] = mark
        lu[pCount] = upper
        pCount++
    }
}
