package vn.mocsinh.mstrans.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TranslatorTest {
    private fun answer(output: String, status: String = "completed") = """
        {"status":"$status","output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(output))}}]}],
         "usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":20},"output_tokens":30}}
    """.trimIndent()

    private fun settings() = Settings(MemoryStore()).apply {
        glossary = "báo giá = 报价"
        profile = "Sales at Mộc Sinh"
        gender = 1
    }

    @Test
    fun outgoingWithMeaning() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(answer("""{"translation":"您好","translation_back_vi":"Xin chào anh"}""")))
        server.start()
        val translator = Translator("sk-test", TranslationModel.SOL, settings(), server.url("/v1/responses").toString())
        val result = translator.translate("Chào anh", Language.named("zh")!!, checkMeaning = true)
        assertEquals("您好", result.text)
        assertEquals("Xin chào anh", result.meaning)
        assertEquals(TokenUsage(100, 20, 30), result.usage)

        val request = server.takeRequest()
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        // Keys sorted: the translation comes before its meaning check in the schema.
        assertTrue(body.indexOf("\"input\"") < body.indexOf("\"instructions\"") && body.indexOf("\"model\"") < body.indexOf("\"text\""), body)
        assertTrue(body.indexOf("\"translation\":") < body.indexOf("\"translation_back_vi\":"), body)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("\"gpt-6.1-sol\"", json["model"].toString())
        assertTrue(json["instructions"].toString().contains("Simplified Chinese"))
        assertTrue(json["instructions"].toString().contains("The user is male."))
        assertTrue(json["instructions"].toString().contains("báo giá = 报价"))
        assertTrue(json["input"].toString().contains("<message>\\nChào anh\\n</message>"))
        server.shutdown()
    }

    @Test
    fun errorsInVietnamese() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"insufficient_quota","message":"You exceeded"}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_api_key","message":"bad"}}"""))
        server.enqueue(MockResponse().setBody(answer("half", status = "incomplete")))
        server.enqueue(MockResponse().setBody(answer("not json")))
        server.start()
        val translator = Translator("k", TranslationModel.LUNA, settings(), server.url("/v1/responses").toString())
        suspend fun message(): String = try {
            translator.translate("x", Language.vietnamese)
            fail("no error")
        } catch (e: TranslateError) {
            e.message ?: ""
        }
        assertTrue(message().contains("hết tiền"))
        assertTrue(message().contains("API key không đúng"))
        assertTrue(message().contains("bị cắt"))
        assertTrue(message().contains("Không nhận được bản dịch"))
        server.shutdown()
    }

    @Test
    fun busyIsTriedAgain() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(answer("""{"translation":"Xin chào"}""")))
        server.start()
        val translator = Translator("k", TranslationModel.LUNA, settings(), server.url("/v1/responses").toString())
        assertEquals("Xin chào", translator.translate("Hello", Language.vietnamese).text)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun formatUnsupportedFallsBack() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid text.format for this model"}}"""))
        server.enqueue(MockResponse().setBody(answer("Xin chào")))
        server.start()
        val translator = Translator("k", TranslationModel.LUNA, settings(), server.url("/v1/responses").toString())
        assertEquals("Xin chào", translator.translate("Hello", Language.vietnamese).text)
        server.takeRequest()
        assertTrue(!server.takeRequest().body.readUtf8().contains("json_schema"))
        server.shutdown()
    }

    @Test
    fun settingsContextAndHistory() {
        var clock = 1_000_000L
        val settings = Settings(MemoryStore()) { clock }
        settings.remember(ChatLine(ChatLine.Side.PARTNER, "你好", clock))
        settings.remember(ChatLine(ChatLine.Side.PARTNER, "你好", clock))
        settings.remember(ChatLine(ChatLine.Side.ME, "x".repeat(3000), clock))
        val context = settings.context()
        assertEquals(1, context.size, "a long line keeps its end within the budget")
        assertTrue(context[0].text.startsWith("…") && context[0].text.length == 2501)
        settings.forgetLast("x".repeat(3000))
        assertEquals(listOf("你好"), settings.context().map { it.text })
        clock += Config.MEMORY_MAX_AGE_MS + 1
        assertTrue(settings.context().isEmpty(), "old lines expire")

        settings.cacheIncoming("Hello  World", "Xin chào")
        assertEquals("Xin chào", settings.cachedIncoming("hello world"))

        repeat(305) { settings.addHistory(HistoryItem(side = ChatLine.Side.ME, source = "s$it", translation = "t$it", languageCode = "en")) }
        val history = settings.history()
        assertEquals(300, history.size)
        assertEquals("s304", history.first().source)
        settings.updateHistory(history.last().copy(starred = true))
        settings.addHistory(HistoryItem(side = ChatLine.Side.ME, source = "new", translation = "new", languageCode = "en"))
        assertTrue(settings.history().any { it.starred }, "starred items stay")
        settings.removeHistory(settings.history().first().id)
        assertEquals("s304", settings.history().first().source)
    }

    @Test
    fun historyIsReadAgainOnlyWhenItChanges() {
        val store = MemoryStore()
        val settings = Settings(store)
        settings.addHistory(HistoryItem(side = ChatLine.Side.ME, source = "a", translation = "A", languageCode = "en"))
        val first = settings.history()
        assertTrue(first === settings.history(), "the same JSON is not read again")
        // Written by another reader of the same store: read anew.
        Settings(store).addHistory(HistoryItem(side = ChatLine.Side.PARTNER, source = "b", translation = "B", languageCode = "zh"))
        assertEquals(listOf("b", "a"), settings.history().map { it.source })
        settings.updateHistory(settings.history().last().copy(meaning = "nghĩa"))
        assertEquals("nghĩa", Settings(store).history().last().meaning)
        // Unreadable: nothing is written over it.
        store.set("history", "[{")
        assertTrue(settings.history().isEmpty())
        settings.addHistory(HistoryItem(side = ChatLine.Side.ME, source = "c", translation = "C", languageCode = "en"))
        assertEquals("[{", store.get("history"))
    }

    @Test
    fun languages() {
        fun code(text: String) = LanguageDetector.language(text)?.code
        assertEquals("zh", code("这个型号的价格是多少？"))
        assertEquals("zh-Hant", code("這個型號的價格是多少？請問"))
        assertEquals("ja", code("この価格でお願いします"))
        assertEquals("ko", code("가격이 얼마예요?"))
        assertEquals("th", code("ราคาเท่าไหร่ครับ"))
        assertEquals("ru", code("Сколько это стоит?"))
        assertEquals("en", code("Can you send me the price list?"))
        assertEquals("id", code("Apakah barang ini bisa dikirim sekarang?"))
        assertEquals("fr", code("Bonjour, pouvez-vous envoyer le prix?"))
        assertEquals("de", code("Können Sie mir bitte den Preis schicken?"))
        assertEquals("es", code("¿Cuánto cuesta el envío a España?"))
        assertEquals(null, code("Bên em gửi báo giá trước thứ Sáu nhé"))
        assertEquals(null, code("anh oi gia bao nhieu"), "Vietnamese without marks")
        assertEquals(null, code("Chào anh"))
        assertEquals("fr", code("Merci, à demain"))
        assertEquals("en", code("@Đào Ngọc Minh we cannot use this address for the delivery"))
        assertTrue(LanguageDetector.isVietnamese("Cảm ơn anh, mai bên em gửi hàng"))
        assertTrue(!LanguageDetector.isVietnamese("Thank you Minh"))
        val settings = Settings(MemoryStore())
        val target = settings.target(Language.AUTO, listOf(ChatLine(ChatLine.Side.PARTNER, "가격이 얼마예요?", 0)))
        assertEquals("ko", target.code)
        assertEquals("ko", settings.lastPartnerCode)
    }

    @Test
    fun glossaryKeepsTermsAsTheyAre() {
        val entries = Glossary.parse("CNC\nspindle = trục chính\n = bỏ qua\n\nMộc Sinh =")
        assertEquals(listOf(Glossary.Entry("CNC"), Glossary.Entry("spindle", "trục chính"), Glossary.Entry("Mộc Sinh")), entries)
        assertEquals("CNC\nspindle = trục chính\nMộc Sinh", Glossary.format(entries))
    }
}
