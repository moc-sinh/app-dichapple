package vn.mocsinh.mstrans.screen

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.core.screen.ScreenSettings

/** What Cài đặt › Dịch màn hình shows of the running service. Counts and times only, never screen text. */
object ScreenState {
    @Volatile var service: ScreenTranslationService? = null
    /** "Đang dịch", "Tạm dừng: bàn phím đang mở"… */
    @Volatile var status: String = ""
    @Volatile var lastError: String? = null
    @Volatile var lastErrorAt: Long = 0
    /** "180 ms · 32 dòng · 6 trong ảnh". */
    @Volatile var lastScan: String? = null
    @Volatile var modelReady: Boolean? = null

    /** The settings page while it is open (main thread). */
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun changed() {
        main.post { listener?.invoke() }
    }

    fun error(text: String) {
        lastError = text
        lastErrorAt = System.currentTimeMillis()
        changed()
    }

    fun settings(context: Context) = ScreenSettings(MSTransApp.of(context).store)

    /** MSTrans · Dịch màn hình is turned on in Android's accessibility settings. */
    fun permitted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val ours = ComponentName(context, ScreenTranslationService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }
}
