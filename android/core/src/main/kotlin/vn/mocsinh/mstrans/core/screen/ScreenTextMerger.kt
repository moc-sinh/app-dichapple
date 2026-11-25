package vn.mocsinh.mstrans.core.screen

/** A rectangle on the screen, in pixels (right and bottom excluded). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height

    fun intersect(other: Box): Box? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (l < r && t < b) Box(l, t, r, b) else null
    }

    fun contains(other: Box): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)
}

/** A piece of text on the screen: from the accessibility tree, or read in a picture. */
data class ScreenText(val text: String, val bounds: Box, val fromImage: Boolean = false)

/**
 * The text of one screen from both sources, each piece once: accessibility first (exact text and
 * place); a parent that only repeats its children goes; text read in pictures goes where the
 * accessibility tree already has it. Only Chinese text in the app's window, off the keyboard.
 */
object ScreenTextMerger {
    fun merge(nodes: List<ScreenText>, ocr: List<ScreenText>, window: Box, excluded: List<Box> = emptyList()): List<ScreenText> {
        val visible = nodes.mapNotNull { place(it, window, excluded) }.distinct()
        val kept = withoutRepeats(visible)
        val read = ocr.mapNotNull { place(it, window, excluded) }.distinct().filter { line -> kept.none { covers(it, line) } }
        return kept + read
    }

    /** In the window by more than half, Chinese, not under the keyboard; cut to the window. */
    private fun place(text: ScreenText, window: Box, excluded: List<Box>): ScreenText? {
        if (!ChineseText.hasHan(text.text) || text.bounds.area == 0L) return null
        val inside = text.bounds.intersect(window) ?: return null
        if (inside.area * 2 <= text.bounds.area) return null
        if (excluded.any { area -> (inside.intersect(area)?.area ?: 0L) * 10 > inside.area * 3 }) return null
        return if (inside == text.bounds) text else text.copy(bounds = inside)
    }

    /**
     * A node holding smaller nodes whose text is inside its own: if those children have all its
     * Chinese, the parent goes (the children are placed better); if not, the children go.
     */
    private fun withoutRepeats(nodes: List<ScreenText>): List<ScreenText> {
        val dropped = HashSet<Int>()
        for (i in nodes.indices.sortedByDescending { nodes[it].bounds.area }) {
            if (i in dropped) continue
            val parent = nodes[i]
            val key = ChineseText.key(parent.text)
            val children = nodes.indices.filter { j ->
                j != i && j !in dropped && parent.bounds.contains(nodes[j].bounds) && ChineseText.key(nodes[j].text).let { it.isNotEmpty() && it in key }
            }
            if (children.isEmpty()) continue
            if (covered(parent.text, children.map { nodes[it].text })) dropped.add(i) else dropped.addAll(children)
        }
        return nodes.filterIndexed { i, _ -> i !in dropped }
    }

    private fun covered(text: String, parts: List<String>): Boolean {
        val counts = HashMap<Int, Int>()
        ChineseText.toSimplified(text).codePoints().forEach { if (ChineseText.isHan(it)) counts.merge(it, 1, Int::plus) }
        for (part in parts) ChineseText.toSimplified(part).codePoints().forEach { if (it in counts) counts.merge(it, -1, Int::plus) }
        return counts.values.all { it <= 0 }
    }

    /** Picture text already there as a node: mostly under it, or its words inside the node's. */
    private fun covers(node: ScreenText, line: ScreenText): Boolean {
        val overlap = node.bounds.intersect(line.bounds) ?: return false
        if (overlap.area * 2 > line.bounds.area) return true
        return ChineseText.key(line.text) in ChineseText.key(node.text)
    }
}
