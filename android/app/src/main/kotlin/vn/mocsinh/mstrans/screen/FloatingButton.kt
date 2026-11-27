package vn.mocsinh.mstrans.screen

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.core.screen.ScreenSettings
import vn.mocsinh.mstrans.ui.dp
import kotlin.math.abs

/**
 * The small round button at the screen's edge, over the translated app: tap to see the Chinese /
 * the translation again, hold to pause / go on, drag up or down to move it. Main thread only.
 */
class FloatingButton(
    private val service: AccessibilityService,
    private val settings: ScreenSettings,
    private val onTap: () -> Unit,
    private val onHold: () -> Unit,
    /**
     * A finger came down somewhere else on the screen (only that: Android does not say where, nor
     * what was touched). Text the app draws itself may be about to move without telling.
     */
    private val onOutside: () -> Unit = {},
) {
    enum class Look { TRANSLATING, ORIGINAL, PAUSED }

    private val windows = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val size = service.dp(44)
    private val view = ImageView(service)
    private var attached = false
    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = service.dp(6)
        title = "MSTrans nút dịch"
    }

    init {
        view.setImageDrawable(service.getDrawable(R.drawable.logo))
        val padding = service.dp(8)
        view.setPadding(padding, padding, padding, padding)
        view.elevation = service.dp(4).toFloat()
        listen()
    }

    fun show(look: Look) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (look == Look.PAUSED) 0xE0606060.toInt() else 0xF2FFFFFF.toInt())
            setStroke(service.dp(2), if (look == Look.TRANSLATING) 0xFF0B6542.toInt() else 0xFFB9461A.toInt())
        }
        view.alpha = if (look == Look.PAUSED) 0.75f else 1f
        view.contentDescription = when (look) {
            Look.TRANSLATING -> "MSTrans đang dịch. Chạm để xem chữ gốc, giữ để tạm dừng"
            Look.ORIGINAL -> "MSTrans đang hiện chữ gốc. Chạm để hiện bản dịch"
            Look.PAUSED -> "MSTrans đã tạm dừng. Giữ để dịch tiếp"
        }
        if (attached) return
        params.y = (service.resources.displayMetrics.heightPixels * settings.buttonY).toInt()
        runCatching { windows.addView(view, params) }.onSuccess { attached = true }
    }

    fun hide() {
        if (!attached) return
        runCatching { windows.removeViewImmediate(view) }
        attached = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun listen() {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        var downY = 0f
        var startY = 0
        var dragging = false
        var held = false
        val hold = Runnable {
            held = true
            onHold()
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                // A touch elsewhere: it goes on to the app as ever (this window is not focusable).
                MotionEvent.ACTION_OUTSIDE -> {
                    onOutside()
                    return@setOnTouchListener false
                }
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = params.y
                    dragging = false
                    held = false
                    main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (!dragging && abs(dy) > slop) {
                        dragging = true
                        main.removeCallbacks(hold)
                    }
                    if (dragging && attached) {
                        val height = service.resources.displayMetrics.heightPixels
                        params.y = (startY + dy).toInt().coerceIn(0, height - size)
                        runCatching { windows.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(hold)
                    val height = service.resources.displayMetrics.heightPixels
                    if (dragging) settings.buttonY = params.y.toFloat() / height
                    else if (!held) {
                        view.performClick()
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(hold)
            }
            true
        }
    }
}
