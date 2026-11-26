package vn.mocsinh.mstrans.core.screen

/** A screenshot's pixels (ARGB, row by row); `left`, `top`: where its corner is on the screen. */
class PixelGrid(val width: Int, val height: Int, val pixels: IntArray, val left: Int = 0, val top: Int = 0) {
    val box: Box get() = Box(left, top, left + width, top + height)

    /** The pixel at a place on the screen (inside `box`). */
    operator fun get(x: Int, y: Int): Int = pixels[(y - top) * width + (x - left)]
}

/**
 * How a piece of text looks on the screen and the room around it: the colour behind it (at its left
 * end, and at its right end where a button's colour runs from one to another), its own colour,
 * whether that background is plain (one colour or such a run, not a picture), where its ink really
 * is (null: none found), and how far the same background goes on each side (pixels) before
 * something else starts.
 */
data class LineStyle(
    val background: Int,
    val text: Int,
    val plain: Boolean,
    val ink: Box?,
    val freeLeft: Int = 0,
    val freeRight: Int = 0,
    val freeAbove: Int = 0,
    val freeBelow: Int = 0,
    val backgroundEnd: Int = background,
    /** The run from `background` to `backgroundEnd` goes down the text (top to bottom), not across it. */
    val down: Boolean = false,
) {
    /**
     * The screenshot shows the text where the app says it is (`line`, text size `sizePx`). It does
     * not while a page is still sliding or growing into place: such a screenshot must not be used.
     */
    fun agrees(line: Box, sizePx: Float): Boolean {
        val ink = ink ?: return false
        if (!plain) return false
        val off = (ink.top + ink.bottom) / 2f - (line.top + line.bottom) / 2f
        // A line may begin or end with a sign that has no ink at its edge (【, 。): up to a letter's width.
        return ink.left - line.left <= sizePx * 0.8f && line.right - ink.right <= sizePx &&
            ink.height >= sizePx * 0.4f && kotlin.math.abs(off) <= line.height * 0.15f
    }
}

/**
 * A text's lines and size, with its look read from the screenshot; `view` is the box of the app's
 * own view of it, where it has one (how the text sits in it tells how it is aligned).
 */
data class Look(val geometry: TextGeometry, val style: LineStyle, val view: Box? = null)

object LineStyles {
    /** Two colours this close (largest difference of a channel) are the same background. */
    private const val SAME = 10
    /** A pixel this far from the background is ink. */
    private const val INK = 40

    /** The look of the text in `line`, read from the screenshot; room is looked for inside `limit` only. */
    fun of(grid: PixelGrid, line: Box, limit: Box): LineStyle? {
        val box = line.intersect(grid.box) ?: return null
        if (box.width < 4 || box.height < 4) return null
        val xs = steps(box.left, box.right, 200)
        val ys = steps(box.top, box.bottom, 64)
        val columns = xs.size
        val inside = IntArray(columns * ys.size) { grid[xs[it % columns], ys[it / columns]] }

        // The background, from a row above and a row below the text (no ink there) and checked
        // against the inside: one colour, a run of colour across the text (a button) or down it (a
        // picture), or else a picture too busy to be either. Rows further out are tried when the
        // nearest run through the text (a box drawn too small).
        var first = 0
        var last = 0
        var down = false
        var found = false
        for (margin in intArrayOf(maxOf(2, box.height / 8), maxOf(3, box.height / 3), maxOf(4, box.height * 2 / 3))) {
            val above = (box.top - margin).takeIf { it >= grid.top }
            val below = (box.bottom - 1 + margin).takeIf { it < grid.top + grid.height }
            val ringAbove = above?.let { y -> IntArray(columns) { grid[xs[it], y] } }
            val ringBelow = below?.let { y -> IntArray(columns) { grid[xs[it], y] } }
            run(ringAbove, ringBelow)?.takeIf { distance(it.first, it.second) > 6 }?.let { (left, right) ->
                if (share(inside, columns) { column, _ -> mix(left, right, column, columns) } >= 0.3f) {
                    first = left
                    last = right
                    found = true
                }
            }
            if (!found && above != null && below != null) {
                val upper = common(ringAbove!!, 0.8f)
                val lower = common(ringBelow!!, 0.8f)
                if (upper != null && lower != null && distance(upper, lower) > 4) {
                    val rows = below - above + 1
                    val top = mix(upper, lower, box.top - above, rows)
                    val bottom = mix(upper, lower, box.bottom - 1 - above, rows)
                    // A run, not the edge between a picture above and the page below: most of
                    // what is not ink must be on it, closely.
                    if (share(inside, columns, SAME) { _, row -> mix(top, bottom, ys[row] - box.top, box.height) } >= 0.45f) {
                        first = top
                        last = bottom
                        down = true
                        found = true
                    }
                }
            }
            if (!found) {
                val both = (ringAbove ?: IntArray(0)) + (ringBelow ?: IntArray(0))
                val flat = common(both, 0.6f) ?: ringAbove?.let { common(it, 0.8f) } ?: ringBelow?.let { common(it, 0.8f) }
                if (flat != null && share(inside, columns) { _, _ -> flat } >= 0.3f) {
                    first = flat
                    last = flat
                    found = true
                }
            }
            if (found) break
        }
        if (!found) {
            val flat = common(inside, 0.5f) ?: return picture(inside)
            first = flat
            last = flat
        }
        fun behind(x: Int, y: Int) = when {
            first == last -> first
            down -> mix(first, last, y - box.top, box.height)
            else -> mix(first, last, x - box.left, box.width)
        }

        // The text's own colour: the pixels furthest from the background (the edges of letters are
        // in between), and where they are.
        var furthest = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for ((row, y) in ys.withIndex()) for ((column, x) in xs.withIndex()) {
            val far = distance(inside[row * columns + column], behind(x, y))
            if (far <= INK) continue
            furthest = maxOf(furthest, far)
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        if (furthest > 0) for ((row, y) in ys.withIndex()) for ((column, x) in xs.withIndex()) {
            val pixel = inside[row * columns + column]
            if (distance(pixel, behind(x, y)) * 4 < furthest * 3) continue
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
            n++
        }
        val text = if (n == 0) OverlayFit.textColor(first) else rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        var ink: Box? = null
        if (n > 0) {
            // A box drawn a little too small (text read in a picture): the ink goes on above and
            // below it, and a letter's edge beside it. The soft edge of a letter counts: it is not
            // background, and room starts after it.
            val reach = maxOf(4, box.height / 2)
            val over = steps(left, right, 100)
            fun inkedRow(y: Int) = y >= grid.top && y < grid.top + grid.height && over.any { distance(grid[it, y], behind(it, y)) > INK }
            while (top > box.top - reach && inkedRow(top - 1)) top--
            while (bottom < box.bottom + reach && inkedRow(bottom)) bottom++
            val side = maxOf(2, box.height / 8)
            val rows = steps(top, bottom, 48)
            fun marked(x: Int) = x >= grid.left && x < grid.left + grid.width && rows.any { distance(grid[x, it], behind(x, it)) > SAME }
            for (x in box.left - 1 downTo box.left - side) if (marked(x)) left = x
            for (x in box.right until box.right + side) if (marked(x)) right = x + 1
            ink = Box(left, top, right, bottom)
        }
        // Room is counted from the text as it really is.
        val outer = ink?.let { Box(minOf(box.left, it.left), minOf(box.top, it.top), maxOf(box.right, it.right), maxOf(box.bottom, it.bottom)) } ?: box

        // Room: the same background (changing no faster than a button's colour run does).
        val area = limit.intersect(grid.box) ?: grid.box
        val band = steps(outer.top + outer.height / 4, maxOf(outer.top + outer.height / 4 + 1, outer.bottom - outer.height / 4), 8)
        fun sideways(from: Int, step: Int, start: Int): Int {
            var x = from
            var before = start
            while (x >= area.left && x < area.right) {
                if (down) {
                    if (band.any { distance(grid[x, it], behind(x, it)) > SAME }) break
                } else {
                    val colour = grid[x, band[0]]
                    if (band.any { distance(grid[x, it], colour) > SAME } || distance(colour, before) > 8 || distance(colour, start) > 64) break
                    before = colour
                }
                x += step
            }
            return (x - from) * step
        }
        val across = steps(outer.left, outer.right, 24)
        fun upDown(from: Int, step: Int): Int {
            var y = from
            val most = outer.height * 3
            while (y >= area.top && y < area.bottom && (y - from) * step < most) {
                if (across.any { distance(grid[it, y], behind(it, y)) > SAME }) break
                y += step
            }
            return (y - from) * step
        }
        return LineStyle(
            background = first,
            backgroundEnd = last,
            down = down,
            text = text,
            plain = true,
            ink = ink,
            freeLeft = sideways(outer.left - 1, -1, if (down) first else first),
            freeRight = sideways(outer.right, 1, if (down) first else last),
            freeAbove = upDown(outer.top - 1, -1),
            freeBelow = upDown(outer.bottom, 1),
        )
    }

    /**
     * The lines of text inside `bounds`, for a box that does not say where its letters are: rows of
     * ink on the box's plain colour, without the page around a button or the button's edge. Null
     * over a picture.
     */
    fun lines(grid: PixelGrid, bounds: Box): TextGeometry? {
        val box = bounds.intersect(grid.box) ?: return null
        if (box.width < 8 || box.height < 8) return null
        val xs = steps(box.left, box.right, 400)
        val ys = steps(box.top, box.bottom, 400)
        val columns = xs.size
        val pixels = IntArray(columns * ys.size) { grid[xs[it % columns], ys[it / columns]] }
        val paper = common(pixels, 0.5f) ?: return null
        val ink = BooleanArray(pixels.size) { distance(pixels[it], paper) > INK }

        // Off come the rows and columns that are all "ink": the page around a button, its edge.
        var left = 0
        var right = columns
        var top = 0
        var bottom = ys.size
        fun row(y: Int) = (left until right).count { ink[y * columns + it] }
        fun column(x: Int) = (top until bottom).count { ink[it * columns + x] }
        while (right - left > 2 && bottom - top > 2) {
            when {
                row(top) * 10 >= (right - left) * 9 -> top++
                row(bottom - 1) * 10 >= (right - left) * 9 -> bottom--
                column(left) * 10 >= (bottom - top) * 9 -> left++
                column(right - 1) * 10 >= (bottom - top) * 9 -> right--
                else -> break
            }
        }

        val runs = ArrayList<IntRange>()
        var start = -1
        for (y in top..bottom) {
            val has = y < bottom && row(y) > 0
            if (has && start < 0) start = y
            if (!has && start >= 0) {
                runs.add(start until y)
                start = -1
            }
        }
        if (runs.isEmpty()) return null
        fun height(run: IntRange) = ys[run.last] - ys[run.first] + 1
        val middle = runs.map { height(it) }.sorted()[runs.size / 2]
        // A thin run is a rule between rows, not a line of text.
        val lines = runs.filter { height(it) >= maxOf(3f, middle * 0.35f) }.map { run ->
            var first = right
            var last = left
            for (y in run) for (x in left until right) if (ink[y * columns + x]) {
                first = minOf(first, x)
                last = maxOf(last, x)
            }
            Box(xs[first], ys[run.first], xs[last] + 1, ys[run.last] + 1)
        }
        return TextGeometry(lines, middle / 0.93f)
    }

    /** Over a picture: its average colour behind the text, black or white letters, no room taken. */
    private fun picture(inside: IntArray): LineStyle {
        var r = 0L
        var g = 0L
        var b = 0L
        for (pixel in inside) {
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
        }
        val average = rgb((r / inside.size).toInt(), (g / inside.size).toInt(), (b / inside.size).toInt())
        return LineStyle(average, OverlayFit.textColor(average), plain = false, ink = null)
    }

    /** At most `count` places from `from` up to `until`, the first and the last always among them. */
    private fun steps(from: Int, until: Int, count: Int): IntArray {
        val length = until - from
        if (length <= count) return IntArray(length) { from + it }
        return IntArray(count) { from + (it.toLong() * (length - 1) / (count - 1)).toInt() }
    }

    /** The commonest colour, if at least `least` of the pixels have it. */
    private fun common(pixels: IntArray, least: Float): Int? {
        if (pixels.isEmpty()) return null
        val counts = HashMap<Int, Int>()
        for (pixel in pixels) counts.merge((pixel shr 4 and 0xF0F0F), 1, Int::plus)
        val bin = counts.maxByOrNull { it.value }!!.key
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (pixel in pixels) if ((pixel shr 4 and 0xF0F0F) == bin) {
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
            n++
        }
        val colour = rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        return colour.takeIf { pixels.count { distance(it, colour) <= SAME * 2 } >= least * pixels.size }
    }

    /** A background that runs evenly from one colour at the left to another at the right. */
    private fun run(above: IntArray?, below: IntArray?): Pair<Int, Int>? {
        val row = above ?: below ?: return null
        val other = below ?: row
        if (row.size < 3) return null
        val colours = IntArray(row.size) { if (distance(row[it], other[it]) <= SAME) row[it] else -1 }
        if (colours[0] == -1 || colours.last() == -1) return null
        val first = colours[0]
        val last = colours.last()
        val even = colours.indices.count { colours[it] != -1 && distance(colours[it], mix(first, last, it, colours.size)) <= 14 }
        return if (even * 10 >= colours.size * 9) first to last else null
    }

    /** How much of the pixels (rows of `columns`) is the background `behind(column, row)` gives. */
    private fun share(pixels: IntArray, columns: Int, within: Int = SAME * 2, behind: (Int, Int) -> Int): Float =
        pixels.indices.count { distance(pixels[it], behind(it % columns, it / columns)) <= within }.toFloat() / pixels.size

    private fun mix(first: Int, last: Int, at: Int, of: Int): Int {
        if (of <= 1) return first
        fun channel(shift: Int): Int {
            val a = first shr shift and 0xFF
            val b = last shr shift and 0xFF
            // Beyond the ends too (room next to a button's text), at the same rate.
            return (a + (b - a) * at / (of - 1)).coerceIn(0, 255)
        }
        return rgb(channel(16), channel(8), channel(0))
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun distance(a: Int, b: Int): Int = maxOf(
        kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
        kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
        kotlin.math.abs((a and 0xFF) - (b and 0xFF)),
    )
}
