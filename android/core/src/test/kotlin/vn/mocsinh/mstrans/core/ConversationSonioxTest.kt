package vn.mocsinh.mstrans.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import vn.mocsinh.mstrans.core.conversation.AudioPort
import vn.mocsinh.mstrans.core.conversation.ConversationModel
import vn.mocsinh.mstrans.core.conversation.ConversationModel.State
import vn.mocsinh.mstrans.core.conversation.ConversationServices
import vn.mocsinh.mstrans.core.conversation.MeetingStore
import vn.mocsinh.mstrans.core.conversation.SpeechServices
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A meeting of several people with Soniox: the real model and Soniox session, against a stand-in for
 * Soniox's server; GPT and the phone's sound faked. The same scenes as the iPhone app's simulation.
 */
class ConversationSonioxTest {
    private class FakeAudio : AudioPort {
        override var onSamples: ((ShortArray) -> Unit)? = null
        override var onInterruption: ((Boolean) -> Unit)? = null
        val echo = ArrayList<Boolean>()
        override fun start(echoCancelling: Boolean) {
            echo.add(echoCancelling)
        }
        override fun setEchoCancelling(on: Boolean) {
            echo.add(on)
        }
        override fun stop() = Unit
        override fun onAudioThread(block: () -> Unit) = block()
        override fun play(pcm: ByteArray, done: () -> Unit) = done()
        override val backlog: Double = 0.0
        override fun setRate(rate: Float) = Unit
        override fun stopPlayback() = Unit
        override fun playAlone(pcm: ByteArray, done: () -> Unit) = done()
        override fun stopAlone() = Unit
    }

    /** Soniox's side of each connection, found by the language it translates into. */
    private class Stand : WebSocketListener() {
        val sockets = ConcurrentHashMap<String, WebSocket>()
        val configs = ConcurrentHashMap<String, JsonObject>()
        val finalized = ConcurrentHashMap<String, Int>()
        private val targets = ConcurrentHashMap<WebSocket, String>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.isEmpty()) return
            val o = Json.parseToJsonElement(text).jsonObject
            if (o["type"]?.jsonPrimitive?.content == "finalize") {
                targets[webSocket]?.let { finalized.merge(it, 1, Int::plus) }
                return
            }
            val target = o["translation"]?.jsonObject?.get("target_language")?.jsonPrimitive?.content ?: return
            configs[target] = o
            targets[webSocket] = target
            sockets[target] = webSocket
        }
    }

    private fun word(text: String, speaker: String, language: String = "zh", final: Boolean = true, start: Int? = null, end: Int? = null) =
        buildJsonObject {
            put("text", text)
            put("is_final", final)
            put("language", language)
            if (speaker.isNotEmpty()) put("speaker", speaker)
            put("translation_status", "original")
            start?.let { put("start_ms", it) }
            end?.let { put("end_ms", it) }
        }

    private fun translated(text: String, final: Boolean = true, language: String = "vi") = buildJsonObject {
        put("text", text)
        put("is_final", final)
        put("language", language)
        put("translation_status", "translation")
    }

    private fun mark(text: String) = buildJsonObject {
        put("text", text)
        put("is_final", true)
    }

    private fun WebSocket.say(vararg tokens: JsonObject) {
        send(buildJsonObject { put("tokens", JsonArray(tokens.toList())) }.toString())
    }

    private suspend fun waitFor(what: String, timeout: Long = 5_000, ok: () -> Boolean) {
        try {
            withTimeout(timeout) { while (!ok()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("waited for: $what", e)
        }
    }

    @Test
    fun meetingOfSeveralPeople() = runBlocking {
        val server = MockWebServer()
        val stand = Stand()
        repeat(4) { server.enqueue(MockResponse().withWebSocketUpgrade(stand)) }
        server.start()
        val settings = Settings(MemoryStore()).apply {
            apiKey = "sk-test"
            sonioxKey = "soniox-test"
            profile = "Minh, mua hàng máy CNC"
        }
        val audio = FakeAudio()
        val folder = Files.createTempDirectory("meetings").toFile()
        val asked = Collections.synchronizedList(ArrayList<Triple<String, SpokenContext, TranslationModel>>())
        var warmUps = 0
        val services = ConversationServices(
            transcribe = { _, _, _ -> SpeechServices.Transcript("", null) },
            translate = { _, text, target, context, model ->
                val before = synchronized(asked) { asked.count { it.first == text } }
                asked.add(Triple(text, context, model))
                // The first request for a sentence marked STALL hangs, as on a stalled phone network.
                delay(if (text.contains("STALL") && before == 0) 30_000 else 100)
                Translation("[${target.code}] $text", null, target)
            },
            speak = { _, _, _, _, _ -> ByteArray(4_800) },
            warmUp = { warmUps += 1 },
        )
        val model = ConversationModel(settings, MemoryStore(), audio, MeetingStore(folder), this, services)
        model.engine = ConversationModel.Engine.SONIOX
        model.readAloud = false
        model.partnerCode = "zh"
        model.voice = ConversationModel.PHONE_VOICE
        model.sonioxUrl = server.url("/").toString().replaceFirst("http", "ws")
        model.setTopic("  báo giá máy CNC ")
        model.start()
        assertTrue(model.running, "listening")
        assertEquals(listOf(false), audio.echo, "Soniox in Chỉ chữ: no echo cancelling, the microphone hears far voices")
        assertTrue(warmUps >= 1, "OpenAI's connection is opened as listening starts")
        waitFor("both Soniox sessions") { stand.sockets.size == 2 }
        val room = stand.sockets["vi"]!!
        val partner = stand.sockets["zh"]!!
        val config = stand.configs["vi"]!!
        assertTrue(config["enable_speaker_diarization"]!!.jsonPrimitive.boolean, "the room's session tells speakers apart")
        assertTrue(config["context"].toString().contains("\"topic\"") && config["context"].toString().contains("báo giá máy CNC") &&
            config["context"].toString().contains("Minh, mua hàng máy CNC"), "Soniox is told the topic and who the user is: ${config["context"]}")
        assertFalse(stand.configs["zh"]!!.containsKey("enable_speaker_diarization"), "the user's turn is one person")

        // Three seconds of the room's sound go to Soniox (all of it, quiet too).
        val tenth = ShortArray(2_400)
        repeat(30) { audio.onSamples!!.invoke(tenth) }
        waitFor("the room's sound sent") { model.spent > 0 }

        // Speaker 1, then their translation, then speaker 2 cuts in: Soniox sends each piece of
        // translation right after the words it translates.
        room.say(word("这个价格", "1", start = 1_000, end = 1_500), word("可以吗？", "1", start = 1_600, end = 1_900))
        waitFor("speaker 1's words") { model.entries.size == 1 && model.entries[0].speaker == "1" && model.entries[0].state == State.Hearing }
        assertTrue(warmUps >= 2, "someone starts speaking: OpenAI's connection is made ready for the end of the sentence")
        room.say(translated("Giá này"), translated(" được không?", final = false))
        waitFor("speaker 1's translation as it comes") { model.entries[0].translation == "Giá này được không?" }
        room.say(translated(" được không?"), word("好的", "2", start = 2_100, end = 2_400))
        waitFor("speaker 2's sentence") { model.entries.size == 2 }
        assertEquals(State.Translating, model.entries[0].state, "speaker 1's sentence goes to GPT")
        assertEquals("Giá này được không?", model.entries[0].translation, "Soniox's translation shows meanwhile")
        room.say(translated("Được."))
        waitFor("speaker 2's own translation, at once") { model.entries[1].translation == "Được." }
        assertFalse(model.entries[0].translation.contains("Được."), "not on speaker 1's sentence")
        waitFor("GPT's translation of speaker 1") {
            model.entries[0].state == State.Done && model.entries[0].translation == "[vi] 这个价格可以吗？"
        }
        assertEquals(TranslationModel.LUNA, asked.first().third, "the others' words with the economy model")

        // Speaker 3 ends with Soniox's end of sentence: GPT is told who said it, what was said before
        // and how it was translated, and what the meeting is about.
        room.say(word("那我们下周一确认订单。", "3", start = 2_800, end = 3_800), mark("<end>"))
        waitFor("speaker 3 sent to GPT") { asked.any { it.first == "那我们下周一确认订单。" } }
        val third = asked.first { it.first == "那我们下周一确认订单。" }.second
        assertEquals("Người 3", third.speaker)
        assertEquals("báo giá máy CNC", third.topic)
        assertEquals(listOf("Người 1: 这个价格可以吗？ → [vi] 这个价格可以吗？", "Người 2: 好的"),
            third.lines.map { "${it.speaker}: ${it.text}" + if (it.translation.isEmpty()) "" else " → ${it.translation}" })
        waitFor("speaker 3 translated") { model.entries.size == 3 && model.entries[2].state == State.Done }

        // GPT's connection stalls: asked again after 2.5 s, the second answer is taken.
        room.say(word("STALL 我们明天发货。", "1"), mark("<end>"))
        waitFor("a stalled GPT asked again", timeout = 4_500) {
            model.entries.lastOrNull()?.translation == "[vi] STALL 我们明天发货。" && model.entries.last().state == State.Done
        }
        assertEquals(2, asked.count { it.first.startsWith("STALL") }, "asked twice, no more")
        assertTrue(model.speedLogText.contains("hỏi thêm lần nữa"), "noted in the speed log")

        // The user speaks: their own session, settled at once when they let go, translated by the
        // app's model with the meeting as context.
        model.talk(true)
        repeat(15) { audio.onSamples!!.invoke(tenth) }
        partner.say(word("Anh gửi giúp em báo giá nhé", "", "vi", start = 300, end = 1_400))
        waitFor("the user's words") { model.entries.lastOrNull()?.mine == true }
        model.talk(false)
        waitFor("letting go settles the user's last words") { (stand.finalized["zh"] ?: 0) >= 1 }
        partner.say(translated("请发报价", language = "zh"), mark("<fin>"))
        waitFor("the user's sentence translated") { model.entries.last().mine && model.entries.last().state == State.Done }
        val mine = asked.last { it.first == "Anh gửi giúp em báo giá nhé" }
        assertEquals("User", mine.second.speaker, "GPT is told it is the user's own sentence")
        assertEquals(TranslationModel.SOL, mine.third, "the user's words with the app's model")
        assertEquals("[zh] Anh gửi giúp em báo giá nhé", model.entries.last().translation)

        val log = model.speedLogText
        assertTrue(log.contains("Người 1") && log.contains("chữ hiện sau") && log.contains("bản dịch đầu tiên sau") &&
            log.contains("từ lúc ngừng nói"), "the speed log has the timings:\n$log")
        assertFalse(log.contains("这个价格") || log.contains("báo giá máy CNC"), "and never what was said")

        model.readAloud = true
        assertEquals(true, audio.echo.last(), "Đọc to with Soniox: echo cancelling on")
        model.stop()
        assertTrue(model.entries.all { it.state == State.Done }, "every sentence finished: ${model.entries.map { it.state }}")
        val lines = model.meeting.lines
        assertEquals(model.entries.size, lines.size, "all in the minutes")
        assertTrue(lines.any { it.speaker == "3" } && model.meeting.transcript.contains("Người 1 · ") &&
            model.meeting.transcript.contains("\nChủ đề: báo giá máy CNC\n"), "the minutes say who spoke and what the meeting was about")
        server.shutdown()
        folder.deleteRecursively()
        Unit
    }
}
