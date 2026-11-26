package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Taobao's "choose a variant" sheet (a phone in dark mode, 26/11/2026): what went wrong there. */
class VariantSheetTest {
    /** Letters 30 wide from `left`; Chinese 36 high, digits and Latin 26 high (as the reader boxes them). */
    private fun letters(text: String, left: Int, top: Int = 1300) = text.mapIndexed { i, c ->
        val tall = ChineseText.isHan(c.code) || c == '|'
        c.toString() to Box(left + i * 30, if (tall) top else top + 6, left + i * 30 + 28, if (tall) top + 36 else top + 32)
    }

    @Test
    fun `a bar between two things is not a digit and not part of either`() {
        // "平台加补后¥221 | 优惠前¥268": the bar was read as a 1 and the price shown as ¥2211.
        val left = letters("平台加补后¥221", 0)
        val bar = "1" to Box(282, 1298, 288, 1338)
        val right = letters("优惠前¥268", 302)
        val pieces = PictureText.pieces(listOf(left + bar + right)).map { it.first }
        assertEquals(listOf("平台加补后", "¥221", "优惠前", "¥268"), pieces)
    }

    @Test
    fun `a one inside a number stays`() {
        assertEquals(listOf("¥2211"), PictureText.pieces(listOf(letters("¥2211", 0))).map { it.first })
        assertEquals(listOf("买1送1"), PictureText.pieces(listOf(letters("买1送1", 0))).map { it.first })
    }

    @Test
    fun `a price at the end of a line is a piece of its own`() {
        val row = letters("非球面300-50...¥329", 0)
        assertEquals(listOf("非球面300-50...", "¥329"), PictureText.pieces(listOf(row)).map { it.first })
        // A price inside a phrase is part of it.
        assertEquals(listOf("满¥99包邮"), PictureText.pieces(listOf(letters("满¥99包邮", 0))).map { it.first })
    }

    @Test
    fun `an address in Latin letters is set apart by its bar`() {
        // Latin words a little apart (a space), the bar well apart.
        val words = listOf(letters("12", 0), letters("Phố", 66), letters("Hàng", 162), letters("Bạc", 288), listOf("|" to Box(400, 1298, 406, 1338)), letters("包邮", 430))
        assertEquals(listOf("12 Phố Hàng Bạc", "包邮"), PictureText.pieces(words).map { it.first })
    }

    @Test
    fun `the sheet's words`() {
        val d = CommerceDictionary()
        assertEquals("Còn hàng", d.lookup("有货"))
        assertEquals("Sau trợ giá sàn", d.lookup("平台加补后"))
        assertEquals("Trước giảm giá", d.lookup("优惠前"))
        assertEquals("Dự kiến giao ngày kia", d.lookup("预计后天送达"))
        assertEquals("Dự kiến giao ngày mai", d.lookup("预计明天送达"))
        assertEquals("Đã chọn “ASTATRE Đen vàng”", d.lookup("已选“ASTATRE黑金”"))
        assertEquals("ASTATRE Đen nhám", d.lookup("ASTATRE磨砂黑"))
        assertEquals("ASTATRE Vàng đồi mồi", d.lookup("ASTATRE玳瑁金"))
    }

    @Test
    fun `a variant's name is put together from the dictionary`() = runBlocking {
        val translator = ScreenTranslator()
        assertEquals("Gọng kính [ghi chú màu] +1.56 phi cầu 0-300...", translator.quick("镜框【颜色请备注】+1.56非球面0-300...")?.text)
        assertEquals("Gọng kính [ghi chú màu] +1.67 phi cầu chống ánh sáng xanh...", translator.quick("镜框【颜色请备注】+1.67非球面防蓝光...")?.text)
        assertEquals("Gọng kính [ghi chú màu] +0 độ ánh sáng xanh chống máy tính điện thoại...", translator.translate("镜框【颜色请备注】+0度蓝光防电脑手机...")?.text)
        assertEquals("Gọng kính [ghi chú màu] +1.56 phi cầu đổi màu...", translator.translate("镜框【颜色请备注】+1.56非球面变色...")?.text)
    }

    @Test
    fun `a number with its unit`() {
        val translator = ScreenTranslator()
        assertEquals("Size 38", translator.quick("38码")?.text)
        assertEquals("500 ml · Bình giữ nhiệt", translator.quick("500毫升 保温杯")?.text)
        assertEquals("Bộ 4 món", translator.quick("四件套")?.text)
    }

    @Test
    fun `a placeholder for a missing picture is left as it is`() {
        assertEquals("无图", ScreenTranslator().quick("无图")?.text)
    }

    @Test
    fun `Vietnamese on the line stays as written, the Chinese is translated`() = runBlocking {
        val translator = ScreenTranslator(engine = TextEngine { "sai" })
        assertEquals("12 Phố Hàng Bạc Miễn phí vận chuyển", translator.translate("12 Phố Hàng Bạc 包邮")?.text)
    }

    @Test
    fun `English left over by the offline translator is not trusted`() = runBlocking {
        // Chinese to English to Vietnamese: 颜色请备注 came back as "Màu Sắc Jing Note", 蓝光 as "BLU-RAY".
        val engine = TextEngine { text ->
            when (text) {
                "这款请备注尺码" -> "Mẫu này Jing Note kích thước"
                "这款手机壳很好" -> "Ốp điện thoại này rất tốt"
                "这款支持USB充电" -> "Mẫu này hỗ trợ sạc USB"
                else -> null
            }
        }
        val translator = ScreenTranslator(engine = engine)
        assertNull(translator.translate("这款请备注尺码"))
        assertEquals("Ốp điện thoại này rất tốt", translator.translate("这款手机壳很好")?.text)
        // Latin letters that were in the line, and common borrowed words, are fine.
        assertEquals("Mẫu này hỗ trợ sạc USB", translator.translate("这款支持USB充电")?.text)
    }

    @Test
    fun `a line of known words is put together rather than left to the offline translator`() = runBlocking {
        val translator = ScreenTranslator(engine = TextEngine { "Gọng kính BLU-RAY Peach" })
        assertEquals("Gọng kính [ghi chú màu] +0 độ ánh sáng xanh chống máy tính điện thoại...", translator.quick("镜框【颜色请备注】+0度蓝光防电脑手机...")?.text)
        // The thing itself first, as Vietnamese says it.
        assertEquals("Áo thun nam cotton 100%", translator.quick("男士纯棉T恤")?.text)
        assertEquals("Tròng kính chống ánh sáng xanh", translator.quick("防蓝光镜片")?.text)
        assertEquals("Mẫu mới mùa thu", translator.quick("秋季新款")?.text)
    }

    @Test
    fun `a bar after a large price is still a bar`() {
        // The price in large digits (50 high), the bar as high as the small text after it.
        val small = letters("平台加补后¥", 0)
        val big = "221".mapIndexed { i, c -> c.toString() to Box(180 + i * 40, 1290, 180 + i * 40 + 36, 1340) }
        val bar = "1" to Box(314, 1299, 320, 1337)
        val after = letters("优惠前¥268", 336)
        assertEquals(listOf("平台加补后", "¥221", "优惠前", "¥268"), PictureText.pieces(listOf(small + big + bar + after)).map { it.first })
    }

    @Test
    fun `a bar inside a word the reader did not cut into letters`() {
        val words = listOf(listOf("Bạc|" to Box(0, 1300, 120, 1336)), letters("包邮", 150))
        assertEquals(listOf("Bạc", "包邮"), PictureText.pieces(words).map { it.first })
    }

    @Test
    fun `Vietnamese next to Chinese in a picture is a piece of its own`() {
        val words = listOf(letters("12", 0), letters("Phố", 66), letters("Hàng", 162), letters("Bạc", 288), letters("包邮", 384))
        assertEquals(listOf("12 Phố Hàng Bạc", "包邮"), PictureText.pieces(words).map { it.first })
        // A brand in capitals before Chinese stays with it.
        assertEquals(listOf("ASTATRE黑金"), PictureText.pieces(listOf(letters("ASTATRE黑金", 0))).map { it.first })
    }

    @Test
    fun `what follows a cut name is another thing`() {
        // The name cut with "..", then the price and the arrow, read as one line.
        val pieces = PictureText.pieces(listOf(letters("非球面0-300..4293 v", 0))).map { it.first }
        assertEquals("非球面0-300..", pieces.first())
        assertTrue(pieces.none { ChineseText.hasHan(it) && "293" in it }, pieces.toString())
    }

    @Test
    fun `a letter misread in a picture is put right from the dictionary`() {
        val d = CommerceDictionary()
        assertEquals("镜框【颜色请备注】+1.56非球面", d.correct("镜框【颜色靖备注】+1.56非球面"))
        assertEquals("ASTATRE玳瑁金", d.correct("ASTATRE玳環金"))
        // Two letters are too few to tell (手? may be 手机, 手环, 手套…).
        assertEquals("防电脑手桃", d.correct("防电脑手桃"))
        assertEquals("加入购物车", d.correct("加入购物车"))
        // A real word one letter from a known one, among unknown words: as read.
        assertEquals("家用大号保温桶好用", d.correct("家用大号保温桶好用"))
    }

    @Test
    fun `a number between two unknown phrases in a picture is not written over`() {
        // "平台加补后¥221 | 优惠前": the ¥ lost, the bar read as 1. Known as a whole: no; so the number stays as drawn.
        val row = listOf(letters("平台加补后2211优惠前", 0))
        assertEquals(listOf("平台加补后", "2211", "优惠前"), PictureText.pieces(row) { false }.map { it.first })
        assertEquals(listOf("平台加补后2211优惠前"), PictureText.pieces(row) { true }.map { it.first })
        // A pattern with a number keeps it ("满199减20" is known).
        assertEquals(listOf("满199减20"), PictureText.pieces(listOf(letters("满199减20", 0))) { it == "满199减20" }.map { it.first })
    }

    @Test
    fun `a price in another colour is another thing`() {
        // White name, grey price, nothing read between them.
        val row = letters("非球面300-50329", 0)
        val ink: (Box) -> Int? = { box -> if (box.left >= 9 * 30) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(listOf("非球面300-50", "329"), PictureText.pieces(listOf(row), ink = ink).map { it.first })
    }

    @Test
    fun `one piece's box stops where the next begins`() {
        val words = listOf(letters("优惠前", 0) .map { (t, b) -> t to Box(b.left, b.top, b.right + 12, b.bottom) }, letters("¥268", 120))
        val pieces = PictureText.pieces(words)
        assertTrue(pieces[0].second.right < pieces[1].second.left, pieces.toString())
    }

    @Test
    fun `the AI is told what else is on the screen, not asked to translate it`() {
        val input = ScreenAi.input(listOf("镜框【颜色请备注】+1.56非球面"), listOf("眼镜店铺", "镜框【颜色请备注】+1.56非球面", "¥293"))
        assertTrue("\"screen\":[\"眼镜店铺\"]" in input, input)
        assertTrue(ScreenAi.INSTRUCTIONS.contains("do not translate it"))
    }

    @Test
    fun `every word of the trade dictionary is Chinese with a Vietnamese translation`() {
        for ((chinese, vietnamese) in IndustryTerms.ALL) {
            assertTrue(ChineseText.hasHan(chinese), chinese)
            assertTrue(vietnamese.isNotBlank() && !ChineseText.hasHan(vietnamese), chinese)
        }
        assertTrue(IndustryTerms.ALL.size >= 300, "${IndustryTerms.ALL.size}")
        // Never one account action for the other, whatever is added.
        assertNull(IndustryTerms.ALL.entries.firstOrNull { "注销" in it.key && "xuất" in it.value })
    }
}
