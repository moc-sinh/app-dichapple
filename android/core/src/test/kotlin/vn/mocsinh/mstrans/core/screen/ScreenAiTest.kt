package vn.mocsinh.mstrans.core.screen

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import vn.mocsinh.mstrans.core.MemoryStore
import vn.mocsinh.mstrans.core.Settings
import vn.mocsinh.mstrans.core.TranslationModel
import vn.mocsinh.mstrans.core.Translator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScreenAiTest {
    private fun answer(output: String) = """
        {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(output))}}]}],
         "usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":0},"output_tokens":30}}
    """.trimIndent()

    @Test
    fun batchesStayWithinLimits() {
        val many = (1..95).map { "第${it}个商品标题" }
        val batches = ScreenAi.batches(many)
        assertEquals(listOf(40, 40, 15), batches.map { it.size })
        val long = (1..5).map { "长".repeat(1000) }
        assertTrue(ScreenAi.batches(long).all { batch -> batch.sumOf { it.length } <= ScreenAi.MAX_CHARS || batch.size == 1 })
    }

    @Test
    fun readsTheAnswerEvenInAFence() {
        assertEquals(mapOf(0 to "Giỏ hàng", 1 to "Mua ngay"),
            ScreenAi.parse("```json\n{\"items\":[{\"id\":0,\"vi\":\"Giỏ hàng\"},{\"id\":1,\"vi\":\"Mua ngay\"}]}\n```"))
        assertEquals(emptyMap(), ScreenAi.parse("xin lỗi"))
    }

    @Test
    fun sendsOnlyTextAndChecksTheAnswer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(answer(
            """{"items":[{"id":0,"vi":"Chủ shop gợi ý đồ tốt, giá ¥199"},{"id":1,"vi":"Đăng xuất tài khoản"}]}""")))
        server.start()
        val translator = Translator("sk-test", TranslationModel.LUNA, Settings(MemoryStore()), server.url("/v1/responses").toString())
        val ai = ScreenAi(write = { instructions, input -> translator.write(instructions, input).first })
        val screen = ScreenTranslator()
        val results = mutableMapOf<String, ScreenTranslator.Result?>()
        val error = screen.translateAll(listOf("店主推荐好物 ¥199", "注销账号说明", "购物车"), ai) { text, result -> results[text] = result }
        assertEquals(null, error)
        assertEquals("Chủ shop gợi ý đồ tốt, giá ¥199", results["店主推荐好物 ¥199"]?.text)
        assertEquals(ScreenTranslator.Source.AI, results["店主推荐好物 ¥199"]?.source)
        // The AI said "log out" for 注销: refused, and offline (no engine here) has nothing either.
        assertEquals(null, results["注销账号说明"])
        assertEquals("Giỏ hàng", results["购物车"]?.text)

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-6-luna", body["model"]?.jsonPrimitive?.content)
        val input = body["input"]!!.jsonPrimitive.content
        assertTrue("店主推荐好物 ¥199" in input && "注销账号说明" in input)
        // Already in the dictionary: never sent.
        assertTrue("购物车" !in input)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun limitFallsBackOffline() = runBlocking {
        var now = 0L
        var calls = 0
        val ai = ScreenAi(write = { _, _ -> calls++; """{"items":[{"id":0,"vi":"Một"}]}""" }, clock = { now }, limitPer10Minutes = 2)
        ai.translate(listOf("一个"))
        ai.translate(listOf("一个"))
        assertFailsWith<ScreenAi.LimitReached> { ai.translate(listOf("一个")) }
        assertEquals(2, calls)
        now += 10 * 60_000L + 1
        ai.translate(listOf("一个"))
        assertEquals(3, calls)

        val offline = object : TextEngine {
            override suspend fun translate(text: String) = "Hai"
        }
        val screen = ScreenTranslator(engine = offline)
        ai.translate(listOf("一个"))
        val results = mutableMapOf<String, ScreenTranslator.Result?>()
        val error = screen.translateAll(listOf("两个"), ai) { text, result -> results[text] = result }
        assertTrue(error != null && "giới hạn" in error, error)
        assertEquals(ScreenTranslator.Result("Hai", ScreenTranslator.Source.ENGINE), results["两个"])
    }
}
