package vn.mocsinh.mstrans.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vn.mocsinh.mstrans.core.screen.ScreenController.Action
import vn.mocsinh.mstrans.core.screen.ScreenController.Event

class ScreenControllerTest {
    private var now = 10_000L
    private val chosen = setOf("com.taobao.taobao", "com.alibaba.wireless")
    private val controller = ScreenController(clock = { now }, ownPackage = "vn.mocsinh.mstrans").apply {
        handle(Event.Started) { it in chosen }
    }

    private fun send(event: Event) = controller.handle(event) { it in chosen }

    private fun scans(actions: List<Action>) = actions.filterIsInstance<Action.Scan>()

    @Test
    fun openingTheAppScansSoon() {
        val actions = send(Event.WindowState("com.taobao.taobao"))
        assertTrue(Action.Clear in actions)
        assertEquals(150L, scans(actions).single().delayMs)
    }

    @Test
    fun scrollingHidesAndScansWhenItStops() {
        send(Event.WindowState("com.taobao.taobao"))
        val first = send(Event.Scrolled("com.taobao.taobao"))
        assertTrue(Action.Clear in first)
        assertEquals(Speed.BALANCED.scrollMs, scans(first).single().delayMs)
        val second = send(Event.Scrolled("com.taobao.taobao"))
        // Each scroll moves the scan later: one scan once the list rests.
        assertTrue(scans(second).single().generation > scans(first).single().generation)
        assertFalse(controller.isCurrent(scans(first).single().generation))
        assertTrue(controller.isCurrent(scans(second).single().generation))
    }

    @Test
    fun aNewPageDropsTheOldPagesResults() {
        val scan = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(scan.generation)
        send(Event.WindowState("com.taobao.taobao"))
        assertFalse(controller.isCurrent(scan.generation))
    }

    @Test
    fun anotherAppClearsAtOnceAndReadsNothing() {
        send(Event.WindowState("com.taobao.taobao"))
        val leave = send(Event.WindowState("com.android.chrome"))
        assertEquals(listOf(Action.Clear, Action.CancelScan, Action.ClearCache), leave)
        // Events from the app not chosen are ignored.
        assertEquals(emptyList(), send(Event.Scrolled("com.android.chrome")))
        assertEquals(emptyList(), send(Event.ContentChanged("com.android.chrome", 1)))
        assertEquals(emptyList(), send(Event.WindowsChanged(keyboard = false)))
    }

    @Test
    fun switchingBetweenChosenAppsGoesOn() {
        send(Event.WindowState("com.taobao.taobao"))
        val actions = send(Event.WindowState("com.alibaba.wireless"))
        assertEquals(1, scans(actions).size)
        assertFalse(Action.ClearCache in actions)
    }

    @Test
    fun msTransItselfIsNeverTranslated() {
        send(Event.WindowState("com.taobao.taobao"))
        val actions = send(Event.WindowState("vn.mocsinh.mstrans"))
        assertTrue(Action.Clear in actions)
        assertTrue(scans(actions).isEmpty())
    }

    @Test
    fun contentChangeOnlyWhenTheChineseChanges() {
        send(Event.WindowState("com.taobao.taobao"))
        assertEquals(1, scans(send(Event.ContentChanged("com.taobao.taobao", 42))).size)
        assertEquals(emptyList(), send(Event.ContentChanged("com.taobao.taobao", 42)))
        val changed = send(Event.ContentChanged("com.taobao.taobao", 43))
        assertEquals(1, scans(changed).size)
        // No flicker: what is shown stays until the new scan draws.
        assertFalse(Action.Clear in changed)
    }

    @Test
    fun scanReportsItsSignature() {
        send(Event.WindowState("com.taobao.taobao"))
        val scan = scans(send(Event.ContentChanged("com.taobao.taobao", null))).single()
        controller.scanFinished(scan.generation, 7)
        assertEquals(emptyList(), send(Event.ContentChanged("com.taobao.taobao", 7)))
    }

    @Test
    fun keyboardPausesAndResumes() {
        send(Event.WindowState("com.taobao.taobao"))
        val shown = send(Event.WindowsChanged(keyboard = true))
        assertEquals(listOf(Action.Clear, Action.CancelScan), shown)
        assertEquals(emptyList(), send(Event.Scrolled("com.taobao.taobao")))
        val hidden = send(Event.WindowsChanged(keyboard = false))
        assertEquals(1, scans(hidden).size)
    }

    @Test
    fun lockingStopsEverythingUntilUnlocked() {
        send(Event.WindowState("com.taobao.taobao"))
        assertEquals(listOf(Action.Clear, Action.CancelScan, Action.ClearCache), send(Event.ScreenOff))
        assertEquals(emptyList(), send(Event.ContentChanged("com.taobao.taobao", 9)))
        assertEquals(1, scans(send(Event.ScreenOn)).size)
    }

    @Test
    fun turnedOffClearsAndStays() {
        send(Event.WindowState("com.taobao.taobao"))
        assertEquals(listOf(Action.Clear, Action.CancelScan, Action.ClearCache), send(Event.Stopped))
        assertEquals(emptyList(), send(Event.WindowState("com.taobao.taobao")))
        assertEquals(1, scans(send(Event.Started)).size)
    }

    @Test
    fun rotationClearsAndScansAgain() {
        send(Event.WindowState("com.taobao.taobao"))
        val actions = send(Event.Rotated)
        assertTrue(Action.Clear in actions)
        assertEquals(1, scans(actions).size)
    }

    @Test
    fun scansOfTheSamePageKeepTheirDistance() {
        controller.speed = Speed.SAVER
        val first = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(first.generation)
        now += 100
        val next = scans(send(Event.ContentChanged("com.taobao.taobao", null))).single()
        assertEquals(Speed.SAVER.minGapMs - 100, next.delayMs)
    }

    @Test
    fun aNewPageIsReadWithoutWaitingForThatDistance() {
        controller.speed = Speed.SAVER
        val first = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(first.generation)
        now += 100
        val next = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        assertEquals(150L, next.delayMs)
        assertTrue(next.restart)
    }

    @Test
    fun aListAtRestIsReadWithoutWaitingForThatDistance() {
        controller.speed = Speed.SAVER
        val first = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(first.generation)
        now += 100
        // The translations are off the screen while a list moves: they come back as soon as it rests.
        val next = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
        assertEquals(Speed.SAVER.scrollMs, next.delayMs)
    }

    @Test
    fun aSmallViewScrollingClearsOnlyItsOwnPlace() {
        val scan = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(scan.generation)
        // A label that flips by itself, a row of pictures going by: the rest of the page stays translated.
        val place = Box(600, 300, 900, 360)
        val actions = send(Event.Scrolled("com.taobao.taobao", area = place))
        assertEquals(Action.ClearArea(place), actions.first())
        assertFalse(Action.Clear in actions)
        assertTrue(controller.isCurrent(scan.generation))
        val again = scans(actions).single()
        assertEquals(scan.generation, again.generation)
        // It is read once it rests.
        assertTrue(again.restart)
    }

    @Test
    fun aSmallViewScrollingIsNotReadWhileAKeyboardIsUp() {
        send(Event.WindowState("com.taobao.taobao"))
        send(Event.WindowsChanged(keyboard = true))
        assertEquals(emptyList(), send(Event.Scrolled("com.taobao.taobao", area = Box(0, 0, 10, 10))))
    }

    @Test
    fun aListReadWhileStillMovingIsWaitedForLongerNextTime() {
        send(Event.WindowState("com.taobao.taobao"))
        val first = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
        assertEquals(Speed.BALANCED.scrollMs, first.delayMs)
        // Its scan begins, and the list scrolls on: it had not come to rest.
        now += first.delayMs
        controller.scanStarted(first.generation)
        now += 60
        val second = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
        assertTrue(second.delayMs > first.delayMs, "${second.delayMs}")
        // Never longer than a change on the page is waited for.
        repeat(20) {
            val scan = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
            now += scan.delayMs
            controller.scanStarted(scan.generation)
            now += 60
        }
        assertEquals(Speed.BALANCED.settleMs, scans(send(Event.Scrolled("com.taobao.taobao"))).single().delayMs)
    }

    @Test
    fun aListThatRestedWhenReadIsWaitedForLessAgain() {
        send(Event.WindowState("com.taobao.taobao"))
        val first = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
        now += first.delayMs
        controller.scanStarted(first.generation)
        now += 60
        val longer = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
        // This time the scan runs to its end before anything moves.
        repeat(10) {
            now += 2_000
            val scan = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
            now += scan.delayMs
            controller.scanStarted(scan.generation)
            now += 500
            controller.scanFinished(scan.generation, 1)
        }
        now += 2_000
        val later = scans(send(Event.Scrolled("com.taobao.taobao"))).single()
        assertTrue(later.delayMs < longer.delayMs, "${later.delayMs}")
        assertEquals(Speed.BALANCED.scrollMs, later.delayMs)
    }

    @Test
    fun aChangeOnThePageKeepsTheScanThatIsRunning() {
        val scan = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(scan.generation)
        // A countdown ticks: the scan under way is still wanted, and one more comes after it.
        val tick = scans(send(Event.ContentChanged("com.taobao.taobao", null))).single()
        assertTrue(controller.isCurrent(scan.generation))
        assertEquals(scan.generation, tick.generation)
        // It does not move a scan already waiting to a later time.
        assertFalse(tick.restart)
    }

    @Test
    fun windowsComingAndGoingKeepTheScanThatIsRunning() {
        val scan = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(scan.generation)
        val windows = scans(send(Event.WindowsChanged(keyboard = false))).single()
        assertTrue(controller.isCurrent(scan.generation))
        assertFalse(windows.restart)
    }

    @Test
    fun aScanAskedForWhileOneRunsComesAfterItAtTheUsualDistance() {
        val scan = scans(send(Event.WindowState("com.taobao.taobao"))).single()
        controller.scanStarted(scan.generation)
        now += 200
        val again = controller.again()
        assertEquals(scan.generation, again.generation)
        assertEquals(Speed.BALANCED.minGapMs - 200, again.delayMs)
        assertFalse(again.restart)
    }
}
