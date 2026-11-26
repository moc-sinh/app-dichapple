package vn.mocsinh.mstrans.core.screen

/**
 * When to read the screen, and when to take the overlay away. Events in, actions out; the service
 * carries the actions out. Each page has a generation (a new screen, a scroll, a turn start a new
 * one): a result from an older one is dropped, so the page before never shows on the page after.
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
        /**
         * Something scrolled in `pkg`. `area`: where the view that scrolled is, when it is a small
         * one (a label flipping by itself, a row of pictures); null: the page itself, or not known.
         */
        data class Scrolled(val pkg: String?, val area: Box? = null) : Event
        /** Something on screen changed; `signature` is its Chinese (null: not known yet). */
        data class ContentChanged(val pkg: String?, val signature: Int?) : Event
        /** Windows came or went; `keyboard` says whether a keyboard is up (null: not known). */
        data class WindowsChanged(val keyboard: Boolean?) : Event
    }

    sealed interface Action {
        /** Take every translation off the screen. */
        data object Clear : Action
        /** Take the translations in this place off the screen; the others stay. */
        data class ClearArea(val area: Box) : Action
        /** Forget translations kept in memory. */
        data object ClearCache : Action
        data object CancelScan : Action
        /**
         * Read the screen after `delayMs`. `restart`: the wait starts again (a new page, a scroll
         * going on); if not, a scan already waiting keeps its time (a change on the page).
         */
        data class Scan(val delayMs: Long, val generation: Long, val restart: Boolean = true) : Action
    }

    @Volatile var generation = 0L
        private set
    private var enabled = false
    private var screenOn = true
    private var keyboard = false
    private var currentPackage: String? = null
    private var lastSignature: Int? = null
    private var lastScanAt = NEVER
    /** The page a scroll asked a scan for, when that scan began, and how much longer a list is now waited for. */
    private var scrollGeneration = -1L
    private var scrollScanAt = NEVER
    private var scrollExtra = 0L

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
                // A new page is read at once, whenever the page before was last read (the scan
                // itself waits for a window still sliding into place).
                return if (keyboard) listOf(Action.Clear, Action.CancelScan) else listOf(Action.Clear, Action.Scan(OPEN_MS, generation))
            }
            is Event.Scrolled -> {
                if (!chosen(event.pkg)) return emptyList()
                currentPackage = event.pkg
                if (keyboard) return emptyList()
                // A small view: only its own place is cleared and read again, once it rests.
                if (event.area != null) return listOf(Action.ClearArea(event.area), scan(speed.scrollMs))
                // The list was read while still moving: it is waited for a little longer from now on.
                if (clock() - scrollScanAt < EARLY_MS) scrollExtra += LONGER_MS
                scrollScanAt = NEVER
                bump()
                scrollGeneration = generation
                // Read as soon as it rests, whenever the scan before was: nothing is translated meanwhile.
                val longest = maxOf(speed.settleMs, speed.scrollMs)
                scrollExtra = scrollExtra.coerceAtMost(longest - speed.scrollMs)
                return listOf(Action.Clear, Action.Scan(speed.scrollMs + scrollExtra, generation))
            }
            is Event.ContentChanged -> {
                if (!chosen(event.pkg)) return emptyList()
                currentPackage = event.pkg
                if (keyboard) return emptyList()
                if (event.signature != null) {
                    if (event.signature == lastSignature) return emptyList()
                    lastSignature = event.signature
                }
                // The same page: what is being read and what is shown stay (a countdown would
                // otherwise throw every scan away before it could draw).
                return listOf(scan(speed.settleMs, restart = false))
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
                if (!wasUp) return listOf(scan(speed.settleMs, restart = false))
                bump()
                return listOf(scan(250))
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
        if (generation != this.generation) return
        lastScanAt = clock()
        if (generation == scrollGeneration && scrollScanAt == NEVER) scrollScanAt = lastScanAt
    }

    @Synchronized
    fun scanFinished(generation: Long, signature: Int?) {
        if (generation != this.generation) return
        if (signature != null) lastSignature = signature
        if (generation == scrollGeneration && scrollScanAt != NEVER) {
            // The list rested while it was read: the wait gets shorter again.
            scrollExtra = (scrollExtra - SHORTER_MS).coerceAtLeast(0)
            scrollScanAt = NEVER
            scrollGeneration = -1
        }
    }

    /** One more scan of the same page, after the one under way. */
    @Synchronized
    fun again(): Action.Scan = scan(0, restart = false)

    private fun stop(): List<Action> {
        bump()
        lastSignature = null
        return listOf(Action.Clear, Action.CancelScan, Action.ClearCache)
    }

    private fun bump() {
        generation++
    }

    /** Not sooner than the speed allows after the scan before. */
    private fun scan(requested: Long, restart: Boolean = true): Action.Scan {
        val gap = lastScanAt + speed.minGapMs - clock()
        return Action.Scan(maxOf(requested, gap, 0), generation, restart)
    }

    private companion object {
        /** A new page is read this long after it opens (its first content is there by then). */
        const val OPEN_MS = 150L
        const val NEVER = Long.MIN_VALUE / 2
        /** A scroll this soon after the scan of a list began: the list had not come to rest. */
        const val EARLY_MS = 250L
        const val LONGER_MS = 50L
        const val SHORTER_MS = 25L
    }
}
