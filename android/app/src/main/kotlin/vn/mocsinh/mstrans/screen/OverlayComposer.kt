package vn.mocsinh.mstrans.screen

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import vn.mocsinh.mstrans.core.screen.Box
import vn.mocsinh.mstrans.core.screen.LineStyle
import vn.mocsinh.mstrans.core.screen.Look
import vn.mocsinh.mstrans.core.screen.OverlayFit
import vn.mocsinh.mstrans.core.screen.OverlayPlanner
import java.util.Objects

/**
 * Turns the translations of one screen into what `OverlayView` draws: `OverlayPlanner` places them
 * (measured in the phone's own font, and its narrow cut), this builds the text and the background
 * that hides the Chinese. One thread at a time. What did not change since the last screen is kept
 * as built, so nothing is laid out twice.
 */
class OverlayComposer(context: Context) {
    /**
     * `shorter`: a shorter word for the same, used where the usual one would be cut or wrapped (a
     * label under an icon). `picture`: the text was read in a picture.
     */
    class Entry(val look: Look, val translation: String, val shorter: String? = null, val picture: Boolean = false)

    private data class Key(val translation: String, val look: Look, val plan: OverlayPlanner.Plan)

    /** A translation laid out, and how much of it is to be seen (1: all of it). */
    private class Built(val drawn: OverlayView.Drawn, val shown: Float)

    private val metrics = context.resources.displayMetrics
    private val smallest = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, metrics)
    private val normal: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val narrow: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val ruler = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = normal
        textSize = RULER
    }
    /** A line's height for each pixel of text size. */
    private val spacing = ruler.fontSpacing / RULER
    /** Widths at the ruler's size, in the normal and the narrow cut. */
    private val widths = HashMap<String, FloatArray>()
    private var built = HashMap<Key, Built>()

    /**
     * `entries` in reading order; `window` is the app's window (nothing is drawn outside it);
     * `others`: where every text of the page is, translated or not (a translation never spreads onto one).
     */
    fun compose(entries: List<Entry>, window: Box, others: List<Box> = emptyList()): List<OverlayView.Drawn> {
        if (entries.isEmpty()) {
            built = HashMap()
            return emptyList()
        }
        val sizes = OverlayPlanner.unify(entries.map { it.look.geometry.sizePx })
        // The page's own margin (how far its leftmost text is from the edge) is kept on both sides:
        // a long translation stops where the page's text would, not at the glass.
        val margin = entries.minOf { it.look.geometry.box.left - window.left }.coerceIn(0, window.width / 8)
        val inside = Box(window.left + margin, window.top, window.right - margin, window.bottom)
        val rooms = entries.map { room(it.look.geometry.box, others) }
        fun plans(words: List<String>) =
            OverlayPlanner.plan(entries.mapIndexed { i, entry -> item(entry.look, words[i], sizes[i], inside, rooms[i]) }, smallest)
        var words = entries.map { it.translation }
        var plans = plans(words)
        // A word cut or wrapped for want of room gives way to its shorter form, where it has one.
        val tight = entries.indices.filter { entries[it].shorter != null && (plans[it].ellipsize || plans[it].maxLines > entries[it].look.geometry.lines.size) }
        if (tight.isNotEmpty()) {
            words = words.mapIndexed { i, text -> if (i in tight) entries[i].shorter!! else text }
            plans = plans(words)
        }
        val texts = words
        val next = HashMap<Key, Built>()
        val out = entries.indices.mapNotNull { i ->
            val entry = entries[i]
            // Text in a picture that does not fit is left as it is: half a word over a photograph helps nobody.
            if (entry.picture && plans[i].ellipsize) return@mapNotNull null
            val key = Key(texts[i], entry.look, plans[i])
            val made = (built[key] ?: build(entry.look, texts[i], plans[i])).also { next[key] = it }
            // Nor is a text cut down to "Mua …" drawn: the Chinese left as it is says more.
            made.drawn.takeIf { made.shown >= LEAST_SHOWN }
        }
        built = next
        if (widths.size > 4_000) widths.clear()
        return out
    }

    /** How far the nearest other text is on each side of `box`: left, right, above, below. */
    private class Room(val left: Int, val right: Int, val above: Int, val below: Int)

    private fun room(box: Box, texts: List<Box>): Room {
        var left = Int.MAX_VALUE
        var right = Int.MAX_VALUE
        var above = Int.MAX_VALUE
        var below = Int.MAX_VALUE
        for (other in texts) {
            // The text itself, or one lying over it (a line read twice), is no neighbour.
            if (other.intersect(box) != null) continue
            val beside = other.top < box.bottom && other.bottom > box.top
            val stacked = other.left < box.right && other.right > box.left
            if (beside && other.left >= box.right) right = minOf(right, other.left - box.right)
            if (beside && other.right <= box.left) left = minOf(left, box.left - other.right)
            if (stacked && other.top >= box.bottom) below = minOf(below, other.top - box.bottom)
            if (stacked && other.bottom <= box.top) above = minOf(above, box.top - other.bottom)
        }
        return Room(left, right, above, below)
    }

    private fun item(look: Look, text: String, size: Float, window: Box, near: Room): OverlayPlanner.Item {
        val geometry = look.geometry
        val style = look.style
        val box = geometry.box
        // Over a picture there is no plain room to take: a little of the picture on each side.
        val side = box.width / 4
        // Nor does a translation spread onto other text, translated or not (a price under a badge).
        val vertical = if (style.plain) minOf(style.freeAbove, style.freeBelow) else box.height / 4
        return OverlayPlanner.Item(
            lines = geometry.lines,
            sizePx = size,
            freeLeft = minOf(if (style.plain) style.freeLeft else side, box.left - window.left, near.left).coerceAtLeast(0),
            freeRight = minOf(if (style.plain) style.freeRight else side, window.right - box.right, near.right).coerceAtLeast(0),
            freeVertical = minOf(vertical, near.above, near.below).coerceAtLeast(0),
            centerY = style.ink?.takeIf { geometry.lines.size == 1 }?.let { (it.top + it.bottom) / 2 } ?: ((box.top + box.bottom) / 2),
            // The same look: the same size, on the same colour, in the same colour.
            group = Objects.hash(Math.round(size), style.background shr 4 and 0x0F0F0F, style.text shr 5 and 0x070707, style.plain),
            measure = Ruler(text),
            align = align(look),
            guessed = look.guessed,
        )
    }

    /**
     * How the text is set, as far as the app's own view of it tells: in the middle of a view wider
     * than it (a tab, a button), at its end, or at its beginning; lines of a paragraph that begin
     * together. Null: the view is as wide as its text (or there is none), and says nothing.
     */
    private fun align(look: Look): OverlayPlanner.Align? {
        val lines = look.geometry.lines
        if (lines.size > 1) {
            val lefts = lines.maxOf { it.left } - lines.minOf { it.left }
            val rights = lines.maxOf { it.right } - lines.minOf { it.right }
            val middles = lines.maxOf { it.left + it.right } - lines.minOf { it.left + it.right }
            return when {
                lefts <= 2 -> OverlayPlanner.Align.START
                middles <= 4 -> OverlayPlanner.Align.CENTER
                rights <= 2 -> OverlayPlanner.Align.END
                else -> OverlayPlanner.Align.START
            }
        }
        val view = look.view ?: return null
        val box = look.geometry.box
        val size = look.geometry.sizePx
        val left = box.left - view.left
        val right = view.right - box.right
        if (left < 0 || right < 0 || left + right < size * 0.5f) return null
        return when {
            kotlin.math.abs(left - right) <= maxOf(3f, maxOf(left, right) * 0.1f) -> OverlayPlanner.Align.CENTER
            right <= size * 0.25f && left > size -> OverlayPlanner.Align.END
            left <= size * 0.5f -> OverlayPlanner.Align.START
            else -> null
        }
    }

    private inner class Ruler(private val text: String) : OverlayPlanner.Measure {
        override fun width(sizePx: Float, condensed: Boolean): Float {
            val both = widths.getOrPut(text) {
                ruler.textSize = RULER
                ruler.typeface = normal
                val wide = ruler.measureText(text)
                ruler.typeface = narrow
                floatArrayOf(wide, ruler.measureText(text))
            }
            // A hair more than measured: a text said to fit must not wrap for a rounded pixel.
            return both[if (condensed) 1 else 0] * sizePx / RULER * 1.01f + 1f
        }

        override fun lines(sizePx: Float, widthPx: Int, condensed: Boolean): Int =
            layout(text, paint(sizePx, condensed, 0), widthPx, OverlayPlanner.Align.START, Int.MAX_VALUE).lineCount

        override fun lineHeight(sizePx: Float): Float = sizePx * spacing

        override fun longestWord(sizePx: Float, condensed: Boolean): Float {
            val word = text.split(' ').maxByOrNull { it.length } ?: return 0f
            return if (word == text) width(sizePx, condensed) else Ruler(word).width(sizePx, condensed)
        }
    }

    private fun build(look: Look, text: String, plan: OverlayPlanner.Plan): Built {
        val layout = layout(text, paint(plan.sizePx, plan.condensed, colour(look.style)), plan.width, plan.align, plan.maxLines)
        val cut = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
        return Built(draw(look, layout, plan), if (text.isEmpty()) 1f else (text.length - cut).toFloat() / text.length)
    }

    private fun draw(look: Look, layout: StaticLayout, plan: OverlayPlanner.Plan): OverlayView.Drawn {
        val style = look.style
        val box = look.geometry.box
        val x = plan.left.toFloat()
        // One line sits with the middle of its capital letters on the middle of the Chinese ink.
        val y = if (layout.lineCount == 1) plan.centerY + plan.sizePx * CAPITAL_MIDDLE - layout.getLineBaseline(0)
        else plan.centerY - layout.height / 2f
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (i in 0 until layout.lineCount) {
            left = minOf(left, layout.getLineLeft(i))
            right = maxOf(right, layout.getLineRight(i))
        }
        val pad = maxOf(1f, plan.sizePx * 0.08f)
        val under = RectF(x + left - pad, y, x + right + pad, y + layout.height)
        if (!style.plain) {
            // Over a picture: one rounded label in the picture's own colour there.
            val around = maxOf(2f, plan.sizePx * 0.2f)
            val label = RectF(
                minOf(under.left, box.left.toFloat()) - around, minOf(under.top, box.top.toFloat()) - around / 2,
                maxOf(under.right, box.right.toFloat()) + around, maxOf(under.bottom, box.bottom.toFloat()) + around / 2,
            )
            return OverlayView.Drawn(listOf(label), style.background, null, plan.sizePx * 0.3f, layout, x, y)
        }
        // On a plain background: the Chinese is covered line by line, and the Vietnamese lies on the
        // same colour as far as that colour really goes above and below.
        // Two pixels at least: the soft edge of the Chinese letters is not quite ink, and must go too.
        val up = minOf(pad, style.freeAbove.toFloat()).coerceAtLeast(2f)
        val down = minOf(pad, style.freeBelow.toFloat()).coerceAtLeast(2f)
        val patches = ArrayList<RectF>()
        for (line in look.geometry.lines) patches.add(RectF(line.left - pad, line.top - up, line.right + pad, line.bottom + down))
        under.top = maxOf(under.top, box.top - style.freeAbove.toFloat())
        under.bottom = minOf(under.bottom, box.bottom + style.freeBelow.toFloat())
        if (under.top < under.bottom) patches.add(under)
        return OverlayView.Drawn(patches, style.background, run(style, box, patches), 0f, layout, x, y)
    }

    /**
     * A run of colour (across a button, or down a picture), carried on at the same rate under text
     * that is wider or taller than the Chinese.
     */
    private fun run(style: LineStyle, box: Box, patches: List<RectF>): Shader? {
        if (style.background == style.backgroundEnd) return null
        val length = if (style.down) box.height else box.width
        if (length < 2) return null
        val from = if (style.down) patches.minOf { it.top } else patches.minOf { it.left }
        val to = if (style.down) patches.maxOf { it.bottom } else patches.maxOf { it.right }
        val origin = if (style.down) box.top else box.left
        fun at(place: Float): Int {
            val part = (place - origin) / (length - 1)
            fun channel(shift: Int): Int {
                val a = style.background shr shift and 0xFF
                val b = style.backgroundEnd shr shift and 0xFF
                return Math.round(a + (b - a) * part).coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        return if (style.down) LinearGradient(0f, from, 0f, to, at(from), at(to), Shader.TileMode.CLAMP)
        else LinearGradient(from, 0f, to, 0f, at(from), at(to), Shader.TileMode.CLAMP)
    }

    /** The Chinese's own colour where it reads well on the background, black or white if not. */
    private fun colour(style: LineStyle): Int {
        val reads = OverlayFit.contrast(style.text, style.background) >= 2.5 && OverlayFit.contrast(style.text, style.backgroundEnd) >= 2.5
        return if (style.plain && reads) style.text else OverlayFit.textColor(style.background)
    }

    private fun paint(size: Float, condensed: Boolean, colour: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (condensed) narrow else normal
        color = colour
    }

    private fun layout(text: String, paint: TextPaint, width: Int, align: OverlayPlanner.Align, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(
                when (align) {
                    OverlayPlanner.Align.START -> Layout.Alignment.ALIGN_NORMAL
                    OverlayPlanner.Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                    OverlayPlanner.Align.END -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    private companion object {
        const val RULER = 100f
        /** A translation of which less than this part can be shown is not drawn at all. */
        const val LEAST_SHOWN = 0.6f
        /** Half the height of a capital letter, in text sizes: where the middle of a line of Latin text is. */
        const val CAPITAL_MIDDLE = 0.36f
    }
}
