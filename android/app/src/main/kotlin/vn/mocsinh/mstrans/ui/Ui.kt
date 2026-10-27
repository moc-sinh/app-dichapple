package vn.mocsinh.mstrans.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * MSTrans colours, taken from the Mộc Sinh logo (as on the iPhone): green is the brand, your side of
 * the chat and every action; orange is the partner's side; gold flags something to double-check.
 */
class Colors(val dark: Boolean) {
    private fun pick(light: Long, night: Long): Int = (if (dark) night else light).toInt()

    val page = pick(0xFFF2F2F7, 0xFF000000)
    val card = pick(0xFFFFFFFF, 0xFF1C1C1E)
    val bar = pick(0xFFF9F9F9, 0xFF121212)
    val text = pick(0xFF000000, 0xFFFFFFFF)
    val secondary = pick(0x993C3C43, 0x99EBEBF5)
    val tertiary = pick(0x4D3C3C43, 0x4DEBEBF5)
    val separator = pick(0xFFD8D8DC, 0xFF38383A)
    /** Behind grey buttons and fields. */
    val fill = pick(0xFFE9E9EE, 0xFF2C2C2E)
    val green = pick(0xFF0B6542, 0xFF34A86F)
    /** Behind white labels. */
    val greenFill = pick(0xFF0B6542, 0xFF1B7A4C)
    val orange = pick(0xFFB9461A, 0xFFF07A47)
    val gold = pick(0xFF8F6200, 0xFFE8B04A)
    val goldFill = 0xFF8F6200.toInt()
    val greenWash = pick(0xFFE7F2EC, 0xFF16271E)
    val orangeWash = pick(0xFFFCEDE6, 0xFF2E1C14)
    val goldWash = pick(0xFFFBF3E2, 0xFF2B2414)
    val red = pick(0xFFD70015, 0xFFFF453A)
    val pressed = pick(0x22000000, 0x33FFFFFF)

    companion object {
        fun of(context: Context): Colors =
            Colors(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }
}

/** Text sizes, in sp, after the iPhone's text styles. */
object TextSize {
    const val LARGE_TITLE = 30f
    const val TITLE = 26f
    const val TITLE2 = 22f
    const val TITLE3 = 20f
    const val HEADLINE = 17f
    const val BODY = 17f
    const val CALLOUT = 16f
    const val SUBHEADLINE = 15f
    const val FOOTNOTE = 13f
    const val CAPTION = 12f
}

fun Context.dp(value: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun Context.dpf(value: Number): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)

val Context.colors: Colors get() = Colors.of(this)

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** `content` with the touch ripple, clipped to the same rounded shape. */
fun Context.pressable(content: Drawable?, radius: Float, pressedColor: Int = colors.pressed): Drawable =
    RippleDrawable(ColorStateList.valueOf(pressedColor), content, rounded(Color.WHITE, radius))

fun Context.icon(id: Int, tint: Int): Drawable =
    getDrawable(id)!!.mutate().apply { setTint(tint) }

fun Context.label(
    text: CharSequence = "",
    size: Float = TextSize.BODY,
    color: Int = colors.text,
    bold: Boolean = false,
    medium: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    else if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setLineSpacing(0f, 1.08f)
}

fun TextView.selectable(): TextView = apply { setTextIsSelectable(true) }

fun TextView.oneLine(): TextView = apply {
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

/** A small leading icon before the text. */
fun TextView.leading(id: Int, tint: Int, sizeDp: Int = 16, padding: Int = 6): TextView = apply {
    val d = context.icon(id, tint)
    val size = context.dp(sizeDp)
    d.setBounds(0, 0, size, size)
    setCompoundDrawables(d, null, null, null)
    compoundDrawablePadding = context.dp(padding)
    gravity = Gravity.CENTER_VERTICAL
}

fun TextView.trailing(id: Int, tint: Int, sizeDp: Int = 14, padding: Int = 4): TextView = apply {
    val d = context.icon(id, tint)
    val size = context.dp(sizeDp)
    d.setBounds(0, 0, size, size)
    setCompoundDrawables(null, null, d, null)
    compoundDrawablePadding = context.dp(padding)
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.vertical(spacing: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (spacing > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(1, dp(spacing)); setColor(Color.TRANSPARENT) }
    }
}

fun Context.horizontal(spacing: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (spacing > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(spacing), 1); setColor(Color.TRANSPARENT) }
    }
}

fun Context.spacer(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

fun matchWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

fun <T : View> T.margins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): T = apply {
    val params = (layoutParams as? ViewGroup.MarginLayoutParams) ?: matchWidth()
    params.setMargins(left, top, right, bottom)
    layoutParams = params
}

fun <T : View> T.onTap(action: () -> Unit): T = apply { setOnClickListener { action() } }

/**
 * A rounded panel on the grouped background; `accent` draws a side bar in that colour. `compact`: less
 * room around what it holds (Hội thoại's transcript, to show more of it).
 */
fun Context.card(accent: Int? = null, spacing: Int = 12, compact: Boolean = false): LinearLayout = vertical(spacing).apply {
    val radius = dpf(if (compact) 14 else 20)
    val edge = if (compact) 10 else 16
    val base = rounded(colors.card, radius)
    if (accent == null) {
        background = base
        setPadding(dp(edge), dp(edge), dp(edge), dp(edge))
    } else {
        val bar = rounded(accent, dpf(2))
        background = LayerDrawable(arrayOf(base, bar)).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerWidth(1, dp(if (compact) 3 else 4))
            setLayerInset(1, dp(if (compact) 7 else 8), dp(edge), 0, dp(edge))
        }
        setPadding(dp(if (compact) 18 else 22), dp(edge), dp(if (compact) 12 else 16), dp(edge))
    }
}

enum class PillStyle { PLAIN, PRIMARY, TINTED, DANGER }

/** A capsule button: grey, green with white text, or tinted. */
fun Context.pill(
    text: String,
    iconId: Int? = null,
    style: PillStyle = PillStyle.PLAIN,
    trailingIcon: Boolean = false,
    tint: Int = colors.green,
    size: Float = TextSize.SUBHEADLINE,
    action: () -> Unit,
): TextView {
    val c = colors
    val (back, fore) = when (style) {
        PillStyle.PLAIN -> c.fill to c.text
        PillStyle.PRIMARY -> c.greenFill to Color.WHITE
        PillStyle.TINTED -> (tint and 0x00FFFFFF or 0x26000000) to tint
        PillStyle.DANGER -> c.fill to c.red
    }
    return label(text, size, fore, medium = true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val radius = dpf(100)
        background = pressable(rounded(back, radius), radius, if (style == PillStyle.PRIMARY) 0x33FFFFFF else c.pressed)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        minHeight = dp(36)
        if (iconId != null) {
            if (trailingIcon) trailing(iconId, fore) else leading(iconId, fore, 16, 6)
            gravity = Gravity.CENTER
        }
        setOnClickListener { action() }
    }
}

fun TextView.enabled(on: Boolean): TextView = apply {
    isEnabled = on
    alpha = if (on) 1f else 0.4f
}

/** A round icon button. */
fun Context.iconButton(id: Int, tint: Int = colors.green, sizeDp: Int = 44, background: Int? = null, description: String, action: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageDrawable(icon(id, tint))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val padding = dp(maxOf(8, (sizeDp - 24) / 2))
        setPadding(padding, padding, padding, padding)
        val radius = dpf(sizeDp)
        this.background = pressable(if (background != null) rounded(background, radius) else null, radius)
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { action() }
    }

fun Context.spinner(color: Int = colors.secondary, sizeDp: Int = 20): ProgressBar =
    ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** "Đang dịch…" with a spinner. */
fun Context.progress(text: String, size: Float = TextSize.SUBHEADLINE): LinearLayout = horizontal(8).apply {
    addView(spinner())
    addView(label(text, size, colors.secondary))
}

/** One choice of a few, like the iPhone's segmented control. */
class Segmented(context: Context, private val options: List<String>, selected: Int, private val onSelect: (Int) -> Unit) :
    LinearLayout(context) {
    private val c = context.colors
    private val items = ArrayList<TextView>()
    var selected = selected
        set(value) {
            field = value
            render()
        }

    init {
        orientation = HORIZONTAL
        val radius = context.dpf(9)
        background = rounded(c.fill, radius)
        setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
        options.forEachIndexed { index, option ->
            val item = context.label(option, TextSize.SUBHEADLINE, c.text, medium = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (this@Segmented.selected != index) {
                        this@Segmented.selected = index
                        onSelect(index)
                    }
                }
            }
            items.add(item)
            addView(item)
        }
        render()
    }

    private fun render() {
        val radius = context.dpf(7)
        items.forEachIndexed { index, item ->
            val on = index == selected
            item.background = if (on) rounded(if (c.dark) 0xFF636366.toInt() else Color.WHITE, radius) else null
            item.elevation = if (on) context.dpf(1) else 0f
        }
    }
}

/** A grouped list like the iPhone's Settings: a header, rows in one card, a footer. */
fun Context.section(header: String?, footer: String? = null, rows: List<View>): LinearLayout = vertical().apply {
    val c = colors
    if (header != null) {
        addView(label(header, TextSize.FOOTNOTE, c.secondary, medium = true).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
        })
    }
    val box = vertical().apply {
        background = rounded(c.card, dpf(12))
        clipToOutline = true
    }
    rows.forEachIndexed { index, row ->
        if (index > 0) box.addView(View(context).apply {
            setBackgroundColor(c.separator)
            layoutParams = matchWidth(maxOf(1, dp(0.5f))).apply { leftMargin = dp(16) }
        })
        box.addView(row, matchWidth())
    }
    addView(box, matchWidth())
    if (footer != null) {
        addView(label(footer, TextSize.FOOTNOTE, c.secondary).apply { setPadding(dp(16), dp(6), dp(16), 0) })
    }
    margins(bottom = dp(24))
}

/** A row that opens something: a title, its value, and a chevron. */
fun Context.row(title: String, value: String? = null, color: Int = colors.text, iconId: Int? = null, action: (() -> Unit)?): LinearLayout =
    horizontal(10).apply {
        val c = colors
        setPadding(dp(16), dp(12), dp(14), dp(12))
        minimumHeight = dp(48)
        val titleView = label(title, TextSize.BODY, color)
        if (iconId != null) titleView.leading(iconId, color, 20, 10)
        addView(titleView, weighted())
        if (value != null) addView(label(value, TextSize.BODY, c.secondary).apply {
            gravity = Gravity.END
            maxLines = 2
            tag = "value"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 0f
        })
        if (action != null) {
            background = pressable(null, 0f)
            setOnClickListener { action() }
        }
    }

/** A setting that is on or off. */
fun Context.switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout = horizontal(10).apply {
    setPadding(dp(16), dp(8), dp(12), dp(8))
    minimumHeight = dp(48)
    addView(label(title, TextSize.BODY), weighted())
    val toggle = Switch(context).apply {
        isChecked = checked
        val c = colors
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, if (c.dark) 0xFFB0B0B5.toInt() else Color.WHITE),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(c.green, if (c.dark) 0xFF48484A.toInt() else 0xFFC7C7CC.toInt()),
        )
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
    addView(toggle)
    setOnClickListener { toggle.toggle() }
}

/** A text field on a grey rounded background. */
fun Context.field(hint: String, text: String = "", lines: Int = 1, secret: Boolean = false): EditText = EditText(this).apply {
    val c = colors
    this.hint = hint
    setText(text)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, TextSize.BODY)
    setTextColor(c.text)
    setHintTextColor(c.tertiary)
    background = null
    setPadding(dp(16), dp(12), dp(16), dp(12))
    if (secret) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.MONOSPACE
        maxLines = 1
    } else if (lines > 1) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 1
        maxLines = lines
        gravity = Gravity.TOP or Gravity.START
    } else {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = 1
    }
}

/** The page title, with buttons at the end. */
fun Context.topBar(title: String, back: (() -> Unit)? = null, vararg actions: View): LinearLayout = horizontal(4).apply {
    val c = colors
    setPadding(dp(if (back != null) 4 else 16), 0, dp(6), 0)
    minimumHeight = dp(56)
    if (back != null) addView(iconButton(R.drawable.ic_back, c.green, description = "Quay lại") { back() })
    addView(label(title, if (back != null) TextSize.HEADLINE else TextSize.TITLE2, c.text, bold = true).oneLine(), weighted())
    for (action in actions) addView(action)
}

/** A list to choose one from, like the iPhone's pickers. */
fun Context.choose(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
            dialog.dismiss()
            if (which != selected) onPick(which)
        }
        .setNegativeButton("Hủy", null)
        .show()
}

fun Context.confirm(title: String, message: String?, button: String, action: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .apply { if (message != null) setMessage(message) }
        .setPositiveButton(button) { _, _ -> action() }
        .setNegativeButton("Hủy", null)
        .show()
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

/** Copies without the keyboard then offering to translate our own text. */
fun Context.copyText(text: String, label: String = "MSTrans") {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    MSTransApp.of(this).settings.handledClipboard = text.hashCode()
}

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** A grey box with a gold note. */
fun Context.noteBox(text: String): TextView = label(text, TextSize.SUBHEADLINE, colors.gold).apply {
    leading(R.drawable.ic_warning, colors.gold, 18, 8)
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
    background = rounded(colors.goldWash, dpf(12))
    setPadding(dp(12), dp(12), dp(12), dp(12))
}

/** Something empty, with what to do about it. */
fun Context.emptyState(iconId: Int, title: String, detail: String, button: View? = null): LinearLayout = vertical(10).apply {
    val c = colors
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(32), dp(48), dp(32), dp(32))
    addView(ImageView(context).apply {
        setImageDrawable(icon(iconId, c.secondary))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    })
    addView(label(title, TextSize.TITLE3, c.text, bold = true).apply { gravity = Gravity.CENTER })
    addView(label(detail, TextSize.SUBHEADLINE, c.secondary).apply { gravity = Gravity.CENTER })
    if (button != null) addView(button, wrap())
}

object Dates {
    private val vi = Locale.forLanguageTag("vi-VN")

    fun time(at: Long): String = SimpleDateFormat("HH:mm", vi).format(Date(at))

    fun full(at: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", vi).format(Date(at))

    /** "Vừa xong", "5 phút trước", "Hôm qua", or the date. */
    fun relative(at: Long, now: Long = System.currentTimeMillis()): String {
        val seconds = (now - at) / 1000
        if (seconds < 60) return "Vừa xong"
        if (seconds < 3600) return "${seconds / 60} phút trước"
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (at >= today) return "${seconds / 3600} giờ trước"
        if (at >= today - 86_400_000) return "Hôm qua"
        if (at >= today - 6 * 86_400_000) return "${(today - at) / 86_400_000 + 1} ngày trước"
        return SimpleDateFormat("dd/MM/yyyy", vi).format(Date(at))
    }
}

/** A view that fills its parent, for a FrameLayout. */
fun fill() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
