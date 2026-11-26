package vn.mocsinh.testshop

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
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
 * field, for the keyboard), password, secure (FLAG_SECURE), taobao (laid out like a real Taobao
 * product page: tabs, text in a picture, a grid, chips, small labels, buttons whose colour runs).
 * Every tap is logged (tag TestShop).
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
            "taobao" -> column.taobao()
            else -> column.home()
        }
        val scroll = ScrollView(this).apply { addView(column) }
        if (screen == "taobao") {
            setContentView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    addView(buyBar())
                },
            )
        } else setContentView(scroll)
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

    // A page laid out like Taobao's

    private fun LinearLayout.taobao() {
        setBackgroundColor(Color.WHITE)
        horizontal {
            for ((i, tab) in listOf("宝贝", "评价", "详情", "推荐").withIndex()) {
                addView(
                    TextView(context).apply {
                        text = tab
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setTextColor(if (i == 2) Color.BLACK else Color.GRAY)
                        setPadding(0, dp(8), 0, dp(8))
                        setOnClickListener { tap("tab $tab") }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }
        horizontal {
            background = rounded(Color.WHITE, 20, ORANGE)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            addView(plain("anker充电头", 15f, Color.DKGRAY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(plain("搜索", 14f, ORANGE).apply {
                background = rounded(Color.rgb(255, 236, 224), 14)
                setPadding(dp(12), dp(4), dp(12), dp(4))
            })
        }
        space(8)
        picture(1_000, 520, Color.rgb(96, 18, 26), Color.rgb(150, 44, 50)) { canvas, paint ->
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            paint.textSize = 70f
            canvas.drawText("65W更配苹果! AI屏显充", 500f, 120f, paint)
            paint.isFakeBoldText = false
            paint.textSize = 36f
            canvas.drawText("表情互动 | 识别机型 | 控温系统", 500f, 190f, paint)
            paint.textSize = 30f
            canvas.drawText("低温快充中", 500f, 400f, paint)
        }
        horizontal {
            gravity = Gravity.BOTTOM
            addView(plain("¥198", 26f, ORANGE, bold = true))
            addView(plain("  约 $ 29.96", 13f, Color.GRAY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(plain("已售 16", 13f, Color.GRAY))
        }
        addView(plain("机乐堂智能数显65W氮化镓充电器typeC口AI智能充电头AVS协议适用iPhone18ProMax手机苹果华为小米", 17f, Color.BLACK, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(6))
        })
        horizontal {
            for (chip in listOf("店铺年销售超1万件 >", "近7天400+人逛过 >", "超200人加购 >")) {
                addView(
                    plain(chip, 12f, ORANGE).apply {
                        background = rounded(Color.WHITE, 4, ORANGE)
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                        maxLines = 1
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) },
                )
            }
        }
        space(10)
        horizontal {
            background = rounded(GREEN, 8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            addView(plain("淘宝官方物流", 17f, Color.WHITE, bold = true))
            addView(plain("  官方跨境服务·丢损必赔", 12f, Color.WHITE))
        }
        horizontal {
            setPadding(dp(12), dp(6), 0, 0)
            addView(plain("官方直邮-陆运  ", 14f, Color.BLACK))
            addView(plain("已享包邮", 14f, GREEN, bold = true))
        }
        addView(plain("预计发货后12-15日送达", 14f, Color.BLACK).apply { setPadding(dp(12), dp(2), 0, dp(8)) })
        for (row in GRID.chunked(2)) horizontal {
            for (card in row) {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        picture(480, 300, Color.rgb(232, 236, 240), Color.rgb(205, 212, 222)) { canvas, paint ->
                            paint.color = Color.rgb(30, 60, 110)
                            paint.isFakeBoldText = true
                            paint.textSize = 44f
                            canvas.drawText(card[0], 40f, 90f, paint)
                            paint.isFakeBoldText = false
                            paint.color = Color.DKGRAY
                            paint.textSize = 28f
                            canvas.drawText(card[1], 40f, 140f, paint)
                        }
                        addView(plain(card[2], 14f, Color.BLACK).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        horizontal {
                            addView(plain(card[3], 12f, ORANGE))
                            addView(plain("  满¥99包邮", 12f, GREEN))
                        }
                        horizontal {
                            gravity = Gravity.BOTTOM
                            addView(plain(card[4], 18f, ORANGE, bold = true))
                            addView(plain("  " + card[5], 11f, Color.GRAY))
                        }
                        setOnClickListener { tap("card ${card[4]}") }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8); bottomMargin = dp(12) },
                )
            }
        }
    }

    /** The bar that stays at the bottom: three small labels under icons, two buttons in a run of colour. */
    private fun buyBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        for (label in listOf("店铺", "客服", "收藏")) {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(View(context).apply { background = rounded(Color.WHITE, 6, Color.DKGRAY) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    addView(plain(label, 10f, Color.GRAY))
                    setOnClickListener { tap("bar $label") }
                },
                LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        for ((label, colours) in listOf("加入购物车" to intArrayOf(Color.rgb(255, 200, 0), Color.rgb(255, 144, 0)), "立即购买" to intArrayOf(Color.rgb(255, 119, 0), Color.rgb(255, 73, 0)))) {
            addView(
                plain(label, 16f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colours).apply {
                        val round = dp(22).toFloat()
                        cornerRadii = if (label == "立即购买") floatArrayOf(0f, 0f, round, round, round, round, 0f, 0f)
                        else floatArrayOf(round, round, 0f, 0f, 0f, 0f, round, round)
                    }
                    setOnClickListener { tap("bar $label") }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
        }
    }

    private fun plain(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun LinearLayout.space(height: Int) {
        addView(View(context), ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    /** A picture whose colour runs from top to bottom, with what `draw` writes in it. */
    private fun LinearLayout.picture(width: Int, height: Int, top: Int, bottom: Int, draw: (Canvas, Paint) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        draw(canvas, paint)
        addView(ImageView(context).apply { setImageBitmap(bitmap); adjustViewBounds = true }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        private val ORANGE = Color.rgb(255, 80, 0)
        private val GREEN = Color.rgb(27, 167, 132)

        /** Text in the picture (two lines), title, promotion, price, buyers. */
        private val GRID = listOf(
            listOf("66W极速闪充", "30分钟充86%", "【66W超级快充】小巧便携充电宝适用苹果华为", "超级立减50%", "¥158", "1000+人付款"),
            listOf("PD 140W 充电器", "单口140W彩屏", "新款屏显氮化镓充电器十口桌面充电站全协议", "官方立减12%", "¥178", "300+人付款"),
            listOf("67W氮化镓", "自带双1米伸缩线", "Divoom点音像素屏充电头67W氮化镓多口", "限时特价", "¥129", "60+人付款"),
            listOf("桌面全能王", "六口同时快充", "470W带伸缩线数显桌面充电站USB多口快充", "超级立减50%", "¥188", "80+人付款"),
        )

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
