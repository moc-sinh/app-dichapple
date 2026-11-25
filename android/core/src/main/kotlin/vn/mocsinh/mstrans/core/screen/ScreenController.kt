package vn.mocsinh.mstrans.core.screen

/**
 * When to read the screen, and when to take the overlay away. Events in, actions out; the service
 * carries the actions out. Each scan has a generation: a result from an older one is dropped, so the
 * page before never shows on the page after.
 */
class ScreenController(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ownPackage: String,
    @Volatile var speed: Speed = Speed.BALANCED,
) {
    sealed interface Event {
        /** Turned on (switch on, permission given, not paused). */
        data object Started : Event
        /** Turned off or paused. */
        data object Stopped : Event
        data object ScreenOff : Event
        /** The phone unlocked. */
        data object ScreenOn : Event
        data object Rotated : Event
        /** A new screen or dialog in `pkg`. */
        data class WindowState(val pkg: String?) : Event
        data class Scrolled(val pkg: String?) : Event
        /** Something on screen changed; `signature` is its Chinese (null: not known yet). */
        data class ContentChanged(val pkg: String?, val signature: Int?) : Event
        /** Windows came or went; `keyboard` says whether a keyboard is up (null: not known). */
        data class WindowsChanged(val keyboard: Boolean?) : Event
    }

    sealed interface Action {
        /** Take every translation off the screen. */
        data object Clear : Action
        /** Forget translations kept in memory. */
        data object ClearCache : Action
        data object CancelScan : Action
        /** Read the screen after `delayMs` (replacing a scan already waiting). */
        data class Scan(val delayMs: Long, val generation: Long) : Action
    }

    @Volatile var generation = 0L
        private set
    private var enabled = false
    private var screenOn = true
    private var keyboard = false
    private var currentPackage: String? = null
    private var lastSignature: Int? = null
    private var lastScanAt = Long.MIN_VALUE / 2

    /** The app in front, as far as the events tell. */
    val foreground: String? get() = currentPackage

    @Synchronized
    fun handle(event: Event, selected: (String) -> Boolean): List<Action> {
        fun inChosenApp() = currentPackage.let { it != null && it != ownPackage && selected(it) }
        fun chosen(pkg: String?) = pkg != null && pkg != ownPackage && selected(pkg)
        when (event) {
            Event.Started -> {
                if (enabled) return emptyList()
                enabled = true
                return if (screenOn && inChosenApp() && !keyboard) listOf(scan(250)) else emptyList()
            }
            Event.Stopped -> {
                if (!enabled) return emptyList()
                enabled = false
                return stop()
            }
            Event.ScreenOff -> {
                if (!screenOn) return emptyList()
                screenOn = false
                return if (enabled) stop() else emptyList()
            }
            Event.ScreenOn -> {
                if (screenOn) return emptyList()
                screenOn = true
                return if (enabled && inChosenApp() && !keyboard) listOf(scan(600)) else emptyList()
            }
            else -> if (!enabled || !screenOn) return emptyList()
        }
        when (event) {
            is Event.WindowState -> {
                val pkg = event.pkg ?: return emptyList()
                if (!chosen(pkg)) {
                    val wasIn = inChosenApp()
                    currentPackage = pkg
                    if (!wasIn) return emptyList()
                    bump()
                    return if (pkg == ownPackage) listOf(Action.Clear, Action.CancelScan)
                    else listOf(Action.Clear, Action.CancelScan, Action.ClearCache)
                }
                currentPackage = pkg
                lastSignature = null
                bump()
                return if (keyboard) listOf(Action.Clear, Action.CancelScan) else listOf(Action.Clear, scan(250))
            }
            is Event.Scrolled -> {
                if (!chosen(event.pkg)) return emptyList()
                currentPackage = event.pkg
                if (keyboard) return emptyList()
                bump()
                return listOf(Action.Clear, scan(speed.settleMs))
            }
            is Event.ContentChanged -> {
                if (!chosen(event.pkg)) return emptyList()
                currentPackage = event.pkg
                if (keyboard) return emptyList()
                if (event.signature != null) {
                    if (event.signature == lastSignature) return emptyList()
                    lastSignature = event.signature
                }
                bump()
                return listOf(scan(speed.settleMs))
            }
            is Event.WindowsChanged -> {
                if (!inChosenApp()) return emptyList()
                if (event.keyboard == true) {
                    if (keyboard) return emptyList()
                    keyboard = true
                    bump()
                    return listOf(Action.Clear, Action.CancelScan)
                }
                val wasUp = keyboard
                if (event.keyboard == false) keyboard = false
                if (keyboard) return emptyList()
                bump()
                return listOf(scan(if (wasUp) 250 else speed.settleMs))
            }
            Event.Rotated -> {
                if (!inChosenApp() || keyboard) return emptyList()
                bump()
                return listOf(Action.Clear, scan(400))
            }
            else -> return emptyList()
        }
    }

    /** True while the scan of `generation` is still wanted. */
    fun isCurrent(generation: Long): Boolean = enabled && screenOn && generation == this.generation

    @Synchronized
    fun scanStarted(generation: Long) {
        if (generation == this.generation) lastScanAt = clock()
    }

    @Synchronized
    fun scanFinished(generation: Long, signature: Int?) {
        if (generation == this.generation && signature != null) lastSignature = signature
    }

    private fun stop(): List<Action> {
        bump()
        lastSignature = null
        return listOf(Action.Clear, Action.CancelScan, Action.ClearCache)
    }

    private fun bump() {
        generation++
    }

    /** Not sooner than the speed allows after the scan before. */
    private fun scan(requested: Long): Action.Scan {
        val gap = lastScanAt + speed.minGapMs - clock()
        return Action.Scan(maxOf(requested, gap, 0), generation)
    }
}
