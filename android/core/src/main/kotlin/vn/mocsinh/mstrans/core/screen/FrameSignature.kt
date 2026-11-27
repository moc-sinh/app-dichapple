package vn.mocsinh.mstrans.core.screen

import kotlin.math.abs

/**
 * A screenshot in 24 × 48 brightness cells: text in pictures is read again only when the picture
 * changed (reading it is the costliest step).
 */
class FrameSignature private constructor(val cells: ByteArray) {
    fun differs(other: FrameSignature?): Boolean {
        if (other == null || other.cells.size != cells.size) return true
        var total = 0L
        var changed = 0
        for (i in cells.indices) {
            val d = abs((cells[i].toInt() and 0xFF) - (other.cells[i].toInt() and 0xFF))
            total += d
            if (d > 24) changed++
        }
        return changed * 100 > cells.size || total > cells.size * 6L
    }

    companion object {
        const val COLUMNS = 24
        const val ROWS = 48

        /** `pixel(x, y)` is an ARGB colour. Four samples per cell. */
        fun of(width: Int, height: Int, pixel: (Int, Int) -> Int): FrameSignature {
            val cells = ByteArray(COLUMNS * ROWS)
            for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
                var sum = 0
                for (sy in 0..1) for (sx in 0..1) {
                    val x = ((column + 0.25 + sx * 0.5) * width / COLUMNS).toInt().coerceIn(0, width - 1)
                    val y = ((row + 0.25 + sy * 0.5) * height / ROWS).toInt().coerceIn(0, height - 1)
                    sum += luma(pixel(x, y))
                }
                cells[row * COLUMNS + column] = (sum / 4).toByte()
            }
            return FrameSignature(cells)
        }

        private fun luma(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (299 * r + 587 * g + 114 * b) / 1000
        }
    }
}
