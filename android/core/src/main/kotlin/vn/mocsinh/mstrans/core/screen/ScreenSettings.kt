package vn.mocsinh.mstrans.core.screen

import vn.mocsinh.mstrans.core.KeyValueStore

/**
 * How often the screen is read: the least time between two scans of a page, the wait after a
 * change on it, the wait after the last movement of a list (Android tells of a list moving every
 * 100 ms: silence a little longer than that means it rests), and the least time between two
 * readings of pictures when only the content changed (a moving banner would otherwise keep the
 * costliest step running). A new page or a scroll reads pictures at once.
 */
enum class Speed(val label: String, val minGapMs: Long, val settleMs: Long, val scrollMs: Long, val imageGapMs: Long) {
    SAVER("Tiết kiệm pin", 1_500, 450, 300, 5_000),
    BALANCED("Cân bằng", 700, 300, 160, 2_500),
    FAST("Nhanh", 300, 250, 130, 1_200),
}

/** Cài đặt › Dịch màn hình, in the same store as the other settings. Off until the user turns it on. */
class ScreenSettings(private val store: KeyValueStore) {
    enum class Mode(val label: String) {
        OFFLINE("Ngoại tuyến"),
        AI("AI nâng cao"),
    }

    data class App(val packageName: String, val label: String)

    object Key {
        const val ENABLED = "screenEnabled"
        const val APPS = "screenApps"
        const val MODE = "screenMode"
        const val SPEED = "screenSpeed"
        const val READ_IMAGES = "screenReadImages"
        const val FLOATING_BUTTON = "screenFloatingButton"
        const val PAUSED = "screenPaused"
        const val WIFI_ONLY = "screenWifiOnly"
        const val AI_LIMIT = "screenAiLimit"
        const val BUTTON_Y = "screenButtonY"
        const val AUTO_OFF = "screenAutoOff"
        const val AI_BEST = "screenAiBest"
    }

    private fun bool(key: String, default: Boolean) = store.get(key)?.toBooleanStrictOrNull() ?: default

    var enabled: Boolean
        get() = bool(Key.ENABLED, false)
        set(value) = store.set(Key.ENABLED, value.toString())

    /** Packages translated. Not set yet: Taobao. */
    var apps: Set<String>
        get() = store.get(Key.APPS)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: setOf(TAOBAO)
        set(value) = store.set(Key.APPS, value.sorted().joinToString("\n"))

    var mode: Mode
        get() = Mode.entries.firstOrNull { it.name == store.get(Key.MODE) } ?: Mode.OFFLINE
        set(value) = store.set(Key.MODE, value.name)

    var speed: Speed
        get() = Speed.entries.firstOrNull { it.name == store.get(Key.SPEED) } ?: Speed.BALANCED
        set(value) = store.set(Key.SPEED, value.name)

    /** Read Chinese in pictures too (Android 11+): the costliest part. */
    var readImages: Boolean
        get() = bool(Key.READ_IMAGES, true)
        set(value) = store.set(Key.READ_IMAGES, value.toString())

    var floatingButton: Boolean
        get() = bool(Key.FLOATING_BUTTON, true)
        set(value) = store.set(Key.FLOATING_BUTTON, value.toString())

    var paused: Boolean
        get() = bool(Key.PAUSED, false)
        set(value) = store.set(Key.PAUSED, value.toString())

    /** The translation pack downloads on Wi-Fi only. */
    var wifiOnly: Boolean
        get() = bool(Key.WIFI_ONLY, true)
        set(value) = store.set(Key.WIFI_ONLY, value.toString())

    /** AI requests allowed in any 10 minutes. */
    var aiLimit: Int
        get() = store.get(Key.AI_LIMIT)?.toIntOrNull()?.coerceIn(1, 200) ?: 30
        set(value) = store.set(Key.AI_LIMIT, value.toString())

    /** Where the floating button was left, as a fraction of the screen height. */
    /**
     * Leaving the chosen apps for another app turns the accessibility permission itself off: an app
     * that looks for accessibility services (a bank's) finds none. Going home or to recent apps is
     * not leaving. On by default.
     */
    var autoOff: Boolean
        get() = bool(Key.AUTO_OFF, true)
        set(value) = store.set(Key.AUTO_OFF, value.toString())

    /** "AI nâng cao" with the better, dearer model (GPT-6.1 Sol) instead of the quick one. */
    var aiBest: Boolean
        get() = bool(Key.AI_BEST, false)
        set(value) = store.set(Key.AI_BEST, value.toString())

    var buttonY: Float
        get() = store.get(Key.BUTTON_Y)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.35f
        set(value) = store.set(Key.BUTTON_Y, value.toString())

    fun isSelected(pkg: String?): Boolean = pkg != null && pkg != OWN && pkg in apps

    companion object {
        const val TAOBAO = "com.taobao.taobao"
        const val OWN = "vn.mocsinh.mstrans"

        val SUGGESTED = listOf(
            App(TAOBAO, "Taobao"),
            App("com.alibaba.wireless", "1688"),
            App("com.xunmeng.pinduoduo", "Pinduoduo"),
            App("com.jingdong.app.mall", "JD"),
            App("com.tencent.mm", "WeChat"),
            App("com.alibaba.intl.android.apps.poseidon", "Alibaba.com"),
        )
    }
}
