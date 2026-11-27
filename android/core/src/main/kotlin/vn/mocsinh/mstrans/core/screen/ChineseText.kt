package vn.mocsinh.mstrans.core.screen

/** Chinese characters on screen: finding them, Traditional to Simplified, lookup keys. */
object ChineseText {
    private const val TABLE = "/vn/mocsinh/mstrans/core/screen/ts_characters.txt"

    /** Traditional → Simplified, one character each (OpenCC's table, read once). */
    private val simplified: Map<Int, Int> by lazy { load() }

    fun isHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    fun hasHan(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isHan(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    fun toSimplified(text: String): String {
        if (!hasHan(text)) return text
        val out = StringBuilder(text.length)
        text.codePoints().forEach { out.appendCodePoint(simplified[it] ?: it) }
        return out.toString()
    }

    /**
     * Letters of Japanese the reader of pictures takes Chinese ones for, put back where Chinese is
     * next to them: 力 read カ ("吸カ强" came out "Suck"), 一 read ー ("三合ー折叠"), 口 read ロ.
     */
    fun unconfused(text: String): String {
        if (text.none { it in LOOK_ALIKES }) return text
        val out = StringBuilder(text)
        for (i in out.indices) {
            val han = LOOK_ALIKES[out[i]] ?: continue
            val before = out.getOrNull(i - 1)
            val after = out.getOrNull(i + 1)
            if (before != null && isHan(before.code) || after != null && isHan(after.code)) out.setCharAt(i, han)
        }
        return out.toString()
    }

    private val LOOK_ALIKES = mapOf(
        'カ' to '力', 'ロ' to '口', 'エ' to '工', 'ー' to '一', 'ニ' to '二', 'ハ' to '八', 'タ' to '夕', 'ト' to '卜', 'ヒ' to '匕',
    )

    /** What a text is looked up by: Simplified, no spaces, no colon at the end ("规格：" is "规格"). */
    fun key(text: String): String = toSimplified(text).filterNot { it.isWhitespace() }.trimEnd(':', '：')

    /** The text cut where Chinese starts and stops: (piece, is Chinese). */
    fun runs(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val current = StringBuilder()
        var han: Boolean? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val isHan = isHan(cp)
            if (han != null && isHan != han) {
                out.add(current.toString() to han)
                current.setLength(0)
            }
            han = isHan
            current.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        if (han != null) out.add(current.toString() to han)
        return out
    }

    /**
     * The Chinese of a screen, digits left out: a countdown ticking every second does not make the
     * screen "new" (it would be read again every second).
     */
    fun signature(texts: Iterable<String>): Int {
        var hash = 17
        for (text in texts) {
            text.codePoints().forEach { if (isHan(it)) hash = hash * 31 + it }
            hash = hash * 31 + 1
        }
        return hash
    }

    private fun load(): Map<Int, Int> {
        val stream = ChineseText::class.java.getResourceAsStream(TABLE) ?: return emptyMap()
        val map = HashMap<Int, Int>(4096)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val cps = line.codePoints().toArray()
                if (cps.size >= 2) map[cps[0]] = cps[1]
            }
        }
        return map
    }
}
