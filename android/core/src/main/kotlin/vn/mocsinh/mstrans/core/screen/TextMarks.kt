package vn.mocsinh.mstrans.core.screen

import kotlin.math.abs

/**
 * A few pixels of a line of text as a screenshot of the app showed it, to tell later whether that
 * text is still there. Some apps move their text without a word to accessibility (Taobao's sheet of
 * variants scrolls its list in silence, 27/11/2026): the translations then lay where the text had
 * been, one row off. The app's window is looked at again now and then, and where the text moved,
 * the translations go and the page is read again.
 */
object TextMarks {
    private const val ACROSS = 12
    private const val DOWN = 3
    /** How much each point may change on average (0 to 255) for the text to be still the same. */
    private const val SAME_AVERAGE = 18

    /**
     * The brightness at a grid of points in `box` (a place on the screen); `pixel` gives the colour
     * at a place, null outside the picture. Null: the box is too small, or not in the picture.
     */
    fun of(box: Box, pixel: (Int, Int) -> Int?): ByteArray? {
        if (box.width < 4 || box.height < 4) return null
        val out = ByteArray(ACROSS * DOWN)
        for (row in 0 until DOWN) for (column in 0 until ACROSS) {
            val x = box.left + ((column + 0.5f) * box.width / ACROSS).toInt()
            val y = box.top + ((row + 0.5f) * box.height / DOWN).toInt()
            val colour = pixel(x, y) ?: return null
            out[row * ACROSS + column] = luma(colour).toByte()
        }
        return out
    }

    /** The text at this place is not what it was (moved away, or another one came). */
    fun changed(before: ByteArray, now: ByteArray): Boolean {
        if (before.size != now.size) return true
        var sum = 0
        for (i in before.indices) sum += abs((before[i].toInt() and 0xFF) - (now[i].toInt() and 0xFF))
        return sum > before.size * SAME_AVERAGE
    }

    private fun luma(argb: Int): Int = (299 * ((argb shr 16) and 0xFF) + 587 * ((argb shr 8) and 0xFF) + 114 * (argb and 0xFF)) / 1000
}
