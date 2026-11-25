package vn.mocsinh.mstrans.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import vn.mocsinh.mstrans.core.screen.Box
import vn.mocsinh.mstrans.core.screen.OverlayFit

/**
 * Every translation on the screen, drawn by one full-screen view: each over the box of its Chinese,
 * on a background like the one around it, wrapped and shrunk to fit, cut with "…" rather than
 * spilling over the buttons next to it.
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : View(context) {
    /** One translation, laid out (built off the main thread). */
    class Drawn(val box: Box, val layout: StaticLayout, val background: Int, val top: Float)

    private val drawn = ArrayList<Drawn>()
    private val location = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics)

    val isEmpty: Boolean get() = drawn.isEmpty()
    val count: Int get() = drawn.size

    fun set(items: List<Drawn>) {
        val wasEmpty = drawn.isEmpty()
        drawn.clear()
        drawn.addAll(items)
        if (wasEmpty && items.isNotEmpty()) fadeIn()
        invalidate()
    }

    fun add(items: List<Drawn>) {
        if (items.isEmpty()) return
        val wasEmpty = drawn.isEmpty()
        drawn.addAll(items)
        if (wasEmpty) fadeIn()
        invalidate()
    }

    fun clear() {
        if (drawn.isEmpty()) return
        animate().cancel()
        drawn.clear()
        invalidate()
    }

    private fun fadeIn() {
        animate().cancel()
        alpha = 0f
        animate().alpha(1f).setDuration(120).start()
    }

    override fun onDraw(canvas: Canvas) {
        if (drawn.isEmpty()) return
        // Boxes are in screen pixels; the view may not start at the screen's corner.
        getLocationOnScreen(location)
        for (item in drawn) {
            val box = item.box
            canvas.save()
            canvas.translate((box.left - location[0]).toFloat(), (box.top - location[1]).toFloat())
            canvas.clipRect(0, 0, box.width, box.height)
            fill.color = item.background
            rect.set(0f, 0f, box.width.toFloat(), box.height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.translate(PADDING_PX.toFloat(), item.top)
            item.layout.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        const val PADDING_PX = 2

        /** Lays `text` out in `box`: size between 9 sp and the box's own text height. */
        fun layout(context: Context, box: Box, text: String, background: Int): Drawn? {
            val width = box.width - 2 * PADDING_PX
            if (width < 8 || box.height < 8) return null
            val metrics = context.resources.displayMetrics
            val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, metrics)
            val maxPx = minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, metrics), box.height * 0.8f).coerceAtLeast(minPx)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = OverlayFit.textColor(background or 0xFF000000.toInt()) }
            fun build(size: Float, lines: Int = Int.MAX_VALUE): StaticLayout {
                paint.textSize = size
                return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
            }
            val fit = OverlayFit.fit(
                height = box.height,
                minPx = minPx,
                maxPx = maxPx,
                lineHeight = { size -> paint.textSize = size; paint.fontSpacing },
                measure = { size -> build(size).height },
            )
            val layout = build(fit.sizePx, fit.maxLines)
            val top = ((box.height - layout.height) / 2f).coerceAtLeast(0f)
            return Drawn(box, layout, background, top)
        }
    }
}
