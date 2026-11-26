package vn.mocsinh.mstrans.screen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.AppLock
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.core.Settings
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.Translator
import vn.mocsinh.mstrans.core.screen.Box
import vn.mocsinh.mstrans.core.screen.ChineseText
import vn.mocsinh.mstrans.core.screen.FrameSignature
import vn.mocsinh.mstrans.core.screen.LineStyle
import vn.mocsinh.mstrans.core.screen.LineStyles
import vn.mocsinh.mstrans.core.screen.Look
import vn.mocsinh.mstrans.core.screen.OverlayFit
import vn.mocsinh.mstrans.core.screen.PaintOrder
import vn.mocsinh.mstrans.core.screen.PixelGrid
import vn.mocsinh.mstrans.core.screen.ScreenAi
import vn.mocsinh.mstrans.core.screen.ScreenController
import vn.mocsinh.mstrans.core.screen.ScreenController.Action
import vn.mocsinh.mstrans.core.screen.ScreenController.Event
import vn.mocsinh.mstrans.core.screen.ScreenSettings
import vn.mocsinh.mstrans.core.screen.ScreenText
import vn.mocsinh.mstrans.core.screen.ScreenTextMerger
import vn.mocsinh.mstrans.core.screen.ScreenTranslator
import vn.mocsinh.mstrans.core.screen.SensitiveScreen
import vn.mocsinh.mstrans.core.screen.SpokenText
import vn.mocsinh.mstrans.core.screen.TextEngine
import vn.mocsinh.mstrans.core.screen.TextFit
import vn.mocsinh.mstrans.core.screen.TextGeometry
import vn.mocsinh.mstrans.core.screen.TextLines
import vn.mocsinh.mstrans.core.screen.TreeWalk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Dịch màn hình: reads the Chinese of the apps the user chose (accessibility tree, and pictures on
 * Android 11+) and lays the Vietnamese over it. Runs only while the switch in Cài đặt › Dịch màn
 * hình is on. Never taps, types or scrolls for the user; never reads another app's content; keeps
 * screenshots in memory only; logs times and counts, never text.
 */
class ScreenTranslationService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Translations are laid out one screen at a time. */
    private val composing = Dispatchers.Default.limitedParallelism(1)
    /** Translating goes on across the scans of an app, and stops when the app is left. */
    private val translations = SupervisorJob(scope.coroutineContext[Job])
    /**
     * The app is asked for the nodes of its screen a few at a time: each question waits for the
     * app's main thread, and one after another a screen of some hundred nodes took most of a second.
     */
    private val readerThreads = Executors.newFixedThreadPool(READERS)
    private val readers = readerThreads.asCoroutineDispatcher()
    /** Which view scrolled is asked of the app off the main thread, one event after another. */
    private val lookups = Executors.newSingleThreadExecutor()
    /**
     * A view that scrolls, as far as its events tell one from another: its window, its kind, how far
     * it can scroll, how many things it lists.
     */
    private data class Scroller(val window: Int, val kind: String?, val maxX: Int, val maxY: Int, val items: Int)

    /** The views that have scrolled on this page: true the page itself, false a small one. Main thread. */
    private val scrollers = HashMap<Scroller, Boolean>()
    /** Small views that scrolled a moment ago, and when: nothing is drawn in them until they rest. */
    @Volatile private var moved: List<Pair<Box, Long>> = emptyList()
    private lateinit var settings: ScreenSettings
    private lateinit var appSettings: Settings
    private lateinit var controller: ScreenController
    private lateinit var overlay: OverlayController
    private lateinit var capture: ScreenCapture
    private lateinit var composer: OverlayComposer
    private var button: FloatingButton? = null
    @Volatile private var engines: MlKitEngines? = null
    /** What was translated stays in memory while the service runs: coming back to a page draws at once. */
    /** The offline translator's calls, for the log: how many, how long in all, the longest. */
    private val engineCalls = AtomicInteger()
    private val engineMs = AtomicLong()
    private val engineLongest = AtomicLong()
    private val translator = ScreenTranslator(
        engine = TextEngine { text ->
            val asked = SystemClock.elapsedRealtime()
            try {
                engines?.engine?.translate(text)
            } finally {
                val took = SystemClock.elapsedRealtime() - asked
                engineCalls.incrementAndGet()
                engineMs.addAndGet(took)
                engineLongest.accumulateAndGet(took) { a, b -> maxOf(a, b) }
            }
        },
        cacheSize = 3_000,
    )
    @Volatile private var ai: ScreenAi? = null
    private var aiKey: String? = null
    private var glossary: String? = null
    private var running = false
    private var connected = false
    /** The app in front, for the floating button (known while paused too). */
    private var foreground: String? = null
    private var imePackages = emptySet<String>()
    /** Home screens (see `launchers`). */
    private var homes = emptySet<String>()
    /** A chosen app was in front since the permission was given (see `leaving`). */
    private var usedChosen = false
    private var receiver: BroadcastReceiver? = null
    private var orientation = Configuration.ORIENTATION_UNDEFINED

    // Scans
    private var scanJob: Job? = null
    /** The page (generation) of the scan under way, and whether one more was asked for meanwhile. */
    private var scanning = -1L
    private var again = false
    private var pending: Action.Scan? = null
    private var pendingSince = 0L
    private var pendingAt = 0L
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
    /** The page whose pictures `imageText` was read from. */
    @Volatile private var imageGeneration = -1L
    @Volatile private var lastLayout = 0
    /** The window that refused a screenshot (FLAG_SECURE): nothing of it is translated. */
    @Volatile private var secureWindow: Int? = null
    /** The window last found at rest, and where. */
    @Volatile private var rested: Pair<Int, Box>? = null
    /** When the page in front opened (or was scrolled), and whether its translations have shown yet: for the log. */
    @Volatile private var pageAt = 0L
    @Volatile private var firstDrawn = false
    /** Asking the app where its letters are, in the scan under way: how often, how long. */
    private val lettersCalls = AtomicInteger()
    @Volatile private var lettersMs = 0L
    /** The page found unsettled, and how many scans of it in a row. */
    private var moving = -1L
    private var movingScans = 0

    // What is on the screen now

    /** The Chinese of the screen in reading order, each with its look. */
    private class Page(val generation: Long, val lines: List<ScreenText>, val looks: Map<ScreenText, Look>, val window: Box)

    /** Where a text is: its look is the same whatever it says (a countdown, a price). */
    private data class Place(val left: Int, val top: Int, val bottom: Int, val lines: Int) {
        constructor(geometry: TextGeometry) : this(geometry.box.left, geometry.box.top, geometry.box.bottom, geometry.lines.size)
    }

    @Volatile private var page: Page? = null
    /**
     * Looks read from screenshots; lines found by their ink, and the boxes in which no ink was the
     * text (a card described in words, a text under a bar): all guarded by `looks`.
     */
    private val looks = lru<Place, Look>(600)
    private val inked = lru<Pair<String, Box>, TextGeometry>(300)
    private val inkless = lru<Pair<String, Box>, Boolean>(300)
    /** The app's own lines found to lie under a bar, where they were when looked at. */
    private val covered = lru<Pair<String, Box>, Boolean>(300)
    /** What the app said about where its letters are (`NOWHERE`: asked, no answer); guarded by itself. */
    private val letters = lru<Pair<String, Box>, Any>(1_000)
    private val translating: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Texts no translator could do: not asked again until the app is left. */
    private val unknown: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = MSTransApp.of(this)
        appSettings = app.settings
        settings = ScreenSettings(app.store)
        controller = ScreenController(ownPackage = packageName, speed = settings.speed)
        overlay = OverlayController(this)
        capture = ScreenCapture(this)
        composer = OverlayComposer(this)
        orientation = resources.configuration.orientation
        listenToScreen()
        connected = true
        ScreenState.service = this
        homes = launchers()
        // The permission given (in Android's settings, by the shortcut, from the tile) is the switch:
        // given, it translates.
        settings.enabled = true
        settings.paused = false
        ScreenState.tileChanged(this)
        refresh()
    }

    /**
     * Off for good: translating stops and the accessibility permission is taken back by MSTrans
     * itself, so no other app sees an accessibility service running. On again from the tile, the
     * settings page, or Android's accessibility shortcut.
     */
    fun turnOff() {
        main.post {
            stopAll()
            runCatching { disableSelf() }
            ScreenState.status = "Đã tắt hẳn quyền Trợ năng"
            ScreenState.changed()
            ScreenState.tileChanged(this)
        }
    }

    /** Home screens: going home, or to the list of recent apps, is not leaving the chosen apps. */
    private fun launchers(): Set<String> = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /**
     * With "Tự tắt khi rời app mua sắm": once a chosen app was used, another app coming to the
     * front turns the permission off. Not for what only passes over (the system's bars and dialogs,
     * keyboards, home, MSTrans itself, Android's settings where it was just turned on).
     */
    private fun leaving(pkg: String) {
        if (settings.isSelected(pkg)) {
            usedChosen = true
            return
        }
        if (!settings.autoOff || !usedChosen) return
        if (pkg == packageName || pkg in imePackages || pkg in homes || pkg in PASSING) return
        // Only an app that really has the screen, and still has it a moment later: a window of
        // the system passing over (Digital Wellbeing, a phone maker's pop-up) is not leaving.
        main.removeCallbacks(leftCheck)
        leftTo = pkg
        main.postDelayed(leftCheck, LEAVE_CHECK_MS)
    }

    private var leftTo: String? = null
    private val leftCheck = Runnable {
        val pkg = leftTo ?: return@Runnable
        if (connected && settings.autoOff && foreground == pkg && !settings.isSelected(pkg) && inFront() == pkg) turnOff()
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
        unknown.clear()
        imePackages = getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        // Not activated: nothing is read or translated.
        val run = settings.enabled && !settings.paused && AppLock.active(this)
        if (run && !running) {
            running = true
            val ml = engines ?: MlKitEngines().also { engines = it }
            scope.launch {
                runCatching { ml.checkModel() }
                ScreenState.changed()
                ml.warmUp()
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
            unknown.clear()
            ml.warmUp()
            main.post {
                imagesDue = true
                if (running) apply(controller.handle(Event.WindowState(controller.foreground), settings::isSelected))
                status()
            }
        }
    }

    private fun updateAi() {
        val key = appSettings.apiKey?.takeIf { settings.mode == ScreenSettings.Mode.AI }
        val tag = key?.let { "$it/${settings.aiLimit}/${settings.aiBest}" }
        if (tag == aiKey) return
        // What the other way of translating made is made again.
        translator.clearCache()
        aiKey = tag
        ai = key?.let {
            val translator = Translator(it, if (settings.aiBest) TranslationModel.SOL else TranslationModel.LUNA, appSettings)
            ScreenAi(write = { instructions, input -> translator.write(instructions, input).first }, limitPer10Minutes = settings.aiLimit)
        }
    }

    // Events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !connected || !settings.enabled) return
        val pkg = event.packageName?.toString()
        val type = event.eventType
        // The translation layer and the floating button are windows of this package too: showing one is not
        // the user moving to MSTrans (it used to cancel the scan of the app just opened).
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == packageName && ownOverlay(event.windowId)) return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null && pkg !in imePackages && pkg != foreground) {
            // Something of another app showing over a chosen one (a notice, a small system window)
            // is not the user leaving it: the app that has the screen says which app is in front.
            if (!settings.isSelected(pkg) && settings.isSelected(foreground) && inFront() == foreground) return
            foreground = pkg
            updateButton()
            leaving(pkg)
        }
        if (!running) return
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrolled(event, pkg)
            return
        }
        val translated = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (pkg != null && pkg in imePackages && keyboardUp()) Event.WindowsChanged(true)
                else {
                    secureWindow = null
                    imagesDue = true
                    // Another page: its views are other views.
                    scrollers.clear()
                    Event.WindowState(pkg)
                }
            // The scan works out whether the Chinese changed (cheaper than reading the tree twice).
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> Event.ContentChanged(pkg, null)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                if (settings.isSelected(controller.foreground)) Event.WindowsChanged(keyboardUp()) else return
            else -> return
        }
        apply(controller.handle(translated, settings::isSelected))
        // The last event of a keyboard going away can come while it is still in the list of windows
        // (it is sliding out), and no other may follow: it is looked for once more a moment later.
        if (translated == Event.WindowsChanged(true)) {
            main.removeCallbacks(keyboardGone)
            main.postDelayed(keyboardGone, KEYBOARD_CHECK_MS)
        }
    }

    private val keyboardGone = Runnable {
        if (running && !keyboardUp()) apply(controller.handle(Event.WindowsChanged(false), settings::isSelected))
    }

    /**
     * Something scrolled. The page itself: every translation goes at once and comes back when it
     * rests. A small view (a label that flips by itself, a row of pictures going by): only what is
     * in it goes; the rest of the page used to blink each time such a view moved. Which it is, the
     * app is asked (where the view is), off the main thread, and it is remembered for the page: the
     * page itself is then not asked about again, and a small view only for where it now is.
     */
    private fun scrolled(event: AccessibilityEvent, pkg: String?) {
        // Only the package name of an app not chosen is looked at.
        if (!settings.isSelected(pkg)) return
        val key = Scroller(event.windowId, event.className?.toString(), event.maxScrollX, event.maxScrollY, event.itemCount)
        val known = scrollers[key]
        if (known == true) {
            pageScrolled(pkg)
            return
        }
        @Suppress("DEPRECATION")
        val copy = if (Build.VERSION.SDK_INT >= 30) AccessibilityEvent(event) else AccessibilityEvent.obtain(event)
        val decided = AtomicBoolean(false)
        // A view not met on this page before, and an app slow to say where it is: taken to be the page.
        val late = if (known != null) null else Runnable { if (decided.compareAndSet(false, true) && running) pageScrolled(pkg) }
        late?.let { main.postDelayed(it, SOURCE_WAIT_MS) }
        lookups.execute {
            // Null: the app did not say. Empty: the view is not on the screen (a label that goes
            // on flipping after it was scrolled away): nothing to be seen has moved.
            val box = runCatching {
                copy.source?.let { node ->
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    if (!node.isVisibleToUser || rect.isEmpty) NO_BOX else Box(rect.left, rect.top, rect.right, rect.bottom)
                }
            }.getOrNull()
            main.post {
                if (!connected || !running) return@post
                val window = page?.window ?: screen()
                val small = box != null && box.area * 5 < window.area * 2
                if (scrollers.size > 64) scrollers.clear()
                scrollers[key] = !small
                if (!decided.compareAndSet(false, true)) return@post
                late?.let { main.removeCallbacks(it) }
                when {
                    !small -> pageScrolled(pkg)
                    box !== NO_BOX && box != null -> apply(controller.handle(Event.Scrolled(pkg, box), settings::isSelected))
                }
            }
        }
    }

    private fun pageScrolled(pkg: String?) {
        imagesDue = true
        apply(controller.handle(Event.Scrolled(pkg), settings::isSelected))
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

    /** The package of the app whose window has the screen (only its name is looked at); null: none, or not known. */
    private fun inFront(): String? = runCatching {
        val all = windows
        val front = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        front?.root?.packageName?.toString()
    }.getOrNull()

    private fun ownOverlay(windowId: Int): Boolean =
        runCatching { windows.firstOrNull { it.id == windowId }?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.getOrDefault(false)

    /** Main thread. */
    private fun apply(actions: List<Action>) {
        // A new page or a scroll: the clock for "how long until the translations show" starts.
        if (Action.Clear in actions && actions.any { it is Action.Scan && it.restart }) {
            pageAt = SystemClock.elapsedRealtime()
            firstDrawn = false
        }
        for (action in actions) when (action) {
            Action.Clear -> {
                page = null
                overlay.clear()
            }
            is Action.ClearArea -> {
                val area = action.area
                val now = SystemClock.uptimeMillis()
                moved = moved.filter { it.first != area && now - it.second < MOVED_MS } + (area to now)
                // What was read in pictures there is gone with them; the next scan reads this place again.
                imageText = imageText.filterNot { inside(it.bounds, area) }
                lastLayout = 0
                page?.let { old ->
                    val kept = old.lines.filterNot { inside(old.looks.getValue(it).geometry.box, area) }
                    if (kept.size != old.lines.size) {
                        page = Page(old.generation, kept, old.looks, old.window)
                        render()
                    }
                }
            }
            Action.CancelScan -> {
                main.removeCallbacks(scanNow)
                pending = null
                scanJob?.cancel()
            }
            // Another app is in front: nothing of this one's screen is kept. Translations made
            // stay in memory (the way back is then instant) until the service stops.
            Action.ClearCache -> {
                lastFrame = null
                imageText = emptyList()
                lastLayout = 0
                secureWindow = null
                synchronized(looks) {
                    looks.clear()
                    inked.clear()
                    inkless.clear()
                    covered.clear()
                }
                moved = emptyList()
                scrollers.clear()
                translations.cancelChildren()
                translating.clear()
                unknown.clear()
            }
            is Action.Scan -> schedule(action)
        }
        if (actions.isNotEmpty()) updateButton()
    }

    /**
     * A scroll going on moves the scan later (never more than `MAX_WAIT_MS` in all); a change on the
     * page does not: a scan already waiting keeps its time, so a page still loading is read early.
     */
    private fun schedule(scan: Action.Scan) {
        val now = SystemClock.uptimeMillis()
        val waiting = pending != null
        if (!waiting) pendingSince = now
        val wanted = if (scan.restart) minOf(now + scan.delayMs, pendingSince + MAX_WAIT_MS) else now + scan.delayMs
        pendingAt = (if (waiting && !scan.restart) minOf(pendingAt, wanted) else wanted).coerceAtLeast(now)
        pending = scan
        main.removeCallbacks(scanNow)
        main.postAtTime(scanNow, pendingAt)
    }

    private fun startScan(generation: Long) {
        if (scanJob?.isActive == true && scanning == generation) {
            // The same page changed while it is being read: once more, after this one.
            again = true
            return
        }
        scanJob?.cancel()
        scanning = generation
        again = false
        val images = imagesDue
        imagesDue = false
        scanJob = scope.launch {
            var unsettled = 0L
            try {
                unsettled = scan(generation, images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScreenState.error("Lỗi khi đọc màn hình (${e.javaClass.simpleName}).")
                Log.w(TAG, "scan failed: ${e.javaClass.simpleName}")
            } finally {
                main.post {
                    if (scanning != generation || !controller.isCurrent(generation)) return@post
                    if (unsettled > 0) {
                        // The page was still settling: read again shortly, pictures too.
                        again = false
                        imagesDue = true
                        schedule(Action.Scan(unsettled, generation, restart = false))
                    } else if (again) {
                        again = false
                        schedule(controller.again())
                    }
                }
            }
        }
    }

    // Reading the screen (off the main thread)

    /**
     * `nodes`: the app's own Chinese. `others`: where it has text without Chinese, or text the user
     * typed (what the reader of pictures makes of those is not translated).
     */
    private class Tree(
        val nodes: List<ScreenText>, val others: List<Box>, val texts: List<String>, val password: Boolean, val editable: Boolean, val visited: Int,
        val asking: List<Asking>,
        /** For each of `nodes`: it may lie under a bar that stays in place (`PaintOrder.underPanels`). */
        val under: BooleanArray,
    )

    /** A text whose letters are still to be asked for: its node (kept until then), its place in `Tree.nodes`. */
    private class Asking(val node: AccessibilityNodeInfo, val index: Int, val text: String, val bounds: Box)

    /**
     * Reads the screen and shows its translations. More than 0: the page had not settled (the
     * screenshot and what the app says of itself did not agree), nothing was shown, and it must be
     * read again after that many milliseconds.
     */
    private suspend fun scan(generation: Long, imagesNow: Boolean): Long {
        if (!controller.isCurrent(generation)) return 0
        controller.scanStarted(generation)
        val started = SystemClock.elapsedRealtime()
        val all = windows
        if (all.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            onMain { apply(controller.handle(Event.WindowsChanged(true), settings::isSelected)) }
            return 0
        }
        var window = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        var root = window?.root
        // Only the package name of a window not chosen is looked at, never its content.
        if (window == null || root == null || !settings.isSelected(root.packageName?.toString())) {
            onMain {
                if (controller.isCurrent(generation)) {
                    page = null
                    overlay.clear()
                }
            }
            return 0
        }
        if (window.id == secureWindow) {
            pause(generation, SensitiveScreen.Reason.SECURE_WINDOW)
            return 0
        }
        // A page opening slides its window into place: until it rests, a screenshot shows the
        // text some pixels beside where the app says it is. Its place is read again every
        // `REST_STEP_MS` (no screenshot needed) until it no longer moves and the app agrees.
        // A window found resting before, where it rested, is not waited for again.
        val windowId = window.id
        val first = window.box()
        if (rested != (windowId to first) && (first != rootBox(root) || first != screen())) {
            val until = SystemClock.elapsedRealtime() + MAX_REST_MS
            var before = first
            var still = 0
            while (SystemClock.elapsedRealtime() < until) {
                delay(REST_STEP_MS)
                if (!controller.isCurrent(generation)) return 0
                val now = windows.firstOrNull { it.id == windowId } ?: return 0
                val nowRoot = now.root ?: return 0
                window = now
                root = nowRoot
                val box = now.box()
                still = if (box == before) still + 1 else 0
                // At rest: where the app says too, or (an app whose root is elsewhere) not moved twice running.
                if (still >= 1 && box == rootBox(nowRoot) || still >= 2) break
                before = box
            }
        }
        rested = windowId to window.box()
        // Where the app's window is, from the app's own root view: the same measure its texts are given in.
        val windowBox = rootBox(root) ?: window.box()
        val screenArea = resources.displayMetrics.let { it.widthPixels.toLong() * it.heightPixels }
        // Status and navigation bars, small system windows above the app.
        val excluded = all.filter {
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.layer > window.layer && it.box().area * 2 < screenArea
        }.map { it.box() }

        lettersCalls.set(0)
        lettersMs = 0L
        val tree = read(root, windowBox)
        SensitiveScreen.check(tree.texts, passwordField = tree.password, editableField = tree.editable)?.let {
            release(tree)
            pause(generation, it)
            return 0
        }
        val layout = tree.nodes.hashCode()
        val ml = engines
        val picturesDue = ml != null && settings.readImages && Build.VERSION.SDK_INT >= 30 &&
            (imagesNow || SystemClock.elapsedRealtime() - lastImageAt >= settings.speed.imageGapMs)
        if (layout == lastLayout && !picturesDue && onMain { !overlay.isEmpty }) {
            release(tree)
            controller.scanFinished(generation, layout)
            return 0
        }

        // Translating begins at once: it needs the words, not where they are. A box that may be a
        // card described in words, or may lie under a bar, waits until the picture of the screen has told.
        val normal = GUESSED_SIZE_SP.sp()
        val doubted = BooleanArray(tree.nodes.size) { tree.under[it] || TextFit.isRegion(tree.nodes[it].text, tree.nodes[it].bounds, normal) }
        request(ScreenTextMerger.merge(tree.nodes.filterIndexed { i, _ -> !doubted[i] }, emptyList(), windowBox, excluded, tree.others).map { it.text })

        // A new page or a scroll needs a screenshot anyway (pictures are read again): it is taken
        // while the app is asked where its letters are. Only now, the screen known not to be sensitive.
        val early = if (picturesDue) CoroutineScope(coroutineContext).async { capture.take(window.id, windowBox, screen(), overlay) } else null
        val nodes = withLetters(tree)
        val under = HashSet<Pair<String, Box>>()
        for ((i, node) in nodes.withIndex()) if (tree.under[i]) under.add(node.text to node.bounds)
        val readAt = SystemClock.elapsedRealtime()

        // What was read in the pictures of another page, or of this one before it was scrolled, is
        // not on this one (and its pictures are read even if the screen looks as before). On the
        // same page it stays until the pictures are read again: nothing blinks meanwhile.
        if (imageGeneration != generation) {
            imageText = emptyList()
            lastFrame = null
            imageGeneration = generation
        }
        // A screenshot: for the colours of text not seen before, for the boxes still in doubt, for text in pictures.
        var known = ScreenTextMerger.merge(judge(nodes, doubted, null, keep = true), imageText, windowBox, excluded, tree.others)
        val unseen = synchronized(looks) {
            known.any { line ->
                val key = line.text to line.bounds
                // Looked at before and found to lie under a bar: not a reason to look again.
                if (key in covered) return@any false
                val geometry = line.geometry ?: inked[key] ?: TextGeometry(listOf(line.bounds), 0f).takeIf { key in inkless }
                // The same lines `show` keeps the look of: those on the screen.
                geometry?.let { onScreen(it, windowBox)?.let { seen -> Place(seen) !in looks } ?: false } ?: true
            }
        }
        var shot: ScreenCapture.Shot.Image? = null
        var grid: PixelGrid? = null
        if (Build.VERSION.SDK_INT >= 30 && (unseen || picturesDue)) {
            when (val taken = early?.await() ?: capture.take(window.id, windowBox, screen(), overlay)) {
                ScreenCapture.Shot.Secure -> {
                    secureWindow = window.id
                    pause(generation, SensitiveScreen.Reason.SECURE_WINDOW)
                    return 0
                }
                is ScreenCapture.Shot.Image -> {
                    shot = taken
                    grid = grid(taken)
                }
                // Refused (Android allows one screenshot every third of a second, or every second).
                ScreenCapture.Shot.Failed -> Unit
            }
            // A page still opening is not yet where the app says it is, or says where it will be
            // while it is drawn somewhere else: colours would be read off the wrong spots and
            // translations drawn beside their text. Nothing is ever shown from such a screenshot;
            // the page is read again, soon at first, then every second. One that still does not
            // agree after that (its text under something else) is shown without a screenshot:
            // where the app says, in the theme's colours.
            val settled = grid?.let { agrees(it, known, windowBox, under) } ?: !unseen
            if (!settled) {
                val tries = unsettled(generation)
                val again = tries <= QUICK_RETRIES + SLOW_RETRIES
                Log.d(TAG, "page not settled (try $tries): ${if (again) "read again" else "shown as it is"}")
                if (again) {
                    shot?.bitmap?.recycle()
                    return if (tries <= QUICK_RETRIES) SETTLE_MS else SLOW_SETTLE_MS
                }
                // It will not settle (something of the page keeps moving, or lies under something
                // else): shown from this screenshot, each of the app's lines only where its ink is.
            } else movingScans = 0
        }
        // The picture of the screen tells which boxes are text: a card described in words is not,
        // nor is a text lying under a bar. What is written there is read from the picture instead.
        val judged = judge(nodes, doubted, grid, keep = false)
        known = ScreenTextMerger.merge(judged, imageText, windowBox, excluded, tree.others)
        val shotAt = SystemClock.elapsedRealtime()
        try {
            if (!controller.isCurrent(generation)) return 0
            // First what the app itself says (and pictures as last read): on screen before the
            // pictures are read again, which is the slow part.
            var stale = show(generation, known, grid, windowBox, under)
            val shownAt = SystemClock.elapsedRealtime()
            var pictures = imageText
            if (picturesDue && shot != null && ml != null) {
                val bitmap = shot.bitmap
                // What is already read is painted out: less to read, nothing read twice, and a
                // countdown in the app's own text does not make the pictures look changed.
                paintOut(bitmap, shot, known)
                val frame = FrameSignature.of(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
                if (frame.differs(lastFrame)) {
                    // A letter misread in a known word is put right; a piece known whole keeps its numbers in it.
                    pictures = ml.read(bitmap, shot.left, shot.top) { piece -> translator.quick(translator.corrected(piece)) != null }
                        .map { line -> translator.corrected(line.text).let { if (it == line.text) line else line.copy(text = it) } }
                    // Read for a page that is gone: not kept for the next one.
                    if (!controller.isCurrent(generation)) return 0
                    imageText = pictures
                    lastFrame = frame
                    stale = show(generation, ScreenTextMerger.merge(judged, pictures, windowBox, excluded, tree.others), grid, windowBox, under)
                }
                lastImageAt = readAt
            }
            lastLayout = layout
            controller.scanFinished(generation, layout)
            val done = SystemClock.elapsedRealtime()
            ScreenState.lastScan = "${done - started} ms · ${known.size} dòng chữ Trung · ${pictures.size} dòng trong ảnh"
            ScreenState.status = "Đang dịch"
            ScreenState.changed()
            Log.d(TAG, "scan ${done - started} ms, ${started - pageAt} ms after the page: tree ${readAt - started} ms (${tree.visited} nodes, " +
                "letters ${lettersCalls.get()} in $lettersMs ms), ${nodes.size - judged.size} boxes not text, shot ${shotAt - readAt} ms, " +
                "shown ${shownAt - shotAt} ms, pictures ${done - shownAt} ms, ${known.size} lines, ${pictures.size} in pictures")
            // Lines left out are asked again shortly (not for ever: as for a page that does not settle).
            if (stale && unsettled(generation) <= QUICK_RETRIES + SLOW_RETRIES) return SETTLE_MS
        } finally {
            shot?.bitmap?.recycle()
        }
        return 0
    }

    private fun rootBox(root: AccessibilityNodeInfo): Box? =
        Rect().also { root.getBoundsInScreen(it) }.takeUnless { it.isEmpty }?.let { Box(it.left, it.top, it.right, it.bottom) }

    /** The whole screen, where a full-screen app's window rests. */
    private fun screen(): Box {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            return Box(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        val metrics = resources.displayMetrics
        return Box(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /** How many scans of this page in a row found it unsettled, this one counted. */
    private fun unsettled(generation: Long): Int {
        if (moving != generation) {
            moving = generation
            movingScans = 0
        }
        return ++movingScans
    }

    /**
     * The app's texts and what kind of screen it is. Where its letters are is not asked here (that
     * asks the app once per text): `withLetters` does, once the screen is known not to be sensitive,
     * while the screenshot is being taken.
     */
    private suspend fun read(root: AccessibilityNodeInfo, window: Box): Tree {
        val nodes = ArrayList<ScreenText>()
        val numbers = ArrayList<Int>()
        val others = ArrayList<Box>()
        val texts = ArrayList<String>()
        val asking = ArrayList<Asking>()
        val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        // The tree as arrays, one entry a node visited: for what is painted over what.
        val parents = ArrayList<Int>()
        val orders = ArrayList<Int>()
        val boxes = ArrayList<Box>()
        val scrolls = ArrayList<Boolean>()
        val worded = ArrayList<Boolean>()
        var password = false
        var editable = false
        val rect = Rect()
        val visited = TreeWalk.walk(
            root, MAX_NODES, MAX_DEPTH, readers,
            child = { node, i -> child(node, i) },
            visit = { node, at ->
                parents.add(at.parent)
                orders.add(node.drawingOrder)
                var box = NO_BOX
                var hasText = false
                var into = 0
                if (node.isVisibleToUser) {
                    if (node.isPassword) password = true
                    if (node.isEditable) editable = true
                    node.getBoundsInScreen(rect)
                    box = Box(rect.left, rect.top, rect.right, rect.bottom)
                    val own = node.text?.toString()
                    val said = own ?: node.contentDescription?.toString()?.takeIf { node.childCount == 0 && !isImage(node.className) }
                    if (!said.isNullOrBlank()) {
                        texts.add(said)
                        // "品牌，可选，按钮": what is written on the screen is 品牌.
                        val text = SpokenText.label(said).trim()
                        if (text.isNotEmpty()) {
                            hasText = true
                            // What the user types stays as typed.
                            if (!node.isEditable && ChineseText.hasHan(text)) {
                                nodes.add(ScreenText(text, box))
                                numbers.add(at.number)
                                if (own != null && node !== root && text == own.trim()) {
                                    asking.add(Asking(node, nodes.lastIndex, own, box))
                                    kept.add(node)
                                }
                            } else others.add(box)
                        }
                    }
                    into = node.childCount
                }
                boxes.add(box)
                scrolls.add(node.isScrollable)
                worded.add(hasText)
                into
            },
            left = { node ->
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < 33 && node !== root && node !in kept) node.recycle()
            },
        )
        val under = PaintOrder.underPanels(parents.toIntArray(), orders.toIntArray(), boxes, scrolls.toBooleanArray(), worded.toBooleanArray(), window)
        return Tree(nodes, others, texts, password, editable, visited, asking, BooleanArray(nodes.size) { under[numbers[it]] })
    }

    /**
     * One child of a node. Android 13 and later hand over one node a question unless told not to
     * stop: told so, a node comes with those under it (up to 50), and a screen takes a few
     * questions instead of one for every node.
     */
    private fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            node.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE)
        } else node.getChild(index)
    }.getOrNull()

    /** The texts, each with where the app says its letters are (asked side by side). */
    private suspend fun withLetters(tree: Tree): List<ScreenText> {
        val began = SystemClock.elapsedRealtime()
        val found = coroutineScope { tree.asking.map { ask -> async(readers) { letters(ask.node, ask.text, ask.bounds) } }.awaitAll() }
        val out = tree.nodes.toMutableList()
        for ((i, ask) in tree.asking.withIndex()) found[i]?.let { out[ask.index] = out[ask.index].copy(geometry = it) }
        release(tree)
        lettersMs = SystemClock.elapsedRealtime() - began
        return out
    }

    /**
     * The app's texts less the boxes that are not text: a box in doubt (far larger than its words,
     * or under a bar that stays in place) is one only if the picture of the screen shows its words
     * in it. `keep`: a box not yet looked at stays in for now.
     */
    private fun judge(nodes: List<ScreenText>, doubted: BooleanArray, grid: PixelGrid?, keep: Boolean): List<ScreenText> = synchronized(looks) {
        nodes.filterIndexed { i, node ->
            if (node.geometry != null || !doubted[i]) return@filterIndexed true
            val key = node.text to node.bounds
            if (key in inked) return@filterIndexed true
            if (key in inkless) return@filterIndexed false
            if (grid == null) return@filterIndexed keep
            val found = LineStyles.lines(grid, node.bounds, node.text)
            if (found != null) inked[key] = found else inkless[key] = true
            found != null
        }
    }

    /** More than half of `box` is in `area`. */
    private fun inside(box: Box, area: Box): Boolean = (box.intersect(area)?.area ?: 0L) * 2 > box.area

    /** The lines of a text that are on the screen (more than half of each); null: none is. */
    private fun onScreen(whole: TextGeometry, window: Box): TextGeometry? {
        val seen = whole.lines.filter { inside(it, window) }
        return when (seen.size) {
            0 -> null
            whole.lines.size -> whole
            else -> TextGeometry(seen, whole.sizePx)
        }
    }

    private fun release(tree: Tree) {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 33) for (ask in tree.asking) runCatching { ask.node.recycle() }
    }

    private fun isImage(className: CharSequence?): Boolean = className?.contains("Image") == true

    /**
     * Where the app says each letter of a text is (a text view answers; a view drawn by the app
     * itself may not): the lines as drawn and the text's real size. Asked once for a text at a place.
     */
    private fun letters(node: AccessibilityNodeInfo, text: String, bounds: Box): TextGeometry? {
        val key = text.trim() to bounds
        synchronized(letters) { letters[key] }?.let { return it as? TextGeometry }
        lettersCalls.incrementAndGet()
        val found = runCatching {
            if (AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY !in node.availableExtraData) return@runCatching null
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, minOf(text.length, MAX_LETTERS))
            }
            if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, arguments)) return@runCatching null
            @Suppress("DEPRECATION")
            val places = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) ?: return@runCatching null
            TextLines.fromCharacters(
                text,
                places.map { place -> (place as? RectF)?.let { Box(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt()) } },
            )
        // Letters said to be outside their own view: asked while the page was moving. Not believed, not kept.
        }.getOrNull()?.takeIf { it.within(bounds) }
        synchronized(letters) { letters[key] = found ?: NOWHERE }
        return found
    }

    /**
     * The screenshot shows the app's text where the app says it is: at least half of a dozen lines
     * asked. A page still sliding into place shows none of them there; a page at rest shows all but
     * those something else lies over. Lines that may be under a bar are not asked at all.
     */
    private fun agrees(grid: PixelGrid, lines: List<ScreenText>, window: Box, under: Set<Pair<String, Box>>): Boolean {
        // Lines from top to bottom of the page: a part of it still moving (the top seldom does) is seen.
        val exact = lines.filter { !it.fromImage && it.geometry?.lines?.size == 1 && (it.text to it.bounds) !in under }.sortedBy { it.geometry!!.box.top }
        val step = maxOf(1, exact.size / AGREE_LINES)
        var asked = 0
        var yes = 0
        for (i in exact.indices step step) {
            val geometry = exact[i].geometry!!
            val style = LineStyles.of(grid, geometry.box, window)?.takeIf { it.plain } ?: continue
            asked++
            if (style.agrees(geometry.box, geometry.sizePx)) yes++
        }
        return asked < 3 || yes * 2 >= asked
    }

    private fun grid(shot: ScreenCapture.Shot.Image): PixelGrid {
        val bitmap = shot.bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelGrid(bitmap.width, bitmap.height, pixels, shot.left, shot.top)
    }

    /**
     * The app's own text painted over with the colour behind it, before pictures are read (or the
     * reader of pictures reads it too, and it is drawn twice). The colours are those `show` just
     * read, kept by place: an event clearing the page meanwhile does not undo this.
     */
    private fun paintOut(bitmap: Bitmap, shot: ScreenCapture.Shot.Image, lines: List<ScreenText>) {
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (line in lines) {
            if (line.fromImage) continue
            val look = synchronized(looks) {
                val key = line.text to line.bounds
                val geometry = line.geometry ?: inked[key] ?: TextGeometry(listOf(line.bounds), 0f).takeIf { key in inkless } ?: return@synchronized null
                looks[Place(geometry)]
            }?.takeIf { it.style.plain } ?: continue
            paint.color = look.style.background
            for (box in look.geometry.lines) {
                canvas.drawRect(box.left - shot.left - 2f, (box.top - shot.top).toFloat(), box.right - shot.left + 2f, (box.bottom - shot.top).toFloat(), paint)
            }
        }
    }

    // Showing translations

    /**
     * The Chinese of the screen as it is now, each line with its look (read from `grid` where there
     * is a screenshot, remembered or plain where not): draws what is already translated, asks for
     * the rest. True: some line was left out for now and the screen must be read again.
     */
    private fun show(generation: Long, lines: List<ScreenText>, grid: PixelGrid?, window: Box, under: Set<Pair<String, Box>>): Boolean {
        val found = HashMap<ScreenText, Look>()
        var stale = false
        // Small views still moving (a label flipping, pictures going by): nothing is drawn in them yet.
        val wait = controller.speed.scrollMs
        val moving = SystemClock.uptimeMillis().let { now -> moved.filter { now - it.second < wait }.map { it.first } }
        synchronized(looks) {
            for (line in lines) {
                val key = line.text to line.bounds
                val sure = line.geometry ?: inked[key]
                    ?: grid?.takeUnless { key in inkless }?.let { LineStyles.lines(it, line.bounds, line.text) }?.also { inked[key] = it }
                // No ink in its box is this text: where it is, and how large, is a guess from the box.
                val guessed = sure == null
                if (guessed && grid != null && !line.fromImage) inkless[key] = true
                val whole = sure ?: TextGeometry(listOf(line.bounds), minOf(line.bounds.height * 0.6f, GUESSED_SIZE_SP.sp()))
                val geometry = onScreen(whole, window) ?: continue
                if (moving.any { inside(geometry.box, it) }) continue
                val place = Place(geometry)
                val exact = line.geometry != null && !line.fromImage
                val view = line.bounds.takeUnless { line.fromImage }
                val style = grid?.let { LineStyles.of(it, geometry.box, window) }
                if (exact && key in under) {
                    // The app's own letters, perhaps under a bar that stays in place: drawn only
                    // where the picture of the screen shows them. No waiting for them to come.
                    val first = geometry.lines[0]
                    val there = grid?.let { LineStyles.of(it, first, window) }
                    if (there == null || !there.plain || !there.agrees(first, geometry.sizePx)) {
                        if (grid != null) covered[key] = true
                        continue
                    }
                }
                // One line whose letters are not where the app said (it was asked while the page was
                // moving): not drawn now, forgotten, and asked again at the next scan.
                if (style != null && exact && geometry.lines.size == 1 && style.plain && !style.agrees(geometry.box, geometry.sizePx)) {
                    synchronized(letters) { letters.remove(key) }
                    stale = true
                    continue
                }
                val read = style?.let {
                    val ink = it.ink
                    val box = geometry.box
                    when {
                        exact || ink == null || geometry.lines.size != 1 -> Look(geometry, it, view, guessed)
                        // A guess from the box: the ink in it is believed only if it can be this text
                        // on one line (an icon above a label is ink too).
                        guessed && !TextFit.isOneLine(line.text, ink.width, ink.height / HAN_INK) -> Look(geometry, it, view, true)
                        // Text read in a picture or found by its ink: its place and size are those of the ink.
                        else -> Look(
                            TextGeometry(listOf(Box(minOf(box.left, ink.left), minOf(box.top, ink.top), maxOf(box.right, ink.right), maxOf(box.bottom, ink.bottom))), ink.height / HAN_INK),
                            it, view,
                        )
                    }
                }?.also { looks[place] = it }
                found[line] = read ?: looks[place]?.let { if (exact) sameLook(it, geometry) else it } ?: plain(line, geometry)
            }
        }
        val order = found.keys.sortedWith(compareBy({ found.getValue(it).geometry.box.top }, { found.getValue(it).geometry.box.left }))
        page = Page(generation, order, found, window)
        render()
        request(order.map { it.text })
        return stale
    }

    /** A look read before at this place, for text that now ends somewhere else (a countdown's digits). */
    private fun sameLook(look: Look, geometry: TextGeometry): Look {
        if (look.geometry == geometry) return look
        val moved = look.geometry.box.right - geometry.box.right
        return Look(geometry, look.style.copy(freeRight = (look.style.freeRight + moved).coerceAtLeast(0)), look.view)
    }

    /** No screenshot (Android 8 to 10): the theme's colours, and room inside the text's own view. */
    private fun plain(line: ScreenText, geometry: TextGeometry): Look {
        val box = geometry.box
        val background = themeBackground()
        return Look(
            geometry,
            LineStyle(
                background, OverlayFit.textColor(background), plain = true, ink = null,
                freeLeft = (box.left - line.bounds.left).coerceAtLeast(0), freeRight = (line.bounds.right - box.right).coerceAtLeast(0),
            ),
            line.bounds.takeUnless { line.fromImage },
            guessed = line.geometry == null,
        )
    }

    /** Lays out and draws every line of the page that has a translation by now. */
    private fun render() {
        scope.launch(composing) {
            val now = page ?: return@launch
            if (!controller.isCurrent(now.generation)) return@launch
            // The made-up test shop only (never a real app's text): what was read and what it became.
            val trying = foreground == TEST_SHOP
            val entries = now.lines.mapNotNull { line ->
                val known = translator.quick(line.text)?.text
                if (trying) Log.d(TEST_TAG, "${if (line.fromImage) "picture" else "app"} [${line.text}] -> [${known ?: "…"}]")
                if (known == null) return@mapNotNull null
                // Nothing is drawn where the "translation" is the Chinese itself (a glossary entry kept as written).
                if (known == line.text || ChineseText.hasHan(known)) return@mapNotNull null
                OverlayComposer.Entry(now.looks.getValue(line), known, translator.shorter(line.text), picture = line.fromImage)
            }
            val drawn = composer.compose(entries, now.window)
            onMain {
                if (page === now && controller.isCurrent(now.generation)) {
                    overlay.set(drawn)
                    if (!firstDrawn && drawn.isNotEmpty()) {
                        firstDrawn = true
                        Log.d(TAG, "shown ${drawn.size} of ${now.lines.size} lines ${SystemClock.elapsedRealtime() - pageAt} ms after the page")
                    }
                }
            }
        }
    }

    /** Translates what is not known yet; each answer goes into memory and the page is drawn again. */
    private fun request(wanted: List<String>) {
        if (unknown.size > 2_000) unknown.clear()
        val fresh = wanted.distinct().filter { it !in unknown && translator.quick(it) == null && translating.add(it) }
        if (fresh.isEmpty()) return
        // Short texts first (a label, the words by a price: each is quick, and there are many of
        // them), then the long ones; among each, as they come on the page.
        val texts = fresh.sortedBy { if (TextFit.ems(it) <= SHORT_EMS) 0 else 1 }
        scope.launch(translations) {
            // The first answer is drawn at once, the following ones every `DRAW_GAP_MS` at most.
            val news = Channel<Unit>(Channel.CONFLATED)
            val drawing = launch {
                for (ignored in news) {
                    render()
                    delay(DRAW_GAP_MS)
                }
            }
            val began = SystemClock.elapsedRealtime()
            val calls = engineCalls.get()
            val spent = engineMs.get()
            engineLongest.set(0)
            val missed = AtomicInteger()
            try {
                val error = translator.translateAll(texts, ai, wanted) { text, result ->
                    translating.remove(text)
                    if (result == null) {
                        unknown.add(text)
                        missed.incrementAndGet()
                    } else news.trySend(Unit)
                }
                if (error != null) ScreenState.error(error)
            } finally {
                drawing.cancel()
                translating.removeAll(texts.toSet())
            }
            render()
            val done = SystemClock.elapsedRealtime()
            Log.d(TAG, "translated ${texts.size - missed.get()} of ${texts.size} lines in ${done - began} ms: engine ${engineCalls.get() - calls} calls, " +
                "${engineMs.get() - spent} ms in all, longest ${engineLongest.get()} ms; ${done - pageAt} ms after the page")
        }
    }

    private fun themeBackground(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) 0xFF202022.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun Float.sp(): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics)

    private suspend fun pause(generation: Long, reason: SensitiveScreen.Reason) {
        onMain {
            if (controller.isCurrent(generation)) {
                page = null
                overlay.clear()
            }
        }
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
        main.removeCallbacks(keyboardGone)
        main.removeCallbacks(leftCheck)
        scope.cancel()
        readerThreads.shutdown()
        lookups.shutdown()
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
        ScreenState.tileChanged(this)
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

        /** The made-up shop of `android/testshop`: only its text is ever written to the log, to check translations. */
        private const val TEST_SHOP = "vn.mocsinh.testshop"
        private const val TEST_TAG = "MSTransTest"

        /** How long another app must keep the screen before "Tự tắt khi rời app mua sắm" turns the permission off. */
        private const val LEAVE_CHECK_MS = 1_500L

        /** Windows that only pass over the app in front (Android's own). */
        private val PASSING = setOf(
            "com.android.systemui", "android", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.settings", "com.android.intentresolver",
        )
        private const val MAX_NODES = 1_500
        private const val MAX_DEPTH = 60
        private const val MAX_WAIT_MS = 1_500L
        private const val MAX_LETTERS = 2_000
        /** About this many of the app's lines, spread over the page, tell whether a screenshot shows it where the app says. */
        private const val AGREE_LINES = 12
        /** A keyboard seen up is looked for again this long after (long enough for it to slide out). */
        private const val KEYBOARD_CHECK_MS = 400L
        /** A window sliding into place is looked at again this often, for this long at most. */
        private const val REST_STEP_MS = 50L
        private const val MAX_REST_MS = 900L
        /**
         * A page still settling is read again after `SETTLE_MS` (Android 14 allows a screenshot
         * every third of a second) a few times, then once more a second later. On a phone, waiting
         * longer (it was eleven seconds) left pages of real apps without translations for that
         * long: a page that has not settled in two seconds is not sliding into place.
         */
        private const val SETTLE_MS = 350L
        private const val QUICK_RETRIES = 3
        private const val SLOW_SETTLE_MS = 1_000L
        private const val SLOW_RETRIES = 1
        /** How much of its text size a line of Chinese ink is high. */
        private const val HAN_INK = 0.92f
        private const val GUESSED_SIZE_SP = 16f
        private val NOWHERE = Any()
        private val NO_BOX = Box(0, 0, 0, 0)
        /** How many questions about its nodes the app is asked at a time. */
        private const val READERS = 4
        /** An app that has not said within this long which view scrolled is taken to be scrolling its page. */
        private const val SOURCE_WAIT_MS = 150L
        /** How long a small view that scrolled is remembered. */
        private const val MOVED_MS = 2_000L
        /** Texts up to this many letters of Chinese are translated before the longer ones. */
        private const val SHORT_EMS = 8f
        /** Translations coming in are drawn this often at most. */
        private const val DRAW_GAP_MS = 120L

        private fun <K, V> lru(most: Int): MutableMap<K, V> = object : LinkedHashMap<K, V>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > most
        }
    }
}

private fun AccessibilityWindowInfo.box(): Box {
    val rect = Rect()
    getBoundsInScreen(rect)
    return Box(rect.left, rect.top, rect.right, rect.bottom)
}
