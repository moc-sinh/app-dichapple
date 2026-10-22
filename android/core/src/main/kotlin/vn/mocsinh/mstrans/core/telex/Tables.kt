// Tables.kt
// Port of VietTelex's TelexCore Tables.swift (MIT, © 2026 Phil Trinh; see
// Keyboard/TelexCore/LICENSE-VietTelex.txt). Static lookup tables for Telex composition.
package vn.mocsinh.mstrans.core.telex

/** Diacritic mark carried by a base letter. */
enum class Mark { NONE, CIRCUMFLEX, BREVE, HORN, BAR }

/** Vietnamese tone. Ordinals match the ordering used in the toned-form tables. */
enum class TelexTone { NONE, ACUTE, GRAVE, HOOK, TILDE, DOT }

object Tables {
    // Each group: [base(toneless), acute, grave, hook, tilde, dot]. Lower + upper.
    private val tonedGroups = listOf(
        "aáàảãạ", "ăắằẳẵặ", "âấầẩẫậ", "eéèẻẽẹ", "êếềểễệ", "iíìỉĩị",
        "oóòỏõọ", "ôốồổỗộ", "ơớờởỡợ", "uúùủũụ", "ưứừửữự", "yýỳỷỹỵ",
        "AÁÀẢÃẠ", "ĂẮẰẲẴẶ", "ÂẤẦẨẪẬ", "EÉÈẺẼẸ", "ÊẾỀỂỄỆ", "IÍÌỈĨỊ",
        "OÓÒỎÕỌ", "ÔỐỒỔỖỘ", "ƠỚỜỞỠỢ", "UÚÙỦŨỤ", "ƯỨỪỬỮỰ", "YÝỲỶỸỴ",
    )

    /** Toneless vowel code point -> 6 toned code points indexed by TelexTone.ordinal. */
    val tonedTable: Map<Int, IntArray> = HashMap<Int, IntArray>().apply {
        for (group in tonedGroups) {
            val points = group.codePoints().toArray()
            put(points[0], points)
        }
    }

    /** Toned code point -> (toneless code point, tone). Includes the toneless form itself. */
    val detoneTable: Map<Int, Pair<Int, TelexTone>> = HashMap<Int, Pair<Int, TelexTone>>().apply {
        for (group in tonedGroups) {
            val points = group.codePoints().toArray()
            val base = points[0]
            for ((i, p) in points.withIndex()) put(p, base to TelexTone.entries[i])
        }
    }

    /** Compose a base ascii letter (lowercase) + mark into a toneless code point. */
    fun markedScalar(base: Int, mark: Mark, upper: Boolean): Int {
        if (mark == Mark.NONE) return if (upper) base - 32 else base
        val ch: Char = when {
            base == 'a'.code && mark == Mark.CIRCUMFLEX -> if (upper) 'Â' else 'â'
            base == 'a'.code && mark == Mark.BREVE -> if (upper) 'Ă' else 'ă'
            base == 'e'.code && mark == Mark.CIRCUMFLEX -> if (upper) 'Ê' else 'ê'
            base == 'o'.code && mark == Mark.CIRCUMFLEX -> if (upper) 'Ô' else 'ô'
            base == 'o'.code && mark == Mark.HORN -> if (upper) 'Ơ' else 'ơ'
            base == 'u'.code && mark == Mark.HORN -> if (upper) 'Ư' else 'ư'
            base == 'd'.code && mark == Mark.BAR -> if (upper) 'Đ' else 'đ'
            else -> return if (upper) base - 32 else base
        }
        return ch.code
    }

    /** Apply a tone to a toneless (possibly marked) vowel. Unchanged if not a vowel or no tone. */
    fun applyTone(scalar: Int, tone: TelexTone): Int {
        if (tone == TelexTone.NONE) return scalar
        val forms = tonedTable[scalar] ?: return scalar
        return forms[tone.ordinal]
    }

    /** 0-25 = bare a-z; 26-32 = â ă ê ô ơ ư đ. */
    const val CLASS_COUNT = 33

    /** Class of an engine letter (lowercase ascii base + mark). */
    fun letterClass(base: Int, mark: Mark): Int {
        if (mark == Mark.NONE) return base - 'a'.code
        return when {
            base == 'a'.code && mark == Mark.CIRCUMFLEX -> 26
            base == 'a'.code && mark == Mark.BREVE -> 27
            base == 'e'.code && mark == Mark.CIRCUMFLEX -> 28
            base == 'o'.code && mark == Mark.CIRCUMFLEX -> 29
            base == 'o'.code && mark == Mark.HORN -> 30
            base == 'u'.code && mark == Mark.HORN -> 31
            base == 'd'.code && mark == Mark.BAR -> 32
            else -> base - 'a'.code
        }
    }

    /** Character -> class, for building tries from the readable rule tables. */
    val charClass: Map<Char, Int> = HashMap<Char, Int>().apply {
        for (c in 'a'..'z') put(c, c.code - 'a'.code)
        for ((i, ch) in "âăêôơưđ".withIndex()) put(ch, 26 + i)
    }

    private val vowelClassMask: Long = run {
        var m = 0L
        for (ch in "aeiouyâăêôơư") m = m or (1L shl charClass.getValue(ch))
        m
    }

    fun isVowelClass(c: Int): Boolean = c in 0 until 64 && (vowelClassMask shr c) and 1L == 1L
}

private const val ASCII_VOWEL_MASK = (1 shl 0) or (1 shl 4) or (1 shl 8) or (1 shl 14) or (1 shl 20) or (1 shl 24)

fun isVowelAscii(c: Int): Boolean {
    val i = c - 'a'.code
    return i in 0 until 26 && (ASCII_VOWEL_MASK shr i) and 1 == 1
}

fun toneForKey(c: Int): TelexTone? = when (c) {
    's'.code -> TelexTone.ACUTE
    'f'.code -> TelexTone.GRAVE
    'r'.code -> TelexTone.HOOK
    'x'.code -> TelexTone.TILDE
    'j'.code -> TelexTone.DOT
    else -> null
}

fun isAsciiLetter(c: Int): Boolean = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
fun isAsciiDigit(c: Int): Boolean = c in '0'.code..'9'.code
fun isUpperAscii(c: Int): Boolean = c in 'A'.code..'Z'.code
fun lowercasedAscii(c: Int): Int = if (isUpperAscii(c)) c + 32 else c

/**
 * Flat trie over the 33-letter class alphabet. Node i's child for class c lives at
 * next[i * 33 + c]; -1 = absent. Each node carries a mask: 0 = not a full word; for rime
 * tries the bits are the allowed tones.
 */
class ClassTrie(words: Iterable<Pair<String, Int>>) {
    private val next: IntArray
    private val masks: IntArray

    init {
        val stride = Tables.CLASS_COUNT
        val nextList = ArrayList<Int>()
        repeat(stride) { nextList.add(-1) }
        val maskList = arrayListOf(0)
        outer@ for ((w, accept) in words) {
            val path = ArrayList<Int>()
            for (ch in w) {
                val c = Tables.charClass[ch] ?: continue@outer
                path.add(c)
            }
            var node = 0
            for (c in path) {
                val slot = node * stride + c
                if (nextList[slot] < 0) {
                    nextList[slot] = maskList.size
                    repeat(stride) { nextList.add(-1) }
                    maskList.add(0)
                }
                node = nextList[slot]
            }
            maskList[node] = maskList[node] or maxOf(accept, 1)
        }
        next = nextList.toIntArray()
        masks = maskList.toIntArray()
    }

    fun step(node: Int, cls: Int): Int {
        if (cls < 0 || cls >= Tables.CLASS_COUNT) return -1
        return next[node * Tables.CLASS_COUNT + cls]
    }

    fun mask(node: Int): Int = masks[node]
}
