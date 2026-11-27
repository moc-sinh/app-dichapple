package vn.mocsinh.mstrans.ui

import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.mocsinh.mstrans.AppLock
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.core.Activation

/** The only screen until MSTrans is activated: the code given by Mộc Sinh, and one button. */
class ActivationScreen(private val activity: MainActivity, private val done: () -> Unit) {
    private val c = activity.colors
    private val scope = MainScope()
    private val field = activity.field("••••••", secret = true).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(12))
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        letterSpacing = 0.2f
        background = rounded(c.card, activity.dpf(14))
        imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, _, _ ->
            check()
            true
        }
    }
    private val message = activity.label("", TextSize.FOOTNOTE, c.red).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val button = activity.pill("Kích hoạt", style = PillStyle.PRIMARY, size = TextSize.BODY) { check() }
    private var checking = false

    val view: View = ScrollView(activity).apply {
        isFillViewport = true
        addView(FrameLayout(activity).apply {
            addView(activity.vertical(18).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(activity.dp(28), activity.dp(24), activity.dp(28), activity.dp(24))
                addView(ImageView(activity).apply {
                    setImageDrawable(activity.getDrawable(R.drawable.logo))
                    layoutParams = LinearLayout.LayoutParams(activity.dp(64), activity.dp(64))
                })
                addView(activity.label("Kích hoạt MSTrans", TextSize.TITLE, bold = true).apply { gravity = Gravity.CENTER })
                addView(activity.label("Nhập mã kích hoạt do Mộc Sinh cung cấp. Chỉ cần nhập một lần trên điện thoại này.",
                    TextSize.SUBHEADLINE, c.secondary).apply { gravity = Gravity.CENTER })
                addView(field, LinearLayout.LayoutParams(activity.dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(button, LinearLayout.LayoutParams(activity.dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(message, matchWidth())
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun check() {
        if (checking) return
        val code = field.text?.toString().orEmpty()
        if (code.isBlank()) {
            say("Nhập mã kích hoạt.")
            return
        }
        checking = true
        button.text = "Đang kiểm tra…"
        button.alpha = 0.6f
        scope.launch {
            // The check is slow on purpose (a fraction of a second).
            val answer = withContext(Dispatchers.Default) { AppLock.of(activity).tryCode(code) }
            checking = false
            button.text = "Kích hoạt"
            button.alpha = 1f
            when (answer) {
                Activation.Answer.Accepted -> {
                    activity.hideKeyboard()
                    activity.toast("Đã kích hoạt MSTrans")
                    scope.cancel()
                    done()
                }
                is Activation.Answer.Wrong -> {
                    field.setText("")
                    say(if (answer.waitMs > 0) "Mã không đúng. Thử lại sau ${seconds(answer.waitMs)}." else "Mã không đúng.")
                }
                is Activation.Answer.TooSoon -> say("Đã nhập sai nhiều lần. Thử lại sau ${seconds(answer.waitMs)}.")
            }
        }
    }

    private fun seconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s < 60) "$s giây" else "${(s + 59) / 60} phút"
    }

    private fun say(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }
}
