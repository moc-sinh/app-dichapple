package vn.mocsinh.mstrans.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.Screen
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.conversation.ConversationModel
import vn.mocsinh.mstrans.core.conversation.ConversationModel.Engine
import vn.mocsinh.mstrans.core.conversation.ConversationModel.State
import vn.mocsinh.mstrans.core.conversation.CostMeter
import vn.mocsinh.mstrans.core.conversation.GoogleVoice
import vn.mocsinh.mstrans.core.conversation.Meeting
import java.util.Locale

/**
 * The Hội thoại tab: what the others say comes up as Vietnamese text, read aloud too with Đọc to;
 * the Tôi nói button turns the user's Vietnamese into the partners' language, read aloud.
 */
class ConversationScreen(private val activity: MainActivity) : Screen {
    private val app = MSTransApp.of(activity)
    private val model: ConversationModel = app.conversation
    private val settings = app.settings
    private val c = activity.colors
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())

    /** The sentences on screen: the others' come up once recognised (a cough never flashes up as a card). */
    private var shownEntries: List<ConversationModel.Entry> = emptyList()
    private var pending = 0
    private var signature = ""
    /** A tap (not a hold) started the user's turn: the next tap ends it. */
    private var latched = false
    private var pressedAt = 0L
    private var renderPosted = false
    private var visible = false
    /** Refreshed while the summary page is open. */
    private var summaryBody: LinearLayout? = null

    // Header
    private val readAloud = Segmented(activity, listOf("Đọc to + chữ", "Chỉ chữ"), if (model.readAloud) 0 else 1) {
        model.readAloud = it == 0
    }
    private val partnerPill = activity.label("", TextSize.SUBHEADLINE, c.text, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = activity.dp(36)
        setPadding(activity.dp(12), 0, activity.dp(12), 0)
        background = activity.pressable(rounded(c.card, activity.dpf(18)), activity.dpf(18))
        maxLines = 1
        setOnClickListener { choosePartner() }
    }

    // Transcript
    private val list = ListView(activity).apply {
        divider = null
        dividerHeight = 0
        transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
        clipToPadding = false
        setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
        selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    }
    private val pendingRow = activity.horizontal(8).apply { setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(8)) }
    private val hint = ScrollView(activity)
    private val newSentence = activity.pill("Câu mới", R.drawable.ic_arrow_down, PillStyle.PRIMARY) {
        list.smoothScrollToPosition(maxOf(0, list.count - 1))
    }.apply { visibility = View.GONE }

    // Controls
    private val problemBox = activity.horizontal(8).apply {
        gravity = Gravity.TOP
        background = rounded(c.card, activity.dpf(14))
        setPadding(activity.dp(12), activity.dp(12), activity.dp(8), activity.dp(12))
    }
    private val listenCircle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val listenRing = View(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(activity.dp(3), 0xB3FFFFFF.toInt())
        }
    }
    private val listenIcon = ImageView(activity)
    private val listenSpinner = activity.spinner(Color.WHITE, 28)
    private val listenButton = FrameLayout(activity).apply {
        background = listenCircle
        addView(listenRing, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
        })
        addView(listenIcon, FrameLayout.LayoutParams(activity.dp(28), activity.dp(28), Gravity.CENTER))
        addView(listenSpinner, FrameLayout.LayoutParams(activity.dp(28), activity.dp(28), Gravity.CENTER))
        setOnClickListener {
            if (model.running) model.stop() else scope.launch { model.start() }
        }
    }
    private val talkIcon = ImageView(activity)
    private val talkTitle = activity.label("", TextSize.HEADLINE, Color.WHITE, bold = true)
    private val talkSubtitle = activity.label("", TextSize.CAPTION, Color.WHITE).oneLine().apply { alpha = 0.9f }
    private val talkBackground = rounded(c.greenFill, activity.dpf(32))
    private val talkButton = activity.horizontal(10).apply {
        background = talkBackground
        minimumHeight = activity.dp(64)
        setPadding(activity.dp(18), activity.dp(8), activity.dp(18), activity.dp(8))
        addView(talkIcon, LinearLayout.LayoutParams(activity.dp(26), activity.dp(26)))
        addView(activity.vertical(2).apply {
            addView(talkTitle)
            addView(talkSubtitle)
        }, weighted())
    }
    private val status = activity.label("", TextSize.FOOTNOTE, c.secondary).oneLine().apply { gravity = Gravity.CENTER }

    private val more: View = activity.iconButton(R.drawable.ic_more, c.green, description = "Tùy chọn") { openMenu(more) }

    override val view: View = activity.vertical().apply {
        addView(activity.topBar("Hội thoại", null, more), matchWidth())
        addView(activity.vertical(10).apply {
            setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(4))
            addView(readAloud, matchWidth())
            addView(activity.horizontal(8).apply {
                addView(activity.label("Tiếng của đối tác", TextSize.SUBHEADLINE, c.secondary), weighted())
                addView(partnerPill)
            }, matchWidth())
        }, matchWidth())
        addView(FrameLayout(activity).apply {
            addView(list, fill())
            addView(hint, fill())
            addView(newSentence, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = activity.dp(8) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(activity.vertical(12).apply {
            setBackgroundColor(c.bar)
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(8))
            addView(problemBox, matchWidth())
            addView(activity.horizontal(14).apply {
                addView(listenButton, LinearLayout.LayoutParams(activity.dp(64), activity.dp(64)))
                addView(talkButton, weighted())
            }, matchWidth())
            addView(status, matchWidth())
        }, matchWidth())
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shownEntries.size
        override fun getItem(position: Int) = shownEntries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = entryRow(shownEntries[position])
        override fun isEnabled(position: Int) = false
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            if (visible) main.postDelayed(this, 1000)
        }
    }

    init {
        list.addFooterView(pendingRow, null, false)
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, first: Int, visibleCount: Int, total: Int) {
                if (first + visibleCount >= total) newSentence.visibility = View.GONE
            }
        })
        talkButton.setOnTouchListener { _, event -> talkTouch(event) }
        model.onChange = { scheduleRender() }
        model.keepScreenOn = { activity.keepScreenOn(it) }
        model.requestPermission = { activity.requestMicrophone() }
        render()
    }

    override fun shown() {
        visible = true
        main.removeCallbacks(ticker)
        main.post(ticker)
        render()
    }

    override fun hidden() {
        visible = false
        main.removeCallbacks(ticker)
    }

    override fun destroyed() {
        hidden()
        if (model.onChange != null) model.onChange = null
        model.keepScreenOn = null
        model.requestPermission = null
        scope.cancel()
    }

    /** Many changes come at once (the meter, ten times a second): drawn once per frame. */
    private fun scheduleRender() {
        if (renderPosted) return
        renderPosted = true
        main.post {
            renderPosted = false
            render()
        }
    }

    private fun render() {
        renderHeader()
        renderTranscript()
        renderControls()
        renderStatus()
        summaryBody?.let { renderSummary(it) }
    }

    // Header

    private fun renderHeader() {
        readAloud.selected = if (model.readAloud) 0 else 1
        val title = partnerTitle()
        if (partnerPill.text != title) {
            partnerPill.text = title
            partnerPill.trailing(R.drawable.ic_chevron_down, c.text, 14, 4)
            partnerPill.contentDescription = "Tiếng của đối tác: $title"
        }
    }

    private fun partnerTitle(): String =
        if (model.partnerCode == Language.AUTO) "Tự động · ${model.partnerLanguage.label}" else model.partnerLanguage.label

    private fun choosePartner() {
        val codes = listOf(Language.AUTO) + Language.all.map { it.code }
        val names = listOf("Tự động (theo người vừa nói)") + Language.all.map { "${it.label} · ${it.name}" }
        activity.choose("Tiếng của đối tác", names, codes.indexOf(model.partnerCode).coerceAtLeast(0)) {
            model.partnerCode = codes[it]
        }
    }

    // Transcript

    private fun renderTranscript() {
        val entries = model.entries
        val shown = entries.filter { it.mine || it.state != State.Recognizing }
        val waiting = entries.size - shown.size
        val sign = buildString {
            append(model.textSize.ordinal).append('|').append(waiting).append('|')
            for (e in shown) {
                append(e.id).append(e.state.toString()).append(e.text.length).append(':').append(e.translation.length)
                    .append(e.language).append(e.target).append(e.speaker).append(';')
            }
        }
        val empty = entries.isEmpty()
        hint.visibility = if (empty) View.VISIBLE else View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) renderHint()
        if (sign == signature) return
        val grew = shown.size > shownEntries.size || waiting > pending
        signature = sign
        shownEntries = shown
        pending = waiting
        pendingRow.removeAllViews()
        if (waiting > 0) {
            pendingRow.addView(activity.progress(if (waiting > 1) "Đang nhận $waiting câu vừa nói…" else "Đang nhận câu vừa nói…"))
        }
        val atEnd = list.lastVisiblePosition >= list.count - 2
        adapter.notifyDataSetChanged()
        // Followed while the end is in view; scrolled up to read, a button offers the new sentence.
        if (grew && !atEnd && !empty) newSentence.visibility = View.VISIBLE
    }

    private fun mainSize(): Float = when (model.textSize) {
        ConversationModel.TextSize.MEDIUM -> TextSize.TITLE3
        ConversationModel.TextSize.LARGE -> TextSize.TITLE2
        ConversationModel.TextSize.EXTRA_LARGE -> TextSize.TITLE
    }

    private fun originalSize(): Float = when (model.textSize) {
        ConversationModel.TextSize.MEDIUM -> TextSize.SUBHEADLINE
        ConversationModel.TextSize.LARGE -> TextSize.BODY
        ConversationModel.TextSize.EXTRA_LARGE -> TextSize.TITLE3
    }

    /** Green: the user. Orange: a partner. Grey: someone speaking Vietnamese. */
    private fun accent(entry: ConversationModel.Entry): Int = when {
        entry.mine -> c.green
        entry.isVietnamese -> c.secondary
        else -> c.orange
    }

    private fun who(entry: ConversationModel.Entry): String {
        if (entry.mine) return "Tôi → ${Language.displayName(entry.target)}"
        val speaker = if (entry.speaker.isEmpty()) "" else "Người ${entry.speaker} · "
        return speaker + if (entry.language.isEmpty()) "Đang nghe" else Language.displayName(entry.language)
    }

    private fun entryRow(entry: ConversationModel.Entry): View {
        val accent = accent(entry)
        val card = activity.card(accent, spacing = 10)
        card.addView(activity.horizontal(6).apply {
            addView(activity.label(who(entry), TextSize.FOOTNOTE, accent, bold = true)
                .leading(if (entry.mine) R.drawable.ic_person else R.drawable.ic_people, accent, 16, 6), weighted())
            addView(activity.label(Dates.time(entry.at), TextSize.CAPTION, c.secondary))
        }, matchWidth())
        val main = entry.translation.ifEmpty { entry.text }
        val original = if (entry.translation.isEmpty()) "" else entry.text
        if (main.isNotEmpty()) card.addView(activity.label(main, mainSize(), c.text, medium = entry.mine), matchWidth())
        if (original.isNotEmpty()) card.addView(activity.label(original, originalSize(), c.secondary), matchWidth())
        when (val state = entry.state) {
            State.Recognizing -> card.addView(activity.progress(if (entry.mine) "Đang nhận câu của bạn…" else "Đang nghe…"))
            State.Translating -> card.addView(activity.progress(
                if (entry.mine) "Đang dịch sang ${Language.displayName(entry.target)}…" else "Đang dịch…",
            ))
            State.Hearing -> Unit
            is State.Failed -> card.addView(activity.horizontal(8).apply {
                addView(activity.label(state.message, TextSize.SUBHEADLINE, c.red).leading(R.drawable.ic_error, c.red, 16, 6), weighted())
                addView(activity.pill("Thử lại", R.drawable.ic_retry, PillStyle.TINTED, tint = c.green) { model.retry(entry.id) })
            }, matchWidth())
            State.Done -> if (entry.translation.isNotEmpty()) {
                val tint = if (entry.mine) c.green else c.orange
                card.addView(activity.horizontal(8).apply {
                    addView(activity.pill("Đọc lại", R.drawable.ic_speaker, PillStyle.TINTED, tint = tint) { model.repeatAloud(entry.id) })
                    addView(activity.spacer())
                    if (entry.mine) addView(activity.pill("Hiện to", R.drawable.ic_expand, PillStyle.TINTED, tint = tint) { enlarge(entry) })
                }, matchWidth())
            }
        }
        card.setOnClickListener { if (entry.mine && entry.translation.isNotEmpty()) enlarge(entry) }
        card.setOnLongClickListener {
            copyChoices(entry)
            true
        }
        return FrameLayout(activity).apply {
            setPadding(0, 0, 0, activity.dp(10))
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun copyChoices(entry: ConversationModel.Entry) {
        val options = ArrayList<Pair<String, String>>()
        if (entry.translation.isNotEmpty()) options.add("Chép bản dịch" to entry.translation)
        if (entry.text.isNotEmpty()) options.add("Chép câu gốc" to entry.text)
        if (options.isEmpty()) return
        AlertDialog.Builder(activity)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                activity.copyText(options[which].second)
                activity.toast("Đã chép")
            }
            .show()
    }

    private fun rich(text: String): CharSequence =
        Html.fromHtml(text.replace("&", "&amp;").replace("<", "&lt;").replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>"), Html.FROM_HTML_MODE_LEGACY)

    private fun renderHint() {
        val box = activity.card().apply {
            addView(activity.label("Cách dùng", TextSize.HEADLINE, c.green, bold = true).leading(R.drawable.ic_info, c.green, 20, 8))
            addView(activity.label(rich("Bấm **nút tròn bên trái** (sóng âm) để bắt đầu nghe, rồi đặt điện thoại giữa bàn. Mọi người nói gì, bạn đọc bằng tiếng Việt ở đây.")))
            addView(activity.label(rich(
                if (model.readAloud) {
                    "**Đọc to + chữ**: bản dịch tiếng Việt còn được đọc to qua loa. Khi nhiều người nói liên tục, giọng đọc nhanh hơn " +
                        "và bỏ qua câu đã cũ để theo kịp; chữ vẫn hiện đủ." +
                        if (model.engine == Engine.ACCURATE) " Lúc điện thoại đang đọc, Chính xác tạm không nghe." else ""
                } else {
                    "**Chỉ chữ**: chỉ hiện bản dịch, không đọc. Hợp cuộc họp nhiều người nói liên tục." +
                        if (model.engine == Engine.SONIOX) " Micro nghe người ngồi xa rõ hơn ở chế độ này." else ""
                },
            )))
            addView(activity.label(rich(
                "Muốn nói: **giữ nút Tôi nói** trong lúc nói (hoặc chạm một lần, nói xong chạm lần nữa). Câu của bạn được dịch sang " +
                    "${model.partnerLanguage.name} và đọc to qua loa cho mọi người nghe.",
            )))
            when (model.engine) {
                Engine.SONIOX -> {
                    addView(activity.label(
                        if (settings.apiKey == null) "Cách dịch: Soniox, chữ và bản dịch chạy ngay khi mọi người đang nói, hết câu là đọc. Đổi trong menu ⋯."
                        else "Cách dịch: Soniox, chữ chạy ngay khi mọi người đang nói, tách câu theo từng người; hết câu GPT dịch lại cho chuẩn rồi đọc. Đổi trong menu ⋯.",
                        TextSize.SUBHEADLINE, c.secondary,
                    ))
                    if (settings.sonioxKey == null) addView(activity.noteBox("Chưa có key Soniox. Dán key trong Cài đặt › Soniox."))
                }
                Engine.LIVE -> {
                    addView(activity.label("Cách dịch: Dịch liền, chữ và giọng chạy ngay khi người khác đang nói; hết câu GPT dịch lại chữ cho chuẩn. Đổi trong menu ⋯.",
                        TextSize.SUBHEADLINE, c.secondary))
                    if (!model.liveSpeaksPartner) addView(activity.noteBox(
                        "Dịch liền chưa đọc được ${model.partnerLanguage.name}. Muốn nói, đổi Cách dịch sang Chính xác (menu ⋯).",
                    ))
                }
                Engine.ACCURATE -> addView(activity.label(
                    "Cách dịch: Chính xác, chữ hiện sau mỗi câu, dùng từ điển thuật ngữ và ngữ cảnh cuộc họp. Đổi trong menu ⋯.",
                    TextSize.SUBHEADLINE, c.secondary,
                ))
            }
            if (GoogleVoice.nameOf(model.voice) != null && settings.googleKey == null) {
                addView(activity.noteBox("Chưa có key Google nên đang đọc bằng giọng điện thoại. Dán key trong Cài đặt › Giọng Google."))
            }
            addView(activity.label(
                if (model.engine == Engine.SONIOX) {
                    "Chi phí ${if (settings.apiKey == null) "Soniox" else "Soniox và OpenAI"} với lựa chọn hiện tại: ${CostMeter.text(hourlyCost())} mỗi giờ họp."
                } else {
                    "Chi phí OpenAI với lựa chọn hiện tại: ${CostMeter.text(hourlyCost())} mỗi giờ có người nói. Khoảng lặng không tính tiền."
                },
                TextSize.SUBHEADLINE, c.secondary,
            ))
        }
        hint.removeAllViews()
        hint.addView(LinearLayout(activity).apply {
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
            addView(box, matchWidth())
        })
    }

    private fun hourlyCost(): Double = CostMeter.perHour(
        live = model.engine == Engine.LIVE, soniox = model.engine == Engine.SONIOX, model = model.quality.model,
        readAloud = model.readAloud, openAIVoice = ConversationModel.isOpenAIVoice(model.voice),
        translatesAgain = model.engine == Engine.LIVE || (model.engine == Engine.SONIOX && settings.apiKey != null),
    )

    // Controls

    private val talkDisabled: Boolean get() = model.engine == Engine.LIVE && !model.liveSpeaksPartner

    private fun renderControls() {
        problemBox.removeAllViews()
        val problem = model.problem
        problemBox.visibility = if (problem == null) View.GONE else View.VISIBLE
        if (problem != null) {
            problemBox.addView(ImageView(activity).apply {
                setImageDrawable(activity.icon(R.drawable.ic_error, c.red))
                layoutParams = LinearLayout.LayoutParams(activity.dp(20), activity.dp(20))
            })
            problemBox.addView(activity.label(problem, TextSize.SUBHEADLINE), weighted())
            if (model.micDenied) problemBox.addView(activity.label("Mở Cài đặt", TextSize.SUBHEADLINE, c.green, bold = true).apply {
                setPadding(activity.dp(4), 0, activity.dp(4), 0)
                setOnClickListener { Setup.openAppSettings(activity) }
            })
            problemBox.addView(activity.iconButton(R.drawable.ic_close, c.secondary, 28, description = "Đóng thông báo") { model.problem = null })
        }

        listenCircle.setColor(if (model.running) c.red else c.greenFill)
        listenRing.visibility = if (model.running) View.VISIBLE else View.INVISIBLE
        val scale = (0.8 + model.level * 0.2).toFloat()
        listenRing.scaleX = scale
        listenRing.scaleY = scale
        listenSpinner.visibility = if (model.starting) View.VISIBLE else View.GONE
        listenIcon.visibility = if (model.starting) View.GONE else View.VISIBLE
        listenIcon.setImageDrawable(activity.icon(if (model.running) R.drawable.ic_stop else R.drawable.ic_waveform, Color.WHITE))
        listenButton.contentDescription = if (model.running) "Dừng nghe" else "Bắt đầu nghe"

        if (!model.talking && !model.starting) latched = false
        talkBackground.setColor(if (model.talking) c.orange else c.greenFill)
        talkButton.alpha = if (talkDisabled) 0.4f else 1f
        talkIcon.setImageDrawable(activity.icon(R.drawable.ic_mic, Color.WHITE))
        talkTitle.text = if (model.talking) "Đang nghe bạn…" else "Tôi nói"
        talkSubtitle.text = when {
            !model.talking -> "Giữ để nói · sang ${model.partnerLanguage.label}"
            latched -> "Nói xong, chạm để dịch"
            else -> "Nói xong, thả tay để dịch"
        }
        talkButton.contentDescription = if (model.talking) "Đang nghe bạn nói. Chạm để dịch" else "Tôi nói, sang ${model.partnerLanguage.name}"
    }

    /** Hold to speak and let go to translate; or tap, speak, and tap again. */
    private fun talkTouch(event: MotionEvent): Boolean {
        if (talkDisabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedAt = System.currentTimeMillis()
                talkButton.isPressed = true
                // The second tap of a tapped turn ends it when the finger lifts.
                if (latched && (model.talking || model.starting)) return true
                latched = false
                model.talk(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                talkButton.isPressed = false
                val held = System.currentTimeMillis() - pressedAt
                if (latched) {
                    latched = false
                    model.talk(false)
                } else if (held < 350 && event.actionMasked == MotionEvent.ACTION_UP) {
                    latched = true
                } else {
                    model.talk(false)
                }
                renderControls()
            }
        }
        return true
    }

    private fun renderStatus() {
        status.text = statusText()
    }

    private fun statusText(): String {
        if (model.starting) return "Đang mở micro…"
        val started = model.startedAt
        if (!model.running || started == null) {
            return if (model.meeting.lines.isEmpty()) "Bấm nút tròn để bắt đầu nghe"
            else "Đã dừng · ${model.meeting.lines.size} câu · bấm nút tròn để nghe tiếp"
        }
        if (model.interrupted) return "Tạm dừng: micro đang bận (cuộc gọi)"
        if (model.speakingMine) return "Đang đọc câu của bạn cho đối tác…"
        if (model.speaking) return "Đang đọc bản dịch…"
        if (model.talking) return "Đang nghe bạn nói"
        val seconds = maxOf(0L, (System.currentTimeMillis() - started) / 1000)
        val time = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        return if (model.hearsSpeech) "Đang nghe · $time · có người đang nói" else "Đang nghe · $time"
    }

    // Menu ⋯

    private fun openMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        val menu = popup.menu
        val busy = model.running || model.starting

        val engines = menu.addSubMenu(Menu.NONE, Menu.NONE, 0, "Cách dịch")
        Engine.entries.forEachIndexed { i, engine ->
            engines.add(1, 100 + i, i, engine.title).isChecked = engine == model.engine
        }
        engines.setGroupCheckable(1, true, true)
        engines.item.isEnabled = !busy

        val qualities = menu.addSubMenu(Menu.NONE, Menu.NONE, 1, "Dịch lời đối phương")
        ConversationModel.Quality.entries.forEachIndexed { i, quality ->
            qualities.add(2, 200 + i, i, quality.title).isChecked = quality == model.quality
        }
        qualities.setGroupCheckable(2, true, true)
        menu.add(2, 250, 1, if (model.topic.isEmpty()) "Chủ đề cuộc họp…" else "Chủ đề: ${model.topic}")

        val silences = menu.addSubMenu(Menu.NONE, Menu.NONE, 2, "Ngừng bao lâu là hết câu")
        ConversationModel.silenceChoices.forEachIndexed { i, seconds ->
            silences.add(3, 300 + i, i, String.format(Locale.US, "%.1f giây", seconds).replace(".", ",")).isChecked = seconds == model.silence
        }
        silences.setGroupCheckable(3, true, true)

        val voices = menu.addSubMenu(Menu.NONE, Menu.NONE, 3, "Giọng đọc")
        ConversationModel.voices.forEachIndexed { i, (id, name) ->
            voices.add(4, 400 + i, i, name).isChecked = id == model.voice
        }
        voices.setGroupCheckable(4, true, true)

        val sizes = menu.addSubMenu(Menu.NONE, Menu.NONE, 4, "Cỡ chữ")
        ConversationModel.TextSize.entries.forEachIndexed { i, size ->
            sizes.add(5, 500 + i, i, size.title).isChecked = size == model.textSize
        }
        sizes.setGroupCheckable(5, true, true)

        val hasLines = model.meeting.lines.isNotEmpty()
        menu.add(6, 600, 5, "Tóm tắt cuộc họp").isEnabled = hasLines
        menu.add(6, 601, 6, "Chia sẻ biên bản").isEnabled = hasLines
        menu.add(6, 602, 7, "Cuộc họp mới").isEnabled = model.canStartNewMeeting
        menu.add(6, 603, 8, "Cuộc họp đã lưu")
        menu.add(6, 604, 8, "Sao chép nhật ký tốc độ")
        menu.add(7, 700, 9, "Cuộc họp này: ${CostMeter.text(model.spent)} (ước tính)").isEnabled = false

        popup.setOnMenuItemClickListener { item ->
            val id = item.itemId
            when (id) {
                in 100..199 -> model.engine = Engine.entries[id - 100]
                250 -> editTopic()
                in 200..249 -> model.quality = ConversationModel.Quality.entries[id - 200]
                in 300..399 -> model.silence = ConversationModel.silenceChoices[id - 300]
                in 400..499 -> model.voice = ConversationModel.voices[id - 400].first
                in 500..599 -> model.textSize = ConversationModel.TextSize.entries[id - 500]
                600 -> {
                    openSummary()
                    model.summarizeCurrent()
                }
                601 -> activity.shareText(model.meeting.transcript)
                602 -> model.newMeeting()
                603 -> openSaved()
                604 -> {
                    activity.copyText(model.speedLogText)
                    activity.toast("Đã chép nhật ký tốc độ")
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    /** What the meeting is about: told to GPT with every sentence, to Soniox when listening starts. */
    private fun editTopic() {
        val field = activity.field("Ví dụ: báo giá máy CNC với nhà cung cấp Thâm Quyến", model.topic)
        val box = FrameLayout(activity).apply {
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("Chủ đề cuộc họp")
            .setMessage("Vài chữ về nội dung, đối tác, sản phẩm: giúp nghe và dịch đúng thuật ngữ, đúng ngữ cảnh. " +
                "GPT dùng ngay; Soniox dùng từ lần bấm nghe sau. Để trống nếu không cần.")
            .setView(box)
            .setPositiveButton("Lưu") { _, _ -> model.setTopic(field.text.toString()) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Pages

    /** The user's translation in large type, to show the partner across the table. */
    private fun enlarge(entry: ConversationModel.Entry) {
        val page = activity.vertical(20).apply {
            setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24))
            addView(activity.horizontal().apply {
                addView(activity.label(Language.displayName(entry.target), TextSize.HEADLINE, c.secondary, bold = true), weighted())
                addView(activity.iconButton(R.drawable.ic_close, c.secondary, description = "Đóng") { activity.pop() })
            }, matchWidth())
            addView(activity.label(entry.translation, 44f, c.text, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setAutoSizeTextTypeUniformWithConfiguration(14, 44, 1, TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(activity.label(entry.text, TextSize.BODY, c.secondary), matchWidth())
            setOnClickListener { activity.pop() }
        }
        activity.push(page)
        page.setBackgroundColor(c.card)
    }

    private fun openSummary() {
        val body = activity.vertical(16)
        summaryBody = body
        renderSummary(body)
        activity.push(activity.page("Tóm tắt",
            activity.iconButton(R.drawable.ic_share, c.green, description = "Chia sẻ") {
                model.meeting.summary?.let { activity.shareText(it) }
            },
            onClose = { summaryBody = null },
        ) {
            addView(body, matchWidth())
        })
    }

    private fun renderSummary(body: LinearLayout) {
        body.removeAllViews()
        val summary = model.meeting.summary
        val problem = model.problem
        when {
            model.summarizing -> body.addView(activity.progress("Đang tóm tắt cuộc họp…", TextSize.BODY))
            summary != null -> body.addView(activity.label(summary, TextSize.BODY).selectable(), matchWidth())
            problem != null -> body.addView(activity.label(problem, TextSize.BODY, c.red).leading(R.drawable.ic_error, c.red, 18, 8))
        }
    }

    /** Meetings kept on the phone, newest first. */
    private fun openSaved() {
        val listBox = activity.vertical(10)
        listBox.addView(activity.progress("Đang mở…"))
        activity.push(activity.page("Cuộc họp đã lưu") { addView(listBox, matchWidth()) })
        scope.launch {
            val meetings = withContext(Dispatchers.IO) { app.meetings.all() }.toMutableList()
            fun fill() {
                listBox.removeAllViews()
                if (meetings.isEmpty()) {
                    listBox.addView(activity.label("Chưa có cuộc họp nào được lưu.", TextSize.BODY, c.secondary))
                    return
                }
                for (meeting in meetings) {
                    listBox.addView(activity.card(spacing = 4).apply {
                        addView(activity.label(meeting.title, TextSize.HEADLINE, bold = true))
                        addView(activity.label("${meeting.length} · ${meeting.lines.size} câu", TextSize.SUBHEADLINE, c.secondary))
                        meeting.lines.firstOrNull()?.let { first ->
                            addView(activity.label(if (first.mine || first.translation.isEmpty()) first.text else first.translation,
                                TextSize.SUBHEADLINE).oneLine())
                        }
                        background = activity.pressable(rounded(c.card, activity.dpf(20)), activity.dpf(20))
                        setOnClickListener { openMeeting(meeting) }
                        setOnLongClickListener {
                            activity.confirm("Xóa cuộc họp ${meeting.title}?", "Không hoàn tác được.", "Xóa") {
                                app.meetings.delete(meeting)
                                meetings.remove(meeting)
                                fill()
                            }
                            true
                        }
                    }, matchWidth())
                }
                listBox.addView(activity.label("Giữ ngón tay trên một cuộc họp để xóa.", TextSize.FOOTNOTE, c.secondary))
            }
            fill()
        }
    }

    private fun openMeeting(meeting: Meeting) {
        val summaryBox = activity.vertical(10)
        var summarizing = false
        var problem: String? = null
        fun renderMeetingSummary() {
            summaryBox.removeAllViews()
            val summary = meeting.summary
            when {
                summary != null -> summaryBox.addView(activity.label(summary, TextSize.BODY).selectable(), matchWidth())
                summarizing -> summaryBox.addView(activity.progress("Đang tóm tắt…", TextSize.BODY))
                else -> summaryBox.addView(activity.pill("Tóm tắt cuộc họp này", style = PillStyle.PRIMARY) {
                    summarizing = true
                    problem = null
                    renderMeetingSummary()
                    scope.launch {
                        try {
                            val (text, usage, model) = ConversationModel.summarize(meeting, settings)
                            meeting.summary = text
                            val cost = meeting.cost ?: CostMeter().also { meeting.cost = it }
                            cost.used(usage, model)
                            app.meetings.save(meeting)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem = e.message
                        }
                        summarizing = false
                        renderMeetingSummary()
                    }
                }, wrap())
            }
            problem?.let { summaryBox.addView(activity.label(it, TextSize.SUBHEADLINE, c.red)) }
        }
        renderMeetingSummary()
        activity.push(activity.page(meeting.title,
            activity.iconButton(R.drawable.ic_share, c.green, description = "Chia sẻ biên bản") { activity.shareText(meeting.transcript) },
        ) {
            addView(activity.card().apply {
                addView(activity.label("Tóm tắt", TextSize.HEADLINE, bold = true))
                addView(summaryBox, matchWidth())
            }, matchWidth())
            meeting.cost?.let {
                addView(activity.label("Chi phí ước tính: ${CostMeter.text(it.dollars)}", TextSize.SUBHEADLINE, c.secondary)
                    .apply { setPadding(activity.dp(4), 0, activity.dp(4), 0) })
            }
            addView(activity.label("Biên bản", TextSize.HEADLINE, bold = true).apply { setPadding(activity.dp(4), 0, 0, 0) })
            addView(activity.card(spacing = 14).apply {
                for (line in meeting.lines) {
                    addView(activity.vertical(4).apply {
                        addView(activity.horizontal().apply {
                            addView(activity.label(line.who, TextSize.FOOTNOTE,
                                if (line.mine) c.green else c.orange, bold = true), weighted())
                            addView(activity.label(Dates.time(line.at), TextSize.CAPTION, c.secondary))
                        }, matchWidth())
                        addView(activity.label(line.translation.ifEmpty { line.text }, TextSize.BODY).selectable())
                        if (line.translation.isNotEmpty()) addView(activity.label(line.text, TextSize.SUBHEADLINE, c.secondary).selectable())
                    }, matchWidth())
                }
            }, matchWidth())
        })
    }
}
