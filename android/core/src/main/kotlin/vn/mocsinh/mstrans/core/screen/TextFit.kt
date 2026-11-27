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

    /**
     * `confidence` (0 to 1) and `angle` (degrees) as the reader gives them; null: not given.
     * `known`: the dictionary knows the text whole. One letter of Chinese ("约 $ 6.5" under a
     * price), or a line the reader was not sure of, is kept only then: an icon taken for a letter
     * is no sentence the dictionary knows.
     */
    fun worth(text: String, confidence: Float?, angle: Float?, known: (String) -> Boolean = { false }): Boolean {
        var han = 0
        text.codePoints().forEach { if (ChineseText.isHan(it)) han++ }
        if (han == 0) return false
        if (angle != null && kotlin.math.abs(angle) > MAX_ANGLE) return false
        val sure = han >= 2 && (confidence == null || confidence >= MIN_CONFIDENCE)
        return sure || known(text.trim())
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
    fun pieces(words: List<List<Pair<String, Box>>>, ink: ((Box) -> Int?)? = null, whole: (String) -> Boolean = { true }): List<Pair<String, Box>> {
        // Every letter, left to right, with the word it was read in. A word the reader gave whole,
        // with a bar in it ("Bạc|"), is cut into its letters, each its share of the word's width.
        val letters = words.flatMapIndexed { w, word ->
            word.filter { it.first.isNotBlank() }.flatMap { (text, box) -> lettersOf(text.trim(), box) }.map { Letter(it.first, it.second, w) }
        }.sortedBy { it.box.left }
        if (letters.isEmpty()) return emptyList()
        // Where a new thing begins: a wide gap; either side of a bar; a price ending a thing.
        val cut = BooleanArray(letters.size)
        val drop = BooleanArray(letters.size)
        for (i in 1 until letters.size) {
            val before = letters[i - 1].box
            val box = letters[i].box
            if (box.left - before.right > maxOf(before.height, box.height) * WORD_GAP) cut[i] = true
        }
        for (i in letters.indices) {
            if (!isBar(letters, i)) continue
            drop[i] = true
            cut[i] = true
            if (i + 1 < letters.size) cut[i + 1] = true
        }
        // Another colour is another thing: a grey or orange price after a white name, even with
        // nothing read between them (the "..." and the ¥ lost).
        if (ink != null) {
            val inks = letters.map { if (it.text.all { c -> c in DOTS }) null else ink(it.box) }
            for (i in 1 until letters.size) {
                val a = inks[i - 1] ?: continue
                val b = inks[i] ?: continue
                if (colourApart(a, b)) cut[i] = true
            }
        }
        // A name cut short ("0-300..") ends there: what follows on the line (its price, an arrow) is another thing.
        for (i in 2 until letters.size) {
            if (letters[i].text.all { it in DOTS }) continue
            val before = letters[i - 1].text
            if (before.all { it in DOTS } && (before.length > 1 || before == "…" || letters[i - 2].text.all { it in DOTS })) cut[i] = true
        }
        var start = 0
        for (i in 1..letters.size) {
            if (i < letters.size && !cut[i]) continue
            priceAtEnd(letters, drop, start, i)?.let { cut[it] = true }
            // Vietnamese next to Chinese (an address before "包邮"): two things, the Vietnamese
            // never translated nor written over (the reader of Chinese misreads its letters).
            val span = (start until i).filter { !drop[it] }
            if (span.any { j -> ScreenTranslator.VIETNAMESE.containsMatchIn(letters[j].text) } && span.any { j -> ChineseText.hasHan(letters[j].text) }) {
                for ((a, b) in span.zipWithNext()) if (ChineseText.hasHan(letters[a].text) != ChineseText.hasHan(letters[b].text)) cut[b] = true
            }
            start = i
        }
        val groups = ArrayList<List<Letter>>()
        var group = ArrayList<Letter>()
        for (i in letters.indices) {
            if (cut[i] && group.isNotEmpty()) {
                groups.add(group)
                group = ArrayList()
            }
            if (!drop[i]) group.add(letters[i])
        }
        if (group.isNotEmpty()) groups.add(group)
        val out = ArrayList<Pair<String, Box>>()
        for (letters in groups) {
            val text = text(letters)
            // A number between two phrases, the whole not a known sentence ("平台加补后2211优惠前": a
            // ¥ lost, a bar read as 1): the number is left as it is drawn, never written over read wrong.
            if (!whole(text.first)) {
                val parts = numbersApart(letters)
                if (parts.size > 1) {
                    parts.forEach { out.add(text(it)) }
                    continue
                }
            }
            out.add(text)
        }
        // The reader's boxes are a little wide: one piece's box never runs into the next one's
        // (its background would cover the first letter of a price).
        return out.sortedBy { it.second.left }.let { sorted ->
            sorted.mapIndexed { i, (text, box) ->
                val next = sorted.getOrNull(i + 1)?.second
                if (next == null || next.left >= box.right || next.left <= box.left + 1) text to box
                else text to Box(box.left, box.top, next.left - 1, box.bottom)
            }
        }
    }

    /** Two ink colours of different things (white and grey, white and orange), not one colour's shades. */
    private fun colourApart(a: Int, b: Int): Boolean {
        val dr = kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
        val dg = kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return maxOf(dr, dg, db) > INK_APART
    }

    private const val INK_APART = 80

    /** Letters back into the words the reader gave (for the spaces between them), with their place. */
    private fun text(letters: List<Letter>): Pair<String, Box> {
        val units = ArrayList<Pair<String, Box>>()
        var text = StringBuilder()
        var box: Box? = null
        var word = -1
        for (letter in letters) {
            if (letter.word != word && box != null) {
                units.add(text.toString() to box)
                text = StringBuilder()
                box = null
            }
            text.append(letter.text)
            box = box?.let { around(it, letter.box) } ?: letter.box
            word = letter.word
        }
        box?.let { units.add(text.toString() to it) }
        return line(units) to units.map { it.second }.reduce(::around)
    }

    /** The letters cut around each run of digits that has Chinese on both sides. */
    private fun numbersApart(letters: List<Letter>): List<List<Letter>> {
        fun han(i: Int) = ChineseText.hasHan(letters[i].text)
        fun digits(i: Int) = letters[i].text.all { it.isDigit() || it in ".,¥￥" }
        val out = ArrayList<List<Letter>>()
        var from = 0
        var i = 0
        while (i < letters.size) {
            if (!digits(i) || i == 0 || !han(i - 1)) {
                i++
                continue
            }
            var end = i
            while (end < letters.size && digits(end)) end++
            if (end < letters.size && han(end)) {
                out.add(letters.subList(from, i))
                out.add(letters.subList(i, end))
                from = end
            }
            i = end
        }
        out.add(letters.subList(from, letters.size))
        return out.filter { it.isNotEmpty() }
    }

    private const val DOTS = ".。…·"

    private class Letter(val text: String, val box: Box, val word: Int)

    private fun lettersOf(text: String, box: Box): List<Pair<String, Box>> {
        if (text.length < 2 || text.none { it.toString() in BARS }) return listOf(text to box)
        val step = box.width.toFloat() / text.length
        return text.mapIndexed { i, c ->
            c.toString() to Box(box.left + (i * step).toInt(), box.top, box.left + ((i + 1) * step).toInt(), box.bottom)
        }
    }

    /**
     * A bar standing between two things ("¥221 | 优惠前¥268"), with room on both sides. The reader
     * often takes it for a 1 (the price then read ¥2211): a 1 as tall as the line and thin, apart
     * from what is around it, is a bar too.
     */
    private fun isBar(letters: List<Letter>, i: Int): Boolean {
        val letter = letters[i]
        // A bar sign is never part of the words around it.
        if (letter.text in BARS) return true
        if (letter.text !in BAR_LIKE) return false
        val before = letters.getOrNull(i - 1) ?: return false
        val after = letters.getOrNull(i + 1) ?: return false
        val height = maxOf(before.box.height, after.box.height)
        if (letter.box.left - before.box.right < height * BAR_GAP || after.box.left - letter.box.right < height * BAR_GAP) return false
        // As tall as the smaller text beside it (a price in large digits may stand before it), and thin.
        return letter.box.height >= minOf(before.box.height, after.box.height) * 0.9f && letter.box.width <= letter.box.height * 0.3f
    }

    /** Where a price ending the letters `from` until `to` begins (after other text), or null. */
    private fun priceAtEnd(letters: List<Letter>, drop: BooleanArray, from: Int, to: Int): Int? {
        val kept = (from until to).filter { !drop[it] }
        val sign = kept.lastOrNull { letters[it].text in CURRENCY } ?: return null
        if (sign == kept.first()) return null
        val after = kept.filter { it > sign }.map { letters[it].text }
        if (after.isEmpty() || after.none { it.any(Char::isDigit) } || !after.all { part -> part.all { it.isDigit() || it == '.' || it == ',' } }) return null
        val before = letters[kept[kept.indexOf(sign) - 1]].text.lastOrNull() ?: return null
        return if (before.isDigit() || before.toString() in CURRENCY) null else sign
    }

    private val BARS = setOf("|", "｜", "丨", "¦", "︱", "│")
    private val BAR_LIKE = setOf("1", "I", "l", "!", "i")
    private val CURRENCY = setOf("¥", "￥")
    private const val BAR_GAP = 0.25f

    private fun around(a: Box, b: Box) = Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    /** Words further apart than this part of the line's height are not one word. */
    private const val WORD_GAP = 0.6f
    private const val SPACE_GAP = 0.2f
}
