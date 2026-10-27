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
import okio.ByteString
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
        /** Sound pieces played (each heard at once). */
        var played = 0
        override fun stop() = Unit
        override fun onAudioThread(block: () -> Unit) = block()
        override fun play(pcm: ByteArray, done: () -> Unit) {
            played += 1
            done()
        }
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
        /** Bytes of sound each connection was sent. */
        val sound = ConcurrentHashMap<WebSocket, Int>()
        /** Connections asked for what is left (the app closing them). */
        val emptied: MutableSet<WebSocket> = Collections.newSetFromMap(ConcurrentHashMap())
        private val targets = ConcurrentHashMap<WebSocket, String>()

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            sound.merge(webSocket, bytes.size, Int::plus)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.isEmpty()) {
                emptied.add(webSocket)
                return
            }
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
        repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(stand)) }
        server.start()
        val settings = Settings(MemoryStore()).apply {
            apiKey = "sk-test"
            sonioxKey = "soniox-test"
            profile = "Minh, mua hàng máy CNC"
        }
        val audio = FakeAudio()
        val folder = Files.createTempDirectory("meetings").toFile()
        val asked = Collections.synchronizedList(ArrayList<Triple<String, SpokenContext, TranslationModel>>())
        val spares = Collections.synchronizedList(ArrayList<String>())
        val fixed = mapOf("Anh gửi giúp em báo giá nhé" to "请把报价发给我。", "我们下周见。" to "Hẹn gặp tuần sau.")
        var warmUps = 0
        val services = ConversationServices(
            transcribe = { _, _, _ -> SpeechServices.Transcript("", null) },
            translate = { _, text, target, context, model, spare ->
                val before = synchronized(asked) { asked.count { it.first == text } }
                asked.add(Triple(text, context, model))
                if (spare) spares.add(text)
                // The first request for a sentence marked STALL hangs, as on a stalled phone network.
                delay(if (text.contains("STALL") && before == 0) 30_000 else 100)
                if (text.contains("FAIL")) throw java.io.IOException("timed out")
                Translation(fixed[text] ?: "[${target.code}] $text", null, target)
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

        // A thought said in two breaths is one sentence: Soniox's end after a comma is a pause.
        room.say(word("如果你们订五百台，", "2"), mark("<end>"))
        waitFor("the paused sentence shown") { model.entries.last().text == "如果你们订五百台，" }
        repeat(6) {
            audio.onSamples!!.invoke(tenth)
            delay(100)
        }
        assertEquals(State.Hearing, model.entries.last().state, "a pause after a comma: the sentence waits for the rest")
        room.say(word("我们可以便宜百分之五。", "2"), mark("<end>"))
        waitFor("the speaker goes on: one sentence, translated whole") {
            model.entries.last().text == "如果你们订五百台，我们可以便宜百分之五。" && model.entries.last().state != State.Hearing
        }
        room.say(word("那个", "1"), mark("<end>"))
        waitFor("a sentence without a full stop shown") { model.entries.last().text == "那个" }
        repeat(6) {
            audio.onSamples!!.invoke(tenth)
            delay(100)
        }
        assertEquals(State.Hearing, model.entries.last().state, "still waiting after 0.6 s")
        repeat(9) {
            audio.onSamples!!.invoke(tenth)
            delay(100)
        }
        waitFor("nothing more said: it ends after a quiet moment") { model.entries.last().text == "那个" && model.entries.last().state != State.Hearing }
        // The speaker goes on after a pause, Soniox settling the new words late: the pause is not ended with them
        // still guessed (they came again as the next sentence once settled).
        room.say(word("如果你们订一千台，", "1"), mark("<end>"))
        waitFor("paused") { model.entries.last().text == "如果你们订一千台，" }
        repeat(3) {
            audio.onSamples!!.invoke(tenth)
            delay(100)
        }
        room.say(word("价格", "1", final = false))
        waitFor("the next words guessed") { model.entries.last().text == "如果你们订一千台，价格" }
        repeat(16) {
            audio.onSamples!!.invoke(tenth)
            delay(100)
        }
        assertEquals(State.Hearing, model.entries.last().state, "a pause with the next words still guessed: not ended")
        room.say(word("价格再低一点。", "1"), mark("<end>"))
        waitFor("one sentence") { model.entries.last().text == "如果你们订一千台，价格再低一点。" }
        assertEquals(1, model.entries.count { it.text.contains("价格再低") }, "the words not twice")
        // A word guessed, then taken back (an empty list): nothing of it is kept.
        room.say(word("嗯", "2", final = false))
        waitFor("a guess shows at once") { model.entries.last().text == "嗯" }
        room.say()
        waitFor("taken back, it is gone (it was kept as a sentence 8 s later)") { model.entries.none { it.text == "嗯" } }

        // GPT's connection stalls: asked again after 2.5 s, the second answer is taken.
        room.say(word("STALL 我们明天发货。", "1"), mark("<end>"))
        waitFor("a stalled GPT asked again", timeout = 4_500) {
            model.entries.lastOrNull()?.translation == "[vi] STALL 我们明天发货。" && model.entries.last().state == State.Done
        }
        assertEquals(2, asked.count { it.first.startsWith("STALL") }, "asked twice, no more")
        assertEquals(listOf("STALL 我们明天发货。"), spares.toList(), "the second time on a connection of its own")
        assertTrue(model.speedLogText.contains("hỏi thêm lần nữa"), "noted in the speed log")

        // GPT fails: asked again a moment later, Soniox's translation still coming in meanwhile (it was kept as it
        // was when GPT failed, cut short); failing again, Soniox's whole translation stays.
        room.say(word("FAIL 这个。", "1"), mark("<end>"))
        room.say(translated("Cái"))
        waitFor("GPT failed once") { model.speedLogText.contains("hỏi lại sau 1,5 s") }
        room.say(translated(" này."))
        waitFor("Soniox's translation still comes in") { model.entries.last().translation == "Cái này." }
        assertEquals(State.Translating, model.entries.last().state, "GPT is asked again")
        waitFor("GPT failed again: Soniox's whole translation kept") {
            model.entries.last().state == State.Done && model.entries.last().translation == "Cái này."
        }
        assertEquals(2, asked.count { it.first == "FAIL 这个。" }, "GPT asked twice")

        // The user speaks: their own session, settled at once when they let go, translated by the
        // app's model with the meeting as context.
        // A partner asks, and the user answers at once: the question ends as the button goes down (Soniox sends no
        // end of sentence while the user talks on: the room's session hears their turn too).
        room.say(word("交货期多长？", "1"))
        waitFor("the partner's question") { model.entries.last().text == "交货期多长？" }
        model.talk(true)
        assertEquals(State.Translating, model.entries.last { it.text == "交货期多长？" }.state, "the partner's question goes to GPT as the user begins")
        val roomBefore = stand.sound[room] ?: 0
        repeat(15) { audio.onSamples!!.invoke(tenth) }
        waitFor("the room's session still hears the room") { (stand.sound[room] ?: 0) - roomBefore >= 15 * 4_800 }
        // The user's voice, heard by the room's session too, is taken out of it; a partner speaking meanwhile is not.
        val turnAt = (stand.sound[room] ?: 0) / 48
        room.say(word("Anh gửi giúp em", "5", "vi", start = turnAt - 1_300, end = turnAt - 800),
            word("等一下。", "1", start = turnAt - 600, end = turnAt - 200), mark("<end>"))
        waitFor("a partner speaking during the user's turn is heard") { model.entries.last().text == "等一下。" && !model.entries.last().mine }
        assertFalse(model.entries.any { !it.mine && it.text.contains("Anh gửi") }, "the user's voice in the room's session is not someone else")
        partner.say(word("Anh gửi giúp em báo giá nhé", "", "vi", start = 300, end = 1_400))
        waitFor("the user's words") { model.entries.lastOrNull()?.mine == true }
        val mineBefore = stand.sound[partner] ?: 0
        model.talk(false)
        repeat(2) { audio.onSamples!!.invoke(tenth) }
        waitFor("the sound said just before letting go still goes to the user's session") { (stand.sound[partner] ?: 0) - mineBefore >= 2 * 4_800 }
        assertEquals(0, stand.finalized["zh"] ?: 0, "and only then are their last words settled")
        waitFor("a moment after letting go") { (stand.finalized["zh"] ?: 0) >= 1 }
        partner.say(translated("请发报价", language = "zh"), mark("<fin>"))
        waitFor("the user's sentence translated") { model.entries.last().mine && model.entries.last().state == State.Done }
        val mine = asked.last { it.first == "Anh gửi giúp em báo giá nhé" }
        assertEquals("User", mine.second.speaker, "GPT is told it is the user's own sentence")
        assertEquals(TranslationModel.SOL, mine.third, "the user's words with the app's model")
        assertEquals("请把报价发给我。", model.entries.last().translation)
        // A partner confirming it in the words the phone read it in is someone speaking: the room's session was not
        // even sent the phone's voice reading the user's words.
        waitFor("the user's words read aloud") { audio.played > 0 }
        room.say(word("好的，我把报价发给你。", "1"), mark("<end>"))
        waitFor("a partner confirming what the phone read is kept") { model.entries.last().text == "好的，我把报价发给你。" }

        val log = model.speedLogText
        assertTrue(log.contains("Người 1") && log.contains("chữ hiện sau") && log.contains("bản dịch đầu tiên sau") &&
            log.contains("từ lúc ngừng nói"), "the speed log has the timings:\n$log")
        assertFalse(log.contains("这个价格") || log.contains("báo giá máy CNC"), "and never what was said")

        model.readAloud = true
        assertEquals(true, audio.echo.last(), "Đọc to with Soniox: echo cancelling on")

        // The phone's own voice heard back while it reads (echo cancelling leaves some): dropped.
        waitFor("everything said so far translated and read") { model.entries.all { it.state == State.Done } }
        delay(300)
        val playedBefore = audio.played
        room.say(word("我们下周见。", "3"), mark("<end>"))
        waitFor("the others' words read aloud") {
            model.entries.any { it.text == "我们下周见。" && it.state == State.Done } && audio.played > playedBefore
        }
        // The microphone's sound goes on meanwhile, as it always does.
        repeat(3) {
            audio.onSamples!!.invoke(tenth)
            delay(50)
        }
        room.say(word("Hẹn gặp tuần sau.", "4", "vi"), mark("<end>"))
        delay(300)
        assertFalse(model.entries.any { it.text == "Hẹn gặp tuần sau." },
            "the phone's voice heard back is not someone speaking: ${model.entries.takeLast(3).map { "${it.text}|${it.translation}|${it.state}" }}\n${model.speedLogText}")

        // The room's session drops in the middle of a sentence: what it settled stays, and the next one is sent again
        // what it had not, and what is said meanwhile.
        repeat(10) { audio.onSamples!!.invoke(tenth) }
        delay(200)
        val dropAt = (stand.sound[room] ?: 0) / 48
        room.say(word("我们的价格", "2", start = dropAt - 900, end = dropAt - 500), word("很好", "2", final = false))
        waitFor("words before the drop") { model.entries.last().text == "我们的价格很好" }
        room.close(1000, "bye")
        waitFor("dropped: its settled words are a sentence, translated") {
            model.entries.any { it.text == "我们的价格" && it.state != State.Hearing }
        }
        waitFor("opened again at once") { stand.sockets["vi"] !== room }
        val again = stand.sockets["vi"]!!
        waitFor("sent again what was not settled") { (stand.sound[again] ?: 0) >= 21_600 }
        delay(200)
        assertTrue((stand.sound[again] ?: 0) <= 33_600, "only that: ${stand.sound[again]} bytes")
        assertTrue(model.speedLogText.contains("gửi lại"), "noted in the speed log")
        again.say(word("很好。", "2"), mark("<end>"))
        waitFor("the words not settled are heard again") { model.entries.last().text == "很好。" }
        // Soniox numbers the new session's speakers anew: the user is now "1", a partner "2". The user's English word
        // is not a partner's, and the partner answering during the turn is.
        again.say(word("好的，没问题。", "2"), mark("<end>"))
        waitFor("a partner in the new session") { model.entries.last().text == "好的，没问题。" }
        val turnSound = stand.sound[again] ?: 0
        model.talk(true)
        repeat(15) { audio.onSamples!!.invoke(tenth) }
        waitFor("the turn's sound sent") { (stand.sound[again] ?: 0) - turnSound >= 15 * 4_800 }
        val anewAt = (stand.sound[again] ?: 0) / 48
        again.say(word("OK", "1", "en", start = anewAt - 1_400, end = anewAt - 1_200), word(" anh", "1", "vi", start = anewAt - 1_100, end = anewAt - 900),
            word("等一下，我问一下。", "2", start = anewAt - 800, end = anewAt - 100), mark("<end>"))
        waitFor("the partner answering during the turn is heard") { model.entries.last().text == "等一下，我问一下。" }
        assertFalse(model.entries.any { !it.mine && it.text == "OK" }, "the user's own English word is not someone else's")
        val zhTurn = stand.finalized["zh"] ?: 0
        partner.say(word("Anh chờ chút", "", "vi", start = 100, end = 1_400))
        model.talk(false)
        waitFor("the user's turn settled") { (stand.finalized["zh"] ?: 0) > zhTurn }
        partner.say(mark("<fin>"))
        waitFor("the user's sentence translated") { model.entries.last().mine && model.entries.last().state == State.Done }

        // Stop while Soniox has not settled the last words: they still come in.
        again.say(word("我们下周", "1"), word("一确认", "1", final = false))
        waitFor("the last words shown") { model.entries.last().text == "我们下周一确认" }
        val zhBefore = stand.finalized["zh"] ?: 0
        model.stop()
        waitFor("stopped: Soniox is asked to settle what it heard") { (stand.finalized["vi"] ?: 0) >= 1 && (stand.finalized["zh"] ?: 0) > zhBefore }
        assertFalse(again in stand.emptied, "and kept open for its answer")
        again.say(word("一确认订单，价格不变。", "1"), mark("<fin>"))
        partner.say(mark("<fin>"))
        waitFor("the last words said before Stop are kept") { model.entries.any { it.text == "我们下周一确认订单，价格不变。" } }
        waitFor("then the sessions close") { again in stand.emptied && partner in stand.emptied }
        waitFor("every sentence finished") { model.entries.all { it.state == State.Done } }
        val lines = model.meeting.lines
        assertEquals(model.entries.size, lines.size, "all in the minutes")
        assertTrue(lines.any { it.speaker == "3" } && model.meeting.transcript.contains("Người 1 · ") &&
            model.meeting.transcript.contains("\nChủ đề: báo giá máy CNC\n"), "the minutes say who spoke and what the meeting was about")
        server.shutdown()
        folder.deleteRecursively()
        Unit
    }
}
