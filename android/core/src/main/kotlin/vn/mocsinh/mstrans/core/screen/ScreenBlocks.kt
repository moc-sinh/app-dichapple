package vn.mocsinh.mstrans.core.screen

/**
 * Text read in a picture, block by block: the lines of a sentence that was wrapped are one text
 * (translated whole, and wrapped again in the same lines); a headline over a smaller line, or two
 * short lines of their own, stay apart.
 */
object ScreenBlocks {
    /** `lines`: the lines of one block as read, top to bottom, each with its place on the screen. */
    fun texts(lines: List<Pair<String, Box>>): List<ScreenText> {
        val shown = lines.filter { it.first.isNotBlank() && it.second.area > 0 }
        val boxes = shown.map { it.second }
        if (shown.size < 2 || !wrapped(boxes)) {
            return shown.map { (line, box) -> ScreenText(line.trim(), box, fromImage = true, geometry = TextGeometry(listOf(box), box.height.toFloat())) }
        }
        val text = StringBuilder()
        for ((line, _) in shown) {
            val part = line.trim()
            if (text.isNotEmpty() && latin(text.last()) && latin(part.first())) text.append(' ')
            text.append(part)
        }
        val around = Box(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
        val size = boxes.map { it.height }.sorted()[boxes.size / 2].toFloat()
        return listOf(ScreenText(text.toString(), around, fromImage = true, geometry = TextGeometry(boxes, size)))
    }

    /** One size, one left edge, one under the other, and every line but the last as wide as the widest. */
    private fun wrapped(boxes: List<Box>): Boolean {
        val height = boxes.map { it.height }.sorted()[boxes.size / 2]
        if (boxes.any { kotlin.math.abs(it.height - height) > height * 0.15f }) return false
        if (boxes.any { kotlin.math.abs(it.left - boxes[0].left) > height * 0.3f }) return false
        if (boxes.zipWithNext().any { (above, below) -> below.top < above.top + above.height / 2 }) return false
        val widest = boxes.maxOf { it.width }
        return boxes.dropLast(1).all { it.width >= widest * 0.9f }
    }

    private fun latin(c: Char) = c.code < 0x250 && c.isLetterOrDigit()
}
