package vn.mocsinh.mstrans.screen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.core.Settings
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.Translator
import vn.mocsinh.mstrans.core.screen.Box
import vn.mocsinh.mstrans.core.screen.ChineseText
import vn.mocsinh.mstrans.core.screen.FrameSignature
import vn.mocsinh.mstrans.core.screen.ScreenAi
import vn.mocsinh.mstrans.core.screen.ScreenController
import vn.mocsinh.mstrans.core.screen.ScreenController.Action
import vn.mocsinh.mstrans.core.screen.ScreenController.Event
import vn.mocsinh.mstrans.core.screen.ScreenSettings
import vn.mocsinh.mstrans.core.screen.ScreenText
import vn.mocsinh.mstrans.core.screen.ScreenTextMerger
import vn.mocsinh.mstrans.core.screen.ScreenTranslator
import vn.mocsinh.mstrans.core.screen.SensitiveScreen
import vn.mocsinh.mstrans.core.screen.TextEngine

/**
 * Dịch màn hình: reads the Chinese of the apps the user chose (accessibility tree, and pictures on
 * Android 11+) and lays the Vietnamese over it. Runs only while the switch in Cài đặt › Dịch màn
 * hình is on. Never taps, types or scrolls for the user; never reads another app's content; keeps
 * screenshots in memory only; logs times and counts, never text.
 */
class ScreenTranslationService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: ScreenSettings
    private lateinit var appSettings: Settings
    private lateinit var controller: ScreenController
    private lateinit var overlay: OverlayController
    private lateinit var capture: ScreenCapture
    private var button: FloatingButton? = null
    @Volatile private var engines: MlKitEngines? = null
    private val translator = ScreenTranslator(engine = TextEngine { text -> engines?.engine?.translate(text) })
    @Volatile private var ai: ScreenAi? = null
    private var aiKey: String? = null
    private var glossary: String? = null
    private var running = false
    private var connected = false
    /** The app in front, for the floating button (known while paused too). */
    private var foreground: String? = null
    private var imePackages = emptySet<String>()
    private var receiver: BroadcastReceiver? = null
    private var orientation = Configuration.ORIENTATION_UNDEFINED

    // Scans
    private var scanJob: Job? = null
    private var pending: Action.Scan? = null
    private var pendingSince = 0L
    private val scanNow = Runnable {
        val scan = pending ?: return@Runnable
        pending = null
        startScan(scan.generation)
    }
    /** A new page, a scroll, a turn: read pictures now, not after `Speed.imageGapMs`. */
    @Volatile private var imagesDue = true
    @Volatile private var lastFrame: FrameSignature? = null
    @Volatile private var imageText: List<ScreenText> = emptyList()
    @Volatile private var lastImageAt = 0L
    @Volatile private var lastLayout = 0
    /** The window that refused a screenshot (FLAG_SECURE): nothing of it is translated. */
    @Volatile private var secureWindow: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = MSTransApp.of(this)
        appSettings = app.settings
        settings = ScreenSettings(app.store)
        controller = ScreenController(ownPackage = packageName, speed = settings.speed)
        overlay = OverlayController(this)
        capture = ScreenCapture(this)
        orientation = resources.configuration.orientation
        listenToScreen()
        connected = true
        ScreenState.service = this
        refresh()
    }

    /** Settings changed (from the settings page or the floating button): apply them now. */
    fun refresh() {
        if (!connected) return
        controller.speed = settings.speed
        val words = appSettings.glossary
        if (words != glossary) {
            glossary = words
            translator.setGlossary(words)
        }
        updateAi()
        imePackages = getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        val run = settings.enabled && !settings.paused
        if (run && !running) {
            running = true
            val ml = engines ?: MlKitEngines().also { engines = it }
            scope.launch {
                runCatching { ml.checkModel() }
                ScreenState.changed()
            }
            apply(controller.handle(Event.Started, settings::isSelected))
            // Already in a chosen app (turned on from the floating button): start there.
            rootInActiveWindow?.packageName?.toString()?.let { pkg ->
                foreground = pkg
                imagesDue = true
                apply(controller.handle(Event.WindowState(pkg), settings::isSelected))
            }
        } else if (!run && running) {
            running = false
            apply(controller.handle(Event.Stopped, settings::isSelected))
            engines?.close()
            engines = null
        } else if (run) {
            // Apps or options changed: read the screen again (or clear it if its app is no longer chosen).
            imagesDue = true
            apply(controller.handle(Event.WindowState(controller.foreground), settings::isSelected))
        }
        status()
        updateButton()
    }

    /** The language pack was downloaded or deleted. */
    fun modelChanged() {
        val ml = engines ?: return
        scope.launch {
            runCatching { ml.checkModel() }
            translator.clearCache()
            main.post {
                imagesDue = true
                if (running) apply(controller.handle(Event.WindowState(controller.foreground), settings::isSelected))
                status()
            }
        }
    }

    private fun updateAi() {
        val key = appSettings.apiKey?.takeIf { settings.mode == ScreenSettings.Mode.AI }
        val tag = key?.let { "$it/${settings.aiLimit}" }
        if (tag == aiKey) return
        aiKey = tag
        ai = key?.let {
            val translator = Translator(it, TranslationModel.LUNA, appSettings)
            ScreenAi(write = { instructions, input -> translator.write(instructions, input).first }, limitPer10Minutes = settings.aiLimit)
        }
    }

    // Events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !connected || !settings.enabled) return
        val pkg = event.packageName?.toString()
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && pkg !in imePackages && pkg != foreground) {
            foreground = pkg
            updateButton()
        }
        if (!running) return
        val translated = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (pkg != null && pkg in imePackages && keyboardUp()) Event.WindowsChanged(true)
                else {
                    secureWindow = null
                    imagesDue = true
                    Event.WindowState(pkg)
                }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                imagesDue = true
                Event.Scrolled(pkg)
            }
            // The scan works out whether the Chinese changed (cheaper than reading the tree twice).
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> Event.ContentChanged(pkg, null)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                if (settings.isSelected(controller.foreground)) Event.WindowsChanged(keyboardUp()) else return
            else -> return
        }
        apply(controller.handle(translated, settings::isSelected))
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == orientation) return
        orientation = newConfig.orientation
        if (!connected) return
        imagesDue = true
        lastFrame = null
        apply(controller.handle(Event.Rotated, settings::isSelected))
        button?.let { updateButton() }
    }

    private fun listenToScreen() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!connected) return
                val event = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> Event.ScreenOff
                    Intent.ACTION_USER_PRESENT -> Event.ScreenOn
                    Intent.ACTION_SCREEN_ON -> if (keyguard?.isKeyguardLocked == true) return else Event.ScreenOn
                    else -> return
                }
                imagesDue = true
                apply(controller.handle(event, settings::isSelected))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screen, filter)
        receiver = screen
    }

    private fun keyboardUp(): Boolean = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    /** Main thread. */
    private fun apply(actions: List<Action>) {
        for (action in actions) when (action) {
            Action.Clear -> overlay.clear()
            Action.CancelScan -> {
                main.removeCallbacks(scanNow)
                pending = null
                scanJob?.cancel()
            }
            Action.ClearCache -> {
                translator.clearCache()
                lastFrame = null
                imageText = emptyList()
                lastLayout = 0
                secureWindow = null
            }
            is Action.Scan -> schedule(action)
        }
        if (actions.isNotEmpty()) updateButton()
    }

    /** Waits for the screen to settle, but never longer than `MAX_WAIT_MS` (a moving banner never settles). */
    private fun schedule(scan: Action.Scan) {
        val now = SystemClock.uptimeMillis()
        if (pending == null) pendingSince = now
        pending = scan
        main.removeCallbacks(scanNow)
        main.postAtTime(scanNow, minOf(now + scan.delayMs, pendingSince + MAX_WAIT_MS).coerceAtLeast(now))
    }

    private fun startScan(generation: Long) {
        scanJob?.cancel()
        val images = imagesDue
        imagesDue = false
        scanJob = scope.launch {
            try {
                scan(generation, images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScreenState.error("Lỗi khi đọc màn hình (${e.javaClass.simpleName}).")
                Log.w(TAG, "scan failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // Reading the screen (off the main thread)

    private class Tree(val nodes: List<ScreenText>, val texts: List<String>, val password: Boolean, val editable: Boolean, val visited: Int)

    private suspend fun scan(generation: Long, imagesNow: Boolean) {
        if (!controller.isCurrent(generation)) return
        controller.scanStarted(generation)
        val started = SystemClock.elapsedRealtime()
        val all = windows
        if (all.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            onMain { apply(controller.handle(Event.WindowsChanged(true), settings::isSelected)) }
            return
        }
        val window = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        val root = window?.root
        // Only the package name of a window not chosen is looked at, never its content.
        if (window == null || root == null || !settings.isSelected(root.packageName?.toString())) {
            onMain { if (controller.isCurrent(generation)) overlay.clear() }
            return
        }
        if (window.id == secureWindow) {
            pause(generation, SensitiveScreen.Reason.SECURE_WINDOW)
            return
        }
        val windowBox = window.box()
        val screenArea = resources.displayMetrics.let { it.widthPixels.toLong() * it.heightPixels }
        // Status and navigation bars, small system windows above the app.
        val excluded = all.filter {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.layer > window.layer && it.box().area * 2 < screenArea
        }.map { it.box() }

        val tree = read(root)
        SensitiveScreen.check(tree.texts, passwordField = tree.password, editableField = tree.editable)?.let {
            pause(generation, it)
            return
        }
        val layout = layoutSignature(tree.nodes)
        val readAt = SystemClock.elapsedRealtime()

        // Text in pictures.
        var shot: ScreenCapture.Shot.Image? = null
        var images = imageText
        val ml = engines
        val imageGap = settings.speed.imageGapMs
        if (ml != null && settings.readImages && Build.VERSION.SDK_INT >= 30 && (imagesNow || readAt - lastImageAt >= imageGap)) {
            when (val taken = capture.take(window.id, windowBox.left, windowBox.top, overlay)) {
                ScreenCapture.Shot.Secure -> {
                    secureWindow = window.id
                    pause(generation, SensitiveScreen.Reason.SECURE_WINDOW)
                    return
                }
                is ScreenCapture.Shot.Image -> {
                    shot = taken
                    val bitmap = taken.bitmap
                    val frame = FrameSignature.of(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
                    if (frame.differs(lastFrame)) {
                        images = ml.read(bitmap, taken.left, taken.top)
                        imageText = images
                        lastFrame = frame
                    }
                    lastImageAt = readAt
                }
                ScreenCapture.Shot.Failed -> Unit
            }
        }
        val imagesAt = SystemClock.elapsedRealtime()
        try {
            if (!controller.isCurrent(generation)) return
            val same = layout == lastLayout && images === shownImages
            if (same && onMain { !overlay.isEmpty }) {
                controller.scanFinished(generation, layout)
                return
            }
            val merged = ScreenTextMerger.merge(tree.nodes, images, windowBox, excluded)
            val backgrounds = merged.associateWith { background(shot, it.bounds) }
            shot?.bitmap?.recycle()
            shot = null
            draw(generation, merged, backgrounds)
            lastLayout = layout
            shownImages = images
            controller.scanFinished(generation, layout)
            val done = SystemClock.elapsedRealtime()
            ScreenState.lastScan = "${done - started} ms · ${merged.size} dòng chữ Trung · ${images.size} dòng trong ảnh"
            ScreenState.status = "Đang dịch"
            ScreenState.changed()
            Log.d(TAG, "scan ${done - started} ms: tree ${readAt - started} ms (${tree.visited} nodes), " +
                "images ${imagesAt - readAt} ms, translate ${done - imagesAt} ms, ${merged.size} lines, ${images.size} from images")
        } finally {
            shot?.bitmap?.recycle()
        }
    }

    @Volatile private var shownImages: List<ScreenText>? = null

    private fun read(root: AccessibilityNodeInfo): Tree {
        val nodes = ArrayList<ScreenText>()
        val texts = ArrayList<String>()
        var password = false
        var editable = false
        var visited = 0
        val rect = Rect()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            visited++
            if (visited <= MAX_NODES && node.isVisibleToUser) {
                if (node.isPassword) password = true
                if (node.isEditable) editable = true
                val text = node.text?.toString()
                    ?: node.contentDescription?.toString()?.takeIf { node.childCount == 0 && !isImage(node.className) }
                if (!text.isNullOrBlank()) {
                    texts.add(text)
                    // What the user types stays as typed.
                    if (!node.isEditable && ChineseText.hasHan(text)) {
                        node.getBoundsInScreen(rect)
                        nodes.add(ScreenText(text.trim(), Box(rect.left, rect.top, rect.right, rect.bottom)))
                    }
                }
                if (depth < MAX_DEPTH) for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
            @Suppress("DEPRECATION")
            if (node !== root && Build.VERSION.SDK_INT < 33) node.recycle()
        }
        return Tree(nodes, texts, password, editable, visited)
    }

    private fun isImage(className: CharSequence?): Boolean = className?.contains("Image") == true

    private fun layoutSignature(nodes: List<ScreenText>): Int {
        var hash = ChineseText.signature(nodes.map { it.text })
        for (node in nodes) hash = hash * 31 + node.bounds.hashCode()
        return hash
    }

    /** Translations known at once are drawn at once; the others join as they come, about every 150 ms. */
    private suspend fun draw(generation: Long, lines: List<ScreenText>, backgrounds: Map<ScreenText, Int>) {
        val first = ArrayList<OverlayView.Drawn>()
        val waiting = LinkedHashMap<String, MutableList<ScreenText>>()
        for (line in lines) {
            val known = translator.quick(line.text)
            if (known != null) layout(line, known.text, backgrounds)?.let { first.add(it) }
            else waiting.getOrPut(line.text) { ArrayList() }.add(line)
        }
        onMain { if (controller.isCurrent(generation)) overlay.show(generation, first, last = waiting.isEmpty()) }
        if (waiting.isEmpty()) return
        val batch = ArrayList<OverlayView.Drawn>()
        suspend fun flush(last: Boolean) {
            val items = synchronized(batch) { ArrayList(batch).also { batch.clear() } }
            if (items.isEmpty() && !last) return
            onMain { if (controller.isCurrent(generation)) overlay.show(generation, items, last) }
        }
        val flusher = scope.launch {
            while (isActive) {
                delay(150)
                flush(false)
            }
        }
        try {
            val error = translator.translateAll(waiting.keys.toList(), ai) { text, result ->
                if (result == null) return@translateAll
                val drawn = waiting[text].orEmpty().mapNotNull { layout(it, result.text, backgrounds) }
                synchronized(batch) { batch.addAll(drawn) }
            }
            if (error != null) ScreenState.error(error)
        } finally {
            flusher.cancel()
        }
        flush(true)
    }

    /** Nothing is drawn where the "translation" is the Chinese itself (a glossary entry kept as written). */
    private fun layout(line: ScreenText, text: String, backgrounds: Map<ScreenText, Int>): OverlayView.Drawn? {
        if (text == line.text || ChineseText.hasHan(text)) return null
        return OverlayView.layout(this, line.bounds, text, backgrounds[line] ?: themeBackground())
    }

    /** The colour just inside the box's edge in the screenshot (the button or page behind the text). */
    private fun background(shot: ScreenCapture.Shot.Image?, box: Box): Int {
        val bitmap: Bitmap = shot?.bitmap ?: return themeBackground()
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        fun sample(sx: Int, sy: Int) {
            val x = sx - shot.left
            val y = sy - shot.top
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return
            val c = bitmap.getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        val inset = 2
        for (i in 0..8) {
            val x = box.left + (box.width - 1) * i / 8
            val y = box.top + (box.height - 1) * i / 8
            sample(x, box.top + inset)
            sample(x, box.bottom - 1 - inset)
            sample(box.left + inset, y)
            sample(box.right - 1 - inset, y)
        }
        if (n == 0) return themeBackground()
        return (0xF2 shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun themeBackground(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) 0xF2202022.toInt() else 0xF2FFFFFF.toInt()
    }

    private suspend fun pause(generation: Long, reason: SensitiveScreen.Reason) {
        onMain { if (controller.isCurrent(generation)) overlay.clear() }
        lastLayout = 0
        ScreenState.status = "Tạm dừng: ${reason.label}"
        ScreenState.changed()
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    // Floating button and status

    private fun updateButton() {
        if (!connected) return
        val wanted = settings.enabled && settings.floatingButton && settings.isSelected(foreground)
        if (!wanted) {
            button?.hide()
            return
        }
        val floating = button ?: FloatingButton(this, settings, onTap = {
            overlay.showingOriginal = !overlay.showingOriginal
            updateButton()
        }, onHold = {
            settings.paused = !settings.paused
            overlay.showingOriginal = false
            refresh()
        }).also { button = it }
        floating.show(
            when {
                settings.paused -> FloatingButton.Look.PAUSED
                overlay.showingOriginal -> FloatingButton.Look.ORIGINAL
                else -> FloatingButton.Look.TRANSLATING
            },
        )
    }

    private fun status() {
        ScreenState.status = when {
            !settings.enabled -> "Đang tắt"
            settings.paused -> "Tạm dừng"
            else -> "Đang chờ app đã chọn"
        }
        ScreenState.changed()
    }

    // Ending

    private fun stopAll() {
        if (!connected) return
        connected = false
        running = false
        main.removeCallbacks(scanNow)
        scope.cancel()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        overlay.remove()
        button?.hide()
        button = null
        engines?.close()
        engines = null
        capture.close()
        translator.clearCache()
        imageText = emptyList()
        lastFrame = null
        if (ScreenState.service === this) ScreenState.service = null
        ScreenState.status = "Chưa có quyền Trợ năng"
        ScreenState.changed()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    companion object {
        const val TAG = "MSTransScreen"
        private const val MAX_NODES = 1_500
        private const val MAX_DEPTH = 60
        private const val MAX_WAIT_MS = 1_500L
    }
}

private fun AccessibilityWindowInfo.box(): Box {
    val rect = Rect()
    getBoundsInScreen(rect)
    return Box(rect.left, rect.top, rect.right, rect.bottom)
}
