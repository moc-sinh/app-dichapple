package vn.mocsinh.mstrans.core.screen

/** Where the original text is drawn: one tight box per visual line, and its text size in pixels. */
data class TextGeometry(val lines: List<Box>, val sizePx: Float) {
    /** The box around all the lines. */
    val box: Box
        get() = Box(lines.minOf { it.left }, lines.minOf { it.top }, lines.maxOf { it.right }, lines.maxOf { it.bottom })

    /** The lines are inside `view`, the text's own view (a few pixels of slack). */
    fun within(view: Box): Boolean = box.let {
        it.left >= view.left - SLACK && it.top >= view.top - SLACK && it.right <= view.right + SLACK && it.bottom <= view.bottom + SLACK
    }

    private companion object {
        const val SLACK = 4
    }
}

object TextLines {
    /**
     * Lines from the place of each character (what a text view tells accessibility; null: not on
     * screen). A Chinese character is one em wide, so the middle width of those is the text size.
     */
    fun fromCharacters(text: String, boxes: List<Box?>): TextGeometry? {
        val lines = ArrayList<Box>()
        val hanWidths = ArrayList<Int>()
        val heights = ArrayList<Int>()
        var line: Box? = null
        for (i in text.indices) {
            val box = boxes.getOrNull(i) ?: continue
            if (text[i].isWhitespace() || box.width <= 0 || box.height <= 0) continue
            val middle = (box.top + box.bottom) / 2
            val current = line
            line = if (current == null || middle < current.top || middle >= current.bottom) {
                current?.let { lines.add(it) }
                box
            } else Box(minOf(current.left, box.left), minOf(current.top, box.top), maxOf(current.right, box.right), maxOf(current.bottom, box.bottom))
            if (ChineseText.isHan(text[i].code)) hanWidths.add(box.width)
            heights.add(box.height)
        }
        line?.let { lines.add(it) }
        if (lines.isEmpty()) return null
        val size = if (hanWidths.isNotEmpty()) hanWidths.sorted()[hanWidths.size / 2].toFloat() else heights.sorted()[heights.size / 2] * 0.75f
        return TextGeometry(lines, size)
    }
}
