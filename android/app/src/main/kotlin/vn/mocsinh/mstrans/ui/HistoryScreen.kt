package vn.mocsinh.mstrans.ui

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.Screen
import vn.mocsinh.mstrans.core.ChatLine
import vn.mocsinh.mstrans.core.HistoryItem

/** Everything translated with the keyboard or in the app, newest first. */
class HistoryScreen(private val activity: MainActivity) : Screen {
    private enum class Filter(val title: String) { ALL("Tất cả"), SENT("Đã gửi"), RECEIVED("Đã nhận"), STARRED("Đánh dấu") }

    private val settings = activity.settings
    private val c = activity.colors
    private var items: List<HistoryItem> = emptyList()
    private var shown: List<HistoryItem> = emptyList()
    private var filter = Filter.ALL
    private var query = ""

    private val more: View = activity.iconButton(R.drawable.ic_more, c.green, description = "Thêm") { openMenu(more) }
    private val list = ListView(activity).apply {
        divider = ColorDrawable(c.separator)
        dividerHeight = maxOf(1, activity.dp(0.5f))
        setBackgroundColor(c.card)
        isVerticalScrollBarEnabled = true
    }
    private val empty = FrameLayout(activity)
    private val footer = activity.label("Đang tắt lưu lịch sử: tin mới không được lưu. Bật lại trong Cài đặt.", TextSize.FOOTNOTE, c.secondary).apply {
        setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(16))
        setBackgroundColor(c.page)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            activity.historyRow(shown[position]).apply { background = activity.pressable(ColorDrawable(c.card), 0f) }
    }

    override val view: View = activity.vertical().apply {
        addView(activity.topBar("Lịch sử", null, more), matchWidth())
        addView(Segmented(activity, Filter.entries.map { it.title }, 0) { index ->
            filter = Filter.entries[index]
            apply()
        }.margins(activity.dp(16), 0, activity.dp(16), activity.dp(8)))
        addView(activity.searchBox("Tìm trong lịch sử") { text ->
            query = text
            apply()
        }.margins(activity.dp(16), 0, activity.dp(16), activity.dp(8)))
        addView(FrameLayout(activity).apply {
            addView(list, fill())
            addView(empty, fill())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    init {
        list.addFooterView(footer, null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { open(it) } }
        list.setOnItemLongClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { actions(it) }
            true
        }
    }

    override fun shown() = reload()

    private fun reload() {
        items = settings.history()
        apply()
    }

    private fun apply() {
        val q = query.trim()
        shown = items.filter { item ->
            val kept = when (filter) {
                Filter.ALL -> true
                Filter.SENT -> item.side == ChatLine.Side.ME
                Filter.RECEIVED -> item.side == ChatLine.Side.PARTNER
                Filter.STARRED -> item.starred
            }
            kept && (q.isEmpty() || item.source.contains(q, ignoreCase = true) || item.translation.contains(q, ignoreCase = true))
        }
        footer.visibility = if (settings.keepHistory) View.GONE else View.VISIBLE
        more.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
        adapter.notifyDataSetChanged()
        empty.removeAllViews()
        if (shown.isEmpty()) {
            empty.visibility = View.VISIBLE
            list.visibility = View.GONE
            empty.addView(emptyState(), fill())
        } else {
            empty.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
    }

    private fun emptyState(): View = when {
        query.isNotBlank() -> activity.emptyState(R.drawable.ic_search, "Không tìm thấy", "Không có tin nào chứa “${query.trim()}”.")
        items.isEmpty() -> activity.emptyState(
            R.drawable.ic_tab_history, "Chưa có bản dịch",
            if (settings.keepHistory) "Tin bạn dịch bằng bàn phím hoặc trong app sẽ hiện ở đây."
            else "Lưu lịch sử đang tắt. Bật trong Cài đặt để giữ các bản dịch.",
        )
        filter == Filter.STARRED -> activity.emptyState(
            R.drawable.ic_star_border, "Chưa đánh dấu tin nào",
            "Giữ ngón tay trên một tin rồi chọn Đánh dấu, để giữ lại các câu hay dùng.",
        )
        else -> activity.emptyState(R.drawable.ic_tab_history, "Không có tin nào", "Thử bộ lọc khác.")
    }.apply { setBackgroundColor(c.page) }

    private fun open(item: HistoryItem) {
        activity.openHistoryDetail(item, onChange = { replace(it) }, onDelete = { delete(item) })
    }

    private fun actions(item: HistoryItem) {
        val options = listOf("Chép bản dịch", "Chép tin gốc", if (item.starred) "Bỏ đánh dấu" else "Đánh dấu", "Xóa")
        AlertDialog.Builder(activity)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { activity.copyText(item.translation); activity.toast("Đã chép bản dịch") }
                    1 -> { activity.copyText(item.source); activity.toast("Đã chép tin gốc") }
                    2 -> replace(item.copy(starred = !item.starred))
                    3 -> delete(item)
                }
            }
            .show()
    }

    private fun replace(item: HistoryItem) {
        items = items.map { if (it.id == item.id) item else it }
        settings.updateHistory(item)
        apply()
    }

    private fun delete(item: HistoryItem) {
        items = items.filter { it.id != item.id }
        settings.removeHistory(item.id)
        apply()
    }

    private fun openMenu(anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add("Xóa lịch sử")
            setOnMenuItemClickListener {
                activity.confirm("Xóa lịch sử?", "Không hoàn tác được.", "Xóa, giữ tin đã đánh dấu") {
                    items = items.filter { it.starred }
                    settings.saveHistory(items)
                    apply()
                }
                true
            }
            show()
        }
    }
}

/** A search box on grey, as in the History and Glossary tabs. */
fun MainActivity.searchBox(hint: String, onChange: (String) -> Unit): android.widget.EditText = field(hint).apply {
    val c = colors
    background = rounded(c.fill, dpf(12))
    setPadding(dp(12), dp(8), dp(12), dp(8))
    val d = icon(R.drawable.ic_search, c.secondary)
    val size = dp(18)
    d.setBounds(0, 0, size, size)
    setCompoundDrawables(d, null, null, null)
    compoundDrawablePadding = dp(8)
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
    })
}
