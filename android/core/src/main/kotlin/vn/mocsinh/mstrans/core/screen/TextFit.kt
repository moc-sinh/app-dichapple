package vn.mocsinh.mstrans.core.screen

/**
 * Whether a box on the screen can be a piece of text, and which rows of ink in it are that text.
 * Shopping apps draw most of their pages themselves: a whole product card is then one node, its
 * title and price given as its description, and a tab is one node with its icon and its label. Such
 * a box must not be covered as if it were a line of text.
 */
object TextFit {
    /** A line of Chinese ink is this much of its text size high. */
    const val INK_OF_SIZE = 0.93f

    /** How long a text is, in Chinese letters: a Latin letter or a digit is about half of one. */
    fun ems(text: String): Float {
        var ems = 0f
        text.trim().codePoints().forEach { c ->
            ems += when {
                ChineseText.isHan(c) || c in 0x3000..0x303F || c in 0xFF00..0xFFEF -> 1f
                Character.isWhitespace(c) -> 0.3f
                c < 0x250 && Character.isLetterOrDigit(c) -> 0.55f
                else -> 0.6f
            }
        }
        return ems
    }

    /**
     * A box far larger than its text could fill at an ordinary size (`normalPx`): a card, a banner,
     * a cell described in words. What is written in it is read from its picture instead.
     */
    fun isRegion(text: String, box: Box, normalPx: Float): Boolean {
        // A label, a button, a text of two or three lines: never.
        if (box.height <= normalPx * 4) return false
        return ems(text) * normalPx * normalPx * 1.3f < box.area * 0.4f
    }

    /**
     * Of the rows of ink found in a text's box (top to bottom), those that are the text: rows of one
     * height, next to each other, as wide in all as the text is at the size their height gives.
     * Null: none are (an icon, a picture, a title the app cut short).
     */
    fun pick(text: String, rows: List<Box>): TextGeometry? {
        val ems = ems(text)
        if (ems <= 0f) return null
        var best: TextGeometry? = null
        var bestOff = Float.MAX_VALUE
        for (from in rows.indices) for (until in from + 1..minOf(rows.size, from + MAX_ROWS)) {
            val group = rows.subList(from, until)
            val heights = group.map { it.height }.sorted()
            val middle = heights[heights.size / 2]
            if (middle < 3 || heights.first() < middle * 0.75f || heights.last() > middle * 1.25f) continue
            val size = middle / INK_OF_SIZE
            val part = group.sumOf { it.width } / (ems * size)
            if (part < LEAST || part > MOST) continue
            val off = kotlin.math.abs(kotlin.math.ln(part))
            if (off < bestOff) {
                bestOff = off
                best = TextGeometry(group.toList(), size)
            }
        }
        return best
    }

    /** One line of this text at this size is about this wide. */
    fun isOneLine(text: String, widthPx: Int, sizePx: Float): Boolean {
        val ems = ems(text)
        if (ems <= 0f || sizePx <= 0f) return false
        return widthPx / (ems * sizePx) in LEAST..MOST
    }

    private const val MAX_ROWS = 12
    /** Letters of other scripts are narrower or wider than reckoned, and a last letter has no gap after it. */
    private const val LEAST = 0.6f
    private const val MOST = 1.4f
}

/**
 * What a screen reader would say of a view, less what it says about the view itself: "品牌，可选，按钮"
 * (brand, can be chosen, button) is the label 品牌. For a view that has a description but no text.
 */
object SpokenText {
    private val SEPARATORS = Regex("[，,、；;]")
    private val ABOUT_THE_VIEW = setOf(
        "按钮", "可选", "不可选", "已选中", "未选中", "选中", "已选择", "未选择", "已选定", "未选定", "链接", "图片", "图像", "图标",
        "标题", "标签", "标签页", "选项卡", "复选框", "单选按钮", "开关", "已开启", "已关闭", "已打开", "已展开", "已折叠", "已停用",
        "不可用", "编辑框", "搜索框", "列表", "弹窗", "菜单",
    )
    private val HOW_TO_USE = Regex("^(?:双击|点按|轻点|长按|点击).*|^第\\d+项.*|^共\\d+项$")

    /** Empty: the description only says what the view is (图片: a picture). */
    fun label(description: String): String {
        val parts = description.split(SEPARATORS)
        val kept = parts.filter { part ->
            val word = part.trim().trimEnd('。', '.')
            word.isNotEmpty() && word !in ABOUT_THE_VIEW && !HOW_TO_USE.matches(word)
        }
        if (kept.size == parts.size) return description.trim()
        return kept.joinToString("，") { it.trim() }
    }
}

/**
 * Whether a line read in a picture is worth translating. The reader takes icons for letters (a cart
 * for 口, a camera for 回), and reads the writing on the goods in a photograph.
 */
object PictureText {
    private const val MIN_CONFIDENCE = 0.5f
    private const val MAX_ANGLE = 12f

    /** `confidence` (0 to 1) and `angle` (degrees) as the reader gives them; null: not given. */
    fun worth(text: String, confidence: Float?, angle: Float?): Boolean {
        var han = 0
        text.codePoints().forEach { if (ChineseText.isHan(it)) han++ }
        if (han < 2) return false
        if (confidence != null && confidence < MIN_CONFIDENCE) return false
        return angle == null || kotlin.math.abs(angle) <= MAX_ANGLE
    }

    /**
     * A line from its words as read, left to right, each with its place: words a good way apart
     * stay apart. The reader gives a line of Chinese without spaces, and a price next to a number
     * of buyers then read as one number ("¥14.20500+").
     */
    fun line(words: List<Pair<String, Box>>): String {
        val out = StringBuilder()
        var before: Box? = null
        for ((word, box) in words.sortedBy { it.second.left }) {
            val text = word.trim()
            if (text.isEmpty()) continue
            val last = before
            if (last != null && out.isNotEmpty()) {
                val gap = box.left - last.right
                val height = maxOf(last.height, box.height)
                // Two words in Latin letters are two words; next to Chinese a space is a small gap,
                // and between Chinese letters only a wide one sets two things apart.
                val latin = (if (out.last().code < 0x250) 1 else 0) + (if (text.first().code < 0x250) 1 else 0)
                if (latin == 2 || gap > height * (if (latin == 1) SPACE_GAP else WORD_GAP)) out.append(' ')
            }
            out.append(text)
            before = box
        }
        return out.toString()
    }

    /**
     * The things written on one line of a picture, each with its place: set apart wherever there is
     * a wide gap, also inside what the reader took for one word. `words`: the line's words as
     * read, each letter with its place. A price is then a thing of its own, with no Chinese in it:
     * it is never translated, so a price read wrong (¥7.90 as 47.90) is never written over the real one.
     */
    fun pieces(words: List<List<Pair<String, Box>>>): List<Pair<String, Box>> {
        // Words, cut again where their own letters stand well apart.
        val units = ArrayList<Pair<String, Box>>()
        for (word in words) {
            var text = StringBuilder()
            var box: Box? = null
            for ((letter, place) in word.sortedBy { it.second.left }) {
                val before = box
                if (before != null && place.left - before.right > maxOf(before.height, place.height) * WORD_GAP) {
                    units.add(text.toString() to before)
                    text = StringBuilder()
                    box = null
                }
                text.append(letter)
                box = box?.let { around(it, place) } ?: place
            }
            box?.let { if (text.isNotBlank()) units.add(text.toString() to it) }
        }
        val out = ArrayList<Pair<String, Box>>()
        var group = ArrayList<Pair<String, Box>>()
        fun close() {
            if (group.isEmpty()) return
            out.add(line(group) to group.map { it.second }.reduce(::around))
            group = ArrayList()
        }
        for (unit in units.sortedBy { it.second.left }) {
            val before = group.lastOrNull()?.second
            if (before != null && unit.second.left - before.right > maxOf(before.height, unit.second.height) * WORD_GAP) close()
            group.add(unit)
        }
        close()
        return out
    }

    private fun around(a: Box, b: Box) = Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    /** Words further apart than this part of the line's height are not one word. */
    private const val WORD_GAP = 0.6f
    private const val SPACE_GAP = 0.2f
}
