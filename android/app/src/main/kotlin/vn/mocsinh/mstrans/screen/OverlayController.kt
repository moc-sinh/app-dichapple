package vn.mocsinh.mstrans.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * The overlay window: an accessibility overlay (no "draw over other apps" permission) that touches
 * pass through to the app below, and that Android 12+ does not block as an untrusted window.
 * Main thread only.
 */
class OverlayController(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    val view = OverlayView(service)
    private var attached = false
    /** The scan whose translations are shown. */
    private var shownGeneration = -1L

    /** The floating button's tap: the Chinese as it is, translations hidden. */
    var showingOriginal = false
        set(value) {
            field = value
            view.visibility = if (value) View.INVISIBLE else View.VISIBLE
        }

    val isEmpty: Boolean get() = view.isEmpty
    val count: Int get() = view.count

    /**
     * Translations of scan `generation`: the first ones of a scan replace what was there (so an older
     * page's translations go), later ones join them. `last`: the scan is done, even with nothing new.
     */
    fun show(generation: Long, items: List<OverlayView.Drawn>, last: Boolean = false) {
        if (generation != shownGeneration) {
            if (items.isEmpty() && !last) return
            shownGeneration = generation
            attach()
            view.set(items)
        } else {
            view.add(items)
        }
    }

    fun clear() {
        shownGeneration = -1L
        view.clear()
    }

    fun hideForCapture() {
        view.visibility = View.INVISIBLE
    }

    fun showAfterCapture() {
        view.visibility = if (showingOriginal) View.INVISIBLE else View.VISIBLE
    }

    fun remove() {
        clear()
        if (attached) runCatching { windows.removeViewImmediate(view) }
        attached = false
    }

    private fun attach() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        params.title = "MSTrans dịch màn hình"
        runCatching { windows.addView(view, params) }.onSuccess { attached = true }.onFailure {
            ScreenState.error("Không hiện được lớp dịch: ${it.javaClass.simpleName}")
        }
    }
}
