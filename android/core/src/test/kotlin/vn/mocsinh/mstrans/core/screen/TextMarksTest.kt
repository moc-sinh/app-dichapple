package vn.mocsinh.mstrans.core.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Text that moved without a word to accessibility (Taobao's sheet of variants, 27/11/2026) is seen moved. */
class TextMarksTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A page of rows of "letters": a black stroke every 7 pixels, a different pattern on each row, rows 60 apart. */
    private fun page(shift: Int): (Int, Int) -> Int? = { x, y ->
        val at = y + shift
        val row = at / 60
        val inRow = at % 60
        if (inRow in 10..40 && (x + row * 3) % 7 < 2) black else white
    }

    @Test
    fun `the same text in the same place is the same`() {
        val box = Box(100, 610, 700, 640)
        val before = assertNotNull(TextMarks.of(box, page(0)))
        assertFalse(TextMarks.changed(before, assertNotNull(TextMarks.of(box, page(0)))))
    }

    @Test
    fun `a list scrolled by part of a row, or by whole rows, is seen`() {
        val box = Box(100, 610, 700, 640)
        val before = assertNotNull(TextMarks.of(box, page(0)))
        assertTrue(TextMarks.changed(before, assertNotNull(TextMarks.of(box, page(25)))))
        // Another row of text exactly where the first was: other letters.
        assertTrue(TextMarks.changed(before, assertNotNull(TextMarks.of(box, page(120)))))
    }

    @Test
    fun `a box outside the picture has no marks`() {
        assertNull(TextMarks.of(Box(100, 610, 700, 640)) { _, y -> if (y < 620) white else null })
        assertNull(TextMarks.of(Box(0, 0, 2, 2)) { _, _ -> white })
    }
}
