package vn.mocsinh.mstrans.ui

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.MSTransApp
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.core.ChatLine
import vn.mocsinh.mstrans.core.HistoryItem

/** A page pushed over the tabs: a title bar with Back, and a scrolling body. */
fun MainActivity.page(title: String, vararg actions: View, onClose: (() -> Unit)? = null, build: LinearLayout.() -> Unit): View {
    val column = vertical()
    column.addView(topBar(title, { pop() }, *actions), matchWidth())
    val body = vertical(16).apply {
        setPadding(dp(16), dp(8), dp(16), dp(24))
        build()
    }
    column.addView(ScrollView(this).apply {
        isFillViewport = true
        addView(body)
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    if (onClose != null) column.tag = Runnable { onClose() }
    return column
}

/** The mark and the name, at the top of the Dịch tab. */
fun MainActivity.brandTitle(): LinearLayout = horizontal(8).apply {
    gravity = Gravity.CENTER
    minimumHeight = dp(52)
    addView(ImageView(context).apply {
        setImageDrawable(getDrawable(R.drawable.logo))
        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
    })
    addView(label("MSTrans", TextSize.HEADLINE, colors.text, bold = true))
    contentDescription = "MSTrans"
}

/** The two accents of the logo: green for you, orange for the partner. */
fun ChatLine.Side.color(c: Colors): Int = if (this == ChatLine.Side.ME) c.green else c.orange
val ChatLine.Side.title: String get() = if (this == ChatLine.Side.ME) "Bạn gửi" else "Đối tác"

/** Who a message is from, and its language: "Đối tác · Tiếng Trung". */
fun MainActivity.sideTag(side: ChatLine.Side, language: String): TextView {
    val color = side.color(colors)
    val text = if (language.isEmpty()) side.title else "${side.title} · $language"
    return label(text, TextSize.FOOTNOTE, color, bold = true)
        .leading(if (side == ChatLine.Side.ME) R.drawable.ic_arrow_right else R.drawable.ic_back, color, 14, 4)
}

/** Vietnamese first (what the user reads), the other language under it. */
fun MainActivity.historyRow(item: HistoryItem): LinearLayout = horizontal(12).apply {
    val c = colors
    gravity = Gravity.TOP
    setPadding(dp(16), dp(10), dp(16), dp(10))
    addView(View(context).apply {
        background = rounded(item.side.color(c), dpf(2))
    }, LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT))
    addView(vertical(4).apply {
        addView(horizontal(6).apply {
            addView(sideTag(item.side, item.languageName), weighted())
            if (item.starred) addView(ImageView(context).apply {
                setImageDrawable(icon(R.drawable.ic_star, c.gold))
                contentDescription = "Đã đánh dấu"
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            })
            addView(label(Dates.relative(item.date), TextSize.CAPTION, c.secondary))
        }, matchWidth())
        addView(label(item.vietnamese, TextSize.BODY, c.text).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(label(item.foreign, TextSize.SUBHEADLINE, c.secondary).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }, weighted())
}

/** One saved translation: both texts to copy, its meaning check, star and delete. */
fun MainActivity.openHistoryDetail(item: HistoryItem, onChange: (HistoryItem) -> Unit, onDelete: () -> Unit) {
    var current = item
    val c = colors
    lateinit var star: ImageView
    star = iconButton(if (item.starred) R.drawable.ic_star else R.drawable.ic_star_border, c.gold,
        description = if (item.starred) "Bỏ đánh dấu" else "Đánh dấu") {
        current = current.copy(starred = !current.starred)
        onChange(current)
        star.setImageDrawable(icon(if (current.starred) R.drawable.ic_star else R.drawable.ic_star_border, c.gold))
        star.contentDescription = if (current.starred) "Bỏ đánh dấu" else "Đánh dấu"
    }
    fun block(header: String, text: String, copyable: Boolean): View = section(header, null, buildList {
        add(label(text, TextSize.BODY).selectable().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        if (copyable) add(row("Chép", null, c.green, R.drawable.ic_copy) {
            copyText(text)
            toast("Đã chép")
        })
    })
    val language = item.languageName
    push(page(Dates.full(item.date), star) {
        addView(block(
            if (item.side == ChatLine.Side.ME) "Bạn viết" else "Đối tác viết" + (if (language.isEmpty()) "" else " · $language"),
            item.source, true,
        ))
        addView(block(
            if (item.side == ChatLine.Side.ME) "Đã dịch sang ${language.ifEmpty { "ngôn ngữ đối tác" }}" else "Bản dịch tiếng Việt",
            item.translation, true,
        ))
        item.meaning?.let { addView(block("Đối tác sẽ hiểu là", it, false)) }
        addView(section(null, null, listOf(row("Xóa khỏi lịch sử", null, c.red, R.drawable.ic_delete) {
            onDelete()
            pop()
        })))
    })
}

val MainActivity.settings get() = MSTransApp.of(this).settings
