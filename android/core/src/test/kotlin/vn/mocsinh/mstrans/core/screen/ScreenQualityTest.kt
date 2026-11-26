package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenQualityTest {
    /** A stand-in for ML Kit: fixed answers, counts its calls. */
    private class FakeEngine(val answers: Map<String, String>) : TextEngine {
        val calls = mutableListOf<String>()
        override suspend fun translate(text: String): String? {
            synchronized(calls) { calls.add(text) }
            return answers[text]
        }
    }

    @Test
    fun `phrases set apart by spaces are translated one by one, the known ones without the engine`() = runBlocking {
        val engine = FakeEngine(emptyMap())
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Bán 500+/tháng · Hàng có sẵn", translator.translate("月销500+  现货")?.text)
        assertEquals("Tổng 3 sản phẩm · Gửi hàng trong 7 ngày", translator.translate("共3件  7天内发货")?.text)
        assertEquals("1.2 vạn+ người đã mua · Bán 500+/tháng · Đã bán 3000+", translator.translate("1.2万+人付款 月销500+ 已售3000+")?.text)
        assertEquals("Tồn kho 128 · Sỉ từ 2 cái", translator.translate("库存128件 2件起批")?.text)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `known phrases are drawn at once, before any translator is asked`() {
        val translator = ScreenTranslator()
        assertEquals("Đã bán 3000+ · Miễn phí vận chuyển", translator.quick("已售3000+  包邮")?.text)
        assertEquals("≈ $29.96", translator.quick("约 $ 29.96")?.text)
        assertEquals("Đã bán 16", translator.quick("已售 16")?.text)
        // One phrase not known: nothing yet.
        assertNull(translator.quick("儿童书包 包邮"))
    }

    @Test
    fun `a line with a phrase the dictionary does not know goes to the engine whole`() = runBlocking {
        // Tried on a phone: the offline translator does worse on a phrase alone ("可升降" became
        // "lift") than on the line it belongs to.
        val engine = FakeEngine(mapOf("儿童书包 轻便护脊减负" to "Cặp sách trẻ em nhẹ, bảo vệ cột sống", "儿童书包" to "Túi trẻ em"))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Cặp sách trẻ em nhẹ, bảo vệ cột sống", translator.translate("儿童书包 轻便护脊减负")?.text)
        assertEquals(listOf("儿童书包 轻便护脊减负"), engine.calls)
    }

    @Test
    fun `when the translator drops a word put in for it the line is asked again as it was`() = runBlocking {
        // 优惠券 is a shop word the dictionary puts in; the translator loses it, but does the plain line well.
        val engine = FakeEngine(mapOf(
            "这张Phiếu giảm giá很划算" to "Cái này rất hời",
            "这张优惠券很划算" to "Phiếu giảm giá này rất hời",
        ))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Phiếu giảm giá này rất hời", translator.translate("这张优惠券很划算")?.text)
    }

    @Test
    fun `a sentence whose required word the translator will not keep is still translated`() = runBlocking {
        // 库存 is "Tồn kho" wherever the translator keeps it. Where it will not, its own words for the
        // sentence are shown: on a phone, every sentence with 运费 or 物流 in it stayed in Chinese.
        val engine = FakeEngine(mapOf("Tồn kho不足" to "Không đủ hàng", "库存不足" to "Không đủ hàng"))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Không đủ hàng", translator.translate("库存不足")?.text)
    }

    @Test
    fun `the required word is kept wherever the translator keeps it`() = runBlocking {
        val engine = FakeEngine(mapOf("Tồn kho不足" to "Tồn kho không đủ", "库存不足" to "Không đủ hàng"))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Tồn kho không đủ", translator.translate("库存不足")?.text)
        assertEquals(listOf("Tồn kho不足"), engine.calls)
    }

    @Test
    fun `deleting an account is never shown as logging out, with or without the words put in`() = runBlocking {
        val engine = FakeEngine(mapOf("确定要Xóa tài khoản吗" to "Bạn có chắc muốn đăng xuất không", "确定要注销账号吗" to "Bạn có chắc muốn đăng xuất không"))
        val translator = ScreenTranslator(engine = engine)
        val shown = translator.translate("确定要注销账号吗")?.text
        assertTrue(shown == null || "đăng xuất" !in shown.lowercase(), "$shown")
    }

    @Test
    fun `the words and number sentences of a shop's pages are known`() {
        val dictionary = CommerceDictionary()
        assertEquals("Còn 01:59:54", dictionary.lookup("距结束 01:59:54"))
        assertEquals("Miễn phí vận chuyển toàn shop", dictionary.lookup("全场包邮"))
        assertEquals("Đã được miễn phí vận chuyển", dictionary.lookup("已享包邮"))
        assertEquals("Mua 2 tặng 1", dictionary.lookup("买二送一"))
        assertEquals("Tồn kho 128", dictionary.lookup("库存128件"))
        assertEquals("Chỉ còn 3", dictionary.lookup("仅剩3件"))
        assertEquals("Dự kiến giao sau 12-15 ngày kể từ khi gửi", dictionary.lookup("预计发货后12-15日送达"))
        assertEquals("Hơn 200 người thêm vào giỏ", dictionary.lookup("超200人加购"))
        assertEquals("Đủ ¥99 miễn phí vận chuyển", dictionary.lookup("满¥99包邮"))
        assertEquals("Giảm ngay 50%", dictionary.lookup("超级立减50%"))
        assertEquals("Giá cuối ¥1,299", dictionary.lookup("到手价¥1,299"))
    }

    @Test
    fun `chips of a shop's page with an arrow are known, the arrow kept`() {
        val dictionary = CommerceDictionary()
        assertEquals("400+ người đã xem trong 7 ngày >", dictionary.lookup("近7天400+人逛过 >"))
        assertEquals("Cửa hàng bán hơn 1 vạn sản phẩm/năm >", dictionary.lookup("店铺年销售超1万件 >"))
        assertEquals("Hơn 200 người thêm vào giỏ ›", dictionary.lookup("超200人加购 ›"))
        assertNull(dictionary.lookup(">"))
    }

    @Test
    fun `the words of Taobao's own pages are known`() {
        // Read off a phone, 26/11/2026: the offline translator made "Trắng", "Reclusion:", "Chợ đất" of some of these.
        val dictionary = CommerceDictionary()
        assertEquals("Cài đặt thanh toán", dictionary.lookup("支付设置"))
        assertEquals("CSKH vận chuyển", dictionary.lookup("物流客服"))
        assertEquals("Trung tâm vận chuyển", dictionary.lookup("物流中心"))
        assertEquals("Chờ gom hàng", dictionary.lookup("待集运"))
        assertEquals("Mua lẻ", dictionary.lookup("单品购买"))
        assertEquals("Mua theo bộ", dictionary.lookup("组合套装"))
        assertEquals("Ưu đãi thêm", dictionary.lookup("可再享:"))
        assertEquals("Thành phố", dictionary.lookup("地市"))
        assertEquals("Lịch sử tìm kiếm", dictionary.lookup("历史搜索"))
        assertEquals("sau trợ giá", dictionary.lookup("补贴后"))
        assertEquals("Dịch vụ xuyên biên giới chính thức · Mất hỏng đền bù", dictionary.lookup("官方跨境服务·丢损必赔"))
        assertEquals("Gửi thẳng chính thức - Đường bộ", dictionary.lookup("官方直邮-陆运"))
    }

    @Test
    fun `the number sentences of Taobao's own pages are known`() {
        val dictionary = CommerceDictionary()
        assertEquals("≈ $0.73-3.03", dictionary.lookup("约 $ 0.73-3.03"))
        assertEquals("1 vạn khách quay lại/năm", dictionary.lookup("店铺一年回头客1万"))
        assertEquals("Cửa hàng 9 năm", dictionary.lookup("9年老店"))
        assertEquals("Đặt hàng giảm phí vận chuyển tới ¥30", dictionary.lookup("下单运费最高立减 ¥ 30"))
        assertEquals("Đánh giá · 23", dictionary.lookup("评价·23"))
        assertEquals("Mua bộ giảm thêm 20 tệ", dictionary.lookup("组合再省20元"))
        assertEquals("trừ 0.5 tệ", dictionary.lookup("抵0.5元"))
        assertEquals("4 vạn+ người đã mua", dictionary.lookup("4万+人付款"))
        assertEquals("Miễn phí vận chuyển quốc tế từ ¥99", dictionary.lookup("¥99跨境包邮"))
    }

    @Test
    fun `a narrow place gets the short sentence too`() {
        val dictionary = CommerceDictionary()
        assertEquals("Freeship", dictionary.short("包邮"))
        assertEquals("Freeship từ ¥99", dictionary.short("满¥99包邮"))
        assertEquals("100+ thêm giỏ", dictionary.short("超100人加购"))
        assertEquals("200+ xem/30 ngày", dictionary.short("近30天200+人逛过"))
        assertEquals("10+ đã mua", dictionary.short("10+人付款"))
        assertEquals("200+ xem/30 ngày >", dictionary.short("近30天200+人逛过 >"))
        assertEquals("Bán 1 vạn+/năm >", dictionary.short("店铺年销售超1万件 >"))
        assertNull(dictionary.short("加厚食品级牛皮纸袋"))
    }

    @Test
    fun `a line choosing a colour and a size needs no translator`() = runBlocking {
        val engine = FakeEngine(emptyMap())
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Quy cách: XL / Đỏ", translator.translate("规格：XL / 红色")?.text)
        assertEquals("Màu sắc: Đen", translator.translate("颜色：黑色")?.text)
        assertEquals("Xanh dương", translator.quick("蓝色")?.text)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `the pieces of a line are translated side by side`() = runBlocking {
        // The whole line comes back without its numbers: not trusted, so it goes piece by piece.
        val running = java.util.concurrent.atomic.AtomicInteger()
        var most = 0
        val engine = object : TextEngine {
            override suspend fun translate(text: String): String {
                val now = running.incrementAndGet()
                synchronized(this) { most = maxOf(most, now) }
                kotlinx.coroutines.delay(40)
                running.decrementAndGet()
                return if (text.length > 4) "Cả dòng không có số" else "Mảnh"
            }
        }
        val translator = ScreenTranslator(engine = engine)
        val result = translator.translate("甲乙1丙丁2戊己3庚辛")
        assertEquals("Mảnh 1 Mảnh 2 Mảnh 3 Mảnh", result?.text)
        assertEquals(2, most)
    }

    @Test
    fun `a narrow place gets the short word`() {
        val translator = ScreenTranslator()
        assertEquals("CSKH", translator.shorter("客服"))
        assertEquals("Lưu", translator.shorter("收藏"))
        assertEquals("Thêm vào giỏ", translator.shorter("加入购物车"))
        assertNull(translator.shorter("设置"))
        // The user's own word for it is used as it is.
        translator.setGlossary("客服 = Hỗ trợ")
        assertNull(translator.shorter("客服"))
    }

    @Test
    fun `a sentence seen with one number is known with another`() = runBlocking {
        val engine = FakeEngine(mapOf("每人限领3张" to "Mỗi người nhận tối đa 3 tờ"))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Mỗi người nhận tối đa 3 tờ", translator.translate("每人限领3张")?.text)
        assertEquals("Mỗi người nhận tối đa 12 tờ", translator.quick("每人限领12张")?.text)
        // Known words with a number need no translator at all.
        assertEquals("Giới hạn mua 3 chiếc", translator.quick("限购3台")?.text)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `numbers that change places in the translation are not guessed`() = runBlocking {
        val engine = FakeEngine(mapOf("买3送1" to "Tặng 1 khi mua 3"))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Tặng 1 khi mua 3", translator.translate("买3送1")?.text)
        assertNull(translator.quick("买5送2"))
    }

    @Test
    fun `with the AI on the offline translation is shown first and the AI's replaces it`() = runBlocking {
        val engine = FakeEngine(mapOf("轻便护脊减负" to "Nhẹ lăn"))
        var asked = 0
        val ai = ScreenAi(write = { _, _ ->
            asked++
            """{"items":[{"id":0,"vi":"Nhẹ, bảo vệ cột sống, giảm tải"}]}"""
        })
        val translator = ScreenTranslator(engine = engine)
        val seen = ArrayList<Pair<String, ScreenTranslator.Source>>()
        assertNull(translator.translateAll(listOf("轻便护脊减负"), ai) { _, result -> result?.let { seen.add(it.text to it.source) } })
        assertEquals(
            listOf("Nhẹ lăn" to ScreenTranslator.Source.ENGINE, "Nhẹ, bảo vệ cột sống, giảm tải" to ScreenTranslator.Source.AI),
            seen,
        )
        assertEquals("Nhẹ, bảo vệ cột sống, giảm tải", translator.quick("轻便护脊减负")?.text)
        // The same line again: known, and the AI is not paid twice.
        translator.translateAll(listOf("轻便护脊减负"), ai) { _, _ -> }
        assertEquals(1, asked)
    }

    @Test
    fun `a line translated offline before the AI was turned on is made better once`() = runBlocking {
        val engine = FakeEngine(mapOf("轻便护脊减负" to "Nhẹ lăn"))
        var asked = 0
        val ai = ScreenAi(write = { _, _ ->
            asked++
            """{"items":[{"id":0,"vi":"Nhẹ, bảo vệ cột sống, giảm tải"}]}"""
        })
        val translator = ScreenTranslator(engine = engine)
        translator.translateAll(listOf("轻便护脊减负"), null) { _, _ -> }
        assertEquals("Nhẹ lăn", translator.quick("轻便护脊减负")?.text)
        translator.translateAll(listOf("轻便护脊减负"), ai) { _, _ -> }
        assertEquals("Nhẹ, bảo vệ cột sống, giảm tải", translator.quick("轻便护脊减负")?.text)
        translator.translateAll(listOf("轻便护脊减负"), ai) { _, _ -> }
        assertEquals(1, asked)
    }

    @Test
    fun `when the AI fails the offline translation stays`() = runBlocking {
        val engine = FakeEngine(mapOf("轻便护脊减负" to "Nhẹ lăn"))
        val ai = ScreenAi(write = { _, _ -> throw IllegalStateException("mất mạng") })
        val translator = ScreenTranslator(engine = engine)
        val error = translator.translateAll(listOf("轻便护脊减负"), ai) { _, _ -> }
        assertTrue(error != null && "mất mạng" in error, error)
        assertEquals("Nhẹ lăn", translator.quick("轻便护脊减负")?.text)
    }

    @Test
    fun `a price read in the picture of the screen is not text of a picture`() {
        // The app's own "¥178" has no Chinese, so it is not translated; the reader sees ¥ as 羊.
        val window = Box(0, 0, 1080, 2400)
        val price = Box(540, 2020, 644, 2062)
        val read = listOf(ScreenText("羊178", Box(541, 2027, 644, 2061), fromImage = true), ScreenText("新品上市", Box(100, 400, 400, 480), fromImage = true))
        val merged = ScreenTextMerger.merge(emptyList(), read, window, others = listOf(price))
        assertEquals(listOf("新品上市"), merged.map { it.text })
    }
}
