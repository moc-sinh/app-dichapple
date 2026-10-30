// SyllableValidator.kt
// Port of VietTelex's TelexCore SyllableValidator.swift (MIT). Rule-based Vietnamese syllable
// validator: a syllable is valid iff onset ∈ ONSETS and rime ∈ RIMES and the tone/coda rule holds.
package vn.mocsinh.mstrans.core.telex

object SyllableValidator {
    val onsets: Set<String> = setOf(
        "", "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh", "l",
        "m", "n", "ng", "ngh", "nh", "p", "ph", "qu", "r", "s", "t", "th",
        "tr", "v", "x", "z", "dz",
        "kr",
    )

    val rimes: Set<String> = """
        a ac ach ai am an ang anh ao ap at au ay ak
        ă ăc ăm ăn ăng ăp ăt ăk
        â âc âm ân âng âp ât âu ây
        e ec em en eng eo ep et
        ê êch êm ên êng ênh êp êt êu
        i ich im in inh ip it iu ia
        ik
        iê iêc iêm iên iêng iêp iêt iêu
        ie
        o oc oi om on ong op ot
        oa oac oach oai oam oan oang oanh oao oap oat oay
        oă oăc oăm oăn oăng oăt
        oe oem oen oeo oet
        oo oong ooc
        ô ôc ôi ôm ôn ông ôp ôt
        ơ ơi ơm ơn ơp ơt
        u uc ui um un ung up ut ua
        uâ uân uâng uât uây
        uê uêch uên uênh
        uô uôc uôi uôm uôn uông uôt uơ
        uy uya uych uyn uynh uyt uyu uyên uyêt
        ư ưa ưc ưi ưm ưn ưng ưt ưu
        ưk
        ươ ươi ươm ươn ương ươp ươt ươu ươc
        y yê yêm yên yêng yêt yêu
    """.split(' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun toneMask(r: String): Int {
        if (r == "ưk") {
            return (1 shl TelexTone.GRAVE.ordinal) or (1 shl TelexTone.ACUTE.ordinal) or (1 shl TelexTone.DOT.ordinal)
        }
        val stop = r.endsWith("p") || r.endsWith("t") || r.endsWith("c") || r.endsWith("ch") || r.endsWith("k")
        return if (stop) (1 shl TelexTone.ACUTE.ordinal) or (1 shl TelexTone.DOT.ordinal) else 0b0011_1111
    }

    val teencodeOnsets: Set<String> = setOf("z", "dz")
    val teencodeRimes: Set<String> = setOf("ie", "ik", "ưk")

    val onsetExact = ClassTrie(onsets.map { it to 1 })
    val rimeExact = ClassTrie(rimes.map { it to toneMask(it) })
    val onsetExactStd = ClassTrie((onsets - teencodeOnsets).map { it to 1 })
    val rimeExactStd = ClassTrie((rimes - teencodeRimes).map { it to toneMask(it) })

    val onsetFolded = ClassTrie(onsets.map { fold(it) to 1 })
    val rimeFolded = ClassTrie(rimes.map { fold(it) to 1 })
    val onsetFoldedStd = ClassTrie((onsets - teencodeOnsets).map { fold(it) to 1 })
    val rimeFoldedStd = ClassTrie((rimes - teencodeRimes).map { fold(it) to 1 })

    private fun fold(s: String) = String(s.map { foldBase(it) }.toCharArray())

    /** Standard spelling: onset k only before i / e / ê / y. */
    fun kOnsetAllows(rimeFirst: Int): Boolean =
        rimeFirst == 'i'.code - 'a'.code || rimeFirst == 'e'.code - 'a'.code || rimeFirst == 28 || rimeFirst == 'y'.code - 'a'.code

    fun foldBase(c: Char): Char = when (c) {
        'ă', 'â' -> 'a'
        'ê' -> 'e'
        'ô', 'ơ' -> 'o'
        'ư' -> 'u'
        'đ' -> 'd'
        else -> c
    }

    private const val A = 'a'.code

    /** `classes[0 until n]` are letter classes of the composed word, `tone` its single tone. */
    fun isValidSyllable(classes: IntArray, n: Int, tone: TelexTone, teencode: Boolean = true): Boolean {
        if (n == 0) return false
        if (teencode && n == 2 && classes[0] == 'o'.code - A && classes[1] == 'y'.code - A) return true
        if (teencode && n >= 3 && tone == TelexTone.GRAVE &&
            classes[n - 2] == 'o'.code - A && classes[n - 1] == 'y'.code - A
        ) {
            val gC = 'g'.code - A
            val rC = 'r'.code - A
            val zC = 'z'.code - A
            val dC = 'd'.code - A
            val cC = 'c'.code - A
            val hC = 'h'.code - A
            if (n == 3 && (classes[0] == gC || classes[0] == rC || classes[0] == zC || classes[0] == hC)) return true
            if (n == 4 && ((classes[0] == dC && classes[1] == zC) || (classes[0] == cC && classes[1] == hC))) return true
        }
        if (teencode && n == 3 && tone == TelexTone.NONE && classes[0] == 32 &&
            classes[1] == 'o'.code - A && classes[2] == 'u'.code - A
        ) return true
        val onsetT = if (teencode) onsetExact else onsetExactStd
        val rimeT = if (teencode) rimeExact else rimeExactStd
        val kC = 'k'.code - A
        val q = 'q'.code - A
        val u = 'u'.code - A
        val g = 'g'.code - A
        val i = 'i'.code - A

        var pos = 0
        while (pos < n && !Tables.isVowelClass(classes[pos])) pos++
        var onsetEnd = pos
        var quGlide = false
        if (pos >= 1 && classes[0] == q && pos < n && classes[pos] == u &&
            pos + 1 < n && Tables.isVowelClass(classes[pos + 1])
        ) {
            onsetEnd = pos + 1
            quGlide = true
        } else if (n >= 3 && classes[0] == g && classes[1] == i && Tables.isVowelClass(classes[2])) {
            onsetEnd = 2
        }

        fun accepts(onsetEnd: Int, rimeStart: Int): Boolean {
            var node = 0
            for (k in 0 until onsetEnd) {
                node = onsetT.step(node, classes[k])
                if (node < 0) return false
            }
            if (onsetT.mask(node) == 0) return false
            if (!teencode && onsetEnd == 1 && classes[0] == kC && rimeStart < n &&
                !kOnsetAllows(classes[rimeStart])
            ) return false
            var rnode = 0
            for (k in rimeStart until n) {
                rnode = rimeT.step(rnode, classes[k])
                if (rnode < 0) return false
            }
            return (rimeT.mask(rnode) shr tone.ordinal) and 1 == 1
        }

        if (accepts(onsetEnd, onsetEnd)) return true
        if (quGlide && accepts(onsetEnd, pos)) return true
        return onsetEnd != pos && accepts(pos, pos)
    }

    /** True if `word` is a well-formed Vietnamese syllable. */
    fun isValidSyllable(word: String, teencode: Boolean = true): Boolean {
        if (word.isEmpty()) return false
        val classes = IntArray(word.length)
        var n = 0
        var tone = TelexTone.NONE
        for (ch in word.lowercase()) {
            var toneless = ch
            val entry = Tables.detoneTable[ch.code]
            if (entry != null) {
                toneless = entry.first.toChar()
                if (entry.second != TelexTone.NONE) {
                    if (tone != TelexTone.NONE) return false
                    tone = entry.second
                }
            }
            val cls = Tables.charClass[toneless] ?: return false
            classes[n++] = cls
        }
        return isValidSyllable(classes, n, tone, teencode)
    }

    /**
     * `bases[i]` is the lowercase ascii base letter of display letter i, with bit 7 set when it
     * carries a mark. Permissive: never rejects a valid word mid-typing.
     */
    fun isValidPrefix(bases: IntArray, n: Int, teencode: Boolean = true): Boolean {
        if (n == 0) return true
        val onsetF = if (teencode) onsetFolded else onsetFoldedStd
        val rimeF = if (teencode) rimeFolded else rimeFoldedStd
        fun cls(i: Int) = (bases[i] and 0x7F) - A
        for (i in 0 until n) if (!isAsciiLetter(bases[i] and 0x7F)) return false

        var pos = 0
        while (pos < n && !isVowelAscii(bases[pos] and 0x7F)) pos++
        if (pos == n) {
            var node = 0
            for (i in 0 until n) {
                node = onsetF.step(node, cls(i))
                if (node < 0) return false
            }
            return true
        }
        if (teencode && n == 2 && bases[0] == 'o'.code && bases[1] == 'y'.code) return true
        if (teencode && n >= 3 && bases[n - 2] == 'o'.code && bases[n - 1] == 'y'.code) {
            val b0 = bases[0] and 0x7F
            val b1 = bases[1] and 0x7F
            if (n == 3 && (b0 == 'g'.code || b0 == 'r'.code || b0 == 'z'.code || b0 == 'd'.code || b0 == 'h'.code)) return true
            if (n == 4 && ((b0 == 'd'.code && b1 == 'z'.code) || (b0 == 'c'.code && b1 == 'h'.code))) return true
        }
        if (teencode && n == 3 && (bases[0] and 0x7F) == 'd'.code && bases[1] == 'o'.code && bases[2] == 'u'.code) return true
        val quAlt = if ((bases[0] and 0x7F) == 'q'.code && bases[pos] == 'u'.code) pos + 1 else -1
        val giAlt = if ((bases[0] and 0x7F) == 'g'.code && n >= 2 && bases[1] == 'i'.code) 2 else -1

        for ((onsetEnd, rimeStart) in listOf(pos to pos, quAlt to quAlt, giAlt to giAlt, quAlt to pos)) {
            if (onsetEnd < 0 || onsetEnd > n) continue
            var node = 0
            var ok = true
            for (i in 0 until onsetEnd) {
                node = onsetF.step(node, cls(i))
                if (node < 0) { ok = false; break }
            }
            if (!ok || onsetF.mask(node) == 0) continue
            var rnode = 0
            ok = true
            for (i in rimeStart until n) {
                rnode = rimeF.step(rnode, cls(i))
                if (rnode < 0) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    fun isValidPrefix(word: String, teencode: Boolean = true): Boolean {
        if (word.isEmpty()) return true
        val bases = IntArray(word.length)
        var n = 0
        for (ch in word.lowercase()) {
            var toneless = ch
            Tables.detoneTable[ch.code]?.let { toneless = it.first.toChar() }
            if (Tables.charClass[toneless] == null) return false
            val folded = foldBase(toneless)
            if (folded.code >= 128) return false
            bases[n++] = folded.code or (if (folded == toneless) 0 else 0x80)
        }
        return isValidPrefix(bases, n, teencode)
    }

    fun isSpellingError(word: String, teencode: Boolean = true): Boolean {
        if (word.isEmpty()) return false
        val classes = IntArray(word.length)
        var n = 0
        var tone = TelexTone.NONE
        for (ch in word.lowercase()) {
            var toneless = ch
            val entry = Tables.detoneTable[ch.code]
            if (entry != null) {
                toneless = entry.first.toChar()
                if (entry.second != TelexTone.NONE) {
                    if (tone != TelexTone.NONE) return true
                    tone = entry.second
                }
            }
            val cls = Tables.charClass[toneless] ?: return false
            classes[n++] = cls
        }
        if (isValidSyllable(classes, n, tone, teencode)) return false
        if (!isValidPrefix(word, teencode)) return true
        if (!teencode && n >= 2 && classes[0] == 'k'.code - A && Tables.isVowelClass(classes[1]) &&
            !kOnsetAllows(classes[1])
        ) return true
        if (tone == TelexTone.GRAVE || tone == TelexTone.HOOK || tone == TelexTone.TILDE) {
            return isValidSyllable(classes, n, TelexTone.ACUTE, teencode)
        }
        return false
    }
}
