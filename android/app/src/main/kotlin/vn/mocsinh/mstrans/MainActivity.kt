package vn.mocsinh.mstrans

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import vn.mocsinh.mstrans.ui.ConversationScreen
import vn.mocsinh.mstrans.ui.GlossaryScreen
import vn.mocsinh.mstrans.ui.HistoryScreen
import vn.mocsinh.mstrans.ui.SettingsScreen
import vn.mocsinh.mstrans.ui.TextSize
import vn.mocsinh.mstrans.ui.TranslateScreen
import vn.mocsinh.mstrans.ui.colors
import vn.mocsinh.mstrans.ui.dp
import vn.mocsinh.mstrans.ui.fill
import vn.mocsinh.mstrans.ui.icon
import vn.mocsinh.mstrans.ui.label
import vn.mocsinh.mstrans.ui.matchWidth
import vn.mocsinh.mstrans.ui.weighted

/** One tab of the app. */
interface Screen {
    val view: View
    /** The tab came into view, or the app came back to the front. */
    fun shown() {}
    fun hidden() {}
    fun destroyed() {}
}

/** The app: five tabs, as on the iPhone, and pages pushed over them. */
class MainActivity : Activity() {
    enum class Tab(val title: String, val iconId: Int) {
        TRANSLATE("Dịch", R.drawable.ic_tab_translate),
        CONVERSATION("Hội thoại", R.drawable.ic_tab_conversation),
        HISTORY("Lịch sử", R.drawable.ic_tab_history),
        GLOSSARY("Thuật ngữ", R.drawable.ic_tab_glossary),
        SETTINGS("Cài đặt", R.drawable.ic_tab_settings),
    }

    val app: MSTransApp get() = MSTransApp.of(this)

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private val screens = HashMap<Tab, Screen>()
    private val pages = ArrayList<View>()
    private var tab = Tab.TRANSLATE
    private var micRequest: CompletableDeferred<Boolean>? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = Tab.entries.firstOrNull { it.name == savedInstanceState?.getString("tab") } ?: tabFor(intent) ?: Tab.TRANSLATE
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFor(intent)?.let { select(it) }
    }

    private fun tabFor(intent: Intent?): Tab? = intent?.getStringExtra(EXTRA_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }

    /** Light or dark changed: everything is drawn again in the new colours. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (root.tag as? Boolean == colors.dark) return
        screens.values.forEach { it.destroyed() }
        screens.clear()
        pages.clear()
        build()
    }

    private fun build() {
        val c = colors
        window.statusBarColor = c.page
        root = FrameLayout(this).apply {
            setBackgroundColor(c.page)
            tag = c.dark
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(View(this).apply { setBackgroundColor(c.separator) }, matchWidth(maxOf(1, dp(0.5f))))
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c.bar)
            setPadding(0, dp(6), 0, dp(4))
        }
        for (t in Tab.entries) tabBar.addView(tabButton(t), weighted())
        column.addView(tabBar, matchWidth())
        root.addView(column, fill())
        setContentView(root)
        select(tab, force = true)
    }

    private fun tabButton(t: Tab): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        tag = t
        addView(ImageView(context).apply {
            setImageDrawable(icon(t.iconId, colors.secondary))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        addView(label(t.title, TextSize.CAPTION - 1, colors.secondary, medium = true).apply { gravity = Gravity.CENTER })
        contentDescription = t.title
        setOnClickListener { select(t) }
    }

    fun select(t: Tab, force: Boolean = false) {
        if (t == tab && !force && content.childCount > 0) {
            while (pages.isNotEmpty()) pop()
            return
        }
        while (pages.isNotEmpty()) pop()
        screens[tab]?.let { if (content.childCount > 0) it.hidden() }
        tab = t
        val screen = screens.getOrPut(t) { make(t) }
        content.removeAllViews()
        (screen.view.parent as? ViewGroup)?.removeView(screen.view)
        content.addView(screen.view, fill())
        val c = colors
        for (i in 0 until tabBar.childCount) {
            val button = tabBar.getChildAt(i) as LinearLayout
            val on = button.tag == t
            val color = if (on) c.green else c.secondary
            (button.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (button.getChildAt(1) as TextView).setTextColor(color)
        }
        if (resumed) screen.shown()
    }

    private fun make(t: Tab): Screen = when (t) {
        Tab.TRANSLATE -> TranslateScreen(this)
        Tab.CONVERSATION -> ConversationScreen(this)
        Tab.HISTORY -> HistoryScreen(this)
        Tab.GLOSSARY -> GlossaryScreen(this)
        Tab.SETTINGS -> SettingsScreen(this)
    }

    /** A page over the tabs (a detail, the saved meetings…); Back takes it away. */
    fun push(page: View) {
        hideKeyboard()
        page.setBackgroundColor(colors.page)
        page.isClickable = true
        pages.add(page)
        root.addView(page, fill())
    }

    fun pop() {
        if (pages.isEmpty()) return
        hideKeyboard()
        val page = pages.removeAt(pages.size - 1)
        root.removeView(page)
        (page.tag as? Runnable)?.run()
        if (pages.isEmpty()) screens[tab]?.shown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pages.isNotEmpty()) pop() else if (tab != Tab.TRANSLATE) select(Tab.TRANSLATE) else super.onBackPressed()
    }

    fun hideKeyboard() {
        val focused = currentFocus ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (pages.isEmpty()) screens[tab]?.shown()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        screens[tab]?.hidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        screens.values.forEach { it.destroyed() }
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Asks for the microphone; true when allowed. */
    suspend fun requestMicrophone(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        val request = CompletableDeferred<Boolean>()
        micRequest?.complete(false)
        micRequest = request
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        return request.await()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        micRequest?.complete(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        micRequest = null
    }

    companion object {
        const val EXTRA_TAB = "tab"
        private const val MIC_REQUEST = 7
    }
}
