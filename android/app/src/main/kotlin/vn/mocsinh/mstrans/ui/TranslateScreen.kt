package vn.mocsinh.mstrans.ui

import android.content.ClipboardManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.Screen
import vn.mocsinh.mstrans.core.ChatLine
import vn.mocsinh.mstrans.core.HistoryItem
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.LanguageDetector
import vn.mocsinh.mstrans.core.TranslateError
import vn.mocsinh.mstrans.core.Translation
import vn.mocsinh.mstrans.core.Translator
import vn.mocsinh.mstrans.core.target

/** Translate anything here; the keyboard does the same inside chat apps. */
class TranslateScreen(private val activity: MainActivity) : Screen {
    private enum class Direction { OUTGOING, INCOMING }

    private val settings = activity.settings
    private val c = activity.colors
    private val scope = MainScope()

    private var direction = Direction.OUTGOING
    private var result: Translation? = null
    private var resultSide = ChatLine.Side.ME
    private var resultLanguage = ""
    private var error: String? = null
    private var busy = false
    private var job: Job? = null
    /** The translation as it is written, while busy; shown from its first words. */
    private var stream: StreamedText? = null
    private var streamLabel: TextView? = null
    private var streamWhole = false
    /** Its meaning check is written after it (the user's own message). */
    private var streamChecked = false
    private var copied = false
    private var recent: List<HistoryItem> = emptyList()
    /** The language "Tự động" stands for now, worked out as the screen shows (not on each key). */
    private var partnerLanguage: Language = Language.all[0]

    private val setupArea = activity.vertical()
    private val directionArea = activity.horizontal(10)
    private val input: EditText = activity.field("", lines = 8).apply {
        minHeight = activity.dp(112)
        setPadding(0, activity.dp(4), 0, activity.dp(4))
    }
    private val inputActions = activity.horizontal(10)
    private val results = activity.vertical(16)

    override val view: View = activity.vertical().apply {
        addView(activity.brandTitle(), matchWidth())
        val body = activity.vertical(16).apply {
            setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(24))
            addView(setupArea, matchWidth())
            addView(directionArea, matchWidth())
            addView(activity.card(spacing = 10).apply {
                addView(input, matchWidth())
                addView(inputActions, matchWidth())
            }, matchWidth())
            addView(results, matchWidth())
        }
        addView(ScrollView(activity).apply {
            isFillViewport = true
            addView(body)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Typing: the connection to OpenAI is opened now, not when Dịch is tapped.
                if (settings.apiKey != null) Translator.warmUp()
                // The text changed: a translation of the text before must not be copied for it.
                if (result != null || busy) {
                    job?.cancel()
                    busy = false
                    result = null
                    renderResults()
                }
                renderInputActions()
            }
        })
        updateHint()
    }

    override fun shown() {
        if (settings.apiKey != null) Translator.warmUp()
        refresh()
    }

    override fun destroyed() = scope.cancel()

    private fun refresh() {
        recent = settings.history().take(3)
        partnerLanguage = settings.target(Language.AUTO, settings.context())
        render()
    }

    private fun render() {
        renderSetup()
        renderDirection()
        renderInputActions()
        renderResults()
    }

    // Setup

    private fun renderSetup() {
        setupArea.removeAllViews()
        val status = Setup.status(activity)
        if (status.done) return
        setupArea.addView(activity.card(c.gold).apply {
            addView(activity.label("Còn ${status.remaining} bước để dùng bàn phím", TextSize.HEADLINE, bold = true)
                .leading(R.drawable.ic_keyboard, c.gold, 20, 8))
            val text = if (status.keyboardEnabled) "Dán API key OpenAI để bàn phím dịch được."
            else "Bật bàn phím MSTrans" + if (status.hasKey) "." else " và dán API key OpenAI."
            addView(activity.label(text, TextSize.SUBHEADLINE, c.secondary))
            addView(activity.pill("Hoàn tất cài đặt", style = PillStyle.PRIMARY) { activity.select(MainActivity.Tab.SETTINGS) }, wrap())
        }, matchWidth())
    }

    // Direction

    private fun renderDirection() {
        directionArea.removeAllViews()
        if (direction == Direction.OUTGOING) {
            directionArea.addView(fixedSide("Tiếng Việt"), weighted())
            directionArea.addView(swapButton())
            directionArea.addView(targetMenu(), weighted())
        } else {
            directionArea.addView(fixedSide("Tiếng đối tác"), weighted())
            directionArea.addView(swapButton())
            directionArea.addView(fixedSide("Tiếng Việt"), weighted())
        }
    }

    private fun fixedSide(title: String): TextView = activity.label(title, TextSize.SUBHEADLINE, c.text, bold = true).apply {
        gravity = Gravity.CENTER
        oneLine()
        minHeight = activity.dp(44)
        background = rounded(c.card, activity.dpf(14))
        setPadding(activity.dp(8), 0, activity.dp(8), 0)
    }

    private fun swapButton(): View = activity.iconButton(
        R.drawable.ic_swap, c.green, background = c.card,
        description = if (direction == Direction.OUTGOING) "Đổi sang dịch tin của đối tác" else "Đổi sang dịch tin bạn gửi",
    ) {
        direction = if (direction == Direction.OUTGOING) Direction.INCOMING else Direction.OUTGOING
        job?.cancel()
        busy = false
        result = null
        error = null
        updateHint()
        render()
    }

    private val targetTitle: String
        get() = if (settings.targetCode == Language.AUTO) "Tự động · ${resolvedTarget.label}" else resolvedTarget.label

    /** "Tự động" answers in the partner's language, as on the keyboard. */
    private val resolvedTarget: Language
        get() = if (settings.targetCode == Language.AUTO) partnerLanguage else Language.named(settings.targetCode) ?: Language.all[0]

    private fun targetMenu(): View = fixedSide(targetTitle).apply {
        trailing(R.drawable.ic_chevron_down, c.text, 16, 2)
        gravity = Gravity.CENTER
        background = activity.pressable(rounded(c.card, activity.dpf(14)), activity.dpf(14))
        contentDescription = "Dịch sang: $targetTitle"
        setOnClickListener {
            val codes = listOf(Language.AUTO) + Language.all.map { it.code }
            val names = listOf("Tự động theo đối tác") + Language.all.map { "${it.label} · ${it.name}" }
            activity.choose("Dịch sang", names, codes.indexOf(settings.targetCode).coerceAtLeast(0)) { index ->
                settings.targetCode = codes[index]
                renderDirection()
            }
        }
    }

    // Input

    private fun updateHint() {
        input.hint = if (direction == Direction.OUTGOING) "Gõ tin bằng tiếng Việt…" else "Dán tin của đối tác…"
        input.contentDescription = if (direction == Direction.OUTGOING) "Tin bạn muốn gửi" else "Tin của đối tác"
    }

    private fun renderInputActions() {
        inputActions.removeAllViews()
        val empty = input.text.isEmpty()
        // One coloured control at a time: Dán while empty, then Dịch.
        if (empty) {
            inputActions.addView(activity.pill("Dán", R.drawable.ic_paste, PillStyle.TINTED, tint = c.green) { paste() })
        } else {
            inputActions.addView(activity.iconButton(R.drawable.ic_close, c.secondary, 36, c.fill, "Xóa chữ") {
                input.setText("")
                result = null
                error = null
                renderResults()
            })
        }
        inputActions.addView(activity.spacer())
        inputActions.addView(activity.pill("Dịch", R.drawable.ic_tab_translate, PillStyle.PRIMARY) { translate() }
            .enabled(input.text.isNotBlank() && !busy))
    }

    private fun paste() {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (text.isNullOrBlank()) {
            activity.toast("Chưa có chữ nào được sao chép.")
            return
        }
        input.setText(text)
        input.setSelection(input.text.length)
        result = null
        error = null
        renderResults()
    }

    // Result

    private fun renderResults() {
        results.removeAllViews()
        streamLabel = null
        val partial = stream?.takeIf { busy }?.text?.trim().orEmpty()
        if (busy) results.addView(if (partial.isEmpty()) busyCard() else streamingCard(partial), matchWidth())
        error?.let { results.addView(errorCard(it), matchWidth()) }
        result?.let { results.addView(resultCard(it), matchWidth()) }
        if (result == null && error == null && !busy) {
            results.addView(if (recent.isEmpty()) tipCard() else recentSection(), matchWidth())
        }
        renderInputActions()
    }

    private fun busyCard(): View = activity.card().apply {
        addView(activity.horizontal(12).apply {
            addView(activity.spinner())
            addView(activity.label(
                if (direction == Direction.OUTGOING) "Đang dịch sang ${resolvedTarget.name}…" else "Đang dịch sang tiếng Việt…",
                TextSize.BODY, c.secondary,
            ), weighted())
            addView(activity.pill("Hủy") {
                job?.cancel()
                busy = false
                renderResults()
            })
        }, matchWidth())
    }

    /**
     * The translation as it is written, from its first words. Whole, it can be copied while its
     * meaning check is still being written.
     */
    private fun streamingCard(text: String): View {
        val side = if (direction == Direction.OUTGOING) ChatLine.Side.ME else ChatLine.Side.PARTNER
        val whole = streamWhole
        return activity.card(side.color(c)).apply {
            addView(activity.horizontal(8).apply {
                addView(activity.spinner())
                val status = if (whole && streamChecked) "Đang kiểm tra nghĩa…" else "Đang dịch…"
                addView(activity.label(status, TextSize.SUBHEADLINE, c.secondary), weighted())
                if (whole) {
                    addView(copyButton(text))
                } else {
                    addView(activity.pill("Hủy") {
                        job?.cancel()
                        busy = false
                        renderResults()
                    })
                }
            }, matchWidth())
            val label = activity.label(text, TextSize.TITLE3)
            if (whole) label.selectable()
            streamLabel = label
            addView(label, matchWidth())
        }
    }

    /** As it comes: the text only, the card again when it becomes whole. */
    private fun drawStream(stream: StreamedText) {
        if (stream !== this.stream || !busy) return
        val text = stream.text.trim()
        if (text.isEmpty()) return
        val label = streamLabel
        if (label == null || stream.whole != streamWhole) {
            streamWhole = stream.whole
            renderResults()
        } else {
            label.text = text
        }
    }

    private fun copyButton(text: String): View =
        activity.pill(if (copied) "Đã chép" else "Sao chép", if (copied) R.drawable.ic_check else R.drawable.ic_copy) {
            activity.copyText(text)
            copied = true
            renderResults()
            scope.launch {
                delay(1500)
                copied = false
                renderResults()
            }
        }

    private fun errorCard(message: String): View = activity.card(c.red).apply {
        addView(activity.label(message, TextSize.BODY, c.red).leading(R.drawable.ic_error, c.red, 20, 8))
        if (settings.apiKey == null) {
            addView(activity.pill("Dán API key trong Cài đặt") { activity.select(MainActivity.Tab.SETTINGS) }, wrap())
        }
    }

    private fun resultCard(result: Translation): View = activity.card(resultSide.color(c)).apply {
        addView(activity.horizontal(8).apply {
            addView(activity.sideTag(resultSide, resultLanguage), weighted())
            addView(activity.iconButton(R.drawable.ic_share, c.green, 40, c.fill, "Chia sẻ bản dịch") { activity.shareText(result.text) })
            addView(copyButton(result.text))
        }, matchWidth())
        addView(activity.label(result.text, TextSize.TITLE3).selectable(), matchWidth())
        result.meaning?.let { meaning ->
            addView(activity.vertical(4).apply {
                addView(activity.label("Đối tác sẽ hiểu là", TextSize.FOOTNOTE, c.green, bold = true)
                    .leading(R.drawable.ic_check_circle, c.green, 14, 4))
                addView(activity.label(meaning, TextSize.SUBHEADLINE, c.secondary).selectable())
            }, matchWidth())
        }
    }

    private fun recentSection(): View = activity.vertical(8).apply {
        addView(activity.horizontal().apply {
            setPadding(activity.dp(4), 0, activity.dp(4), 0)
            addView(activity.label("Gần đây", TextSize.HEADLINE, bold = true), weighted())
            addView(activity.label("Xem tất cả", TextSize.SUBHEADLINE, c.green).apply {
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                setOnClickListener { activity.select(MainActivity.Tab.HISTORY) }
            })
        }, matchWidth())
        addView(activity.vertical().apply {
            background = rounded(c.card, activity.dpf(20))
            setPadding(0, activity.dp(4), 0, activity.dp(4))
            recent.forEachIndexed { index, item ->
                if (index > 0) addView(View(activity).apply { setBackgroundColor(c.separator) },
                    matchWidth(1).apply { leftMargin = activity.dp(32) })
                addView(activity.historyRow(item).apply {
                    background = activity.pressable(null, 0f)
                    setOnClickListener {
                        activity.openHistoryDetail(item, onChange = { settings.updateHistory(it); refresh() },
                            onDelete = { settings.removeHistory(item.id); refresh() })
                    }
                }, matchWidth())
            }
        }, matchWidth())
    }

    private fun tipCard(): View = activity.card().apply {
        addView(activity.label("Dùng ngay trong Zalo, WeChat, Messenger", TextSize.SUBHEADLINE, bold = true)
            .leading(R.drawable.ic_keyboard, c.green, 18, 8))
        addView(activity.label(
            "Chuyển sang bàn phím MSTrans, gõ tiếng Việt rồi bấm Dịch. Tin của đối tác: giữ tin › Sao chép, " +
                "bản dịch tiếng Việt hiện ở dòng trên cùng của bàn phím.",
            TextSize.SUBHEADLINE, c.secondary,
        ))
    }

    // Translating

    private fun translate() {
        val source = input.text.toString().trim()
        if (source.isEmpty()) return
        val apiKey = settings.apiKey
        if (apiKey == null) {
            error = TranslateError.noKey().message
            renderResults()
            return
        }
        activity.hideKeyboard()
        val outgoing = direction == Direction.OUTGOING
        val context = settings.context()
        if (outgoing && settings.targetCode == Language.AUTO) partnerLanguage = settings.target(Language.AUTO, context)
        val target = if (outgoing) resolvedTarget else Language.vietnamese
        busy = true
        error = null
        result = null
        job?.cancel()
        this.stream?.stopped = true
        // Shown as it is written (OpenAI streams the answer), from its first words.
        val stream = StreamedText { drawStream(it) }
        this.stream = stream
        streamWhole = false
        val checkMeaning = outgoing && settings.checkMeaning
        streamChecked = checkMeaning
        renderResults()
        val translator = Translator(apiKey, settings.model, settings)
        lateinit var task: Job
        task = scope.launch {
            try {
                val translation = translator.translate(source, target, context, checkMeaning, stream::heard)
                // The history's JSON and the language of the partner's message: not on the main thread.
                val (item, latest) = withContext(Dispatchers.Default) {
                    val item = HistoryItem(
                        side = if (outgoing) ChatLine.Side.ME else ChatLine.Side.PARTNER,
                        source = source,
                        translation = translation.text,
                        languageCode = if (outgoing) target.code else LanguageDetector.language(source)?.code ?: "",
                        meaning = translation.meaning,
                    )
                    settings.addHistory(item)
                    item to settings.history().take(3)
                }
                recent = latest
                resultSide = item.side
                resultLanguage = item.languageName
                result = translation
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Lỗi không rõ."
            } finally {
                stream.stopped = true
                // A cancelled translation leaves the one that replaced it busy.
                if (job === task) {
                    busy = false
                    renderResults()
                }
            }
        }
        job = task
    }
}
