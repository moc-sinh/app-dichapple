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
import vn.mocsinh.mstrans.MainActivity
import vn.mocsinh.mstrans.R
import vn.mocsinh.mstrans.Screen
import vn.mocsinh.mstrans.core.Glossary

/** Product names, trade terms and names that must always be translated the same way. */
class GlossaryScreen(private val activity: MainActivity) : Screen {
    private val settings = activity.settings
    private val c = activity.colors
    private val terms = ArrayList<Glossary.Entry>()
    private var shown: List<Glossary.Entry> = emptyList()
    private var query = ""

    private val list = ListView(activity).apply {
        divider = ColorDrawable(c.separator)
        dividerHeight = maxOf(1, activity.dp(0.5f))
        setBackgroundColor(c.card)
    }
    private val empty = FrameLayout(activity)
    private val footer = activity.label("", TextSize.FOOTNOTE, c.secondary).apply {
        setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(16))
        setBackgroundColor(c.page)
    }
    private val search = activity.searchBox("Tìm thuật ngữ") { text ->
        query = text
        apply()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = termRow(shown[position])
    }

    override val view: View = activity.vertical().apply {
        addView(activity.topBar("Thuật ngữ", null, activity.iconButton(R.drawable.ic_add, c.green, description = "Thêm thuật ngữ") { edit(null) }), matchWidth())
        addView(search.margins(activity.dp(16), 0, activity.dp(16), activity.dp(8)))
        addView(FrameLayout(activity).apply {
            addView(list, fill())
            addView(empty, fill())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    init {
        list.addFooterView(footer, null, false)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { edit(it) } }
        list.setOnItemLongClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { term ->
                activity.confirm("Xóa “${term.left}”?", null, "Xóa") {
                    terms.remove(term)
                    save()
                }
            }
            true
        }
    }

    override fun shown() {
        terms.clear()
        terms.addAll(Glossary.parse(settings.glossary))
        apply()
    }

    private fun apply() {
        val q = query.trim()
        shown = if (q.isEmpty()) terms.toList() else terms.filter { it.left.contains(q, true) || it.right.contains(q, true) }
        footer.text = "${terms.size} thuật ngữ, dùng cho cả tin bạn gửi và tin đối tác."
        search.visibility = if (terms.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        empty.removeAllViews()
        val nothing = shown.isEmpty()
        empty.visibility = if (nothing) View.VISIBLE else View.GONE
        list.visibility = if (nothing) View.GONE else View.VISIBLE
        if (!nothing) return
        val state = if (terms.isEmpty()) {
            activity.emptyState(
                R.drawable.ic_tab_glossary, "Chưa có thuật ngữ",
                "Thêm tên sản phẩm, thuật ngữ ngành, tên riêng… Mọi bản dịch, cả trên bàn phím, sẽ luôn dùng đúng cách dịch bạn chọn.",
                activity.pill("Thêm thuật ngữ", R.drawable.ic_add, PillStyle.PRIMARY) { edit(null) },
            )
        } else {
            activity.emptyState(R.drawable.ic_search, "Không tìm thấy", "Không có thuật ngữ nào chứa “$q”.")
        }
        empty.addView(state.apply { setBackgroundColor(c.page) }, fill())
    }

    private fun termRow(term: Glossary.Entry): View = activity.horizontal(10).apply {
        setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
        background = activity.pressable(ColorDrawable(c.card), 0f)
        addView(activity.label(term.left, TextSize.BODY, c.text, medium = true))
        addView(android.widget.ImageView(activity).apply {
            setImageDrawable(activity.icon(R.drawable.ic_arrow_right, c.green))
            layoutParams = LinearLayout.LayoutParams(activity.dp(16), activity.dp(16))
        })
        addView(activity.label(term.right.ifEmpty { "Giữ nguyên" }, TextSize.BODY, if (term.right.isEmpty()) c.secondary else c.text), weighted())
        contentDescription = if (term.right.isEmpty()) "${term.left}, giữ nguyên" else "${term.left} dịch là ${term.right}"
    }

    /** Adds a term (`term` null) or changes one. */
    private fun edit(term: Glossary.Entry?) {
        val source = activity.field("Ví dụ: 主轴, spindle, CNC", term?.left ?: "").apply { background = rounded(c.fill, activity.dpf(10)) }
        val target = activity.field("Ví dụ: trục chính", term?.right ?: "").apply { background = rounded(c.fill, activity.dpf(10)) }
        val problem = activity.label("Từ gốc không được chứa dấu “=”.", TextSize.FOOTNOTE, c.red).apply { visibility = View.GONE }
        val form = activity.vertical(6).apply {
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(activity.label("Từ hoặc cụm từ", TextSize.FOOTNOTE, c.secondary, medium = true))
            addView(source, matchWidth())
            addView(problem)
            addView(activity.label("Luôn dịch thành", TextSize.FOOTNOTE, c.secondary, medium = true).margins(top = activity.dp(10)))
            addView(target, matchWidth())
            addView(activity.label("Để trống nếu muốn giữ nguyên, ví dụ tên thương hiệu.", TextSize.FOOTNOTE, c.secondary))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (term == null) "Thêm thuật ngữ" else "Sửa thuật ngữ")
            .setView(form)
            .setPositiveButton("Lưu") { _, _ ->
                val saved = Glossary.Entry(source.text.toString().trim(), target.text.toString().trim())
                val index = if (term == null) -1 else terms.indexOf(term)
                if (index >= 0) terms[index] = saved else terms.add(0, saved)
                save()
            }
            .setNegativeButton("Hủy", null)
            .apply {
                if (term != null) setNeutralButton("Xóa") { _, _ ->
                    terms.remove(term)
                    save()
                }
            }
            .create()
        // "=" separates the term from its translation in the saved list.
        fun check() {
            val text = source.text.toString()
            problem.visibility = if (text.contains("=")) View.VISIBLE else View.GONE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = text.isNotBlank() && !text.contains("=")
        }
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = check()
        })
        dialog.setOnShowListener {
            check()
            if (term == null) source.requestFocus()
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun save() {
        settings.glossary = Glossary.format(terms)
        // A partner message copied again is translated again, with the new terms.
        settings.clearIncomingCache()
        apply()
    }
}
