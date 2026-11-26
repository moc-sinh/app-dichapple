package vn.mocsinh.mstrans.core.screen

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextLinesTest {
    private fun row(left: Int, top: Int, widths: List<Int>, height: Int): List<Box> {
        var x = left
        return widths.map { width -> Box(x, top, x + width, top + height).also { x += width } }
    }

    @Test
    fun `one line is the box around its characters and a Chinese character is one em wide`() {
        val geometry = TextLines.fromCharacters("设置", row(42, 370, listOf(40, 40), 52))!!
        assertEquals(listOf(Box(42, 370, 122, 422)), geometry.lines)
        assertEquals(40f, geometry.sizePx)
    }

    @Test
    fun `wrapped text gives one box for each visual line`() {
        val boxes = row(42, 100, List(6) { 40 }, 50) + row(42, 150, List(4) { 40 }, 50)
        val geometry = TextLines.fromCharacters("不锈钢保温水杯大容量", boxes)!!
        assertEquals(listOf(Box(42, 100, 282, 150), Box(42, 150, 202, 200)), geometry.lines)
        assertEquals(Box(42, 100, 282, 200), geometry.box)
    }

    @Test
    fun `digits and signs do not set the text size`() {
        val geometry = TextLines.fromCharacters("已售3000+", row(42, 100, listOf(30, 30, 17, 17, 17, 17, 18), 40))!!
        assertEquals(30f, geometry.sizePx)
        assertEquals(listOf(Box(42, 100, 188, 140)), geometry.lines)
    }

    @Test
    fun `letters said to be outside their own view are not believed`() {
        // Asked while the page was still moving: the view is where it will be, the letters where they were.
        val view = Box(42, 1481, 1038, 1560)
        assertTrue(TextGeometry(listOf(Box(73, 1495, 451, 1531)), 36f).within(view))
        assertFalse(TextGeometry(listOf(Box(73, 1540, 451, 1586)), 36f).within(view))
    }

    @Test
    fun `characters that are not on screen are left out`() {
        val shown = row(42, 100, listOf(40, 40), 50)
        val geometry = TextLines.fromCharacters("包邮现货", shown + listOf(null, null))!!
        assertEquals(listOf(Box(42, 100, 122, 150)), geometry.lines)
        assertNull(TextLines.fromCharacters("包邮", listOf(null, null)))
    }
}

class LineStylesTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFC81E1E.toInt()
    private val grey = 0xFFD6D7D7.toInt()

    private class Screen(val width: Int, val height: Int, fill: Int) {
        val pixels = IntArray(width * height) { fill }

        fun rect(box: Box, color: Int) {
            for (y in box.top until box.bottom) for (x in box.left until box.right) pixels[y * width + x] = color
        }

        /** Text as upright strokes: 3 pixels of ink every 8, 5 pixels clear above and below. */
        fun text(box: Box, color: Int) {
            for (x in box.left until box.right step 8) rect(Box(x, box.top + 5, minOf(x + 3, box.right), box.bottom - 5), color)
        }

        fun grid() = PixelGrid(width, height, pixels)
    }

    private val all = Box(0, 0, 400, 200)
    private val line = Box(50, 80, 150, 110)

    @Test
    fun `the colours are the page's and the text's own`() {
        val screen = Screen(400, 200, white).apply { text(line, red) }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertEquals(white, style.background)
        assertEquals(red, style.text)
        assertTrue(style.plain)
    }

    @Test
    fun `the ink is where the strokes are`() {
        val screen = Screen(400, 200, white).apply { text(line, black) }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertEquals(Box(50, 85, 149, 105), style.ink)
    }

    @Test
    fun `ink that goes on above and below a box drawn too low is all found`() {
        val screen = Screen(400, 200, white).apply { text(line, black) }
        val style = LineStyles.of(screen.grid(), Box(50, 90, 150, 100), all)!!
        assertEquals(Box(50, 85, 149, 105), style.ink)
    }

    @Test
    fun `a screenshot agrees with the app when the ink is where the app says the text is`() {
        val screen = Screen(400, 200, white).apply { text(line, black) }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertTrue(style.agrees(line, 20f))
    }

    @Test
    fun `a screenshot taken while the page was still moving does not agree with the app`() {
        // The page was drawn 40 pixels lower and to the right of where the app says it is.
        val screen = Screen(400, 200, white).apply { text(line.offset(40, 12), black) }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertFalse(style.agrees(line, 20f))
        val empty = LineStyles.of(Screen(400, 200, white).grid(), line, all)!!
        assertFalse(empty.agrees(line, 20f))
        // Ten pixels lower only, on a line 54 high as on the phone it was seen on (the picture and
        // the app's words were not laid one on the other): enough to draw every translation beside
        // its text.
        val tall = Box(50, 60, 150, 114)
        val lower = Screen(400, 200, white).apply { text(tall.offset(0, 10), black) }
        assertFalse(LineStyles.of(lower.grid(), tall, all)!!.agrees(tall, 36f))
        val right = Screen(400, 200, white).apply { text(tall, black) }
        assertTrue(LineStyles.of(right.grid(), tall, all)!!.agrees(tall, 36f))
    }

    @Test
    fun `room goes as far as the next thing on the row`() {
        val screen = Screen(400, 200, white).apply {
            text(line, black)
            rect(Box(300, 0, 320, 200), grey)
        }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertEquals(150, style.freeRight)
        assertEquals(50, style.freeLeft)
    }

    @Test
    fun `room stops at the edge of the window`() {
        val screen = Screen(400, 200, white).apply { text(line, black) }
        val style = LineStyles.of(screen.grid(), line, Box(20, 0, 380, 200))!!
        assertEquals(30, style.freeLeft)
        assertEquals(230, style.freeRight)
    }

    @Test
    fun `text on a button has the button's colour behind it and room up to the button's edges`() {
        val label = Box(90, 80, 170, 110)
        val screen = Screen(400, 200, white).apply {
            rect(Box(30, 60, 230, 130), grey)
            text(label, black)
        }
        val style = LineStyles.of(screen.grid(), label, all)!!
        assertEquals(grey, style.background)
        assertEquals(black, style.text)
        assertEquals(60, style.freeLeft)
        assertEquals(60, style.freeRight)
        assertEquals(20, style.freeAbove)
        assertEquals(20, style.freeBelow)
    }

    @Test
    fun `a button whose colour runs from yellow to orange keeps that run behind the text`() {
        val label = Box(90, 80, 170, 110)
        val screen = Screen(400, 200, white)
        for (x in 30 until 230) screen.rect(Box(x, 60, x + 1, 130), 0xFFFF0000.toInt() or ((200 - (x - 30) / 2) shl 8))
        screen.text(label, white)
        val style = LineStyles.of(screen.grid(), label, all)!!
        assertTrue(style.plain)
        assertEquals(white, style.text)
        assertEquals(0xFFFFAA00.toInt(), style.background)
        assertEquals(0xFFFF8300.toInt(), style.backgroundEnd)
        assertEquals(60, style.freeLeft)
        assertEquals(60, style.freeRight)
    }

    @Test
    fun `a picture whose colour runs from top to bottom keeps that run behind the text`() {
        val screen = Screen(400, 200, white)
        fun red(y: Int) = 0xFF000000.toInt() or ((60 + y / 2) shl 16) or (20 shl 8) or 30
        for (y in 0 until 200) screen.rect(Box(0, y, 400, y + 1), red(y))
        screen.text(line, white)
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertTrue(style.plain)
        assertTrue(style.down)
        assertEquals(white, style.text)
        assertClose(red(80), style.background)
        assertClose(red(109), style.backgroundEnd)
        assertEquals(50, style.freeLeft)
        assertEquals(250, style.freeRight)
    }

    private fun assertClose(expected: Int, actual: Int) {
        for (shift in listOf(16, 8, 0)) {
            val a = expected shr shift and 0xFF
            val b = actual shr shift and 0xFF
            assertTrue(kotlin.math.abs(a - b) <= 2, "expected ${Integer.toHexString(expected)}, was ${Integer.toHexString(actual)}")
        }
    }

    @Test
    fun `ink a little outside the sides of a box drawn too narrow is found, and room counted from it`() {
        val screen = Screen(400, 200, white).apply { text(line, black) }
        val style = LineStyles.of(screen.grid(), Box(52, 80, 148, 110), all)!!
        assertEquals(Box(50, 85, 149, 105), style.ink)
        assertEquals(50, style.freeLeft)
        assertEquals(251, style.freeRight)
    }

    @Test
    fun `the soft edge of a letter beside its box does not stop the room from being found`() {
        // Half-drawn pixels at the edge of the last letter, just outside the box the text was given.
        val soft = 0xFFE6E6E6.toInt()
        val screen = Screen(400, 200, white).apply {
            text(line, black)
            rect(Box(150, 85, 152, 105), soft)
            rect(Box(48, 85, 50, 105), soft)
        }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertEquals(Box(48, 85, 152, 105), style.ink)
        assertEquals(48, style.freeLeft)
        assertEquals(248, style.freeRight)
    }

    @Test
    fun `text under a picture is on the page's colour, not on a run from the picture's`() {
        val screen = Screen(400, 200, white).apply {
            rect(Box(0, 0, 400, 78), grey)
            text(line, black)
        }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertEquals(white, style.background)
        assertEquals(white, style.backgroundEnd)
        assertFalse(style.down)
    }

    @Test
    fun `room below stops at the next line of text`() {
        val screen = Screen(400, 200, white).apply {
            text(line, black)
            text(Box(50, 140, 150, 170), black)
        }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertEquals(35, style.freeBelow)
        assertEquals(80, style.freeAbove)
    }

    @Test
    fun `a picture behind the text is not a plain background`() {
        val screen = Screen(400, 200, white)
        var seed = 7
        for (y in line.top - 10 until line.bottom + 10) for (x in line.left - 10 until line.right + 10) {
            seed = seed * 1103515245 + 12345
            screen.pixels[y * 400 + x] = 0xFF000000.toInt() or (seed ushr 8 and 0xFFFFFF)
        }
        val style = LineStyles.of(screen.grid(), line, all)!!
        assertFalse(style.plain)
        assertEquals(0, style.freeRight)
    }

    @Test
    fun `text in a box that does not say where its letters are is found by its ink`() {
        val screen = Screen(400, 200, white).apply {
            rect(Box(30, 40, 330, 160), grey)
            text(Box(50, 60, 250, 90), black)
            text(Box(50, 100, 170, 130), black)
        }
        val geometry = LineStyles.lines(screen.grid(), Box(30, 40, 330, 160))!!
        assertEquals(listOf(Box(50, 65, 245, 85), Box(50, 105, 165, 125)), geometry.lines)
        assertEquals(21.5f, geometry.sizePx, 0.1f)
    }

    @Test
    fun `the page around a button and its edge are not its text`() {
        val screen = Screen(400, 200, white).apply {
            rect(Box(30, 40, 330, 160), grey)
            rect(Box(30, 40, 330, 42), black)
            text(Box(50, 85, 250, 115), black)
        }
        val geometry = LineStyles.lines(screen.grid(), Box(20, 30, 340, 170))!!
        assertEquals(listOf(Box(50, 90, 245, 110)), geometry.lines)
    }

    @Test
    fun `of an icon and its label in one box only the label is the text`() {
        val screen = Screen(400, 200, white).apply {
            rect(Box(180, 40, 230, 86), black)
            text(Box(184, 92, 226, 124), black)
        }
        val geometry = LineStyles.lines(screen.grid(), Box(150, 30, 260, 130), "店铺")!!
        assertEquals(listOf(Box(184, 97, 226, 119)), geometry.lines)
        assertEquals(22 / 0.93f, geometry.sizePx, 0.1f)
    }

    @Test
    fun `a box whose ink cannot be its text has no lines`() {
        // A card: a picture, and under it a title too short to be the card's whole description.
        val screen = Screen(400, 200, white).apply {
            rect(Box(40, 20, 360, 130), red)
            text(Box(50, 140, 210, 170), black)
        }
        assertNull(LineStyles.lines(screen.grid(), Box(40, 20, 360, 180), "公主-可水洗牛皮纸袋厨房冰箱蔬菜收纳袋防水加厚食品袋环保 6.80元"))
    }

    @Test
    fun `the words of a bar lying over a box are not that box's text`() {
        // A card has scrolled under the page's header: a name at the left of it, two buttons at the right.
        val screen = Screen(400, 200, white).apply {
            text(Box(20, 60, 100, 90), black)
            text(Box(290, 60, 380, 90), black)
        }
        // As wide in all as the card's description would be, but not one piece of writing.
        assertNull(LineStyles.lines(screen.grid(), Box(10, 40, 390, 110), "牛皮纸袋厨房冰箱蔬菜水果收纳袋防水"))
        // Each of them alone is still found as the short text it is.
        assertEquals(listOf(Box(20, 65, 95, 85)), LineStyles.lines(screen.grid(), Box(10, 40, 200, 110), "官方客服")!!.lines)
    }

    @Test
    fun `a picture has no lines of ink to find`() {
        val screen = Screen(400, 200, white)
        var seed = 7
        for (i in screen.pixels.indices) {
            seed = seed * 1103515245 + 12345
            screen.pixels[i] = 0xFF000000.toInt() or (seed ushr 8 and 0xFFFFFF)
        }
        assertNull(LineStyles.lines(screen.grid(), Box(30, 40, 330, 160)))
    }

    @Test
    fun `text outside the screenshot has no style`() {
        assertNull(LineStyles.of(Screen(400, 200, white).grid(), Box(500, 80, 600, 110), all))
    }
}

class OverlayPlannerTest {
    /** Half an em a letter, lines 1.2 em high: close to a real font, exact to reason with. */
    private class Letters(val count: Int) : OverlayPlanner.Measure {
        override fun width(sizePx: Float, condensed: Boolean) = count * sizePx * 0.5f * (if (condensed) 0.86f else 1f)
        override fun lines(sizePx: Float, widthPx: Int, condensed: Boolean) = ceil(width(sizePx, condensed) / widthPx).toInt().coerceAtLeast(1)
        override fun lineHeight(sizePx: Float) = sizePx * 1.2f
    }

    private fun item(
        box: Box, letters: Int, size: Float = 40f, freeLeft: Int = 0, freeRight: Int = 0, freeVertical: Int = 0, group: Int = box.hashCode(),
        align: OverlayPlanner.Align? = null, guessed: Boolean = false,
    ) = OverlayPlanner.Item(listOf(box), size, freeLeft, freeRight, freeVertical, (box.top + box.bottom) / 2, group, Letters(letters), align, guessed)

    private fun plan(vararg items: OverlayPlanner.Item, minPx: Float = 20f) = OverlayPlanner.plan(items.toList(), minPx)

    @Test
    fun `a translation longer than the original takes the empty room on its right at the same size`() {
        val plan = plan(item(Box(42, 370, 122, 422), letters = 7, freeRight = 800)).single()
        assertEquals(40f, plan.sizePx)
        assertEquals(42, plan.left)
        assertEquals(OverlayPlanner.Align.START, plan.align)
        assertEquals(1, plan.maxLines)
        assertFalse(plan.ellipsize)
        assertFalse(plan.condensed)
        assertEquals(396, plan.centerY)
    }

    @Test
    fun `text with the same room on both sides stays in the middle`() {
        val plan = plan(item(Box(90, 80, 170, 110), letters = 8, freeLeft = 60, freeRight = 60)).single()
        assertEquals(OverlayPlanner.Align.CENTER, plan.align)
        assertEquals(42, plan.left)
        assertEquals(176, plan.width)
        assertEquals(40f, plan.sizePx)
    }

    @Test
    fun `text in the middle of its view stays in the middle whatever is beside it`() {
        // A tab: room on its left up to the edge of the screen, on its right only up to the next tab.
        val plan = plan(item(Box(100, 80, 180, 110), letters = 8, freeLeft = 100, freeRight = 60, align = OverlayPlanner.Align.CENTER)).single()
        assertEquals(OverlayPlanner.Align.CENTER, plan.align)
        assertEquals(52, plan.left)
        assertEquals(176, plan.width)
    }

    @Test
    fun `text that begins its view is never moved to the middle`() {
        val plan = plan(item(Box(100, 80, 180, 110), letters = 8, freeLeft = 60, freeRight = 60, align = OverlayPlanner.Align.START)).single()
        assertEquals(OverlayPlanner.Align.START, plan.align)
        assertEquals(100, plan.left)
    }

    @Test
    fun `a little room on both sides does not make text centred`() {
        // A label between its neighbours, a few pixels from each: not one set in the middle of something.
        val plan = plan(item(Box(100, 80, 180, 110), letters = 4, freeLeft = 12, freeRight = 12)).single()
        assertEquals(OverlayPlanner.Align.START, plan.align)
        assertEquals(100, plan.left)
    }

    @Test
    fun `a little too long is set in narrower letters at the same size`() {
        val plan = plan(item(Box(90, 80, 170, 110), letters = 10, freeLeft = 60, freeRight = 60)).single()
        assertEquals(40f, plan.sizePx)
        assertTrue(plan.condensed)
        assertEquals(1, plan.maxLines)
    }

    @Test
    fun `longer still becomes a little smaller on one line`() {
        val plan = plan(item(Box(90, 80, 170, 110), letters = 12, freeLeft = 60, freeRight = 60)).single()
        assertEquals(34.11f, plan.sizePx, 0.01f)
        assertTrue(plan.condensed)
        assertEquals(1, plan.maxLines)
        assertFalse(plan.ellipsize)
    }

    @Test
    fun `texts of the same look get the same size`() {
        val plans = plan(
            item(Box(90, 80, 170, 110), letters = 12, freeLeft = 60, freeRight = 60, group = 7),
            item(Box(390, 80, 470, 110), letters = 4, freeLeft = 60, freeRight = 60, group = 7),
            item(Box(690, 80, 770, 110), letters = 4, freeLeft = 60, freeRight = 60, group = 8),
        )
        assertEquals(34.11f, plans[0].sizePx, 0.01f)
        assertEquals(34.11f, plans[1].sizePx, 0.01f)
        assertTrue(plans[1].condensed)
        assertEquals(40f, plans[2].sizePx)
        assertFalse(plans[2].condensed)
    }

    @Test
    fun `much too long goes on two lines when there is room above and below`() {
        val plan = plan(item(Box(42, 100, 122, 150), letters = 14, freeRight = 100, freeVertical = 30)).single()
        assertEquals(40f, plan.sizePx)
        assertEquals(2, plan.maxLines)
        assertFalse(plan.ellipsize)
        assertFalse(plan.condensed)
    }

    @Test
    fun `too long with no room is made smaller, as far as it still reads, rather than cut`() {
        // "Mua …" tells nothing: the whole of it a size down does.
        val plan = plan(item(Box(42, 100, 122, 150), letters = 14, freeRight = 100)).single()
        assertEquals(27.9f, plan.sizePx, 0.1f)
        assertEquals(1, plan.maxLines)
        assertFalse(plan.ellipsize)
    }

    @Test
    fun `only what does not fit at the smallest size is cut, at the usual one`() {
        val plan = plan(item(Box(42, 100, 122, 150), letters = 60, freeRight = 100)).single()
        assertEquals(32f, plan.sizePx, 0.01f)
        assertEquals(1, plan.maxLines)
        assertTrue(plan.ellipsize)
    }

    @Test
    fun `a size that was only guessed gives way before anything else`() {
        // A label whose letters the app does not place: its size is a guess, never a reason to wrap or cut.
        val plan = plan(item(Box(42, 100, 122, 150), letters = 8, freeVertical = 60, guessed = true)).single()
        assertEquals(23.25f, plan.sizePx, 0.1f)
        assertEquals(1, plan.maxLines)
        assertFalse(plan.ellipsize)
    }

    @Test
    fun `one line becomes two at most`() {
        // A tab whose translation is long: two lines, not a column of words down the page.
        val plan = plan(item(Box(42, 100, 122, 150), letters = 60, freeRight = 100, freeVertical = 300)).single()
        assertEquals(2, plan.maxLines)
        assertTrue(plan.ellipsize)
    }

    @Test
    fun `one text much too long does not make the others of its look small`() {
        val plans = plan(
            item(Box(90, 80, 170, 110), letters = 20, freeLeft = 60, freeRight = 60, group = 7),
            item(Box(390, 80, 470, 110), letters = 4, freeLeft = 60, freeRight = 60, group = 7),
        )
        assertTrue(plans[0].sizePx < 32f, "size ${plans[0].sizePx}")
        assertEquals(32f, plans[1].sizePx, 0.01f)
    }

    @Test
    fun `big text may be made smaller than small text may`() {
        // A banner's headline at 80 px: still easy to read at 60 % of that.
        val plan = plan(item(Box(100, 100, 500, 180), letters = 30, size = 80f, freeRight = 300), minPx = 20f).single()
        assertEquals(52.4f, plan.sizePx, 0.1f)
        assertEquals(1, plan.maxLines)
        assertFalse(plan.ellipsize)
    }

    @Test
    fun `text never goes under the smallest readable size`() {
        val plan = plan(item(Box(42, 100, 102, 130), letters = 10, size = 24f), minPx = 22f).single()
        assertEquals(22f, plan.sizePx, 0.01f)
        assertTrue(plan.ellipsize)
    }

    @Test
    fun `text against something on its right grows to the left`() {
        val plan = plan(item(Box(300, 100, 380, 150), letters = 8, freeLeft = 250)).single()
        assertEquals(OverlayPlanner.Align.END, plan.align)
        assertEquals(380, plan.left + plan.width)
        assertEquals(40f, plan.sizePx)
    }

    @Test
    fun `text near the right edge with the row empty on its left grows to the left`() {
        // "已售 16" at the end of a row: 21 px to the page's margin, hundreds to the price on its left.
        val plan = plan(item(Box(920, 100, 1038, 140), letters = 7, size = 34f, freeLeft = 500, freeRight = 21)).single()
        assertEquals(OverlayPlanner.Align.END, plan.align)
        assertEquals(1038, plan.left + plan.width)
    }

    @Test
    fun `two texts on a row do not write over each other`() {
        val plans = plan(
            item(Box(42, 100, 122, 150), letters = 8, freeRight = 100),
            item(Box(222, 100, 302, 150), letters = 8, freeLeft = 100),
        )
        val firstEnds = plans[0].left + 160
        assertTrue(plans[1].left >= firstEnds, "second starts at ${plans[1].left}, first ends at $firstEnds")
    }

    @Test
    fun `a paragraph is wrapped again in its own lines`() {
        val lines = listOf(Box(42, 100, 282, 150), Box(42, 150, 202, 200))
        val paragraph = OverlayPlanner.Item(lines, 40f, 0, 0, 0, 150, 1, Letters(30))
        val plan = OverlayPlanner.plan(listOf(paragraph), 20f).single()
        assertEquals(2, plan.maxLines)
        assertFalse(plan.ellipsize)
        assertTrue(plan.condensed)
        assertTrue(plan.sizePx in 36.8f..37.21f, "size ${plan.sizePx}")
        assertEquals(42, plan.left)
        assertEquals(240, plan.width)
    }

    @Test
    fun `sizes a few percent apart become one size`() {
        assertEquals(listOf(40f, 40f, 40f, 52f), OverlayPlanner.unify(listOf(40f, 41f, 39.5f, 52f)))
    }
}
