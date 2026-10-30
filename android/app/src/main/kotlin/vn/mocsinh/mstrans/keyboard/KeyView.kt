package vn.mocsinh.mstrans.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import vn.mocsinh.mstrans.R
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a key press asks the text field to do. */
sealed class KeyAction {
    /** A letter on the letters page; goes through Telex. */
    data class Letter(val c: Char) : KeyAction()
    /** Text put in as is (digits, punctuation, an accented letter from the long-press row). */
    data class Text(val s: String) : KeyAction()
    data class Delete(val word: Boolean) : KeyAction()
    object Space : KeyAction()
    object Newline : KeyAction()
    /** The space bar held as a trackpad: characters to the right (left when negative). */
    data class MoveCursor(val steps: Int) : KeyAction()
    /** Takes back the key typed last: holding it chose an accent instead, or turned the space bar into a trackpad. */
    object UndoLast : KeyAction()
}

/** The keyboard's own colours, as on the iPhone: white letter keys on grey, darker keys for Shift, ⌫, 123. */
class KeyColors(val dark: Boolean) {
    private fun pick(light: Long, night: Long): Int = (if (dark) night else light).toInt()

    val background = pick(0xFFD3D6DC, 0xFF1F1F21)
    val key = pick(0xFFFFFFFF, 0xFF636366)
    val special = pick(0xFFABB1BA, 0xFF3D3D40)
    /** A letter or the space bar while pressed. */
    val pressed = pick(0xFFABB1BA, 0xFF8E8E93)
    val shadow = pick(0xFF898A8D, 0x80000000)
    val text = pick(0xFF000000, 0xFFFFFFFF)
    val disabledText = pick(0x4D000000, 0x4DFFFFFF)
    val popup = pick(0xFFFFFFFF, 0xFF6C6C70)
    /** Send, Go, Search… in the Mộc Sinh green, like the Dịch button. */
    val prominent = pick(0xFF0B6542, 0xFF1B7A4C)
    val prominentPressed = pick(0xFF084D32, 0xFF146140)
}

/**
 * The key area, laid out and behaving like MSTrans's iPhone keyboard: no dead zones between keys,
 * fast typing with two thumbs, long-press accents, space-bar cursor control, double-tap Shift for
 * Caps Lock and a delete key that speeds up to whole words. One view draws every key, so a touch
 * never waits for a button to be found. Pop-ups and the accent row are drawn by `overlay`, which
 * reaches above the keys.
 */
@SuppressLint("ViewConstructor")
class KeyView(
    context: Context,
    private val colors: KeyColors,
    private val overlay: KeyOverlay,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun perform(action: KeyAction)
        /** The globe key: the next keyboard. */
        fun nextKeyboard()
        /** The globe key held: the list of keyboards. */
        fun chooseKeyboard()
    }

    enum class Shift { OFF, ONCE, LOCKED }
    private enum class Page { LETTERS, NUMBERS, SYMBOLS }

    private sealed class Kind {
        data class Character(val s: String) : Kind()
        object ShiftKey : Kind()
        object Delete : Kind()
        data class PageKey(val page: Page) : Kind()
        object Space : Kind()
        object Return : Kind()
        object Globe : Kind()
    }

    private class Key(val kind: Kind) {
        val frame = RectF()
        val hit = RectF()
        var pressed = false
        /** Fingers on the key while it is lit, and when it lit up (see `unlight`). */
        var litBy = 0
        var litAt = 0L
        val character: String? get() = (kind as? Kind.Character)?.s
    }

    private class Track(var key: Key, val startX: Float, val startY: Float) {
        var committed = false
        /** Typed the moment the finger landed (see `typesOnTouchDown`); a long press takes it back. */
        var typedOnDown = false
        /** Typed as a capital: its accents are offered as capitals too. */
        var upper = false
        /** The key lit instead of a pop-up (see `showsPopups`). */
        var lit: Key? = null
        var popup = false
        var anchorX = 0f
        /** Where the finger was when its key's long press was set: moving off it is not holding. */
        var holdFromX = startX
        var holdFromY = startY
        val beganAt = SystemClock.uptimeMillis()
    }

    var hapticsEnabled = true
    var clicksEnabled = true
    /**
     * Letters, digits, symbols and the space bar type as the finger lands, not as it lifts. Holding a
     * vowel still offers its accents and holding the space bar still moves the cursor: the key typed
     * is taken back. Return still waits for the lift, so a message is never sent by a graze.
     */
    var typesOnTouchDown = true
    /** The enlarged letter above a pressed key. Off, the key lights up instead and hides nothing. */
    var showsPopups = false
    var showsGlobe = false
        set(value) {
            if (field == value) return
            field = value
            built.clear()
            showPage()
        }
    /** Extra height of every key, in dp (Cài đặt › Bàn phím › Cỡ bàn phím). */
    var extraKeyHeight = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    var shift = Shift.OFF
        private set

    private var page = Page.LETTERS
    private var rows: List<List<Key>> = emptyList()
    private var keys: List<Key> = emptyList()
    private val built = HashMap<Page, List<List<Key>>>()
    private val tracks = LinkedHashMap<Int, Track>()
    private var lastShiftTap = 0L
    /** Where the last Shift tap started from: a double tap from a sentence's capital locks capitals too. */
    private var lastShiftFrom = Shift.OFF
    /** Caps Lock, kept while 123 shows. */
    private var capsLock = false
    /** Whether the next letter starts a sentence, as last set: kept while 123 shows. */
    private var wantsAutoShift = false
    private var trackpad = false

    private var returnTitle = "xuống dòng"
    private var returnProminent = false
    private var returnEnabled = true

    private val handler = Handler(Looper.getMainLooper())
    private var longPressPointer: Int? = null
    private val longPress = Runnable { longPressFired() }
    private var trackpadPointer: Int? = null
    private var accentPointer: Int? = null
    private var deleteCount = 0
    private val deleteRepeat = object : Runnable {
        override fun run() {
            deleteTick()
            handler.postDelayed(this, 90)
        }
    }
    private val audio = context.getSystemService(AudioManager::class.java)

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val radius = dp(6.5f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shiftOff = icon(R.drawable.ic_shift)
    private val shiftOn = icon(R.drawable.ic_shift_on)
    private val capsLockIcon = icon(R.drawable.ic_caps_lock)
    private val backspace = icon(R.drawable.ic_backspace)
    private val globe = icon(R.drawable.ic_globe)

    private fun icon(id: Int): Drawable = context.getDrawable(id)!!.mutate().apply { setTint(colors.text) }

    init {
        isHapticFeedbackEnabled = true
        showPage()
    }

    // State from the keyboard

    /** Sentence-start capitalisation; Caps Lock wins. */
    fun setAutoShift(on: Boolean) {
        wantsAutoShift = on
        if (shift == Shift.LOCKED || page != Page.LETTERS) return
        val next = if (on) Shift.ONCE else Shift.OFF
        if (next != shift) {
            shift = next
            invalidate()
        }
    }

    fun configureReturn(title: String, prominent: Boolean, enabled: Boolean) {
        if (title == returnTitle && prominent == returnProminent && enabled == returnEnabled) return
        returnTitle = title
        returnProminent = prominent
        returnEnabled = enabled
        invalidate()
    }

    /** Back to letters, as after a space on the number page. */
    fun showLetters() {
        if (page == Page.LETTERS) return
        page = Page.LETTERS
        shift = lettersShift
        showPage()
    }

    /** The number page, as the keyboard opens on a field for digits. */
    fun showNumbers() {
        if (page == Page.NUMBERS) return
        if (page == Page.LETTERS) capsLock = shift == Shift.LOCKED
        page = Page.NUMBERS
        shift = Shift.OFF
        showPage()
    }

    /** The keyboard went away: nothing stays held, lit or open. */
    fun reset() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(deleteRepeat)
        longPressPointer = null
        trackpadPointer = null
        accentPointer = null
        trackpad = false
        for (track in tracks.values) {
            track.key.pressed = false
            track.lit?.let { it.litBy = 0; it.pressed = false }
        }
        tracks.clear()
        overlay.clear()
        invalidate()
    }

    /** Shift as the letters come back: Caps Lock if it was on, else a capital at a sentence start. */
    private val lettersShift: Shift get() = if (capsLock) Shift.LOCKED else if (wantsAutoShift) Shift.ONCE else Shift.OFF

    // Building and layout

    private fun rowKinds(page: Page): List<List<Kind>> {
        fun chars(s: String) = s.map { Kind.Character(it.toString()) }
        val other = if (page == Page.LETTERS) Page.NUMBERS else Page.LETTERS
        val bottom = if (showsGlobe) listOf(Kind.PageKey(other), Kind.Globe, Kind.Space, Kind.Return)
        else listOf(Kind.PageKey(other), Kind.Space, Kind.Return)
        return when (page) {
            Page.LETTERS -> listOf(chars("qwertyuiop"), chars("asdfghjkl"), listOf(Kind.ShiftKey) + chars("zxcvbnm") + Kind.Delete, bottom)
            Page.NUMBERS -> listOf(chars("1234567890"), chars("-/:;()₫&@\""), listOf(Kind.PageKey(Page.SYMBOLS)) + chars(".,?!'") + Kind.Delete, bottom)
            Page.SYMBOLS -> listOf(chars("[]{}#%^*+="), chars("_\\|~<>€£¥•"), listOf(Kind.PageKey(Page.NUMBERS)) + chars(".,?!'") + Kind.Delete, bottom)
        }
    }

    private fun showPage() {
        rows = built.getOrPut(page) { rowKinds(page).map { row -> row.map { Key(it) } } }
        keys = rows.flatten()
        layoutKeys()
        invalidate()
    }

    /** Where the keys go, in dp, after the iPhone keyboard's (MSTrans's `KeyMetrics`). */
    private class Metrics(
        val keyHeight: Float,
        /** Between letter rows, and above the first row. */
        val rowGap: Float,
        /** Between the z row and the bottom row. */
        val bottomGap: Float,
        val bottomKeyHeight: Float,
        /** Under the bottom row. */
        val bottomPad: Float,
        val side: Float,
        val gap: Float,
    ) {
        val area: Float get() = 3 * (rowGap + keyHeight) + bottomGap + bottomKeyHeight + bottomPad
    }

    private fun metrics(widthPx: Int): Metrics {
        val width = widthPx / density
        val extra = extraKeyHeight
        val m = when {
            // Landscape: the screen is short on its side, so keys grow half as much.
            width > 560 -> Metrics(34 + extra / 2, 7f, 7f, 34 + extra / 2, 4f, 6f, 6f)
            width >= 380 -> Metrics(45 + extra, 11f, 11f, 45 + extra, 6f, 4f, 6f)
            else -> Metrics(42 + extra, 10f, 10f, 42 + extra, 6f, 3.5f, 5.5f)
        }
        return Metrics(dp(m.keyHeight), dp(m.rowGap), dp(m.bottomGap), dp(m.bottomKeyHeight), dp(m.bottomPad), dp(m.side), dp(m.gap))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, metrics(width).area.roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        val width = width.toFloat()
        if (width <= 0 || rows.size != 4) return
        val m = metrics(width.toInt())
        val g = m.gap
        val side = m.side
        val u = (width - 2 * side - 9 * g) / 10
        val bottomTop = height - m.bottomPad - m.bottomKeyHeight
        val zTop = bottomTop - m.bottomGap - m.keyHeight
        val aTop = zTop - m.rowGap - m.keyHeight
        val qTop = aTop - m.rowGap - m.keyHeight
        val spans = listOf(qTop to m.keyHeight, aTop to m.keyHeight, zTop to m.keyHeight, bottomTop to m.bottomKeyHeight)

        for ((r, row) in rows.withIndex()) {
            val (y, h) = spans[r]
            fun place(key: Key, x: Float, w: Float) = key.frame.set(x, y, x + w, y + h)
            when (r) {
                0, 1 -> {
                    val n = row.size
                    var x = (width - (n * u + (n - 1) * g)) / 2
                    for (key in row) { place(key, x, u); x += u + g }
                }
                2 -> {
                    val edge = (u * 1.36f).roundToInt().toFloat()
                    val inner = row.size - 2
                    place(row.first(), side, edge)
                    if (page == Page.LETTERS) {
                        var x = (width - (inner * u + (inner - 1) * g)) / 2
                        for (key in row.subList(1, row.size - 1)) { place(key, x, u); x += u + g }
                    } else {
                        val start = side + edge + 2 * g
                        val end = width - side - edge - 2 * g
                        val keyWidth = (end - start - (inner - 1) * g) / inner
                        var x = start
                        for (key in row.subList(1, row.size - 1)) { place(key, x, keyWidth); x += keyWidth + g }
                    }
                    place(row.last(), width - side - edge, edge)
                }
                else -> {
                    val pageWidth = (u * 2.35f + g).roundToInt().toFloat()
                    val returnWidth = (u * 2.6f + g).roundToInt().toFloat()
                    val globeWidth = (u * 1.25f).roundToInt().toFloat()
                    val fixed = pageWidth + returnWidth + (if (showsGlobe) globeWidth + g else 0f) + 2 * g
                    var x = side
                    for (key in row) {
                        val w = when (key.kind) {
                            is Kind.PageKey -> pageWidth
                            Kind.Globe -> globeWidth
                            Kind.Return -> returnWidth
                            else -> width - 2 * side - fixed
                        }
                        place(key, x, w)
                        x += w + g
                    }
                }
            }
        }
        // Hit areas meet halfway between keys and between rows, and reach the edges: no touch is lost.
        for ((r, row) in rows.withIndex()) {
            val minY = if (r == 0) 0f else (rows[r - 1][0].frame.bottom + row[0].frame.top) / 2
            val maxY = if (r == rows.size - 1) height.toFloat() else (row[0].frame.bottom + rows[r + 1][0].frame.top) / 2
            for ((i, key) in row.withIndex()) {
                val f = key.frame
                val left = if (i == 0) 0f else (row[i - 1].frame.right + f.left) / 2
                val right = if (i == row.size - 1) width else (f.right + row[i + 1].frame.left) / 2
                key.hit.set(left, minY, right, maxY)
            }
        }
    }

    // Drawing

    private fun color(key: Key): Int = when (key.kind) {
        is Kind.Character, Kind.Space -> if (key.pressed) colors.pressed else colors.key
        Kind.Return -> when {
            returnProminent && returnEnabled -> if (key.pressed) colors.prominentPressed else colors.prominent
            returnProminent -> colors.special
            else -> if (key.pressed) colors.key else colors.special
        }
        Kind.ShiftKey -> if (shift == Shift.OFF && !key.pressed) colors.special else colors.key
        else -> if (key.pressed) colors.key else colors.special
    }

    private val shadowRect = RectF()

    override fun onDraw(canvas: Canvas) {
        for (key in keys) {
            val f = key.frame
            fill.color = colors.shadow
            shadowRect.set(f.left, f.top + dp(1f), f.right, f.bottom + dp(1f))
            canvas.drawRoundRect(shadowRect, radius, radius, fill)
            fill.color = color(key)
            canvas.drawRoundRect(f, radius, radius, fill)
            if (!trackpad) drawContent(canvas, key)
        }
    }

    private fun drawContent(canvas: Canvas, key: Key) {
        val f = key.frame
        when (val kind = key.kind) {
            is Kind.Character -> {
                val upper = shift != Shift.OFF && page == Page.LETTERS
                drawLabel(canvas, f, if (upper) kind.s.uppercase() else kind.s, if (page == Page.LETTERS) 23f else 22f, colors.text, -dp(1f))
            }
            Kind.ShiftKey -> drawIcon(canvas, f, when (shift) {
                Shift.LOCKED -> capsLockIcon
                Shift.ONCE -> shiftOn
                Shift.OFF -> shiftOff
            })
            Kind.Delete -> drawIcon(canvas, f, backspace)
            Kind.Globe -> drawIcon(canvas, f, globe)
            is Kind.PageKey -> drawLabel(canvas, f, when (kind.page) {
                Page.LETTERS -> "ABC"
                Page.NUMBERS -> "123"
                Page.SYMBOLS -> "#+="
            }, 16f, colors.text)
            Kind.Space -> drawLabel(canvas, f, "dấu cách", 16f, colors.text)
            Kind.Return -> drawLabel(canvas, f, returnTitle, 16f, when {
                returnProminent && returnEnabled -> 0xFFFFFFFF.toInt()
                returnEnabled -> colors.text
                else -> colors.disabledText
            })
        }
    }

    private fun drawLabel(canvas: Canvas, f: RectF, s: String, sizeDp: Float, color: Int, dy: Float = 0f) {
        text.color = color
        text.textSize = dp(sizeDp)
        val room = f.width() - dp(6f)
        val w = text.measureText(s)
        if (w > room && w > 0) text.textSize = dp(sizeDp) * max(0.6f, room / w)
        val fm = text.fontMetrics
        canvas.drawText(s, f.centerX(), f.centerY() - (fm.ascent + fm.descent) / 2 + dy, text)
    }

    private fun drawIcon(canvas: Canvas, f: RectF, icon: Drawable) {
        val size = dp(22f).roundToInt()
        val left = (f.centerX() - size / 2f).roundToInt()
        val top = (f.centerY() - size / 2f).roundToInt()
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
    }

    private fun setPressed(key: Key, on: Boolean) {
        if (key.pressed == on) return
        key.pressed = on
        invalidate()
    }

    private fun feedback(sound: Int = AudioManager.FX_KEYPRESS_STANDARD, haptic: Boolean = true) {
        if (clicksEnabled) audio?.playSoundEffect(sound, -1f)
        @Suppress("DEPRECATION")
        if (hapticsEnabled && haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    // Touches

    private fun hitKey(x: Float, y: Float): Key? =
        keys.firstOrNull { it.hit.contains(x, y) }
            ?: keys.minByOrNull { hypot(it.frame.centerX() - x, it.frame.centerY() - y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                finish(e.getPointerId(i), e.getX(i), e.getY(i), cancelled = false)
            }
            MotionEvent.ACTION_CANCEL -> for (id in tracks.keys.toList()) finish(id, 0f, 0f, cancelled = true)
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        // Another key while a vowel's accent row is open: the row closes and its letter stays as typed.
        if (accentPointer != null && accentPointer != id) closeAccents()
        // Two thumbs: a key still held is typed by the time the next one goes down, in the order
        // pressed, and no longer opens its accents or the trackpad.
        for ((other, track) in tracks) {
            if (other == id) continue
            cancelLongPress(other)
            if (!track.committed && other != trackpadPointer) commitHeld(track)
        }
        val key = hitKey(x, y) ?: return
        val track = Track(key, x, y)
        tracks[id] = track
        begin(track, id)
    }

    /** A key still held when the next one goes down: typed now, as its lift would have. */
    private fun commitHeld(track: Track) {
        when (track.key.kind) {
            is Kind.Character -> commit(track)
            Kind.Space -> {
                track.committed = true
                listener.perform(KeyAction.Space)
            }
            Kind.Return -> {
                track.committed = true
                if (returnEnabled) listener.perform(KeyAction.Newline)
            }
            else -> Unit
        }
    }

    private fun closeAccents() {
        accentPointer = null
        overlay.picker = null
    }

    private fun begin(track: Track, id: Int) {
        val key = track.key
        when (val kind = key.kind) {
            is Kind.Character -> {
                showPopup(track)
                if (typesOnTouchDown) {
                    track.typedOnDown = true
                    type(track)
                }
                feedback()
                if (ACCENTS.containsKey(kind.s.lowercase())) scheduleLongPress(id, 400)
            }
            Kind.ShiftKey -> {
                feedback()
                val now = SystemClock.uptimeMillis()
                val quick = now - lastShiftTap < 350
                val from = shift
                shift = when (shift) {
                    Shift.LOCKED -> Shift.OFF
                    // A double tap locks capitals: from Shift tapped on, or from a sentence's capital.
                    Shift.ONCE -> if (quick && lastShiftFrom == Shift.OFF) Shift.LOCKED else Shift.OFF
                    Shift.OFF -> if (quick && lastShiftFrom == Shift.ONCE) Shift.LOCKED else Shift.ONCE
                }
                lastShiftTap = now
                lastShiftFrom = from
                track.committed = true
                invalidate()
            }
            Kind.Delete -> {
                feedback(AudioManager.FX_KEYPRESS_DELETE)
                setPressed(key, true)
                listener.perform(KeyAction.Delete(word = false))
                deleteCount = 0
                handler.removeCallbacks(deleteRepeat)
                handler.postDelayed(deleteRepeat, 450)
            }
            is Kind.PageKey -> {
                feedback()
                if (page == Page.LETTERS) capsLock = shift == Shift.LOCKED
                page = kind.page
                shift = if (kind.page == Page.LETTERS) lettersShift else Shift.OFF
                track.committed = true
                showPage()
            }
            Kind.Space -> {
                feedback(AudioManager.FX_KEYPRESS_SPACEBAR)
                setPressed(key, true)
                if (typesOnTouchDown) {
                    track.committed = true
                    track.typedOnDown = true
                    listener.perform(KeyAction.Space)
                }
                track.anchorX = track.startX
                scheduleLongPress(id, 350)
            }
            Kind.Return -> {
                feedback(AudioManager.FX_KEYPRESS_RETURN)
                setPressed(key, true)
            }
            Kind.Globe -> {
                feedback()
                setPressed(key, true)
                scheduleLongPress(id, 500)
            }
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val track = tracks[id] ?: return
        if (id == trackpadPointer) {
            val step = dp(8f)
            val steps = ((x - track.anchorX) / step).toInt()
            if (steps != 0) {
                track.anchorX += steps * step
                listener.perform(KeyAction.MoveCursor(steps))
            }
            return
        }
        if (id == accentPointer) {
            overlay.picker?.let { if (it.select(x)) overlay.invalidate() }
            return
        }
        when (track.key.kind) {
            is Kind.Character -> {
                // A finger that moves off where it held is not holding the key: no accent row.
                if (hypot(x - track.holdFromX, y - track.holdFromY) > dp(10f)) cancelLongPress(id)
                val key = hitKey(x, y) ?: return
                if (key === track.key || key.character == null || track.committed) return
                // Sliding to another letter before lifting types that letter instead.
                track.key = key
                track.holdFromX = x
                track.holdFromY = y
                showPopup(track)
                cancelLongPress(id)
                if (ACCENTS.containsKey(key.character!!.lowercase())) scheduleLongPress(id, 400)
            }
            Kind.Globe -> if (!track.key.hit.contains(x, y)) cancelLongPress(id)
            else -> Unit
        }
    }

    private fun finish(id: Int, x: Float, y: Float, cancelled: Boolean) {
        cancelLongPress(id)
        val track = tracks.remove(id) ?: return
        removePopup(track)
        if (id == trackpadPointer) {
            trackpadPointer = null
            trackpad = false
            setPressed(track.key, false)
            invalidate()
            return
        }
        if (id == accentPointer) {
            accentPointer = null
            val picker = overlay.picker
            overlay.picker = null
            if (!cancelled && picker != null) {
                val chosen = picker.options[picker.selected]
                if (!track.typedOnDown) {
                    consumeOnceShift()
                    listener.perform(KeyAction.Text(chosen))
                } else if (picker.selected != 0) {
                    // The plain letter went in as the finger landed: the accented one replaces it.
                    listener.perform(KeyAction.UndoLast)
                    listener.perform(KeyAction.Text(chosen))
                }
            }
            return
        }
        var dropped = cancelled
        if (cancelled && track.key.character != null && !track.committed) {
            // A short tap that barely moved was a key press, and is typed rather than lost.
            val quick = SystemClock.uptimeMillis() - track.beganAt < 350 && hypot(x - track.startX, y - track.startY) < dp(14f)
            if (quick) dropped = false
        }
        when (track.key.kind) {
            is Kind.Character -> if (!track.committed && !dropped) commit(track)
            Kind.Delete -> {
                handler.removeCallbacks(deleteRepeat)
                setPressed(track.key, false)
            }
            Kind.Space -> {
                setPressed(track.key, false)
                if (!dropped && !track.committed) listener.perform(KeyAction.Space)
            }
            Kind.Return -> {
                setPressed(track.key, false)
                if (!dropped && returnEnabled && !track.committed) listener.perform(KeyAction.Newline)
            }
            Kind.Globe -> {
                setPressed(track.key, false)
                if (!dropped && !track.committed && track.key.hit.contains(x, y)) listener.nextKeyboard()
            }
            else -> Unit
        }
    }

    private fun commit(track: Track) {
        removePopup(track)
        type(track)
    }

    /** Types the key's character. */
    private fun type(track: Track) {
        track.committed = true
        val s = track.key.character ?: return
        track.upper = shift != Shift.OFF && page == Page.LETTERS
        val typed = if (track.upper) s.uppercase() else s
        // Before the keyboard hears of the key: it may turn Shift straight back on.
        consumeOnceShift()
        if (page == Page.LETTERS) listener.perform(KeyAction.Letter(typed[0])) else listener.perform(KeyAction.Text(typed))
    }

    private fun consumeOnceShift() {
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            invalidate()
        }
    }

    // Pop-ups and accents

    /** Marks the key under the finger: its pop-up, or the key lit when pop-ups are off. */
    private fun showPopup(track: Track) {
        val s = track.key.character ?: return removePopup(track)
        if (!showsPopups) {
            if (track.lit === track.key) return
            track.lit?.let { unlight(it) }
            light(track.key)
            track.lit = track.key
            return
        }
        val shown = if (shift != Shift.OFF && page == Page.LETTERS) s.uppercase() else s
        overlay.showPopup(track, RectF(track.key.frame), shown, this)
        track.popup = true
    }

    private fun removePopup(track: Track) {
        track.lit?.let {
            track.lit = null
            unlight(it)
        }
        if (track.popup) {
            track.popup = false
            overlay.removePopup(track)
        }
    }

    private fun light(key: Key) {
        key.litBy += 1
        key.litAt = SystemClock.uptimeMillis()
        setPressed(key, true)
    }

    /** A finger left the key. It goes dark once no finger holds it, and not before it has been lit 70 ms. */
    private fun unlight(key: Key) {
        key.litBy = max(0, key.litBy - 1)
        if (key.litBy > 0) return
        val shown = SystemClock.uptimeMillis() - key.litAt
        if (shown >= MINIMUM_LIT) return setPressed(key, false)
        handler.postDelayed({ if (key.litBy == 0) setPressed(key, false) }, MINIMUM_LIT - shown)
    }

    private fun scheduleLongPress(id: Int, delay: Long) {
        handler.removeCallbacks(longPress)
        longPressPointer = id
        handler.postDelayed(longPress, delay)
    }

    private fun cancelLongPress(id: Int) {
        if (id != longPressPointer) return
        handler.removeCallbacks(longPress)
        longPressPointer = null
    }

    private fun longPressFired() {
        val id = longPressPointer ?: return
        longPressPointer = null
        val track = tracks[id] ?: return
        if (track.committed && !track.typedOnDown) return
        when (val kind = track.key.kind) {
            is Kind.Character -> {
                val options = ACCENTS[kind.s.lowercase()] ?: return
                val upper = if (track.typedOnDown) track.upper else shift != Shift.OFF && page == Page.LETTERS
                removePopup(track)
                val picker = AccentRow(if (upper) options.map { it.uppercase() } else options, RectF(track.key.frame), width.toFloat(), density)
                picker.select(track.holdFromX)
                overlay.picker = picker
                overlay.anchor = this
                accentPointer = id
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            Kind.Space -> {
                // The space typed as the finger landed goes: holding the bar moves the cursor instead.
                if (track.typedOnDown) listener.perform(KeyAction.UndoLast)
                track.committed = true
                trackpadPointer = id
                trackpad = true
                invalidate()
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            Kind.Globe -> {
                track.committed = true
                setPressed(track.key, false)
                listener.chooseKeyboard()
            }
            else -> Unit
        }
    }

    // Delete repeat: letters first, then whole words

    private fun deleteTick() {
        deleteCount += 1
        val words = deleteCount > 16
        if (words && deleteCount % 2 == 1) return
        listener.perform(KeyAction.Delete(word = words))
        feedback(AudioManager.FX_KEYPRESS_DELETE, haptic = false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        reset()
    }

    companion object {
        private const val MINIMUM_LIT = 70L

        val ACCENTS: Map<String, List<String>> = mapOf(
            "a" to listOf("a", "á", "à", "ả", "ã", "ạ", "ă", "â"),
            "e" to listOf("e", "é", "è", "ẻ", "ẽ", "ẹ", "ê"),
            "i" to listOf("i", "í", "ì", "ỉ", "ĩ", "ị"),
            "o" to listOf("o", "ó", "ò", "ỏ", "õ", "ọ", "ô", "ơ"),
            "u" to listOf("u", "ú", "ù", "ủ", "ũ", "ụ", "ư"),
            "y" to listOf("y", "ý", "ỳ", "ỷ", "ỹ", "ỵ"),
            "d" to listOf("d", "đ"),
            "-" to listOf("-", "–", "—", "•"),
            "₫" to listOf("₫", "$", "€", "£", "¥", "₩"),
            "?" to listOf("?", "¿"),
            "!" to listOf("!", "¡"),
            "'" to listOf("'", "‘", "’"),
            "\"" to listOf("\"", "“", "”", "„"),
            "." to listOf(".", "…"),
            "0" to listOf("0", "°"),
            "/" to listOf("/", "\\"),
        )
    }
}

/** The row of accented letters shown when a vowel is held; slide to choose, lift to type. In key-area coordinates. */
class AccentRow(val options: List<String>, key: RectF, areaWidth: Float, density: Float) {
    val inset = 6 * density
    val cellWidth = max(min(key.width(), 40 * density), 30 * density)
    val frame: RectF
    var selected = 0
        private set

    init {
        val w = cellWidth * options.size + 2 * inset
        val h = key.height() + 12 * density
        val x = min(max(key.left - inset, 2 * density), areaWidth - w - 2 * density)
        val y = key.top - h - 8 * density
        frame = RectF(x, y, x + w, y + h)
    }

    /** True when the choice changed. `x` is in the key area's coordinates. */
    fun select(x: Float): Boolean {
        val index = ((x - frame.left - inset) / cellWidth).toInt().coerceIn(0, options.size - 1)
        if (index == selected) return false
        selected = index
        return true
    }
}

/**
 * Draws key pop-ups and the accent row over the whole keyboard, so they can reach up over the bar.
 * Touches pass through it.
 */
class KeyOverlay(context: Context, private val colors: KeyColors) : View(context) {
    private class Popup(val key: RectF, val text: String)

    private val popups = LinkedHashMap<Any, Popup>()
    var picker: AccentRow? = null
        set(value) {
            field = value
            invalidate()
        }
    /** The key area the pop-ups' coordinates are in. */
    var anchor: View? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val cell = RectF()
    private val mine = IntArray(2)
    private val theirs = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    fun showPopup(owner: Any, key: RectF, letter: String, area: View) {
        anchor = area
        popups[owner] = Popup(key, letter)
        invalidate()
    }

    fun removePopup(owner: Any) {
        if (popups.remove(owner) != null) invalidate()
    }

    fun clear() {
        popups.clear()
        picker = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    /** No height of its own: it covers whatever the keyboard's other parts make (it would otherwise take the whole screen). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exact = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), if (exact) MeasureSpec.getSize(heightMeasureSpec) else 0)
    }

    override fun onDraw(canvas: Canvas) {
        val area = anchor ?: return
        if (popups.isEmpty() && picker == null) return
        getLocationInWindow(mine)
        area.getLocationInWindow(theirs)
        val dx = (theirs[0] - mine[0]).toFloat()
        val dy = (theirs[1] - mine[1]).toFloat()
        canvas.save()
        canvas.translate(dx, dy)
        // Never above the keyboard's own top.
        val top = -dy
        for (popup in popups.values) drawPopup(canvas, popup, area.width.toFloat(), top)
        picker?.let { drawPicker(canvas, it, top) }
        canvas.restore()
    }

    private fun drawPopup(canvas: Canvas, popup: Popup, areaWidth: Float, top: Float) {
        val k = popup.key
        val d = density
        val bw = max(k.width() + 22 * d, 48 * d)
        val bh = k.height() + 14 * d
        val bx = min(max(k.centerX() - bw / 2, 0f), areaWidth - bw)
        val by = max(k.top - bh - 6 * d, top)
        val balloon = RectF(bx, by, bx + bw, by + bh)
        path.reset()
        path.addRoundRect(balloon, 10 * d, 10 * d, Path.Direction.CW)
        if (balloon.bottom - 8 * d < k.top + 8 * d) {
            path.moveTo(balloon.left + 3 * d, balloon.bottom - 8 * d)
            path.lineTo(balloon.right - 3 * d, balloon.bottom - 8 * d)
            path.lineTo(k.right, k.top + 8 * d)
            path.lineTo(k.left, k.top + 8 * d)
            path.close()
        }
        path.addRoundRect(k, 6.5f * d, 6.5f * d, Path.Direction.CW)
        fill.color = colors.popup
        fill.setShadowLayer(2 * d, 0f, d, 0x40000000)
        canvas.drawPath(path, fill)
        fill.clearShadowLayer()
        text.color = colors.text
        text.textSize = 34 * d
        val fm = text.fontMetrics
        canvas.drawText(popup.text, balloon.centerX(), balloon.centerY() - (fm.ascent + fm.descent) / 2, text)
    }

    private fun drawPicker(canvas: Canvas, picker: AccentRow, top: Float) {
        val d = density
        val f = RectF(picker.frame)
        if (f.top < top) f.offset(0f, top - f.top)
        fill.color = colors.popup
        fill.setShadowLayer(4 * d, 0f, 2 * d, 0x4D000000)
        canvas.drawRoundRect(f, 10 * d, 10 * d, fill)
        fill.clearShadowLayer()
        text.textSize = 24 * d
        val fm = text.fontMetrics
        for ((i, option) in picker.options.withIndex()) {
            val left = f.left + picker.inset + i * picker.cellWidth
            cell.set(left, f.top + picker.inset, left + picker.cellWidth, f.bottom - picker.inset)
            if (i == picker.selected) {
                fill.color = colors.prominent
                canvas.drawRoundRect(cell, 6 * d, 6 * d, fill)
            }
            text.color = if (i == picker.selected) 0xFFFFFFFF.toInt() else colors.text
            canvas.drawText(option, cell.centerX(), cell.centerY() - (fm.ascent + fm.descent) / 2, text)
        }
    }
}
