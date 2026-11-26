package vn.mocsinh.testshop

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Taobao's "choose a variant" sheet, as on a phone in dark mode: a dialog window with a shadow
 * (Android gives such a window a screenshot larger than the window), its text drawn by the app
 * itself (no accessibility text, as Taobao does), so only the reader of pictures sees it. The
 * address is made up.
 */
object SkuSheet {
    private val ORANGE = Color.rgb(255, 98, 26)
    private val GREEN = Color.rgb(26, 186, 128)
    private val SHEET = Color.rgb(28, 28, 30)
    private val ROW = Color.rgb(44, 44, 46)
    private val GREY = Color.rgb(142, 142, 147)

    /** Name of the variant as cut on screen, price. */
    private val VARIANTS = listOf(
        "镜框【颜色请备注】+0度蓝光防电脑手机..." to "¥311",
        "镜框【颜色请备注】+1.56非球面0-300..." to "¥293",
        "镜框【颜色请备注】+1.61非球面300-50..." to "¥329",
        "镜框【颜色请备注】+1.67非球面400-80..." to "¥383",
        "镜框【颜色请备注】+1.74非球面500以..." to "¥527",
        "镜框【颜色请备注】+1.56非球面防蓝光..." to "¥329",
        "镜框【颜色请备注】+1.61非球面防蓝光..." to "¥383",
        "镜框【颜色请备注】+1.67非球面防蓝光..." to "¥491",
        "镜框【颜色请备注】+1.74非球面防蓝光..." to "¥581",
        "镜框【颜色请备注】+1.56非球面变色..." to "¥329",
        "镜框【颜色请备注】+1.61非球面变色50..." to "¥383",
        "镜框【颜色请备注】+1.67非球面变色50..." to "¥491",
    )

    fun show(activity: Activity, tapped: (String) -> Unit) {
        val dialog = Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val sheet = SheetView(activity, tapped)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SHEET)
                cornerRadii = floatArrayOf(dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat(), 0f, 0f, 0f, 0f)
            }
            addView(ScrollView(activity).apply { addView(sheet) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(FrameLayout(activity).apply {
                setPadding(dp(16f), dp(8f), dp(16f), dp(12f))
                addView(TextView(activity).apply {
                    text = "加入购物车"
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(ORANGE); cornerRadius = dp(24f).toFloat() }
                    setOnClickListener { tapped("sku cart") }
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
            })
        }
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.86f).toInt())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
    }

    /** The sheet's content, drawn: header, address, price, quantity, chips, a list of variants. */
    private class SheetView(context: Context, private val tapped: (String) -> Unit) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val rowTop = 340f
        private val rowStep = 54f

        private fun dp(v: Float) = v * d

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(rowTop + rowStep * VARIANTS.size + 16f).toInt())
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val row = ((event.y / d - rowTop) / rowStep).toInt()
                tapped(if (row in VARIANTS.indices) "variant ${row + 1}" else "sheet")
            }
            return true
        }

        private fun text(canvas: Canvas, value: String, x: Float, y: Float, sizeSp: Float, color: Int, bold: Boolean = false) {
            paint.textSize = dp(sizeSp)
            paint.color = color
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(value, dp(x), dp(y), paint)
        }

        private fun box(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 6f, stroke: Boolean = false) {
            paint.color = color
            paint.style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
            paint.strokeWidth = dp(1.2f)
            rect.set(dp(l), dp(t), dp(r), dp(b))
            canvas.drawRoundRect(rect, dp(radius), dp(radius), paint)
            paint.style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            val w = width / d
            text(canvas, "已选“ASTATRE黑金”", 16f, 34f, 16f, Color.WHITE)
            text(canvas, "✕", w - 36f, 34f, 20f, Color.WHITE)
            // Address | free delivery, expected delivery (the address is made up).
            text(canvas, "◎ 12 Phố Hàng Bạc", 16f, 72f, 15f, Color.WHITE)
            paint.textSize = dp(15f)
            val after = 16f + paint.measureText("◎ 12 Phố Hàng Bạc") / d
            text(canvas, "|  包邮", after + 6f, 72f, 15f, Color.WHITE)
            text(canvas, "预计后天送达", after + 62f, 72f, 15f, GREEN)
            text(canvas, "›", w - 28f, 72f, 18f, GREY)
            // Picture, price, quantity, stock.
            box(canvas, 16f, 96f, 96f, 176f, Color.rgb(90, 90, 94), 4f)
            text(canvas, "平台加补后¥", 108f, 122f, 15f, ORANGE, bold = true)
            paint.textSize = dp(15f)
            paint.typeface = Typeface.DEFAULT_BOLD
            var x = 108f + paint.measureText("平台加补后¥") / d
            text(canvas, "221", x, 124f, 22f, ORANGE, bold = true)
            paint.textSize = dp(22f)
            x += paint.measureText("221") / d + 6f
            text(canvas, "|  优惠前¥268", x, 122f, 15f, ORANGE, bold = true)
            box(canvas, 108f, 140f, 194f, 172f, ROW, 4f)
            text(canvas, "－      1      ＋", 118f, 162f, 15f, Color.WHITE)
            text(canvas, "有货", 210f, 162f, 15f, GREY)
            // Chips.
            val chips = listOf("ASTATRE磨砂黑  ¥221", "ASTATRE玳瑁金  ¥221", "ASTATRE黑金  ¥221")
            var left = 16f
            var top = 192f
            for ((i, chip) in chips.withIndex()) {
                paint.textSize = dp(15f)
                val width = 44f + paint.measureText(chip) / d + 12f
                if (left + width > w - 16f) {
                    left = 16f
                    top += 46f
                }
                val selected = i == chips.lastIndex
                box(canvas, left, top, left + width, top + 36f, ROW, 4f)
                if (selected) box(canvas, left, top, left + width, top + 36f, ORANGE, 4f, stroke = true)
                box(canvas, left + 4f, top + 4f, left + 36f, top + 32f, Color.rgb(110, 110, 114), 3f)
                text(canvas, chip, left + 44f, top + 24f, 15f, if (selected) ORANGE else Color.WHITE)
                left += width + 10f
            }
            // The variants: a light "no picture" square, the name as cut, the price, a chevron.
            for ((i, variant) in VARIANTS.withIndex()) {
                val t = rowTop + i * rowStep
                box(canvas, 16f, t, w - 16f, t + 44f, ROW, 4f)
                box(canvas, 16f, t, 54f, t + 44f, Color.rgb(235, 235, 235), 4f)
                text(canvas, "无图", 23f, t + 27f, 10f, Color.rgb(160, 160, 160))
                text(canvas, variant.first, 64f, t + 28f, 15.5f, Color.WHITE)
                text(canvas, variant.second, w - 82f, t + 28f, 15f, GREY)
                text(canvas, "⌄", w - 38f, t + 26f, 18f, Color.WHITE)
            }
        }
    }
}
