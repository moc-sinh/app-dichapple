package vn.mocsinh.mstrans.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenTextMergerTest {
    private val window = Box(0, 100, 1080, 2300)

    @Test
    fun parentRepeatingItsChildrenIsDropped() {
        val parent = ScreenText("价格 ¥199 包邮", Box(0, 200, 1080, 300))
        val a = ScreenText("价格", Box(0, 200, 200, 300))
        val b = ScreenText("包邮", Box(400, 200, 600, 300))
        val merged = ScreenTextMerger.merge(listOf(parent, a, b), emptyList(), window)
        assertEquals(listOf(a, b), merged)
    }

    @Test
    fun parentWithMoreThanItsChildrenStays() {
        val parent = ScreenText("立即购买 限时特价", Box(0, 200, 1080, 300))
        val child = ScreenText("立即购买", Box(0, 200, 400, 300))
        assertEquals(listOf(parent), ScreenTextMerger.merge(listOf(parent, child), emptyList(), window))
    }

    @Test
    fun imageTextUnderAnAccessibilityNodeIsDropped() {
        val node = ScreenText("加入购物车", Box(0, 2000, 500, 2100))
        val sameText = ScreenText("加入购物车", Box(10, 2010, 490, 2090), fromImage = true)
        val covered = ScreenText("加入", Box(0, 2000, 200, 2100), fromImage = true)
        val inPhoto = ScreenText("新品上市", Box(100, 500, 800, 600), fromImage = true)
        assertEquals(listOf(node, inPhoto), ScreenTextMerger.merge(listOf(node), listOf(sameText, covered, inPhoto), window))
    }

    @Test
    fun outsideTheWindowOrUnderTheKeyboardIsDropped() {
        val above = ScreenText("状态栏", Box(0, 0, 300, 90))
        val half = ScreenText("一半", Box(0, 60, 300, 140))
        val keyboard = Box(0, 1500, 1080, 2300)
        val under = ScreenText("键盘下", Box(0, 1600, 300, 1700))
        val fine = ScreenText("正常", Box(0, 400, 300, 500))
        val latin = ScreenText("¥199", Box(0, 600, 300, 700))
        assertEquals(listOf(fine), ScreenTextMerger.merge(listOf(above, half, under, fine, latin), emptyList(), window, listOf(keyboard)))
    }

    @Test
    fun duplicatesOnce() {
        val a = ScreenText("首页", Box(0, 2200, 200, 2280))
        assertEquals(listOf(a), ScreenTextMerger.merge(listOf(a, a.copy()), emptyList(), window))
    }
}

class SensitiveScreenTest {
    @Test
    fun passwordFieldKeyboardAndSecureWindow() {
        assertEquals(SensitiveScreen.Reason.PASSWORD_FIELD, SensitiveScreen.check(listOf("登录"), passwordField = true))
        assertEquals(SensitiveScreen.Reason.KEYBOARD, SensitiveScreen.check(listOf("搜索"), keyboard = true))
        assertEquals(SensitiveScreen.Reason.SECURE_WINDOW, SensitiveScreen.check(emptyList(), secureWindow = true))
    }

    @Test
    fun paymentWordsAnywhere() {
        assertEquals(SensitiveScreen.Reason.KEYWORD, SensitiveScreen.check(listOf("请输入支付密码")))
        assertEquals(SensitiveScreen.Reason.KEYWORD, SensitiveScreen.check(listOf("付款碼")))
        assertEquals(SensitiveScreen.Reason.KEYWORD, SensitiveScreen.check(listOf("Enter CVV")))
    }

    @Test
    fun weakerWordsOnlyWithAField() {
        // Taobao's settings list has "修改密码" (change password) as a menu item: not a password screen.
        assertNull(SensitiveScreen.check(listOf("账号与安全", "修改密码")))
        assertEquals(SensitiveScreen.Reason.KEYWORD, SensitiveScreen.check(listOf("验证码"), editableField = true))
        assertNull(SensitiveScreen.check(listOf("搜索宝贝"), editableField = true))
    }
}

class FrameSignatureTest {
    private fun frame(width: Int, height: Int, color: (Int, Int) -> Int) = FrameSignature.of(width, height) { x, y -> color(x, y) }

    @Test
    fun sameImageSameSignature() {
        val a = frame(1080, 2340) { x, _ -> if (x < 540) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val b = frame(1080, 2340) { x, _ -> if (x < 540) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertFalse(a.differs(b))
        assertTrue(a.differs(null))
    }

    @Test
    fun changedRegionIsSeen() {
        val a = frame(1080, 2340) { _, _ -> 0xFFFFFFFF.toInt() }
        val b = frame(1080, 2340) { x, y -> if (x in 100..400 && y in 300..700) 0xFF202020.toInt() else 0xFFFFFFFF.toInt() }
        assertTrue(a.differs(b))
        assertEquals(FrameSignature.COLUMNS * FrameSignature.ROWS, a.cells.size)
    }

    @Test
    fun noiseIsNotAChange() {
        val a = frame(1080, 2340) { _, _ -> 0xFF808080.toInt() }
        val b = frame(1080, 2340) { _, _ -> 0xFF828282.toInt() }
        assertFalse(a.differs(b))
    }
}

class OverlayFitTest {
    /** Text 0.6 em wide per character, lines 1.2 em high. */
    private fun measure(text: String, width: Int) = { size: Float ->
        val perLine = maxOf(1, (width / (size * 0.6f)).toInt())
        val lines = (text.length + perLine - 1) / perLine
        (lines * size * 1.2f).toInt()
    }

    @Test
    fun biggestSizeThatFits() {
        val fit = OverlayFit.fit(height = 60, minPx = 20f, maxPx = 48f, lineHeight = { it * 1.2f }, measure = measure("Giỏ hàng", 600))
        assertEquals(48f, fit.sizePx)
        assertFalse(fit.ellipsized)
        val tight = OverlayFit.fit(height = 60, minPx = 20f, maxPx = 48f, lineHeight = { it * 1.2f }, measure = measure("Thêm vào giỏ hàng ngay hôm nay", 300))
        assertTrue(tight.sizePx in 20f..48f)
        assertTrue(measure("Thêm vào giỏ hàng ngay hôm nay", 300)(tight.sizePx) <= 60)
        assertFalse(tight.ellipsized)
    }

    @Test
    fun tooLongIsCutNotSpilled() {
        val text = "Một câu mô tả sản phẩm rất dài không thể nào vừa trong khung nhỏ này được"
        val fit = OverlayFit.fit(height = 50, minPx = 20f, maxPx = 40f, lineHeight = { it * 1.2f }, measure = measure(text, 200))
        assertEquals(20f, fit.sizePx)
        assertTrue(fit.ellipsized)
        assertEquals(2, fit.maxLines)
    }

    @Test
    fun textColourByContrast() {
        assertEquals(0xFF000000.toInt(), OverlayFit.textColor(0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), OverlayFit.textColor(0xFF101010.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), OverlayFit.textColor(0xFFC01010.toInt()))
        // Taobao orange reads better in black.
        assertEquals(0xFF000000.toInt(), OverlayFit.textColor(0xFFFF5000.toInt()))
        assertTrue(OverlayFit.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) > 20)
    }
}

class ScreenSettingsTest {
    @Test
    fun offByDefaultWithTaobaoChosen() {
        val settings = ScreenSettings(vn.mocsinh.mstrans.core.MemoryStore())
        assertFalse(settings.enabled)
        assertEquals(setOf("com.taobao.taobao"), settings.apps)
        assertEquals(ScreenSettings.Mode.OFFLINE, settings.mode)
        assertEquals(Speed.BALANCED, settings.speed)
        assertTrue(settings.readImages)
        assertTrue(settings.isSelected("com.taobao.taobao"))
        assertFalse(settings.isSelected("com.tencent.mm"))
        assertFalse(settings.isSelected("vn.mocsinh.mstrans"))
    }

    @Test
    fun choicesAreKept() {
        val store = vn.mocsinh.mstrans.core.MemoryStore()
        ScreenSettings(store).apply {
            enabled = true
            apps = setOf("com.alibaba.wireless", "com.tencent.mm")
            mode = ScreenSettings.Mode.AI
            speed = Speed.SAVER
        }
        val again = ScreenSettings(store)
        assertTrue(again.enabled)
        assertEquals(setOf("com.alibaba.wireless", "com.tencent.mm"), again.apps)
        assertEquals(ScreenSettings.Mode.AI, again.mode)
        assertEquals(Speed.SAVER, again.speed)
        again.apps = emptySet()
        assertEquals(emptySet(), ScreenSettings(store).apps)
    }

    @Test
    fun sixSuggestedApps() {
        assertEquals(
            listOf("com.taobao.taobao", "com.alibaba.wireless", "com.xunmeng.pinduoduo", "com.jingdong.app.mall", "com.tencent.mm",
                "com.alibaba.intl.android.apps.poseidon"),
            ScreenSettings.SUGGESTED.map { it.packageName },
        )
    }
}
