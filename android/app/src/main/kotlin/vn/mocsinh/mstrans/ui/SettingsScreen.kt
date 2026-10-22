package vn.mocsinh.mstrans.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.Screen
import vn.mocsinh.mstrans.core.Language
import vn.mocsinh.mstrans.core.Tone
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.Translator

class SettingsScreen(private val activity: MainActivity) : Screen {
    private val settings = activity.settings
    private val c = activity.colors
    private val scope = MainScope()
    private val body = activity.vertical().apply { setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(32)) }
    private val scroll = ScrollView(activity).apply { addView(body) }
    private var testing = false
    private var testResult: Pair<Boolean, String>? = null
    private var contextCleared = false
    private var testRow: LinearLayout? = null
    private var resultLabel: TextView? = null

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
        body.addView(setupSection())
        body.addView(openAISection())
        body.addView(sonioxSection())
        body.addView(googleSection())
        body.addView(outgoingSection())
        body.addView(aboutYouSection())
        body.addView(incomingSection())
        body.addView(everywhereSection())
        body.addView(keyboardSection())
        body.addView(dataSection())
        body.addView(aboutSection())
        scroll.post { scroll.scrollTo(0, y) }
    }

    // Sections

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
            step(status.hasKey, "Dán API key OpenAI", "Ở mục OpenAI ngay bên dưới."),
        ))
    }

    private fun openAISection(): View {
        val key = secretField("sk-...", settings.apiKey ?: "") { text ->
            settings.apiKey = text.trim().ifEmpty { null }
            testResult = null
            showTest()
        }
        val tester = activity.row("Kiểm tra kết nối", null, c.green) { test() }
        testRow = tester
        val result = activity.label("", TextSize.FOOTNOTE).apply {
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(10))
        }
        resultLabel = result
        showTest()
        return activity.section("OpenAI",
            "“Chuẩn nhất” dùng GPT-6.1 Sol, khoảng 0,003 USD mỗi tin. “Nhanh” dùng GPT-6 Luna, rẻ hơn khoảng 20 lần. " +
                "Key chỉ lưu trên điện thoại này.",
            listOf(
                key,
                activity.row("Chế độ dịch", settings.model.label) {
                    val models = TranslationModel.entries
                    activity.choose("Chế độ dịch", models.map { it.label }, models.indexOf(settings.model)) {
                        settings.model = models[it]
                        testResult = null
                        build()
                    }
                },
                tester,
                result,
            ))
    }

    /** The connection test's button and answer, without building the page again (the key field keeps its focus). */
    private fun showTest() {
        testRow?.let { row ->
            ((row.getChildAt(0)) as? TextView)?.text = if (testing) "Đang kiểm tra…" else "Kiểm tra kết nối"
            row.isEnabled = !testing
            row.alpha = if (testing) 0.6f else 1f
        }
        val label = resultLabel ?: return
        val outcome = testResult
        if (outcome == null) {
            label.visibility = View.GONE
            return
        }
        val (ok, text) = outcome
        label.visibility = View.VISIBLE
        label.text = text
        label.setTextColor(if (ok) c.green else c.red)
        label.leading(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error, if (ok) c.green else c.red, 16, 6)
    }

    private fun sonioxSection(): View = activity.section("Soniox",
        "Cho tab Hội thoại, Cách dịch “Soniox”: chữ và bản dịch chạy ngay khi mọi người đang nói, khoảng 0,2 USD mỗi giờ " +
            "(Dịch liền của OpenAI khoảng 2 USD). Không có key OpenAI thì đọc bằng giọng điện thoại. Key chỉ lưu trên điện thoại này.",
        listOf(
            secretField("Key Soniox", settings.sonioxKey ?: "") { settings.sonioxKey = it.trim().ifEmpty { null } },
            activity.row("Lấy key ở console.soniox.com", null, c.green) { Setup.openLink(activity, "https://console.soniox.com") },
        ))

    private fun googleSection(): View = activity.section("Giọng Google",
        "Cho giọng đọc Aoede (nữ) và Charon (nam) ở tab Hội thoại, menu ⋯ › Giọng đọc: tiếng Việt tự nhiên, khoảng nửa giây một câu. " +
            "Miễn phí 1 triệu ký tự mỗi tháng (khoảng 20 giờ đọc), quá thì 30 USD cho 1 triệu ký tự. Dự án Google cần gắn thanh toán. " +
            "Key chỉ lưu trên điện thoại này.",
        listOf(
            secretField("Key Google Cloud", settings.googleKey ?: "") { settings.googleKey = it.trim().ifEmpty { null } },
            activity.row("Bật Cloud Text-to-Speech API và tạo key", null, c.green) {
                Setup.openLink(activity, "https://console.cloud.google.com/apis/library/texttospeech.googleapis.com")
            },
        ))

    private fun outgoingSection(): View {
        val codes = listOf(Language.AUTO) + Language.all.map { it.code }
        val names = listOf("Tự động theo đối tác") + Language.all.map { "${it.label} · ${it.name}" }
        val current = codes.indexOf(settings.targetCode).coerceAtLeast(0)
        return activity.section("Tin bạn gửi",
            "“Tự động” trả lời đúng ngôn ngữ đối tác đang dùng. Kiểm tra nghĩa cho bạn đọc lại bằng tiếng Việt câu đối tác sẽ nhận.",
            listOf(
                activity.row("Dịch sang", names[current]) {
                    activity.choose("Dịch sang", names, current) {
                        settings.targetCode = codes[it]
                        build()
                    }
                },
                padded(Segmented(activity, Tone.entries.map { it.label }, settings.tone.ordinal) { settings.tone = Tone.entries[it] }),
                activity.switchRow("Kiểm tra nghĩa (dịch ngược)", settings.checkMeaning) { settings.checkMeaning = it },
            ))
    }

    private fun aboutYouSection(): View = activity.section("Về bạn",
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
        ))

    private fun incomingSection(): View = activity.section("Tin đối tác gửi",
        if (settings.instantIncoming) {
            "Sao chép tin rồi chạm vào ô trả lời: bản dịch hiện ngay ở dòng trên cùng của bàn phím, không cần chạm thêm."
        } else {
            "Giữ tin của đối tác › Sao chép, rồi chạm vào ô trả lời: dòng trên cùng của bàn phím hiện “Có tin vừa sao chép · chạm để dịch”, " +
                "chạm một lần. Bật “Dịch ngay” để bỏ cả cái chạm đó."
        },
        listOf(activity.switchRow("Dịch ngay khi mở bàn phím", settings.instantIncoming) {
            settings.instantIncoming = it
            build()
        }))

    /** Text selected in any app, or shared to MSTrans, is translated without the keyboard. */
    private fun everywhereSection(): View = activity.section("Dịch trong mọi app",
        "Dùng được cả ở app không đổi được bàn phím. Tin đối tác được dịch sang tiếng Việt; câu tiếng Việt của bạn được dịch sang " +
            "tiếng đối tác, rồi chạm “Thay chữ” (hoặc Sao chép) để gửi.",
        listOf(
            step(null, "Chọn chữ › Dịch bằng MSTrans",
                "Giữ ngón tay trên tin để chọn chữ, rồi trong menu hiện ra chạm ⋮ (Thêm) › Dịch bằng MSTrans."),
            step(null, "Chia sẻ › MSTrans", "Ở app không cho chọn chữ: Chia sẻ tin › Dịch bằng MSTrans."),
        ))

    private fun keyboardSection(): View {
        val sizes = listOf("Như bàn phím thường", "Cao hơn một chút", "Cao hơn")
        return activity.section("Bàn phím",
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
                        build()
                    }
                },
            ))
    }

    private fun dataSection(): View = activity.section("Dữ liệu",
        "Ngữ cảnh là vài tin gần nhất gửi kèm để dịch đúng xưng hô; tự hết hạn sau 45 phút. Lịch sử và key chỉ lưu trên điện thoại này; " +
            "OpenAI không lưu tin vào tài khoản của bạn.",
        listOf(
            activity.switchRow("Lưu lịch sử dịch", settings.keepHistory) { settings.keepHistory = it },
            activity.row(if (contextCleared) "Đã xóa ngữ cảnh" else "Xóa ngữ cảnh đang nhớ", null, if (contextCleared) c.secondary else c.green,
                action = if (contextCleared) null else ({
                    settings.clearContext()
                    contextCleared = true
                    build()
                })),
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

    private fun secretField(hint: String, text: String, onChange: (String) -> Unit): EditText =
        activity.field(hint, text, secret = true).apply { watch(this, onChange) }

    private fun textField(hint: String, text: String, lines: Int, onChange: (String) -> Unit): EditText =
        activity.field(hint, text, lines).apply { watch(this, onChange) }

    // Actions

    private fun test() {
        val apiKey = settings.apiKey
        if (apiKey == null) {
            testResult = false to "Chưa có API key."
            showTest()
            return
        }
        if (testing) return
        testing = true
        testResult = null
        showTest()
        val tested = apiKey
        val translator = Translator(apiKey, settings.model, settings)
        scope.launch {
            val outcome = try {
                val result = translator.translate("Anh gửi giúp em báo giá trước thứ Sáu nhé.", Language.named("en") ?: Language.all[0],
                    checkMeaning = true)
                true to "Kết nối ổn: ${result.text}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to (e.message ?: "Lỗi không rõ.")
            }
            testing = false
            // A key pasted meanwhile is not the one this answer is about.
            if (settings.apiKey == tested) testResult = outcome
            showTest()
        }
    }
}
