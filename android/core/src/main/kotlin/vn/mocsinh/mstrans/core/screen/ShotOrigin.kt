package vn.mocsinh.mstrans.core.screen

import kotlin.math.abs

/**
 * Where the corner of a window's screenshot lies on the screen. Taken for granted to be the
 * window's corner, it is not for a window with a shadow (a dialog, Taobao's "choose a variant"
 * sheet): Android draws such a window on a surface larger than the window by the same on every
 * side, for the shadow, and the screenshot is of that surface. Everything read from it was then
 * drawn that much lower and to the right of where it is.
 */
object ShotOrigin {
    private const val SLACK = 2

    /** `width` × `height`: the picture; `window`: the window's bounds; `screen`: the display's. */
    fun of(width: Int, height: Int, window: Box, screen: Box): Pair<Int, Int> {
        val wider = width - window.width
        val taller = height - window.height
        if (abs(wider) <= SLACK && abs(taller) <= SLACK) return window.left to window.top
        // Some phones give the whole display for a window.
        if (abs(width - screen.width) <= SLACK && abs(height - screen.height) <= SLACK) return screen.left to screen.top
        // Room for a shadow, the same on each side.
        if (wider >= 0 && taller >= 0) return (window.left - wider / 2) to (window.top - taller / 2)
        return window.left to window.top
    }
}
