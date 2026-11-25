package vn.mocsinh.mstrans.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * A screenshot for reading text in pictures, in memory only (never written anywhere).
 * Android 14+: the app's window alone, without MSTrans's overlay. Android 11 to 13: the whole
 * screen, with the overlay hidden for the moment of the shot. Android 8 to 10: none.
 */
class ScreenCapture(private val service: AccessibilityService) {
    sealed interface Shot {
        /** `left`, `top`: where the bitmap's corner is on the screen. */
        class Image(val bitmap: Bitmap, val left: Int, val top: Int) : Shot
        /** The app protects this window (FLAG_SECURE): nothing on it is translated. */
        data object Secure : Shot
        data object Failed : Shot
    }

    private val executor = Executors.newSingleThreadExecutor()

    suspend fun take(windowId: Int, windowLeft: Int, windowTop: Int, overlay: OverlayController): Shot {
        if (Build.VERSION.SDK_INT >= 34) return window(windowId, windowLeft, windowTop)
        if (Build.VERSION.SDK_INT < 30) return Shot.Failed
        withContext(Dispatchers.Main) { overlay.hideForCapture() }
        return try {
            // Two frames for the hidden overlay to leave the screen.
            delay(40)
            display()
        } finally {
            withContext(Dispatchers.Main) { overlay.showAfterCapture() }
        }
    }

    @RequiresApi(34)
    private suspend fun window(windowId: Int, left: Int, top: Int): Shot = suspendCancellableCoroutine { continuation ->
        service.takeScreenshotOfWindow(windowId, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                continuation.resume(image(result, left, top))
            }

            override fun onFailure(errorCode: Int) {
                continuation.resume(failure(errorCode))
            }
        })
    }

    @RequiresApi(30)
    private suspend fun display(): Shot = suspendCancellableCoroutine { continuation ->
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                continuation.resume(image(result, 0, 0))
            }

            override fun onFailure(errorCode: Int) {
                continuation.resume(failure(errorCode))
            }
        })
    }

    @RequiresApi(30)
    private fun failure(errorCode: Int): Shot =
        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) Shot.Secure else Shot.Failed

    /** Copied to an ordinary bitmap at once, the hardware buffer closed. */
    @RequiresApi(30)
    private fun image(result: AccessibilityService.ScreenshotResult, left: Int, top: Int): Shot {
        val buffer = result.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return Shot.Failed
            val copy = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            if (copy == null) Shot.Failed else Shot.Image(copy, left, top)
        } finally {
            buffer.close()
        }
    }

    fun close() = executor.shutdown()
}
