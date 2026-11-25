package vn.mocsinh.testshop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Screens for the device tries, chosen with `--es screen <name>`: home (list, countdown),
 * detail, settings (every required term), image (Chinese only inside a picture), search (a
 * field, for the keyboard), password, secure (FLAG_SECURE). Every tap is logged (tag TestShop).
 */
class ShopActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var taps = 0
    private lateinit var tapCount: TextView
    private var remaining = 7_198
    private var countdown: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "home"
        if (screen == "secure") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        taps = savedInstanceState?.getInt("taps") ?: 0
        val column = column()
        tapCount = column.line("点击次数：$taps", 14f)
        when (screen) {
            "detail" -> column.detail()
            "settings" -> column.settings()
            "image" -> column.image()
            "search" -> column.search()
            "password" -> column.password()
            "secure" -> column.secure()
            else -> column.home()
        }
        setContentView(ScrollView(this).apply { addView(column) })
        Log.d(TAG, "screen $screen")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("taps", taps)
    }

    override fun onDestroy() {
        countdown?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun LinearLayout.home() {
        line("测试商城", 22f)
        val timer = line("距结束 01:59:58", 14f, Color.rgb(200, 30, 30))
        val tick = object : Runnable {
            override fun run() {
                remaining--
                timer.text = "距结束 %02d:%02d:%02d".format(remaining / 3600, remaining / 60 % 60, remaining % 60)
                main.postDelayed(this, 1_000)
            }
        }
        countdown = tick
        main.postDelayed(tick, 1_000)
        horizontal {
            for (screen in listOf("搜索" to "search", "设置" to "settings", "图片商品" to "image")) {
                action(screen.first) { open(screen.second) }
            }
        }
        for (i in 0 until 30) {
            val product = PRODUCTS[i % PRODUCTS.size]
            line("${i + 1}. ${product[0]}", 16f).setOnClickListener { tap("title ${i + 1}"); open("detail") }
            horizontal {
                line(product[1], 18f, Color.rgb(200, 30, 30))
                line("  " + product[2], 14f)
            }
            line(product[3], 13f, Color.GRAY)
            horizontal {
                action(if (i == 2) "加入購物車" else "加入购物车") { tap("cart ${i + 1}") }
                action("立即购买") { tap("buy ${i + 1}") }
            }
        }
        horizontal {
            line("购物车", 16f)
            line("    我的淘宝", 16f)
        }
    }

    private fun LinearLayout.detail() {
        line("商品详情", 22f)
        line("秋季新款纯棉短袖T恤 男士宽松休闲上衣", 17f)
        line("到手价¥1,299 限时-20%", 18f, Color.rgb(200, 30, 30))
        line("满199减20", 15f)
        line("规格：XL / 红色", 15f)
        line("型号：A12-B", 15f)
        line("尺寸50*30cm 重量2.5kg", 15f)
        line("库存128件 2件起批", 15f)
        line("不含税 运费 ¥12", 15f)
        line("運費險 七天无理由退货", 15f)
        line("48小时内发货", 15f)
        line("1.2万+人付款 月销500+ 已售3000+", 15f)
        line("这款衣服面料柔软透气，适合日常穿着，颜色多样可选。", 15f)
        line("本店支持一件代发，欢迎批发咨询。", 15f)
        line("账号注销后将无法恢复，请谨慎操作。", 15f)
        horizontal {
            action("加入购物车") { tap("detail cart") }
            action("立即购买") { tap("detail buy") }
        }
    }

    private fun LinearLayout.settings() {
        line("我的淘宝", 22f)
        for (term in SETTINGS) line(term, 16f).apply {
            minimumHeight = dp(40)
            setOnClickListener { tap("row $term") }
        }
    }

    private fun LinearLayout.image() {
        line("图片商品", 22f)
        val bitmap = Bitmap.createBitmap(1_000, 760, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(180, 40, 20))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 92f; isFakeBoldText = true }
        canvas.drawText("新品上市", 80f, 150f, paint)
        canvas.drawText("限时特价 ¥59.9", 80f, 310f, paint)
        canvas.drawText("全场包邮", 80f, 470f, paint)
        paint.color = Color.rgb(250, 235, 200)
        canvas.drawRect(0f, 560f, 1_000f, 760f, paint)
        paint.color = Color.BLACK
        paint.textSize = 76f
        canvas.drawText("买二送一 现货", 80f, 690f, paint)
        addView(ImageView(context).apply { setImageBitmap(bitmap); adjustViewBounds = true }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        line("图片下方的说明文字", 15f)
    }

    private fun LinearLayout.search() {
        line("搜索宝贝", 22f)
        addView(EditText(context).apply { hint = "请输入商品名称" })
        line("历史搜索", 16f)
        for (word in listOf("纯棉短袖", "保温水杯", "办公椅", "儿童书包")) line(word, 15f)
        line("热门推荐", 16f)
    }

    private fun LinearLayout.password() {
        line("请输入支付密码", 20f)
        addView(EditText(context).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD })
        line("忘记密码", 15f)
    }

    private fun LinearLayout.secure() {
        line("受保护的页面", 22f)
        line("订单信息", 16f)
        line("商品名称：保温水杯 500ml", 16f)
        line("实付款 ¥39.00", 16f)
    }

    // Views

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
    }

    private fun LinearLayout.line(text: String, size: Float, color: Int = Color.BLACK): TextView {
        val view = TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(6), 0, dp(2))
        }
        addView(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return view
    }

    private fun LinearLayout.horizontal(build: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; build() })
    }

    private fun LinearLayout.action(label: String, action: () -> Unit) {
        addView(Button(context).apply { text = label; setOnClickListener { action() } })
    }

    private fun open(screen: String) {
        startActivity(Intent(this, ShopActivity::class.java).putExtra("screen", screen))
    }

    private fun tap(what: String) {
        taps++
        tapCount.text = "点击次数：$taps"
        Log.d(TAG, "tap $what ($taps)")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TestShop"

        private val PRODUCTS = listOf(
            listOf("秋季新款纯棉短袖T恤 男士宽松", "¥89.90", "-20%", "已售3000+  包邮"),
            listOf("不锈钢保温水杯 大容量便携", "¥39.00", "满199减20", "月销500+  现货"),
            listOf("办公室人体工学椅子 可升降", "¥459", "券后¥429", "1.2万+人付款"),
            listOf("儿童书包 轻便护脊减负", "¥128", "预售", "48小时内发货"),
            listOf("厨房收纳置物架 多层加厚", "¥56.8", "運費 ¥8", "共3件  7天内发货"),
            listOf("蓝牙无线耳机 超长续航", "¥199", "-35%", "2件起批  一件代发"),
            listOf("夏季凉席 三件套 冰丝", "¥75", "混批", "1,299条评价"),
        )

        private val SETTINGS = listOf(
            "设置", "账号与安全", "修改密码", "收货地址", "消息通知", "清除缓存", "帮助与反馈", "切换账户", "退出当前账户", "退出登录",
            "注销账号", "登录", "我的订单", "待付款", "待发货", "待收货", "购物车", "加入购物车", "立即购买", "起批量", "一件代发", "混批",
            "现货", "预售", "包邮", "不含税", "含税", "规格", "库存", "型号", "物流", "运费", "退款", "售后",
        )
    }
}
