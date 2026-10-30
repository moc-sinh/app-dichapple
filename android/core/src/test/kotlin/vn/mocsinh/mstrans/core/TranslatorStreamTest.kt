package vn.mocsinh.mstrans.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A translation streamed from OpenAI (Translator with `progress`): the server-sent events read as they
 * come (`ResponseStream`), the translation read out of its JSON while it is being written
 * (`Prompt.partialTranslation`), so it can go in the chat before the meaning check after it, and the
 * whole request against a scripted OpenAI: retries, and what a failure costs.
 */
class TranslatorStreamTest {
    private val english = Language.named("en")!!
    private val translation = "Hello, I am sending this month's price again."
    private val meaning = "Chào anh, em gửi lại giá tháng này."
    private val both = buildJsonObject {
        put("translation", translation)
        put(Prompt.MEANING_KEY, meaning)
    }.toString()

    // The translation read while its JSON is written

    @Test
    fun translationReadAsItIsWritten() {
        val answers = listOf(
            "您好，我把本月冷冻榴莲的报价再发给您。\n海防FOB价格为每公斤3.2美元。" to "Chào anh, em gửi lại báo giá sầu riêng đông lạnh tháng này.",
            "He said \"OK\" — 50% off \\ C:\\path\t tab" to null,
            "Emoji 😊👍🏽 and 𠜎 (beyond the first plane)" to "Biểu tượng 😊",
            "" to "trống",
            "  spaces around  " to null,
        )
        var checked = 0
        for ((translation, meaning) in answers) {
            // As the model writes it: one key after the other, in the schema's order.
            val written = buildJsonObject {
                put("translation", translation)
                if (meaning != null) put(Prompt.MEANING_KEY, meaning)
            }.toString()
            // And with the escapes OpenAI uses for anything beyond ASCII now and then.
            val escaped = StringBuilder().apply {
                var k = 0
                while (k < written.length) {
                    val point = written.codePointAt(k)
                    if (point < 0x80 || point > 0xFFFF) appendCodePoint(point) else append("\\u%04x".format(point))
                    k += Character.charCount(point)
                }
            }.toString()
            val utf16Escaped = written.map { if (it.code < 0x80) "$it" else "\\u%04x".format(it.code) }.joinToString("")
            for (variant in listOf(written, escaped, utf16Escaped, written.replace("\":\"", "\" : \""))) {
                var completeAt: Int? = null
                var last = ""
                for (end in 1..variant.length) {
                    val soFar = variant.substring(0, end)
                    val partial = Prompt.partialTranslation(soFar)
                    checked++
                    if (partial == null) {
                        assertTrue(last.isEmpty(), "the translation went away: $soFar")
                        continue
                    }
                    if (!partial.second) {
                        assertTrue(translation.startsWith(partial.first), "partial ${partial.first} of $translation")
                        last = partial.first
                    } else if (completeAt == null) {
                        completeAt = end
                        assertEquals(translation.trim(), partial.first, "whole")
                    }
                }
                // Whole before the meaning check began (which is what saves the wait).
                assertNotNull(completeAt, "never whole: $variant")
                val before = variant.indexOf("\"${Prompt.MEANING_KEY}\"")
                if (before >= 0) assertTrue(completeAt <= before + 1, "whole only after the meaning began")
            }
        }
        assertTrue(checked > 1000, "$checked")

        assertNull(Prompt.partialTranslation(""), "empty")
        assertNull(Prompt.partialTranslation("{"), "brace only")
        assertNull(Prompt.partialTranslation("{\"transla"), "key not whole")
        assertNull(Prompt.partialTranslation("{\"translation\":"), "no value yet")
        assertEquals("" to false, Prompt.partialTranslation("{\"translation\":\""), "value begun")
        assertEquals("ab" to false, Prompt.partialTranslation("{\"translation\":\"ab\\"), "escape not whole")
        assertEquals("ab" to false, Prompt.partialTranslation("{\"translation\":\"ab\\u00"), "unicode escape not whole")
        assertEquals("😊" to true, Prompt.partialTranslation("{\"translation\":\"\\ud83d\\ude0a\""), "surrogate pair")
        assertEquals("" to false, Prompt.partialTranslation("{\"translation\":\"\\ud83d"), "surrogate pair not whole")
        assertEquals("a\uFFFDbcdefg" to true, Prompt.partialTranslation("{\"translation\":\"a\\ud83dbcdefg\""), "half a pair")
        assertEquals("ab" to false, Prompt.partialTranslation("{\"translation\":\"ab\\u00zz"), "not an escape yet")
        assertNull(Prompt.partialTranslation("Xin chào"), "not JSON (a model without the format)")
        // The meaning check's key starts like the translation's: never taken for it.
        assertNull(Prompt.partialTranslation("{\"translation_back_vi\":\"Chào\""), "meaning first is not the translation")
        assertEquals("Hi" to true, Prompt.partialTranslation("{\"translation\":\"Hi\",\"translation_back_vi\":\"Chào\"}"), "both")
    }

    // The events

    private fun delta(text: String, n: Int) = buildJsonObject {
        put("type", "response.output_text.delta")
        put("delta", text)
        put("item_id", "msg_1")
        put("output_index", 1)
        put("content_index", 0)
        put("logprobs", buildJsonArray {})
        put("sequence_number", n)
    }.toString()

    private fun response(status: String, output: String) = buildJsonObject {
        put("status", status)
        put("output", buildJsonArray {
            add(buildJsonObject { put("type", "reasoning"); put("summary", buildJsonArray {}) })
            add(buildJsonObject {
                put("type", "message")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", output) }) })
            })
        })
        putJsonObject("usage") {
            put("input_tokens", 300)
            putJsonObject("input_tokens_details") { put("cached_tokens", 0) }
            put("output_tokens", 40)
        }
    }

    @Test
    fun eventsAsTheyCome() {
        val created = buildJsonObject {
            put("type", "response.created")
            put("sequence_number", 0)
            putJsonObject("response") { put("status", "in_progress") }
        }.toString()
        val finalOutput = "{\"translation\":\"Hello\",\"translation_back_vi\":\"Xin chào\"}"
        val completed = buildJsonObject {
            put("type", "response.completed")
            put("sequence_number", 9)
            put("response", response("completed", finalOutput))
        }.toString()
        val stream = ResponseStream()
        val grown = ArrayList<String>()
        val lines = listOf(
            "event: response.created", "data: $created", "",
            "event: response.output_text.delta", "data: " + delta("{\"translation\":\"Hel", 1), "",
            ": a comment line", "data: " + delta("lo\",\"translation_back_vi\":\"Xin", 2), "",
            "data:" + delta(" chào\"}", 3), "", "event: response.completed", "data: $completed", "",
        )
        for (line in lines) if (stream.feed(line)) grown.add(stream.text)
        assertEquals(listOf("{\"translation\":\"Hel", "{\"translation\":\"Hello\",\"translation_back_vi\":\"Xin", finalOutput), grown)
        assertEquals("completed", stream.response?.get("status").str())
        assertEquals(TokenUsage(300, 0, 40), TokenUsage.from(stream.response!!))
        assertNull(stream.failure)

        val failed = ResponseStream()
        failed.feed("data: " + buildJsonObject {
            put("type", "response.failed")
            putJsonObject("response") {
                put("status", "failed")
                putJsonObject("error") { put("code", "server_error"); put("message", "x") }
            }
        })
        assertEquals("server_error", failed.failure?.get("code").str())
        val errored = ResponseStream()
        errored.feed("data: " + buildJsonObject {
            put("type", "error")
            put("code", "rate_limit_exceeded")
            put("message", "slow down")
            put("param", JsonNull)
        })
        assertEquals("rate_limit_exceeded", errored.failure?.get("code").str())
        val incomplete = ResponseStream()
        incomplete.feed("data: " + buildJsonObject {
            put("type", "response.incomplete")
            putJsonObject("response") { put("status", "incomplete") }
        })
        assertEquals("incomplete", incomplete.response?.get("status").str())
        val junk = ResponseStream()
        assertTrue(!junk.feed("data: [DONE]") && !junk.feed("data: {not json") && junk.response == null, "junk ignored")
    }

    // End to end, against a scripted OpenAI

    /** The events of an answer whose text is `output`, written a few characters at a time. */
    private fun events(output: String, piece: Int = 4, end: String = "response.completed", status: String = "completed"): String {
        val lines = ArrayList<String>()
        lines += listOf("event: response.created", "data: " + buildJsonObject {
            put("type", "response.created")
            putJsonObject("response") { put("status", "in_progress") }
        }, "")
        output.chunked(piece).forEachIndexed { n, chunk ->
            lines += listOf("event: response.output_text.delta", "data: " + delta(chunk, n + 1), "")
        }
        lines += listOf("event: $end", "data: " + buildJsonObject {
            put("type", end)
            put("response", response(status, output))
        }, "")
        return lines.joinToString("\n") + "\n"
    }

    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    /** The stream cut after `at` characters: the connection drops there. */
    private fun dropped(body: String, at: Int): MockResponse {
        val sent = body.substring(0, at)
        return stream(sent)
            .setHeader("Content-Length", sent.toByteArray().size + 1000)
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
    }

    private class Heard {
        val events: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(ArrayList())
        val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())
        fun add(text: String, whole: Boolean) {
            events.add(text to whole)
            threads.add(Thread.currentThread().name)
        }
    }

    private fun withServer(vararg answers: MockResponse, test: suspend (MockWebServer, Translator) -> Unit) = runBlocking {
        val server = MockWebServer()
        answers.forEach { server.enqueue(it) }
        server.start()
        try {
            test(server, Translator("sk-test", TranslationModel.SOL, Settings(MemoryStore()), server.url("/v1/responses").toString()))
        } finally {
            server.shutdown()
        }
    }

    private suspend fun Translator.outgoing(heard: Heard, meaning: Boolean = true) =
        translate("Chào anh, em gửi lại giá tháng này.", english, checkMeaning = meaning) { text, whole -> heard.add(text, whole) }

    @Test
    fun wholeBeforeTheMeaning() = withServer(stream(events(both))) { server, translator ->
        val heard = Heard()
        val result = translator.outgoing(heard)
        assertEquals(translation, result.text)
        assertEquals(meaning, result.meaning)
        assertEquals(300, result.usage?.input)
        val wholeAt = heard.events.indexOfFirst { it.second }
        assertTrue(wholeAt >= 0 && heard.events[wholeAt].first == translation, "whole event: ${heard.events.lastOrNull()}")
        // Nothing heard after the whole translation: the meaning check's deltas came after it.
        val deltas = both.chunked(4).size
        assertEquals(heard.events.size - 1, wholeAt, "only one whole event, the last")
        assertTrue(deltas > heard.events.size + 5, "whole came before the meaning (${heard.events.size} events, $deltas deltas)")
        assertTrue(heard.threads.none { it == Thread.currentThread().name }, "heard on OkHttp's thread")
        val request = server.takeRequest()
        assertEquals("text/event-stream", request.getHeader("Accept"))
        val body = request.body.readUtf8()
        val json = parseObject(body)!!
        assertEquals(true, json["stream"].bool(), "streamed")
        assertNotNull(json["text"].obj()?.get("format"), "JSON format asked")
        // Keys still sorted: the translation comes before its meaning check in the schema.
        assertTrue(body.indexOf("\"store\"") < body.indexOf("\"stream\"") && body.indexOf("\"stream\"") < body.indexOf("\"text\""), body)
        assertTrue(body.indexOf("\"translation\":") < body.indexOf("\"translation_back_vi\":"), body)
    }

    @Test
    fun partnerMessageAsItIsWritten() {
        val output = buildJsonObject { put("translation", "Anh ơi, giá FOB là 3,2 đô một ký.") }.toString()
        withServer(stream(events(output, piece = 3))) { _, translator ->
            val heard = Heard()
            val result = translator.translate("哥，FOB价格是每公斤3.2美元。", Language.vietnamese) { text, whole -> heard.add(text, whole) }
            assertEquals("Anh ơi, giá FOB là 3,2 đô một ký.", result.text)
            val texts = heard.events.map { it.first }
            assertTrue(texts.size > 5 && texts.zipWithNext().all { (a, b) -> b.startsWith(a) }, "grows: $texts")
            assertEquals(listOf(true), heard.events.filter { it.second }.map { it.second })
        }
    }

    @Test
    fun busyServerIsTriedAgain() = withServer(
        MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"busy\"}}"),
        stream(events(both)),
    ) { server, translator ->
        assertEquals(meaning, translator.outgoing(Heard()).meaning)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun dropBeforeTheTranslationIsWholeIsTriedAgain() {
        val body = events(both)
        withServer(dropped(body, body.indexOf("\"delta\"", body.indexOf("\"delta\"") + 1)), stream(body)) { server, translator ->
            assertEquals(meaning, translator.outgoing(Heard()).meaning)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun dropDuringTheMeaningKeepsTheTranslation() {
        val body = events(both)
        withServer(dropped(body, body.indexOf("event: response.completed") - 150)) { server, translator ->
            val heard = Heard()
            val result = translator.outgoing(heard)
            assertEquals(translation, result.text)
            assertNull(result.meaning)
            assertEquals(1, server.requestCount, "not asked again")
            assertTrue(heard.events.last().second)
        }
    }

    @Test
    fun cutOffDuringTheMeaningKeepsTheTranslation() = withServer(
        stream(events(both.substring(0, both.length - 12), end = "response.incomplete", status = "incomplete")),
    ) { _, translator ->
        val result = translator.outgoing(Heard())
        assertEquals(translation, result.text)
        assertNull(result.meaning)
    }

    @Test
    fun cutOffInsideTheTranslationIsNeverHalfATranslation() = withServer(
        stream(events(both.substring(0, 20), end = "response.incomplete", status = "incomplete")),
    ) { _, translator ->
        val error = assertFailsWith<TranslateError> { translator.outgoing(Heard()) }
        assertTrue(error.message!!.contains("quá dài"), error.message)
    }

    @Test
    fun modelWithoutTheFormatIsAskedAgainWithout() = withServer(
        MockResponse().setResponseCode(400).setBody(
            "{\n  \"error\": {\n    \"message\": \"Invalid value for text.format: json_schema\",\n    \"code\": null\n  }\n}",
        ),
        stream(events(translation)),
    ) { server, translator ->
        val heard = Heard()
        assertEquals(translation, translator.outgoing(heard).text)
        server.takeRequest()
        val again = parseObject(server.takeRequest().body.readUtf8())!!
        assertTrue(again["text"] == null && again["stream"].bool() == true, "asked again without the format: $again")
        assertTrue(heard.events.isNotEmpty() && heard.events.none { it.second }, "plain text is never called whole before the end")
    }

    @Test
    fun refusedKeySaysSoAsWithoutStreaming() = withServer(
        MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"Incorrect API key\",\"code\":\"invalid_api_key\"}}"),
    ) { _, translator ->
        val error = assertFailsWith<TranslateError> { translator.outgoing(Heard()) }
        assertEquals("API key không đúng. Kiểm tra lại trong Cài đặt.", error.message)
    }

    @Test
    fun errorEventInTheStream() = withServer(
        stream("data: " + buildJsonObject {
            put("type", "error")
            put("code", "rate_limit_exceeded")
            put("message", "Slow down")
            put("param", JsonNull)
        } + "\n\n"),
    ) { _, translator ->
        val error = assertFailsWith<TranslateError> { translator.outgoing(Heard()) }
        assertTrue(error.message!!.contains("giới hạn"), error.message)
    }

    @Test
    fun failedResponseBeforeAnythingIsTriedAgain() = withServer(
        stream("data: " + buildJsonObject {
            put("type", "response.failed")
            putJsonObject("response") {
                put("status", "failed")
                putJsonObject("error") { put("code", "server_error"); put("message", "x") }
            }
        } + "\n\n"),
        stream(events(both)),
    ) { server, translator ->
        assertEquals(meaning, translator.outgoing(Heard()).meaning)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun withoutProgressNothingIsStreamed() = withServer(
        MockResponse().setBody(response("completed", both).toString()),
    ) { server, translator ->
        assertEquals(meaning, translator.translate("x", english, checkMeaning = true).meaning)
        val request = server.takeRequest()
        assertNull(request.getHeader("Accept")?.takeIf { it.contains("event-stream") })
        assertNull(Json.parse(request.body.readUtf8())["stream"])
    }

    private object Json {
        fun parse(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    }
}
