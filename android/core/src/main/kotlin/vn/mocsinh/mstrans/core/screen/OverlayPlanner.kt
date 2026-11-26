package vn.mocsinh.mstrans.core.screen

/**
 * Where each translation goes so the screen still looks like the app's own: on one line at the
 * original's size where there is room (Vietnamese is longer, so it first takes the empty space next
 * to the original), then a little smaller, the same for every text of the same look, then on more
 * lines, and only then cut with "…".
 */
object OverlayPlanner {
    enum class Align { START, CENTER, END }

    /**
     * How wide and tall the translation is at a text size, in the real font on the phone.
     * `condensed`: the narrow cut of the same font, tried before the text is made smaller.
     */
    interface Measure {
        fun width(sizePx: Float, condensed: Boolean): Float
        fun lines(sizePx: Float, widthPx: Int, condensed: Boolean): Int
        fun lineHeight(sizePx: Float): Float
    }

    /**
     * One translation to place: the original's lines and size, the plain room on each side and
     * above and below, the middle of its ink, and its look (`group`: same number, same size).
     */
    class Item(
        val lines: List<Box>,
        val sizePx: Float,
        val freeLeft: Int,
        val freeRight: Int,
        val freeVertical: Int,
        val centerY: Int,
        val group: Int,
        val measure: Measure,
        /** How the text sits in its own view, where that is known; null: judged by the room around it. */
        val align: Align? = null,
    )

    /** The text block: `width` wide from `left`, the text at `align` in it, its middle at `centerY`. */
    data class Plan(
        val sizePx: Float,
        val left: Int,
        val width: Int,
        val align: Align,
        val maxLines: Int,
        val ellipsize: Boolean,
        val centerY: Int,
        val condensed: Boolean = false,
    )

    /**
     * Text is never made smaller than this part of the original's size, nor under `minPx`. Big text
     * (a banner's headline) may go down to `BIG_SCALE`, as long as it stays twice `minPx`.
     */
    private const val MIN_SCALE = 0.8f
    private const val BIG_SCALE = 0.6f

    /** How much a text was squeezed: the part of its size kept, and whether in the narrow cut. */
    private class Level(val scale: Float, val condensed: Boolean)

    private class Span(val left: Int, val right: Int, val align: Align) {
        val width: Int get() = right - left
    }

    /** Plans in the order of `items` (reading order: an earlier text takes shared room first). */
    fun plan(items: List<Item>, minPx: Float): List<Plan> {
        val alone = pass(items, minPx, arrayOfNulls(items.size))
        // Texts of the same look are squeezed alike, as much as the tightest of them needs.
        val same = arrayOfNulls<Level>(items.size)
        for (members in items.indices.groupBy { items[it].group }.values) {
            if (members.size < 2) continue
            val scale = members.minOf { alone[it].sizePx / items[it].sizePx }
            val condensed = members.any { alone[it].condensed }
            if (scale < 1f || condensed) for (i in members) same[i] = Level(scale, condensed)
        }
        return if (same.all { it == null }) alone else pass(items, minPx, same)
    }

    private fun pass(items: List<Item>, minPx: Float, same: Array<Level?>): List<Plan> {
        val taken = ArrayList<Box>()
        return items.mapIndexed { i, item ->
            val plan = place(item, minPx, same[i], taken)
            val measure = item.measure
            val oneLine = plan.maxLines == 1 && !plan.ellipsize
            val width = if (oneLine) minOf(plan.width, kotlin.math.ceil(measure.width(plan.sizePx, plan.condensed)).toInt()) else plan.width
            val left = when (plan.align) {
                Align.START -> plan.left
                Align.END -> plan.left + plan.width - width
                Align.CENTER -> plan.left + (plan.width - width) / 2
            }
            val half = (plan.maxLines * measure.lineHeight(plan.sizePx) / 2).toInt() + 1
            taken.add(Box(left, plan.centerY - half, left + width, plan.centerY + half))
            plan
        }
    }

    private fun place(item: Item, minPx: Float, same: Level?, taken: List<Box>): Plan {
        val box = Box(item.lines.minOf { it.left }, item.lines.minOf { it.top }, item.lines.maxOf { it.right }, item.lines.maxOf { it.bottom })
        val size = item.sizePx
        val measure = item.measure
        val most = same?.scale ?: 1f
        val least = maxOf(minPx / size, minOf(MIN_SCALE, maxOf(BIG_SCALE, minPx * 2 / size))).coerceAtMost(most)
        val narrowOnly = same?.condensed == true

        // Room on each side, less what an earlier translation took, less a gap before what comes next.
        var freeLeft = item.freeLeft
        var freeRight = item.freeRight
        for (other in taken) {
            if (other.bottom <= box.top || other.top >= box.bottom) continue
            if (other.left >= box.right - 2) freeRight = minOf(freeRight, other.left - box.right)
            if (other.right <= box.left + 2) freeLeft = minOf(freeLeft, box.left - other.right)
        }
        val gap = maxOf(4, Math.round(size * 0.3f))
        val left = (freeLeft - gap).coerceAtLeast(0)
        val right = (freeRight - gap).coerceAtLeast(0)
        // Its view says how the text is set, where there is one; if not, the room around it does:
        // as much on both sides, and a good deal of it, is text set in the middle of something.
        val centred = when (item.align) {
            Align.CENTER -> true
            Align.START, Align.END -> false
            null -> item.freeLeft >= size * 0.75f && item.freeRight >= size * 0.75f &&
                kotlin.math.abs(item.freeLeft - item.freeRight) <= maxOf(4f, maxOf(item.freeLeft, item.freeRight) * 0.1f)
        }
        val start = Span(box.left, box.right + right, Align.START)
        val end = Span(box.left - left, box.right, Align.END)
        val both = Span(end.left, start.right, Align.CENTER)
        val spans = when {
            centred -> minOf(left, right).let { listOf(Span(box.left - it, box.right + it, Align.CENTER)) }
            // A column of texts that begin (or end) together stays a column.
            item.align == Align.START -> listOf(start)
            item.align == Align.END -> listOf(end)
            // Close to something on its right (or to the page's margin) with far more room on
            // its left: it was set from the right.
            item.freeRight < size && item.freeLeft > maxOf(size * 2, item.freeRight * 4f) -> listOf(end, start, both)
            else -> listOf(start, end, both)
        }
        fun plan(span: Span, level: Level, lines: Int, cut: Boolean) =
            Plan(size * level.scale, span.left, span.width, span.align, lines, cut, item.centerY, level.condensed)

        // One line: as it is, then in the narrow cut, then smaller.
        if (item.lines.size == 1) for (span in spans) {
            val level = when {
                !narrowOnly && measure.width(size * most, false) <= span.width -> Level(most, false)
                measure.width(size * most, true) <= span.width -> Level(most, true)
                else -> (span.width / measure.width(size, true)).takeIf { it >= least }?.let { Level(minOf(it, most), true) }
            }
            if (level != null) return plan(span, level, 1, false)
        }

        // More lines, where the background goes on above and below (or the original had them).
        val span = if (item.lines.size > 1 || spans.size == 1) spans[0] else spans.take(2).maxBy { it.width }
        val room = box.height + 2 * item.freeVertical
        fun fits(level: Level): Int? =
            measure.lines(size * level.scale, span.width, level.condensed).takeIf { it * measure.lineHeight(size * level.scale) <= room }
        val tries = listOfNotNull(Level(most, false).takeUnless { narrowOnly }, Level(most, true))
        for (level in tries) fits(level)?.let { return plan(span, level, it, false) }
        if (fits(Level(least, true)) != null) {
            var low = least
            var high = most
            repeat(12) {
                val middle = (low + high) / 2
                if (fits(Level(middle, true)) != null) low = middle else high = middle
            }
            return plan(span, Level(low, true), fits(Level(low, true))!!, false)
        }
        val lines = (room / measure.lineHeight(size * least)).toInt().coerceAtLeast(1)
        return plan(span, Level(least, true), lines, true)
    }

    /** Sizes a few percent apart are the same size on screen: each becomes the middle one of its like. */
    fun unify(sizes: List<Float>): List<Float> {
        val order = sizes.indices.sortedBy { sizes[it] }
        val out = FloatArray(sizes.size)
        var from = 0
        while (from < order.size) {
            var until = from + 1
            while (until < order.size && sizes[order[until]] <= sizes[order[from]] * 1.07f) until++
            val middle = sizes[order[(from + until) / 2]]
            for (i in from until until) out[order[i]] = middle
            from = until
        }
        return out.toList()
    }
}
