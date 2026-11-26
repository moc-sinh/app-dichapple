package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenTranslatorTest {
    /** A stand-in for ML Kit: fixed answers, counts its calls. */
    private class FakeEngine(val answers: Map<String, String>) : TextEngine {
        val calls = mutableListOf<String>()
        override suspend fun translate(text: String): String? {
            synchronized(calls) { calls.add(text) }
            return answers[text]
        }
    }

    @Test
    fun dictionaryFirstWithoutTheEngine() = runBlocking {
        val engine = FakeEngine(emptyMap())
        val translator = ScreenTranslator(engine = engine)
        assertEquals(ScreenTranslator.Result("Đăng xuất", ScreenTranslator.Source.DICTIONARY), translator.translate("退出登录"))
        assertEquals("Xóa tài khoản", translator.translate("注销账号")?.text)
        assertEquals("Mua đủ 199 giảm 20", translator.translate("满199减20")?.text)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun logOutIsNeverDeleteAccountEvenWhenTheEngineIsWrong() = runBlocking {
        // The engine mixes them up on purpose.
        val engine = FakeEngine(mapOf(
            "注销" to "Đăng xuất",
            "确定要退出当前账号吗" to "Bạn có chắc muốn xóa tài khoản hiện tại không",
            "账号注销后无法恢复" to "Sau khi đăng xuất tài khoản không thể khôi phục",
            "注销后数据将被删除" to "Sau khi hủy, dữ liệu sẽ bị xóa",
        ))
        val translator = ScreenTranslator(engine = engine)
        assertNull(translator.translate("注销"))
        assertNull(translator.translate("确定要退出当前账号吗"))
        assertNull(translator.translate("账号注销后无法恢复"))
        assertEquals("Sau khi hủy, dữ liệu sẽ bị xóa", translator.translate("注销后数据将被删除")?.text)
        // Both on one line: each part from the dictionary, never mixed.
        assertEquals("Đăng xuất | Xóa tài khoản", translator.translate("退出登录 | 注销账号")?.text)
    }

    @Test
    fun numbersSurviveOrTheLineIsTranslatedInPieces() = runBlocking {
        val engine = FakeEngine(mapOf(
            "会员价¥1,299折后-20%" to "Giá hội viên ¥1.299 sau chiết khấu 20%",
            "会员价" to "Giá hội viên",
            "折后" to "Sau chiết khấu",
        ))
        val translator = ScreenTranslator(engine = engine)
        val result = translator.translate("会员价¥1,299折后-20%")
        assertEquals("Giá hội viên ¥1,299 Sau chiết khấu -20%", result?.text)
        assertEquals(ScreenTranslator.Source.CHUNKS, result?.source)
    }

    @Test
    fun termsAreReplacedBeforeTheEngineAndMustSurvive() = runBlocking {
        val engine = FakeEngine(mapOf(
            "这件Hàng có sẵn，Miễn phí vận chuyển" to "Mặt hàng này Hàng có sẵn, Miễn phí vận chuyển",
            "Tồn kho不足" to "Không đủ hàng",
        ))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Mặt hàng này Hàng có sẵn, Miễn phí vận chuyển", translator.translate("这件现货，包邮")?.text)
        // The engine dropped a replaced term: not trusted, and nothing in pieces either.
        assertNull(translator.translate("库存不足"))
    }

    @Test
    fun chineseLeftInTheAnswerIsNotTrusted() = runBlocking {
        val translator = ScreenTranslator(engine = FakeEngine(mapOf("天猫超市" to "Siêu thị 天猫")))
        assertNull(translator.translate("天猫超市"))
    }

    @Test
    fun withoutChineseThereIsNothingToDo() = runBlocking {
        val translator = ScreenTranslator(engine = FakeEngine(emptyMap()))
        assertNull(translator.translate("¥199"))
        assertNull(translator.quick("Hello"))
    }

    @Test
    fun traditionalTranslatesLikeSimplified() = runBlocking {
        val engine = FakeEngine(mapOf("这款很好看" to "Mẫu này rất đẹp"))
        val translator = ScreenTranslator(engine = engine)
        assertEquals("Mẫu này rất đẹp", translator.translate("這款很好看")?.text)
        assertEquals(translator.translate("这款很好看")?.text, translator.translate("這款很好看")?.text)
        assertEquals(listOf("这款很好看"), engine.calls)
    }

    @Test
    fun userGlossaryComesFirst() = runBlocking {
        val translator = ScreenTranslator(engine = FakeEngine(emptyMap()))
        translator.setGlossary("购物车 = Xe đẩy\nbáo giá = 报价\nTaobao = Đào Bảo\n天猫 =")
        assertEquals(ScreenTranslator.Result("Xe đẩy", ScreenTranslator.Source.GLOSSARY), translator.translate("购物车"))
        assertEquals("báo giá", translator.translate("報價")?.text)
        // Kept as it is: nothing to cover.
        assertEquals("天猫", translator.translate("天猫")?.text)
        assertEquals("Thêm vào giỏ hàng", translator.translate("加入购物车")?.text)
    }

    @Test
    fun glossaryChangeEmptiesTheCache() = runBlocking {
        val engine = FakeEngine(mapOf("这款很好看" to "Mẫu này rất đẹp"))
        val translator = ScreenTranslator(engine = engine)
        translator.translate("这款很好看")
        translator.translate("这款很好看")
        assertEquals(1, engine.calls.size)
        assertEquals(ScreenTranslator.Source.CACHE, translator.quick("这款很好看")?.source)
        translator.setGlossary("好看 = xinh")
        assertNull(translator.quick("这款很好看"))
    }

    @Test
    fun cacheKeepsTheNewest() = runBlocking {
        val engine = FakeEngine(mapOf("一号" to "số một", "二号" to "số hai", "三号" to "số ba", "四号" to "số bốn"))
        val translator = ScreenTranslator(engine = engine, cacheSize = 3)
        for (word in listOf("一号", "二号", "三号", "四号")) translator.translate(word)
        assertEquals(3, translator.cached)
        assertNull(translator.quick("一号"))
        assertEquals("số bốn", translator.quick("四号")?.text)
    }

    @Test
    fun atMostTwoEngineCallsAtOnce() = runBlocking {
        val running = AtomicInteger()
        var most = 0
        val engine = object : TextEngine {
            override suspend fun translate(text: String): String {
                val now = running.incrementAndGet()
                synchronized(this) { most = maxOf(most, now) }
                delay(20)
                running.decrementAndGet()
                return "x"
            }
        }
        val translator = ScreenTranslator(engine = engine)
        (1..8).map { async { translator.translate("第${it}行字") } }.awaitAll()
        assertEquals(2, most)
    }

    @Test
    fun acceptChecksAnswersFromElsewhere() {
        val translator = ScreenTranslator()
        assertNull(translator.accept("注销账号流程", "Quy trình đăng xuất"))
        assertNull(translator.accept("¥99", "99"))
        assertEquals("Giá ¥99", translator.accept("价格¥99", "Giá ¥99", ScreenTranslator.Source.AI)?.text)
        assertEquals(ScreenTranslator.Source.CACHE, translator.quick("价格¥99")?.source)
    }
}
