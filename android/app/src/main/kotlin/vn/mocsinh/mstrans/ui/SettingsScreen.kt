package vn.mocsinh.mstrans.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.Screen
import vn.mocsinh.mstrans.core.KeyCheck
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.Tone
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.Translator
import vn.mocsinh.mstrans.screen.ScreenState

/**
 * Cài đặt: a short list of groups, each opening its own page with the details. A key typed in is
 * confirmed with its service ("Xác nhận kết nối"); once confirmed it is shown hidden and dimmed,
 * locked until "Đổi key".
 */
class SettingsScreen(private val activity: MainActivity) : Screen {
    private val settings = activity.settings
    private val store = activity.app.store
    private val c = activity.colors
    private val scope = MainScope()
    private val body = activity.vertical().apply { setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(32)) }
    private val scroll = ScrollView(activity).apply { addView(body) }
    private var contextCleared = false

    override val view: View = activity.vertical().apply {
        addView(activity.topBar("Cài đặt"), matchWidth())
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun shown() {
        contextCleared = false
        build()
    }

    override fun destroyed() = scope.cancel()

    /** Built again each time the tab shows: back from Android's settings, the steps done are ticked. */
    private fun build() {
        val y = scroll.scrollY
        body.removeAllViews()
        if (!Setup.status(activity).done) body.addView(setupSection())
        body.addView(connectionsSection())
        body.addView(messagesSection())
        body.addView(screenSection())
        body.addView(moreSection())
        body.addView(aboutSection())
        scroll.post { scroll.scrollTo(0, y) }
    }

    // The tab: groups

    private fun setupSection(): View {
        val status = Setup.status(activity)
        return activity.section("Bắt đầu", null, listOf(
            step(status.keyboardEnabled, "Bật bàn phím MSTrans",
                "Cài đặt điện thoại › Hệ thống › Bàn phím › Bàn phím ảo (hoặc Quản lý bàn phím) › bật MSTrans. Android cảnh báo " +
                    "bàn phím nào cũng có thể đọc chữ bạn gõ: đó là lời nhắc chung; MSTrans chỉ gửi đi câu bạn bấm Dịch."),
            activity.row("Mở danh sách bàn phím", null, c.green) { Setup.openKeyboardSettings(activity) },
            step(status.keyboardSelected, "Chọn MSTrans khi gõ",
                "Chạm vào ô nhập, rồi chạm biểu tượng bàn phím ở góc dưới màn hình (hoặc phím quả địa cầu) và chọn MSTrans."),
            activity.row("Chọn bàn phím", null, c.green) { Setup.pickKeyboard(activity) },
            step(status.hasKey, "Dán và xác nhận API key OpenAI", "Ở mục Kết nối dịch vụ › OpenAI ngay bên dưới."),
        ))
    }

    private fun connectionsSection(): View = activity.section("Kết nối dịch vụ",
        "Key chỉ lưu trên điện thoại này. Bấm “Xác nhận kết nối” để biết key dùng được; key đã xác nhận được che và khóa lại.",
        KeyCheck.Service.entries.map { service -> activity.row(service.title, keyState(service)) { openKey(service) } })

    private fun keyState(service: KeyCheck.Service): String {
        val key = keyOf(service) ?: return "Chưa có key"
        return if (KeyCheck.confirmed(store, service, key)) "✓ Đã xác nhận" else "Chưa xác nhận"
    }

    private fun messagesSection(): View {
        val target = Language.all.firstOrNull { it.code == settings.targetCode }?.let { "${it.label} · ${it.name}" } ?: "Tự động"
        return activity.section("Dịch tin nhắn", null, listOf(
            activity.row("Tin bạn gửi", target) { open("Tin bạn gửi") { outgoingSection(it) } },
            activity.row("Về bạn", listOf("Không nêu", "Nam", "Nữ").getOrElse(settings.gender) { "" }) { open("Về bạn") { aboutYouSection() } },
            activity.row("Tin đối tác gửi", if (settings.instantIncoming) "Tự dịch" else "Chạm để dịch") { open("Tin đối tác gửi") { incomingSection(it) } },
            activity.row("Dịch trong mọi app", null) { open("Dịch trong mọi app") { everywhereSection() } },
        ))
    }

    /** Chinese on the screen of Taobao and other chosen apps, translated where it is. */
    private fun screenSection(): View {
        val screen = ScreenState.settings(activity)
        val value = when {
            !ScreenState.permitted(activity) || ScreenState.service == null -> "Tắt"
            screen.paused -> "Tạm dừng"
            else -> "Đang bật"
        }
        return activity.section("Dịch Web",
            "Chữ Trung trong Taobao, 1688… tự hiện bản dịch tiếng Việt ngay trên màn hình, không cần bấm dịch. Dịch ngoại tuyến, miễn phí.",
            listOf(activity.row("Dịch Web", value) { activity.select(MainActivity.Tab.SCREEN) }))
    }

    private fun moreSection(): View = activity.section("Khác", null, listOf(
        activity.row("Bàn phím", null) { open("Bàn phím") { keyboardSection(it) } },
        activity.row("Dữ liệu và lịch sử", if (settings.keepHistory) "Đang lưu" else "Không lưu") { open("Dữ liệu và lịch sử") { dataSection(it) } },
    ))

    private fun aboutSection(): View = activity.section(null,
        "Bộ gõ Telex: TelexCore của VietTelex, © 2026 Phil Trinh, dùng theo giấy phép MIT (github.com/ptrinh/viettelex).",
        listOf(activity.horizontal(14).apply {
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
            addView(ImageView(activity).apply {
                setImageDrawable(activity.getDrawable(R.drawable.logo))
                layoutParams = LinearLayout.LayoutParams(activity.dp(44), activity.dp(44))
            })
            addView(activity.vertical(2).apply {
                addView(activity.label("MSTrans", TextSize.HEADLINE, bold = true))
                addView(activity.label("Phiên bản $version · Mộc Sinh", TextSize.SUBHEADLINE, c.secondary))
            })
        }))

    private val version: String
        get() {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            @Suppress("DEPRECATION")
            return "${info.versionName} (${info.versionCode})"
        }

    // Pages

    /**
     * A page over the tab whose sections `content` makes; `content` is given the way to make them
     * again (a choice changed), and the tab is made again when the page closes.
     */
    private fun open(title: String, content: (again: () -> Unit) -> List<View>) {
        lateinit var page: LinearLayout
        fun fill() {
            page.removeAllViews()
            content(::fill).forEach { page.addView(it) }
        }
        activity.push(activity.page(title, onClose = { build() }) { page = this })
        fill()
    }

    private fun keyOf(service: KeyCheck.Service): String? = when (service) {
        KeyCheck.Service.OPENAI -> settings.apiKey
        KeyCheck.Service.SONIOX -> settings.sonioxKey
        KeyCheck.Service.GOOGLE -> settings.googleKey
    }

    private fun setKey(service: KeyCheck.Service, key: String?) {
        when (service) {
            KeyCheck.Service.OPENAI -> settings.apiKey = key
            KeyCheck.Service.SONIOX -> settings.sonioxKey = key
            KeyCheck.Service.GOOGLE -> settings.googleKey = key
        }
    }

    /** One service's key: typed and confirmed, or shown hidden and locked once confirmed. */
    private fun openKey(service: KeyCheck.Service) {
        var editing = false
        var checking = false
        var answer: Pair<Boolean, String>? = null
        open(service.title) { again ->
            val key = keyOf(service)
            val confirmed = KeyCheck.confirmed(store, service, key)
            val rows = ArrayList<View>()
            if (key != null && confirmed && !editing) {
                rows.add(activity.vertical(4).apply {
                    setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
                    addView(activity.label(KeyCheck.masked(key), TextSize.BODY, c.secondary).apply {
                        typeface = Typeface.MONOSPACE
                        alpha = 0.55f
                    })
                    addView(activity.label("Đã xác nhận kết nối", TextSize.FOOTNOTE, c.green).leading(R.drawable.ic_check_circle, c.green, 16, 6))
                })
                rows.add(activity.row("Đổi key", null, c.green) {
                    editing = true
                    answer = null
                    again()
                })
                rows.add(activity.row("Xóa key", null, c.red) {
                    activity.confirm("Xóa key ${service.title}?", "Các tính năng dùng key này sẽ ngừng cho tới khi bạn dán key mới.", "Xóa") {
                        setKey(service, null)
                        KeyCheck.forget(store, service)
                        editing = false
                        answer = null
                        again()
                    }
                })
            } else {
                val field = activity.field(hint(service), key ?: "", secret = true)
                rows.add(field)
                val confirm = activity.pill(if (checking) "Đang xác nhận…" else "Xác nhận kết nối", style = PillStyle.PRIMARY) {
                    if (checking) return@pill
                    val typed = field.text?.toString()?.trim().orEmpty()
                    if (typed.isEmpty()) {
                        answer = false to "Chưa có key."
                        again()
                        return@pill
                    }
                    setKey(service, typed)
                    KeyCheck.forget(store, service)
                    checking = true
                    answer = null
                    activity.hideKeyboard()
                    again()
                    scope.launch {
                        val problem = check(service, typed)
                        checking = false
                        if (problem == null) {
                            KeyCheck.confirm(store, service, typed)
                            editing = false
                            answer = true to "Kết nối ổn."
                        } else answer = false to problem
                        again()
                    }
                }.enabled(!checking)
                rows.add(LinearLayout(activity).apply {
                    setPadding(activity.dp(16), activity.dp(6), activity.dp(16), activity.dp(12))
                    addView(confirm, matchWidth())
                })
            }
            answer?.let { (ok, text) ->
                rows.add(activity.label(text, TextSize.FOOTNOTE, if (ok) c.green else c.red).apply {
                    setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
                    leading(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error, if (ok) c.green else c.red, 16, 6)
                })
            }
            listOfNotNull(
                activity.section("Key", footer(service), rows),
                modelSection(again).takeIf { service == KeyCheck.Service.OPENAI },
                activity.section(null, null, listOf(activity.row(link(service).first, null, c.green) { Setup.openLink(activity, link(service).second) })),
            )
        }
    }

    private fun hint(service: KeyCheck.Service) = when (service) {
        KeyCheck.Service.OPENAI -> "sk-..."
        KeyCheck.Service.SONIOX -> "Key Soniox"
        KeyCheck.Service.GOOGLE -> "Key Google Cloud"
    }

    private fun footer(service: KeyCheck.Service) = when (service) {
        KeyCheck.Service.OPENAI -> "Cho bàn phím, tab Dịch, Hội thoại và chế độ AI nâng cao của Dịch Web. Xác nhận bằng cách dịch thử một câu " +
            "(tốn chưa tới 0,001 USD)."
        KeyCheck.Service.SONIOX -> "Cho tab Hội thoại, Cách dịch “Soniox”: chữ và bản dịch chạy ngay khi mọi người đang nói, khoảng 0,2 USD mỗi giờ. " +
            "Xác nhận không tốn phí."
        KeyCheck.Service.GOOGLE -> "Cho giọng đọc Aoede (nữ) và Charon (nam) ở tab Hội thoại. Miễn phí 1 triệu ký tự mỗi tháng, quá thì 30 USD cho " +
            "1 triệu ký tự; dự án Google cần gắn thanh toán và bật Cloud Text-to-Speech API. Xác nhận không tốn phí."
    }

    private fun link(service: KeyCheck.Service): Pair<String, String> = when (service) {
        KeyCheck.Service.OPENAI -> "Lấy key ở platform.openai.com" to "https://platform.openai.com/api-keys"
        KeyCheck.Service.SONIOX -> "Lấy key ở console.soniox.com" to "https://console.soniox.com"
        KeyCheck.Service.GOOGLE -> "Bật Cloud Text-to-Speech API và tạo key" to "https://console.cloud.google.com/apis/library/texttospeech.googleapis.com"
    }

    /** Null: the key works. OpenAI is asked a sentence (the model is checked too); the others a free list. */
    private suspend fun check(service: KeyCheck.Service, key: String): String? = when (service) {
        KeyCheck.Service.OPENAI -> try {
            Translator(key, settings.model, settings).translate("Anh gửi giúp em báo giá trước thứ Sáu nhé.", Language.named("en") ?: Language.all[0])
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Lỗi không rõ."
        }
        KeyCheck.Service.SONIOX -> KeyCheck.soniox(key)
        KeyCheck.Service.GOOGLE -> KeyCheck.google(key)
    }

    private fun modelSection(again: () -> Unit): View = activity.section("Chế độ dịch",
        "“Chuẩn nhất” dùng GPT-6.1 Sol, khoảng 0,003 USD mỗi tin. “Nhanh” dùng GPT-6 Luna, rẻ hơn khoảng 20 lần.",
        listOf(activity.row("Chế độ dịch", settings.model.label) {
            val models = TranslationModel.entries
            activity.choose("Chế độ dịch", models.map { it.label }, models.indexOf(settings.model)) {
                settings.model = models[it]
                again()
            }
        }))

    private fun outgoingSection(again: () -> Unit): List<View> {
        val codes = listOf(Language.AUTO) + Language.all.map { it.code }
        val names = listOf("Tự động theo đối tác") + Language.all.map { "${it.label} · ${it.name}" }
        val current = codes.indexOf(settings.targetCode).coerceAtLeast(0)
        return listOf(activity.section("Tin bạn gửi",
            "“Tự động” trả lời đúng ngôn ngữ đối tác đang dùng. Kiểm tra nghĩa cho bạn đọc lại bằng tiếng Việt câu đối tác sẽ nhận.",
            listOf(
                activity.row("Dịch sang", names[current]) {
                    activity.choose("Dịch sang", names, current) {
                        settings.targetCode = codes[it]
                        again()
                    }
                },
                padded(Segmented(activity, Tone.entries.map { it.label }, settings.tone.ordinal) { settings.tone = Tone.entries[it] }),
                activity.switchRow("Kiểm tra nghĩa (dịch ngược)", settings.checkMeaning) { settings.checkMeaning = it },
            )))
    }

    private fun aboutYouSection(): List<View> = listOf(activity.section("Về bạn",
        "Giúp bản dịch xưng hô đúng và dùng đúng tiểu từ lịch sự (tiếng Thái, tiếng Nhật…). Thuật ngữ ngành: thêm ở tab Thuật ngữ.",
        listOf(
            padded(Segmented(activity, listOf("Không nêu", "Nam", "Nữ"), settings.gender) {
                settings.gender = it
                // A partner message copied again is translated again, with these details.
                settings.clearIncomingCache()
            }),
            textField("Xưng hô, ví dụ: tôi xưng em, gọi đối tác là anh", settings.addressing, 3) {
                settings.addressing = it
                settings.clearIncomingCache()
            },
            textField("Về bạn, ví dụ: Minh, phụ trách mua hàng, nhập máy CNC từ Trung Quốc", settings.profile, 4) {
                settings.profile = it
                settings.clearIncomingCache()
            },
        )))

    private fun incomingSection(again: () -> Unit): List<View> = listOf(activity.section("Tin đối tác gửi",
        if (settings.instantIncoming) {
            "Sao chép tin rồi chạm vào ô trả lời: bản dịch hiện ngay ở dòng trên cùng của bàn phím, không cần chạm thêm."
        } else {
            "Giữ tin của đối tác › Sao chép, rồi chạm vào ô trả lời: dòng trên cùng của bàn phím hiện “Có tin vừa sao chép · chạm để dịch”, " +
                "chạm một lần. Bật “Tự dịch tin vừa sao chép” để bỏ cả cái chạm đó."
        },
        listOf(activity.switchRow("Tự dịch tin vừa sao chép", settings.instantIncoming) {
            settings.instantIncoming = it
            again()
        })))

    /** Text selected in any app, or shared to MSTrans, is translated without the keyboard. */
    private fun everywhereSection(): List<View> = listOf(activity.section("Dịch trong mọi app",
        "Dùng được cả ở app không đổi được bàn phím. Tin đối tác được dịch sang tiếng Việt; câu tiếng Việt của bạn được dịch sang " +
            "tiếng đối tác, rồi chạm “Thay chữ” (hoặc Sao chép) để gửi.",
        listOf(
            step(null, "Chọn chữ › Dịch bằng MSTrans",
                "Giữ ngón tay trên tin để chọn chữ, rồi trong menu hiện ra chạm ⋮ (Thêm) › Dịch bằng MSTrans."),
            step(null, "Chia sẻ › MSTrans", "Ở app không cho chọn chữ: Chia sẻ tin › Dịch bằng MSTrans."),
        )))

    private fun keyboardSection(again: () -> Unit): List<View> {
        val sizes = listOf("Như bàn phím thường", "Cao hơn một chút", "Cao hơn")
        return listOf(activity.section("Bàn phím",
            "Ra chữ ngay khi chạm: chữ vào ô nhập lúc ngón tay vừa chạm phím, không chờ nhấc tay; tắt thì chữ vào khi nhấc tay, " +
                "trượt sang phím khác trước khi nhấc để đổi chữ. Chữ nổi khi bấm phím: chữ to hiện phía trên phím đang bấm; tắt thì phím chỉ sáng lên, không che các phím bên cạnh. " +
                "Cỡ bàn phím: hai cỡ sau cho phím cao hơn; mở lại bàn phím là thấy. Giữ một nguyên âm để chọn dấu. Giữ phím cách rồi " +
                "kéo để di chuyển con trỏ. Chạm 2 lần Shift để khóa chữ hoa, 2 lần phím cách để thêm dấu chấm. Chạm logo MSTrans trên " +
                "bàn phím để xem tin đối tác, câu vừa dịch và ngữ cảnh.",
            listOf(
                activity.switchRow("Rung khi gõ", settings.haptics) { settings.haptics = it },
                activity.switchRow("Âm thanh khi gõ", settings.keySounds) { settings.keySounds = it },
                activity.switchRow("Ra chữ ngay khi chạm phím", settings.typesOnTouchDown) { settings.typesOnTouchDown = it },
                activity.switchRow("Chữ nổi khi bấm phím", settings.keyPopups) { settings.keyPopups = it },
                activity.row("Cỡ bàn phím", sizes[settings.keyboardSize]) {
                    activity.choose("Cỡ bàn phím", sizes, settings.keyboardSize) {
                        settings.keyboardSize = it
                        again()
                    }
                },
            )))
    }

    private fun dataSection(again: () -> Unit): List<View> = listOf(activity.section("Dữ liệu và lịch sử",
        "Ngữ cảnh là vài tin gần nhất gửi kèm để dịch đúng xưng hô; tự hết hạn sau 45 phút. Lịch sử và key chỉ lưu trên điện thoại này; " +
            "OpenAI không lưu tin vào tài khoản của bạn.",
        listOf(
            activity.switchRow("Lưu lịch sử dịch", settings.keepHistory) { settings.keepHistory = it },
            activity.row(if (contextCleared) "Đã xóa ngữ cảnh" else "Xóa ngữ cảnh đang nhớ", null, if (contextCleared) c.secondary else c.green,
                action = if (contextCleared) null else ({
                    settings.clearContext()
                    contextCleared = true
                    again()
                })),
        )))

    // Rows

    /** One setup step. `done == null` is advice the app cannot check. */
    private fun step(done: Boolean?, title: String, detail: String): View = activity.horizontal(12).apply {
        gravity = Gravity.TOP
        setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
        val iconId = when (done) {
            true -> R.drawable.ic_check_circle
            null -> R.drawable.ic_info
            false -> R.drawable.ic_circle
        }
        addView(ImageView(activity).apply {
            setImageDrawable(activity.icon(iconId, if (done == true) c.green else c.secondary))
            layoutParams = LinearLayout.LayoutParams(activity.dp(22), activity.dp(22))
        })
        addView(activity.vertical(2).apply {
            addView(activity.label(title, TextSize.BODY))
            addView(activity.label(detail, TextSize.FOOTNOTE, c.secondary))
        }, weighted())
        contentDescription = title + when (done) {
            true -> ", đã xong"
            false -> ", chưa xong"
            null -> ""
        }
    }

    private fun padded(view: View): View = LinearLayout(activity).apply {
        setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
        addView(view, matchWidth())
    }

    private fun watch(field: EditText, onChange: (String) -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
        })
    }

    private fun textField(hint: String, text: String, lines: Int, onChange: (String) -> Unit): EditText =
        activity.field(hint, text, lines).apply { watch(this, onChange) }

}
