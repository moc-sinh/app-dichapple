package vn.mocsinh.mstrans.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.StaticLayout
import android.view.View

/**
 * Every translation on the screen, drawn by one full-screen view. Each one covers its Chinese with
 * the very colour behind it (so no patch shows) and is written in the Chinese's own colour and
 * size; `OverlayComposer` decides where.
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : View(context) {
    /**
     * One translation, laid out (built off the main thread), in screen pixels. `patches` are filled
     * with `background` (or `shader`, a button's run of colour); `radius` rounds them (a label over
     * a picture). The text's corner is at `x`, `y`.
     */
    class Drawn(
        val patches: List<RectF>,
        val background: Int,
        val shader: Shader?,
        val radius: Float,
        val layout: StaticLayout,
        val x: Float,
        val y: Float,
    )

    private var drawn: List<Drawn> = emptyList()
    private val location = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    val isEmpty: Boolean get() = drawn.isEmpty()
    val count: Int get() = drawn.size

    /** All the translations of the screen at once: what stays does not move or blink. */
    fun set(items: List<Drawn>) {
        val wasEmpty = drawn.isEmpty()
        drawn = items
        if (wasEmpty && items.isNotEmpty()) fadeIn()
        invalidate()
    }

    fun clear() {
        if (drawn.isEmpty()) return
        animate().cancel()
        drawn = emptyList()
        invalidate()
    }

    private fun fadeIn() {
        animate().cancel()
        alpha = 0f
        animate().alpha(1f).setDuration(120).start()
    }

    override fun onDraw(canvas: Canvas) {
        if (drawn.isEmpty()) return
        // Everything is in screen pixels; the view may not start at the screen's corner.
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        // Backgrounds first, so one translation's background never goes over another's letters.
        for (item in drawn) {
            fill.shader = item.shader
            fill.color = item.background
            for (patch in item.patches) {
                if (item.radius > 0f) canvas.drawRoundRect(patch, item.radius, item.radius, fill) else canvas.drawRect(patch, fill)
            }
        }
        fill.shader = null
        for (item in drawn) {
            canvas.save()
            canvas.translate(item.x, item.y)
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
