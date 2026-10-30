package vn.mocsinh.mstrans.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.core.ChatLine
import vn.mocsinh.mstrans.core.HistoryItem
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.LanguageDetector
import vn.mocsinh.mstrans.core.Settings
import vn.mocsinh.mstrans.core.TranslateError
import vn.mocsinh.mstrans.core.Translation
import vn.mocsinh.mstrans.core.Translator
import vn.mocsinh.mstrans.core.target
import vn.mocsinh.mstrans.core.telex.Composer
import vn.mocsinh.mstrans.core.telex.TypedText
import vn.mocsinh.mstrans.ui.Colors
import vn.mocsinh.mstrans.ui.PillStyle
import vn.mocsinh.mstrans.ui.TextSize
import vn.mocsinh.mstrans.ui.copyText
import vn.mocsinh.mstrans.ui.dp
import vn.mocsinh.mstrans.ui.dpf
import vn.mocsinh.mstrans.ui.horizontal
import vn.mocsinh.mstrans.ui.label
import vn.mocsinh.mstrans.ui.leading
import vn.mocsinh.mstrans.ui.oneLine
import vn.mocsinh.mstrans.ui.pill
import vn.mocsinh.mstrans.ui.pressable
import vn.mocsinh.mstrans.ui.rounded
import vn.mocsinh.mstrans.ui.spinner
import vn.mocsinh.mstrans.ui.vertical
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * The translation keyboard, as on the iPhone: an info line, a translate bar and a Telex keyboard.
 *
 * - Info line, only while there is something to read: the Vietnamese translation of the partner's
 *   message the user just copied, or the meaning of what was just sent.
 * - Translate bar: the Mộc Sinh mark (opens everything at once: the partner's last message, the
 *   check of what was sent, the context), the target language ("Tự động" follows the partner),
 *   "Dịch", undo.
 * - Keys: `KeyView`; typing goes through `Composer`.
 */
class MSTransKeyboard : InputMethodService(), KeyView.Listener {
    private sealed class Mode {
        object Normal : Mode()
        object Picking : Mode()
        object Busy : Mode()
        object Done : Mode()
        data class Failed(val message: String) : Mode()
        data class Panel(val title: String) : Mode()
    }

    private sealed class Info {
        object Idle : Info()
        /** Something new was copied; translating it waits for a tap unless instant mode is on. */
        object Copied : Info()
        object IncomingBusy : Info()
        data class Incoming(val source: String, val translation: String) : Info()
        data class Outgoing(val translation: Translation, val original: String) : Info()
        data class Message(val text: String) : Info()
    }

    private enum class Caps { NONE, WORDS, SENTENCES, CHARACTERS }

    private class Typed(val before: String?, val edits: List<Composer.Edit>)

    private val settings: Settings get() = MSTransApp.of(this).settings
    private val scope = MainScope()
    /** The context and history (JSON in the settings) are read and written here, in order, off the main thread. */
    private val store = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())
    private val clipboard: ClipboardManager? by lazy { getSystemService(ClipboardManager::class.java) }

    private var built = false
    private lateinit var c: Colors
    private lateinit var kc: KeyColors
    private lateinit var root: FrameLayout
    private lateinit var infoLine: TextView
    private lateinit var bar: LinearLayout
    private lateinit var keys: KeyView
    private lateinit var overlay: KeyOverlay
    private lateinit var panel: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var panelText: TextView
    private lateinit var panelButton: TextView
    private var panelAction: (() -> Unit)? = null
    /** Runs when "Xong" closes the panel. */
    private var panelDone: (() -> Unit)? = null
    private var languagePill: TextView? = null

    private var mode: Mode = Mode.Normal
        set(value) {
            field = value
            renderBar()
        }
    private var info: Info = Info.Idle
        set(value) {
            field = value
            renderInfo()
        }

    private var targetCode = Language.AUTO
    private var activeTarget: Language? = null
    private var original = ""
    /** The latest translations both ways, for the check of what was sent and the overview. */
    private var lastOutgoing: Pair<Translation, String>? = null
    private var lastIncoming: Pair<String, String>? = null
    private var task: Job? = null
    private var incomingTask: Job? = null
    /** The clipboard already translated by itself in instant mode, so it is not sent twice. */
    private var attemptedClip: Int? = null
    /** The clipboard at the last look, and when the user's message was last translated. */
    private var seenClip: Int? = null
    private var clipAtOutgoing: Int? = null
    /** The text field the keyboard types in: another one means a translation must not go there. */
    private var fieldKey: String? = null
    private var switchedWhileBusy = false
    /** The field takes text as typed: an e-mail address, a link, a password, a number. */
    private var plainTyping = false
    /** The editor takes key events only. */
    private var rawKeys = false
    private var multiLine = false
    private var caps = Caps.SENTENCES
    private var shown = false

    private val composer = Composer()
    private var lastKeyAt = -10.0
    private var typedSinceAppear = false
    private var checkPending = false
    /** The field was looked at since the last key (the next key need not look first). */
    private var checkedSinceKey = true
    /** The key typed last and the text before it, so holding that key can take it back. */
    private var lastTyped: Typed? = null
    /** The selection as the app last reported it, moved on by each edit sent since. */
    private var selStart = -1
    private var selEnd = -1
    /** Where the edits sent will put the cursor, in order: their reports are not changes made elsewhere. */
    private val expected = ArrayList<Int>()
    /** The language "Tự động" stands for on the pill, worked out off the main thread. */
    private var pillTarget: Language? = null
    private var pillRequest = 0

    private val check = Runnable { checkField() }
    private val clipRetry = Runnable { checkClipboard(waitForTyping = true) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { checkClipboard(waitForTyping = true) }

    private fun now(): Double = SystemClock.uptimeMillis() / 1000.0

    // Lifecycle

    override fun onCreate() {
        super.onCreate()
        // The first Telex key builds the engine's tables, and the first translation the language
        // model: now, off the main thread, not on the first key.
        Thread {
            val warm = Composer()
            warm.start("", "", 0.0)
            for (key in "nguwowif chuaw") if (key == ' ') warm.space(0.0) else warm.letter(key, 0.0)
            LanguageDetector.language("Can you send me the price list?")
        }.start()
    }

    override fun onDestroy() {
        scope.cancel()
        store.close()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    /** Never the full-screen text box Android offers in landscape: the keyboard stays as it is. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        build()
        return root
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        if (!built) build()
        shown = true
        val key = "${editorInfo.packageName}#${editorInfo.fieldId}"
        val sameField = key == fieldKey
        fieldKey = key
        targetCode = settings.targetCode
        keys.hapticsEnabled = settings.haptics
        keys.clicksEnabled = settings.keySounds
        keys.typesOnTouchDown = settings.typesOnTouchDown
        keys.showsPopups = settings.keyPopups
        keys.extraKeyHeight = floatArrayOf(0f, 3f, 6f)[settings.keyboardSize]
        keys.showsGlobe = offersGlobe()
        readFieldTraits(editorInfo)
        selStart = editorInfo.initialSelStart
        selEnd = editorInfo.initialSelEnd
        expected.clear()
        lastTyped = null
        readField()
        typedSinceAppear = false
        configureReturn()
        updateAutoCapitalization()
        refreshPillTarget()
        // The same field started again (the app sent the message and reset it): what was just
        // translated stays on show.
        if (!(restarting && sameField)) {
            mode = if (switchedWhileBusy && !sameField) Mode.Failed(FIELD_SWITCHED) else Mode.Normal
            info = Info.Idle
        }
        switchedWhileBusy = false
        matchNavigationBar()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        clipboard?.addPrimaryClipChangedListener(clipListener)
        checkClipboard(waitForTyping = false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        shown = false
        // The text being translated was read from this field: it must not replace another one.
        if (mode == Mode.Busy) switchedWhileBusy = true
        task?.cancel()
        incomingTask?.cancel()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        main.removeCallbacks(check)
        main.removeCallbacks(clipRetry)
        checkPending = false
        if (built) keys.reset()
    }

    private fun offersGlobe(): Boolean {
        if (Build.VERSION.SDK_INT >= 28) return shouldOfferSwitchingToNextInputMethod()
        val token = window?.window?.attributes?.token ?: return false
        @Suppress("DEPRECATION")
        return getSystemService(InputMethodManager::class.java)?.shouldOfferSwitchingToNextInputMethod(token) ?: false
    }

    override fun nextKeyboard() {
        if (Build.VERSION.SDK_INT >= 28) {
            switchToNextInputMethod(false)
            return
        }
        val token = window?.window?.attributes?.token ?: return
        @Suppress("DEPRECATION")
        getSystemService(InputMethodManager::class.java)?.switchToNextInputMethod(token, false)
    }

    override fun chooseKeyboard() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    /** The strip under the keys in the keyboard's colour. */
    private fun matchNavigationBar() {
        val w = window?.window ?: return
        @Suppress("DEPRECATION")
        run {
            w.navigationBarColor = kc.background
            if (Build.VERSION.SDK_INT >= 27) {
                val flags = w.decorView.systemUiVisibility
                w.decorView.systemUiVisibility =
                    if (kc.dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    /** What the field is for. An e-mail address, a link, a password or a number is typed as is; digits open on the number page. */
    private fun readFieldTraits(editorInfo: EditorInfo) {
        val type = editorInfo.inputType
        val kind = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val plainVariations = listOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        val digits = kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE || kind == InputType.TYPE_CLASS_DATETIME
        rawKeys = kind == InputType.TYPE_NULL
        plainTyping = digits || rawKeys || (kind == InputType.TYPE_CLASS_TEXT && variation in plainVariations)
        multiLine = kind == InputType.TYPE_CLASS_TEXT && type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        caps = when {
            plainTyping -> Caps.NONE
            type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> Caps.CHARACTERS
            type and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> Caps.WORDS
            type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 -> Caps.SENTENCES
            else -> Caps.NONE
        }
        if (digits) keys.showNumbers() else keys.showLetters()
    }

    // Building

    private fun build() {
        c = Colors.of(this)
        kc = KeyColors(c.dark)
        root = FrameLayout(this).apply { setBackgroundColor(kc.background) }
        val column = vertical()

        infoLine = label("", TextSize.SUBHEADLINE, c.text).oneLine().apply {
            setPadding(dp(12), 0, dp(12), 0)
            background = pressable(null, 0f)
            visibility = View.GONE
            setOnClickListener { openInfo() }
        }
        column.addView(infoLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))

        bar = horizontal(6).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(8), 0)
        }
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        overlay = KeyOverlay(this, kc)
        keys = KeyView(this, kc, overlay, this)
        val area = FrameLayout(this)
        area.addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildPanel()
        area.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
            setMargins(dp(6), dp(4), dp(6), dp(4))
        })
        column.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        built = true
        renderBar()
        renderInfo()
    }

    private fun buildPanel() {
        panel = vertical(8).apply {
            background = rounded(kc.popup, dpf(12))
            setPadding(dp(12), dp(8), dp(12), dp(10))
            visibility = View.GONE
            isClickable = true
        }
        panelText = label("", TextSize.BODY, c.text)
        panelScroll = ScrollView(this).apply { addView(panelText) }
        panel.addView(panelScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panelButton = label("", TextSize.CALLOUT, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            background = pressable(rounded(c.greenFill, dpf(10)), dpf(10), 0x33FFFFFF)
            setOnClickListener { panelAction?.invoke() }
        }
        panel.addView(panelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
    }

    // Keys

    override fun perform(action: KeyAction) {
        val ic = currentInputConnection ?: return
        val now = now()
        if (composer.before == null) {
            readField()
        } else if (now - lastKeyAt >= SETTLE && !checkedSinceKey) {
            // The first key after a pause: the field has caught up, so it is looked at first.
            reconcile(now)
        }
        lastKeyAt = now
        checkedSinceKey = false
        typedSinceAppear = true
        val before = composer.before
        var typed = false
        var edits: List<Composer.Edit> = emptyList()
        when (action) {
            is KeyAction.Letter -> {
                edits = if (plainTyping) composer.insert(action.c.toString(), now) else composer.letter(action.c, now)
                typed = true
            }
            is KeyAction.Text -> {
                edits = composer.insert(action.s, now)
                typed = true
            }
            is KeyAction.Delete -> {
                if (selStart in 0 until selEnd) {
                    // Selected text goes as a whole.
                    ic.commitText("", 1)
                    composer.forget()
                    expect(selStart)
                } else {
                    edits = if (action.word) composer.deleteWord(now) else composer.deleteBackward(now)
                }
            }
            KeyAction.Space -> {
                edits = composer.space(now)
                typed = true
                keys.showLetters()
            }
            KeyAction.Newline -> pressReturn(ic, now, before)
            is KeyAction.MoveCursor -> moveCursor(ic, action.steps, now)
            KeyAction.UndoLast -> {
                val last = lastTyped
                val undo = last?.let { Composer.inverse(it.edits, it.before) }
                if (undo != null) {
                    apply(ic, undo, before)
                    // The next key reads the field again.
                    composer.forget()
                }
            }
        }
        lastTyped = if (typed) Typed(before, edits) else null
        apply(ic, edits, before)
        scheduleCheck()
        edited()
    }

    /**
     * Sends the edits to the app. Deletes count characters as a person sees them: each becomes the
     * UTF-16 units it takes in the text known before the cursor, or a ⌫ key past it.
     */
    private fun apply(ic: InputConnection, edits: List<Composer.Edit>, before: String?) {
        if (edits.isEmpty()) return
        if (rawKeys) {
            for (edit in edits) when (edit) {
                is Composer.Edit.Delete -> repeat(edit.count) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
                is Composer.Edit.Insert -> edit.text.forEach { sendKeyChar(it) }
            }
            expected.clear()
            return
        }
        val known = StringBuilder(before ?: "")
        var exact = before != null
        var deleted = 0
        var inserted = 0
        ic.beginBatchEdit()
        for (edit in edits) when (edit) {
            is Composer.Edit.Delete -> {
                var units = 0
                var past = 0
                repeat(edit.count) {
                    if (known.isEmpty()) {
                        past++
                    } else {
                        val cut = Composer.lastGraphemeStart(known)
                        units += known.length - cut
                        known.setLength(cut)
                    }
                }
                if (units > 0) ic.deleteSurroundingText(units, 0)
                if (past > 0) {
                    exact = false
                    repeat(past) {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                    }
                }
                deleted += units
            }
            is Composer.Edit.Insert -> {
                ic.commitText(edit.text, 1)
                known.append(edit.text)
                inserted += edit.text.length
            }
        }
        ic.endBatchEdit()
        if (exact && selStart >= 0) expect(selStart - deleted + inserted) else expected.clear()
    }

    /** The cursor will be at `position` once the app has the edits sent. */
    private fun expect(position: Int) {
        // Not moved: the app reports nothing.
        if (position == selStart && position == selEnd) return
        expected.add(position)
        if (expected.size > 64) expected.removeAt(0)
        selStart = position
        selEnd = position
    }

    /** Return: the field's action (Gửi, Tìm kiếm…) or a new line. */
    private fun pressReturn(ic: InputConnection, now: Double, before: String?) {
        val action = editorAction()
        when {
            action != null -> {
                apply(ic, composer.finishWord(now), before)
                ic.performEditorAction(action)
            }
            multiLine || noEnterAction() -> apply(ic, composer.newline(now), before)
            else -> {
                apply(ic, composer.finishWord(now), before)
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    private fun noEnterAction(): Boolean =
        (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

    /** The action the field asks Return to take, or null for a new line. */
    private fun editorAction(): Int? {
        val editorInfo = currentInputEditorInfo ?: return null
        if (noEnterAction()) return null
        if (editorInfo.actionLabel != null && editorInfo.actionId != 0) return editorInfo.actionId
        return when (val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS -> action
            else -> null
        }
    }

    private fun configureReturn() {
        val editorInfo = currentInputEditorInfo
        val action = editorAction()
        val title = when {
            action == null -> "xuống dòng"
            editorInfo?.actionLabel != null && editorInfo.actionId == action -> editorInfo.actionLabel.toString()
            action == EditorInfo.IME_ACTION_GO -> "Đi"
            action == EditorInfo.IME_ACTION_SEARCH -> "Tìm kiếm"
            action == EditorInfo.IME_ACTION_SEND -> "Gửi"
            action == EditorInfo.IME_ACTION_NEXT -> "Tiếp"
            action == EditorInfo.IME_ACTION_PREVIOUS -> "Trước"
            else -> "Xong"
        }
        keys.configureReturn(title, prominent = action != null, enabled = true)
    }

    /** The space bar held as a trackpad: the cursor moves over whole characters. */
    private fun moveCursor(ic: InputConnection, steps: Int, now: Double) {
        if (steps == 0) return
        if (selStart < 0) {
            val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(steps)) { sendDownUpKeyEvents(code) }
            composer.forget()
            expected.clear()
            return
        }
        val units = if (steps < 0) {
            val text = StringBuilder(ic.getTextBeforeCursor(-steps * 4 + 8, 0)?.toString() ?: "")
            var moved = 0
            repeat(-steps) {
                val cut = Composer.lastGraphemeStart(text)
                moved += text.length - cut
                text.setLength(cut)
            }
            -moved
        } else {
            val text = ic.getTextAfterCursor(steps * 4 + 8, 0)?.toString() ?: ""
            Composer.graphemes(text).take(steps).sumOf { it.length }
        }
        if (units == 0) return
        val collapsed = selStart == selEnd
        val position = (if (steps < 0) selStart else selEnd) + units
        ic.setSelection(position, position)
        if (collapsed) composer.moveCursor(units, now) else composer.forget()
        expect(position)
    }

    // Following the field

    /** Takes the field's text as it is now: on appearing, or after a change made elsewhere. */
    private fun readField() {
        val (before, after) = fieldText()
        composer.start(before, after, now())
    }

    private fun fieldText(): Pair<String, String> {
        val ic = currentInputConnection ?: return "" to ""
        val before = ic.getTextBeforeCursor(KEEP, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(AFTER, 0)?.toString() ?: ""
        return before to after
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart == newSelEnd) {
            val i = expected.indexOf(newSelStart)
            if (i >= 0) {
                // The app caught up with edits sent from here.
                expected.subList(0, i + 1).clear()
                if (expected.isEmpty()) {
                    selStart = newSelStart
                    selEnd = newSelEnd
                }
                return
            }
        }
        selStart = newSelStart
        selEnd = newSelEnd
        expected.clear()
        fieldReported(newSelEnd)
    }

    /**
     * The cursor or the text changed elsewhere: the user moved the cursor, the app sent the message
     * and emptied the field, or something was pasted. While keys are going in the keyboard keeps to
     * what it typed and looks once typing pauses; an emptied field is taken at once.
     */
    private fun fieldReported(cursor: Int) {
        if (!shown) return
        if (composer.before == null) {
            updateAutoCapitalization()
            configureReturn()
            return
        }
        val now = now()
        if (now - lastKeyAt < SETTLE && cursor != 0) {
            scheduleCheck()
            return
        }
        reconcile(now)
        if (!typedSinceAppear) updateAutoCapitalization()
    }

    private fun reconcile(now: Double) {
        val (before, after) = fieldText()
        reconcile(before, after, now)
    }

    /** Takes the field's text if it was changed elsewhere (see `Composer.fieldChanged`). */
    private fun reconcile(before: String, after: String, now: Double) {
        checkedSinceKey = true
        if (composer.before == null) return
        if (!composer.fieldChanged(before, after, now)) return
        lastTyped = null
        updateAutoCapitalization()
        configureReturn()
    }

    /** Looks at the field once typing pauses, and again once the app must have caught up. */
    private fun scheduleCheck() {
        if (checkPending) return
        checkPending = true
        val wait = max(0.05, lastKeyAt + SETTLE - now())
        main.postDelayed(check, (wait * 1000).toLong())
    }

    private fun checkField() {
        checkPending = false
        if (!shown) return
        val now = now()
        val idle = now - lastKeyAt
        if (idle < SETTLE) return scheduleCheck()
        reconcile(now)
        val caughtUp = TypedText.LAG + 0.1
        if (idle < caughtUp) {
            checkPending = true
            main.postDelayed(check, ((caughtUp - idle) * 1000).toLong())
        } else {
            // Edits that left the cursor where it was are never reported.
            expected.clear()
        }
    }

    private fun edited() {
        when (mode) {
            Mode.Busy -> {
                // The translation would replace the field, these keys with it.
                task?.cancel()
                mode = Mode.Normal
            }
            Mode.Done, is Mode.Failed -> main.post { if (mode == Mode.Done || mode is Mode.Failed) mode = Mode.Normal }
            else -> Unit
        }
        if (info is Info.Outgoing || info is Info.Message) {
            main.post { if (info is Info.Outgoing || info is Info.Message) info = Info.Idle }
        }
        updateAutoCapitalization()
    }

    private fun updateAutoCapitalization() {
        if (!built) return
        val before = composer.before ?: fieldText().first
        val on = when (caps) {
            Caps.CHARACTERS -> true
            Caps.WORDS -> before.isEmpty() || before.last().isWhitespace()
            Caps.SENTENCES -> when {
                before.isEmpty() || before.endsWith("\n") -> true
                before.endsWith(" ") -> before.lastOrNull { it != ' ' && it != '\t' }?.let { it in ".!?\n" } ?: false
                else -> false
            }
            Caps.NONE -> false
        }
        keys.setAutoShift(on)
    }

    // Translate bar

    private fun renderBar() {
        if (!built) return
        bar.removeAllViews()
        languagePill = null
        when (val m = mode) {
            Mode.Normal -> {
                bar.addView(logoButton())
                bar.addView(FrameLayout(this).apply {
                    addView(languagePillView(), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL,
                    ))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                bar.addView(pill("Dịch", R.drawable.ic_tab_translate, PillStyle.PRIMARY) { translateDraft() })
            }
            Mode.Picking -> {
                bar.addView(languageChips(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                bar.addView(pill("Xong") { mode = Mode.Normal })
            }
            Mode.Busy -> {
                bar.addView(status("Đang dịch sang ${activeTarget?.name ?: "ngôn ngữ đích"}…", spinner = true), weighted())
                bar.addView(pill("Hủy") {
                    task?.cancel()
                    mode = Mode.Normal
                })
            }
            Mode.Done -> {
                bar.addView(status("Đã dịch sang ${activeTarget?.name ?: "ngôn ngữ đích"}", iconId = R.drawable.ic_check_circle), weighted())
                bar.addView(pill("Hoàn tác") { undo() })
            }
            is Mode.Failed -> {
                bar.addView(status(m.message, iconId = R.drawable.ic_error, tint = c.red), weighted())
                bar.addView(pill("Đóng") { mode = Mode.Normal })
            }
            is Mode.Panel -> {
                bar.addView(status(m.title, color = c.secondary), weighted())
                bar.addView(pill("Xong") { closePanel() })
            }
        }
        val panelShown = mode is Mode.Panel
        if (panelShown) {
            // As tall as the keys it covers: the keyboard keeps its height.
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                height = max(dp(140), (keys.height.takeIf { it > 0 } ?: keys.measuredHeight) - dp(8))
            }
            keys.reset()
        }
        panel.visibility = if (panelShown) View.VISIBLE else View.GONE
        keys.visibility = if (panelShown) View.INVISIBLE else View.VISIBLE
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun languagePillView(): TextView {
        val target = shownTarget
        return pill(pillTitle(target), R.drawable.ic_chevron_down, trailingIcon = true) { mode = Mode.Picking }.apply {
            contentDescription = "Ngôn ngữ dịch: ${target.name}"
            languagePill = this
        }
    }

    private fun pillTitle(target: Language): String =
        if (targetCode == Language.AUTO) "Tự động · ${target.label}" else "Việt → ${target.label}"

    /** The pill's language now: the one chosen, or for "Tự động" the one worked out last. */
    private val shownTarget: Language
        get() {
            if (targetCode != Language.AUTO) return Language.named(targetCode) ?: Language.all[0]
            return pillTarget ?: Language.named(settings.lastPartnerCode) ?: Language.all[0]
        }

    /** Works out again, off the main thread, which language "Tự động" stands for. */
    private fun refreshPillTarget() {
        if (targetCode != Language.AUTO) return
        val request = ++pillRequest
        scope.launch {
            val target = withContext(store) { settings.target(Language.AUTO, settings.context()) }
            if (request != pillRequest) return@launch
            pillTarget = target
            languagePill?.let {
                it.text = pillTitle(target)
                it.contentDescription = "Ngôn ngữ dịch: ${target.name}"
            }
        }
    }

    private fun languageChips(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = horizontal(6)
        var selected: View? = null
        val options = listOf(Language.AUTO to "Tự động") + Language.all.map { it.code to it.label }
        for ((code, title) in options) {
            val chip = pill(title, style = if (code == targetCode) PillStyle.PRIMARY else PillStyle.PLAIN) {
                targetCode = code
                settings.targetCode = code
                refreshPillTarget()
                mode = Mode.Normal
            }
            if (code == targetCode) selected = chip
            row.addView(chip)
        }
        scroll.addView(row)
        selected?.let { chip -> scroll.post { scroll.scrollTo(max(0, chip.left - dp(40)), 0) } }
        return scroll
    }

    private fun status(text: String, color: Int = c.text, spinner: Boolean = false, iconId: Int? = null, tint: Int = c.green): View =
        horizontal(6).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            if (spinner) addView(this@MSTransKeyboard.spinner(c.secondary, 18))
            if (iconId != null) addView(ImageView(this@MSTransKeyboard).apply {
                setImageDrawable(getDrawable(iconId)!!.mutate().apply { setTint(tint) })
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            addView(label(text, TextSize.SUBHEADLINE, color).oneLine())
        }

    /** The Mộc Sinh mark: shows which keyboard is up, and opens everything at once. */
    private fun logoButton(): View = ImageView(this).apply {
        setImageResource(R.drawable.logo)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = pressable(null, dpf(20))
        contentDescription = "MSTrans: xem tin đối tác, câu vừa dịch và ngữ cảnh"
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        setOnClickListener { openOverview() }
    }

    // Info line

    /** The icon carries the colour (orange: partner, green: you, red: problem); the text stays plain. */
    private fun renderInfo() {
        if (!built) return
        val (text, iconId, tint) = when (val i = info) {
            Info.Idle -> {
                infoLine.visibility = View.GONE
                return
            }
            Info.Copied -> Triple("Có tin vừa sao chép · chạm để dịch", R.drawable.ic_paste, c.orange)
            Info.IncomingBusy -> Triple("Đang dịch tin mới của đối tác…", R.drawable.ic_bubble, c.orange)
            is Info.Incoming -> Triple(i.translation, R.drawable.ic_bubble, c.orange)
            is Info.Outgoing -> Triple(
                i.translation.meaning?.let { "Nghĩa: $it" } ?: "Đã dịch sang ${i.translation.language.name}",
                R.drawable.ic_check_circle, c.green,
            )
            is Info.Message -> Triple(i.text, R.drawable.ic_error, c.red)
        }
        infoLine.text = text.replace('\n', ' ')
        infoLine.setTextColor(if (info == Info.IncomingBusy) c.secondary else c.text)
        infoLine.leading(iconId, tint, 16, 7)
        infoLine.contentDescription = if (info is Info.Incoming) "Tin đối tác, bằng tiếng Việt: $text" else text
        infoLine.visibility = View.VISIBLE
    }

    private fun openInfo() {
        when (val i = info) {
            Info.Idle, is Info.Message -> openOverview()
            Info.Copied -> translateCopied()
            Info.IncomingBusy -> Unit
            is Info.Incoming -> presentIncoming(i.translation)
            is Info.Outgoing -> openOutgoingCheck()
        }
    }

    // Panel

    /** Each part is (text, secondary): secondary parts are small grey captions. */
    private fun showPanel(title: String, parts: List<Pair<String, Boolean>>, button: String, done: (() -> Unit)? = null, action: () -> Unit) {
        val text = SpannableStringBuilder()
        for ((i, part) in parts.withIndex()) {
            val start = text.length
            text.append(if (i == 0) "" else "\n").append(part.first)
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            if (part.second) {
                text.setSpan(AbsoluteSizeSpan(13, true), start, text.length, flags)
                text.setSpan(ForegroundColorSpan(c.secondary), start, text.length, flags)
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, flags)
            } else {
                text.setSpan(AbsoluteSizeSpan(17, true), start, text.length, flags)
                text.setSpan(ForegroundColorSpan(c.text), start, text.length, flags)
            }
        }
        panelText.text = text
        panelScroll.scrollTo(0, 0)
        panelButton.text = button
        panelAction = action
        panelDone = done
        mode = Mode.Panel(title)
    }

    private fun closePanel() {
        val done = panelDone
        panelDone = null
        mode = Mode.Normal
        done?.invoke()
    }

    /** Behind the mark: the partner's last message, the check of what was sent, and the context. */
    private fun openOverview() {
        val needsHistory = lastIncoming == null || lastOutgoing == null
        scope.launch {
            val (recent, lines) = withContext(store) {
                (if (needsHistory) settings.recentHistory() else emptyList()) to settings.context()
            }
            showOverview(recent, lines)
        }
    }

    private fun showOverview(recent: List<HistoryItem>, lines: List<ChatLine>) {
        val parts = ArrayList<Pair<String, Boolean>>()
        val incoming = lastIncoming
        if (incoming != null) {
            parts += incomingParts(incoming.second)
        } else {
            recent.firstOrNull { it.side == ChatLine.Side.PARTNER }?.let { parts += incomingParts(it.translation) }
        }
        val outgoing = lastOutgoing
        if (outgoing != null) {
            val t = outgoing.first
            parts += outgoingParts(outgoing.second, t.text, t.language.name, t.meaning)
        } else {
            recent.firstOrNull { it.side == ChatLine.Side.ME }?.let {
                parts += outgoingParts(it.source, it.translation, it.language?.name ?: it.languageName, it.meaning)
            }
        }
        parts += (if (lines.isEmpty()) "Ngữ cảnh: chưa có tin nào" else "Ngữ cảnh: ${lines.size} tin gần đây") to true
        parts += lines.map { (if (it.side == ChatLine.Side.PARTNER) "Đối tác: " else "Tôi: ") + it.text to false }
        showPanel("MSTrans", parts, "Cuộc mới (xóa ngữ cảnh)") {
            scope.launch(store) { settings.clearContext() }
            lastIncoming = null
            lastOutgoing = null
            refreshPillTarget()
            closePanel()
        }
    }

    /** Only the translation: the partner's message itself is in the chat. */
    private fun incomingParts(translation: String) = listOf("Tin đối tác (đã dịch)" to true, translation to false)

    private fun outgoingParts(original: String, translation: String, language: String, meaning: String?): List<Pair<String, Boolean>> {
        val parts = arrayListOf((if (language.isEmpty()) "Bạn gửi" else "Bạn gửi ($language)") to true, original to true, "→ $translation" to false)
        if (meaning != null) parts += "Nghĩa: $meaning" to true
        return parts
    }

    private fun openOutgoingCheck() {
        val (t, written) = lastOutgoing ?: return
        val parts = arrayListOf("Bạn viết" to true, written to false, "Đã đổi sang ${t.language.name}" to true, t.text to false)
        t.meaning?.let { parts += listOf("Nghĩa khi dịch ngược" to true, it to false) }
        showPanel("Kiểm tra bản dịch", parts, "Hoàn tác, lấy lại câu gốc") { undo() }
    }

    /** Only the translation: the partner's message is right above in the chat. */
    private fun presentIncoming(translation: String) {
        showPanel("Tin đối tác → Tiếng Việt", listOf(translation to false), "Sao chép bản dịch") {
            copyText(translation)
            panelButton.text = "Đã sao chép"
        }
    }

    // Translating the user's message

    private fun readyKey(): String? {
        settings.apiKey?.let { return it }
        showPanel(
            "Cần API key",
            listOf(
                "Bàn phím chưa có API key OpenAI." to true,
                "Mở app MSTrans › Cài đặt, dán API key (bắt đầu bằng sk-) vào ô OpenAI, rồi quay lại đây bấm Dịch." to false,
            ),
            "Mở MSTrans",
        ) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.SETTINGS.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            closePanel()
        }
        return null
    }

    private fun translateDraft() {
        val apiKey = readyKey() ?: return
        val context = settings.context()
        // The language the pill shows ("Tự động" is worked out off the main thread); only before that is done, here.
        val target = if (targetCode == Language.AUTO && pillTarget == null) settings.target(targetCode, context) else shownTarget
        activeTarget = target
        val text = readWholeField()
        if (text.isBlank()) {
            mode = Mode.Failed(TranslateError.empty().message ?: "")
            return
        }
        mode = Mode.Busy
        task?.cancel()
        val field = fieldKey
        val checkMeaning = settings.checkMeaning
        task = scope.launch {
            try {
                val result = Translator(apiKey, settings.model, settings).translate(text, target, context, checkMeaning)
                if (mode != Mode.Busy) return@launch
                // Another text field has the keyboard now: its text is not the one translated.
                if (fieldKey != field || !shown) {
                    mode = Mode.Failed(FIELD_SWITCHED)
                    return@launch
                }
                original = text
                // In the history first: the translation is still there to copy from the app if
                // replacing the field goes wrong.
                val item = HistoryItem(side = ChatLine.Side.ME, source = text, translation = result.text, languageCode = target.code,
                    meaning = result.meaning)
                launch(store) { settings.addHistory(item) }
                replaceWholeField(result.text)
                launch(store) { settings.remember(ChatLine(ChatLine.Side.ME, result.text, System.currentTimeMillis())) }
                clipAtOutgoing = seenClip
                refreshPillTarget()
                lastOutgoing = result to text
                mode = Mode.Done
                info = Info.Outgoing(result, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mode == Mode.Busy) mode = Mode.Failed(e.message ?: "Không dịch được. Thử lại.")
            }
        }
    }

    private fun undo() {
        panelButton.text = "Đang lấy lại…"
        lastOutgoing?.first?.text?.let { sent ->
            scope.launch(store) { settings.forgetLast(sent) }
            refreshPillTarget()
        }
        replaceWholeField(original)
        lastOutgoing = null
        mode = Mode.Normal
        info = Info.Idle
    }

    // The whole text field

    /**
     * The field's whole text: as the app hands it over in one piece, else around the cursor. Some
     * apps hand over only part of a long text in one piece: then the longer of the two.
     */
    private fun readWholeField(): String {
        val ic = currentInputConnection ?: return ""
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val whole = extracted?.takeIf { it.startOffset == 0 }?.text?.toString()
        val before = ic.getTextBeforeCursor(FIELD_LIMIT, 0) ?: ""
        val selected = ic.getSelectedText(0) ?: ""
        val after = ic.getTextAfterCursor(FIELD_LIMIT, 0) ?: ""
        val around = "$before$selected$after"
        return if (whole != null && whole.length >= around.length) whole else around
    }

    /**
     * Replaces the field's whole text; the keyboard reads the field again before the next key.
     * Up to 1.5.2 the field was taken to be as long as the app said in one piece, and an app that
     * said less of a long text kept the rest in front of the translation. Now whatever is left
     * around the cursor is deleted too, until the field says it is empty.
     */
    private fun replaceWholeField(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val whole = extracted?.text
        if (whole != null && extracted.startOffset == 0) ic.setSelection(0, whole.length)
        ic.commitText("", 1)
        var rounds = 0
        while (rounds++ < CLEAR_ROUNDS) {
            val before = ic.getTextBeforeCursor(FIELD_LIMIT, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(FIELD_LIMIT, 0)?.length ?: 0
            if (before == 0 && after == 0) break
            ic.deleteSurroundingText(before, after)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        composer.forget()
        expected.clear()
        lastTyped = null
    }

    // Translating the partner's messages

    private fun clipText(): String? = runCatching {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(this)?.toString()
    }.getOrNull()

    /** Marked sensitive by the app it came from (a password manager, a banking app). */
    private fun clipIsSensitive(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching { clipboard?.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true }
            .getOrDefault(false)
    }

    /**
     * Offers a fresh copy, or in instant mode translates it once by itself. Not while the user is
     * typing: the info line would appear and change the keyboard's height under their thumbs.
     */
    private fun checkClipboard(waitForTyping: Boolean) {
        if (!shown || settings.apiKey == null) return
        if (waitForTyping && now() - lastKeyAt < 1.5) {
            main.removeCallbacks(clipRetry)
            main.postDelayed(clipRetry, 1_600)
            return
        }
        val text = clipText()?.trim()
        val hash = text?.hashCode()
        seenClip = hash
        val fresh = !text.isNullOrEmpty() && hash != settings.handledClipboard
        when (info) {
            Info.Idle, is Info.Incoming, is Info.Outgoing -> {
                if (!fresh) return
                // The meaning of the message just translated stays: only something copied after it takes its place.
                if (info is Info.Outgoing && hash == clipAtOutgoing) return
                if (settings.instantIncoming && attemptedClip != hash && !clipIsSensitive()) {
                    attemptedClip = hash
                    translateCopied(automatically = true)
                } else {
                    info = Info.Copied
                }
            }
            Info.Copied -> if (!fresh) info = Info.Idle
            else -> Unit
        }
    }

    private fun translateCopied(automatically: Boolean = false) {
        if (readyKey() == null) return
        if (mode is Mode.Panel) mode = Mode.Normal
        val copied = clipText()?.trim().orEmpty()
        if (copied.isEmpty()) {
            info = Info.Message("Chưa đọc được tin đã sao chép. Sao chép lại tin rồi thử lại.")
            return
        }
        if (automatically && looksLikeSecret(copied)) {
            info = Info.Copied
            return
        }
        val hash = copied.hashCode()
        // Whatever was copied is translated into Vietnamese: no guessing whether it already is.
        val cached = settings.cachedIncoming(copied)
        if (cached != null) {
            settings.handledClipboard = hash
            scope.launch(store) { settings.remember(ChatLine(ChatLine.Side.PARTNER, copied, System.currentTimeMillis())) }
            refreshPillTarget()
            show(copied, cached)
            return
        }
        info = Info.IncomingBusy
        // The context as it was before this message: the message itself goes in on its own, not twice.
        scope.launch {
            val context = withContext(store) {
                val lines = settings.context().toMutableList()
                val last = lines.lastOrNull()
                if (last != null && last.side == ChatLine.Side.PARTNER && last.text == copied) lines.removeAt(lines.size - 1)
                settings.remember(ChatLine(ChatLine.Side.PARTNER, copied, System.currentTimeMillis()))
                lines
            }
            refreshPillTarget()
            translateIncoming(copied, context, hash)
        }
    }

    /** The copy counts as handled once translated: one that failed (no network) is offered again. */
    private fun translateIncoming(text: String, context: List<ChatLine>, hash: Int) {
        val apiKey = settings.apiKey ?: return
        info = Info.IncomingBusy
        incomingTask?.cancel()
        incomingTask = scope.launch {
            try {
                val result = Translator(apiKey, settings.model, settings).translate(text, Language.vietnamese, context)
                launch(store) {
                    settings.cacheIncoming(text, result.text)
                    settings.addHistory(HistoryItem(side = ChatLine.Side.PARTNER, source = text, translation = result.text,
                        languageCode = LanguageDetector.language(text)?.code ?: ""))
                }
                settings.handledClipboard = hash
                show(text, result.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                info = Info.Message(e.message ?: "Không dịch được tin đối tác. Thử lại.")
            }
        }
    }

    /**
     * On the info line; a long one is read in full with a tap on it. It never opens over the keys by itself: a
     * keyboard that came up with no keys, a translation in their place, was taken for broken.
     */
    private fun show(source: String, translation: String) {
        lastIncoming = source to translation
        info = Info.Incoming(source, translation)
    }

    companion object {
        /** A pause this long means the keys typed so far have reached the app. */
        private const val SETTLE = 0.5
        /** The text before the cursor the keyboard keeps (as `TypedText` does). */
        private const val KEEP = 300
        private const val AFTER = 64
        private const val FIELD_LIMIT = 20_000
        /** Pieces of a field deleted at most: 50 × 20,000 characters on each side of the cursor. */
        private const val CLEAR_ROUNDS = 50
        private const val FIELD_SWITCHED = "Đã sang ô nhập khác. Bấm Dịch lại."

        /**
         * A password, a code, a link or an e-mail address: one word of plain letters with digits or
         * signs in it. Copied, it is not a message, and instant mode does not send it to OpenAI by
         * itself (a tap still translates it).
         */
        fun looksLikeSecret(text: String): Boolean =
            text.length >= 6 && text.all { it.code < 128 && !it.isWhitespace() } &&
                text.any { it.isDigit() || it in "@/:._-#$%&*+=\\|~^" }
    }
}
