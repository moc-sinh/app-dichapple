package vn.mocsinh.mstrans.core.screen

import kotlin.math.pow

/** Sizing and colouring a translation inside the box of the Chinese it covers. */
object OverlayFit {
    /** `maxLines` is Int.MAX_VALUE unless the text had to be cut ("…"). */
    data class Fit(val sizePx: Float, val maxLines: Int, val ellipsized: Boolean)

    /**
     * The largest text size in [minPx, maxPx] whose wrapped height fits `height`. `measure(size)` is
     * the height at that size, wrapped to the box's width (so it never spills sideways). Too long
     * even at `minPx`: the smallest size, cut to the lines that fit.
     */
    fun fit(height: Int, minPx: Float, maxPx: Float, lineHeight: (Float) -> Float, measure: (Float) -> Int): Fit {
        if (measure(minPx) > height) {
            val lines = (height / lineHeight(minPx)).toInt().coerceAtLeast(1)
            return Fit(minPx, lines, true)
        }
        var low = minPx
        var high = maxOf(minPx, maxPx)
        if (measure(high) <= height) return Fit(high, Int.MAX_VALUE, false)
        repeat(12) {
            val middle = (low + high) / 2
            if (measure(middle) <= height) low = middle else high = middle
        }
        return Fit(low, Int.MAX_VALUE, false)
    }

    /** Black or white, whichever reads better on `background` (ARGB). */
    fun textColor(background: Int): Int {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return if (contrast(background, black) >= contrast(background, white)) black else white
    }

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
