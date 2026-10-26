// The Hội thoại tab's pure parts, the same checks as the iPhone app's Tests/Conversation/main.swift.
package vn.mocsinh.mstrans.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import vn.mocsinh.mstrans.core.conversation.CostMeter
import vn.mocsinh.mstrans.core.conversation.EchoGuard
import vn.mocsinh.mstrans.core.conversation.GoogleVoice
import vn.mocsinh.mstrans.core.conversation.LiveEvent
import vn.mocsinh.mstrans.core.conversation.LiveCaptions
import vn.mocsinh.mstrans.core.conversation.LiveGate
import vn.mocsinh.mstrans.core.conversation.MyVoiceFilter
import vn.mocsinh.mstrans.core.conversation.LiveSession
import vn.mocsinh.mstrans.core.conversation.PCM
import vn.mocsinh.mstrans.core.conversation.SonioxEvent
import vn.mocsinh.mstrans.core.conversation.SonioxToken
import vn.mocsinh.mstrans.core.conversation.SonioxTranscript
import vn.mocsinh.mstrans.core.conversation.SpeechSegmenter
import vn.mocsinh.mstrans.core.conversation.SpeechServices
import vn.mocsinh.mstrans.core.conversation.TranscriptFilter
import vn.mocsinh.mstrans.core.conversation.VoiceCache
import vn.mocsinh.mstrans.core.conversation.VoiceStream
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.fail

class Checks {
    private val failures = ArrayList<String>()
    fun check(ok: Boolean, what: String) {
        if (!ok) failures.add(what)
    }

    fun done() {
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}

/** Room noise and speech-like sound, deterministic. */
class Sound {
    private var state = 7UL
    private fun next(): Double {
        state = state * 6364136223846793005UL + 1442695040888963407UL
        return ((state shr 11).toLong() % 2000) / 1000.0 - 1
    }

    fun quiet(seconds: Double, db: Double = -62.0): ShortArray {
        val amplitude = 10.0.pow(db / 20) * 32768 * 1.7
        return ShortArray((seconds * 24_000).toInt()) { (next() * amplitude).coerceIn(-32767.0, 32767.0).toInt().toShort() }
    }

    /** A voice whose loudness rises and falls four times a second (syllables), over room noise. */
    fun speech(seconds: Double, db: Double = -26.0): ShortArray {
        val amplitude = 10.0.pow(db / 20) * 32768 * 1.41
        val floor = quiet(seconds)
        return ShortArray(floor.size) { i ->
            val t = i / 24_000.0
            val syllable = maxOf(0.08, abs(sin(PI * 4 * t)))
            val voice = sin(2 * PI * 180 * t) * amplitude * syllable
            (voice + floor[i]).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }
}

operator fun ShortArray.plus(other: ShortArray): ShortArray {
    val out = copyOf(size + other.size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}

class ConversationPartsTest {
    private val sound = Sound()
    private fun quiet(s: Double, db: Double = -62.0) = sound.quiet(s, db)
    private fun speech(s: Double, db: Double = -26.0) = sound.speech(s, db)

    private fun segments(samples: ShortArray, silence: Double = 0.7, maxLength: Double = 15.0, chunk: Int = 2_400): List<SpeechSegmenter.Utterance> {
        val segmenter = SpeechSegmenter(silence, maxLength)
        val found = ArrayList<SpeechSegmenter.Utterance>()
        var offset = 0
        while (offset < samples.size) {
            found += segmenter.push(samples.copyOfRange(offset, minOf(samples.size, offset + chunk)))
            offset += chunk
        }
        segmenter.flush()?.let { found.add(it) }
        return found
    }

    @Test
    fun segmenter() {
        val c = Checks()
        val found = segments(quiet(1.0) + speech(2.0) + quiet(1.2) + speech(1.5) + quiet(1.0))
        c.check(found.size == 2, "two sentences give two utterances, got ${found.size}")
        if (found.size == 2) {
            c.check(abs(found[0].start - 0.7) < 0.12, "first starts with 0.3 s before speech, got ${found[0].start}")
            c.check(found[0].duration > 2 && found[0].duration < 2.8, "first holds its speech, got ${found[0].duration}")
            c.check(abs(found[1].start - 3.9) < 0.15, "second starts before its speech, got ${found[1].start}")
        }
        c.check(segments(quiet(1.0) + speech(1.0) + quiet(0.4) + speech(1.0) + quiet(1.0)).size == 1, "a 0.4 s pause does not end a sentence")
        c.check(segments(quiet(1.0) + speech(1.0) + quiet(0.5) + speech(1.0) + quiet(1.0), silence = 0.4).size == 2,
            "with 0.4 s set, a 0.5 s pause ends it")
        c.check(segments(quiet(5.0)).isEmpty(), "a quiet room gives nothing")
        c.check(segments(quiet(6.0, -38.0)).isEmpty(), "a noisy room gives nothing")
        c.check(segments(quiet(1.0) + speech(0.1) + quiet(1.0)).isEmpty(), "a click is not speech")
        c.check(segments(quiet(1.0, -40.0) + speech(1.5, -22.0) + quiet(1.0, -40.0)).size == 1, "speech over a noisy room is heard")
        c.check(segments(quiet(1.0) + speech(1.5, -42.0) + quiet(1.0)).size == 1, "a far voice is heard")
        // A voice too quiet to begin an utterance over a noisy room is still sound above it: Dịch liền sends it on.
        val far = SpeechSegmenter()
        far.push(quiet(3.0, -50.0))
        c.check(!far.audible && !far.speaking, "the room's own noise is neither")
        val voice = speech(2.0, -42.0)
        val room = quiet(2.0, -50.0)
        val farVoice = ShortArray(voice.size) { (voice[it] + room[it]).coerceIn(-32768, 32767).toShort() }
        var audible = 0
        var speaking = 0
        for (start in farVoice.indices step 2_400) {
            far.push(farVoice.copyOfRange(start, minOf(farVoice.size, start + 2_400)))
            if (far.audible) audible += 1
            if (far.speaking) speaking += 1
        }
        c.check(speaking == 0 && audible >= 16, "a far, quiet voice: audible $audible tenths of 20, an utterance $speaking")
        far.push(quiet(1.0, -50.0))
        c.check(!far.audible, "and quiet again after it")

        val talk = speech(7.0) + quiet(0.25) + speech(6.0) + quiet(0.25) + speech(9.0) + quiet(1.0)
        val long = segments(quiet(0.5) + talk, maxLength = 12.0)
        c.check(long.size >= 2, "a 22 s talk is cut, got ${long.size}")
        c.check(long.size >= 2 && abs(long[1].start - 7.75) < 0.3, "cut at the breath, got ${if (long.size >= 2) long[1].start else -1}")
        c.check(long.all { it.duration <= 12.05 }, "no piece longer than the limit")
        c.check(long.dropLast(1).all { it.duration >= 3 }, "no tiny piece")
        val total = long.sumOf { it.duration }
        c.check(total > 21 && total < 24, "nothing lost when cut, got $total")

        val held = speech(16.0) + quiet(0.3) + speech(4.0)
        val seconds = SpeechSegmenter.split(held) / 24_000.0
        c.check(seconds > 16 && seconds < 16.35, "a long turn split at the breath, got $seconds")
        c.check(SpeechSegmenter.split(shortArrayOf(1, 2, 3)) == 3, "a scrap of sound is not split")

        val muted = SpeechSegmenter()
        muted.push(quiet(1.0))
        muted.push(speech(1.0))
        c.check(muted.mute() != null, "speech in progress goes out on mute")
        c.check(muted.push(speech(2.0) + quiet(1.0)).isEmpty(), "nothing heard while muted")
        muted.unmute()
        c.check(muted.push(speech(1.2) + quiet(1.0)).size == 1, "heard again after unmute")

        // The pause that ends a sentence gets shorter as someone talks on: a room that never pauses long
        // is cut at its breaths, not every ten seconds.
        val paused = SpeechSegmenter(0.8)
        c.check(paused.pause(2.0) == 0.8 && abs(paused.pause(5.5) - 0.575) < 1e-9 && abs(paused.pause(12.0) - 0.35) < 1e-9,
            "the pause: as set, then down to 0.35 s at eight seconds")
        c.check(SpeechSegmenter(0.3).pause(12.0) == 0.3, "never longer than the one set")
        c.check(segments(quiet(1.0) + speech(9.0) + quiet(0.5) + speech(2.0) + quiet(1.0), silence = 0.8).size == 2,
            "after nine seconds, a breath of half a second ends the sentence")
        c.check(segments(quiet(1.0) + speech(2.0) + quiet(0.5) + speech(2.0) + quiet(1.0), silence = 0.8).size == 1,
            "after two seconds, it does not")

        c.check(PCM.wav(shortArrayOf(1, -2, 3)).size == 44 + 6, "WAV header is 44 bytes")
        c.check(PCM.samples(PCM.data(shortArrayOf(1, -2, 32767, -32768))).contentEquals(shortArrayOf(1, -2, 32767, -32768)), "PCM round trip")
        c.done()
    }

    @Test
    fun phantomTextAndEcho() {
        val c = Checks()
        for (text in listOf(
            "Cảm ơn các bạn đã theo dõi và hẹn gặp lại", "Hãy subscribe cho kênh Ghiền Mì Gõ để không bỏ lỡ",
            "Thanks for watching!", "字幕由Amara.org社区提供", "ご視聴ありがとうございました",
            "ha ha ha ha ha ha ha ha", "的的的的的的的的的的的的的的", "...", "",
        )) c.check(TranscriptFilter.isNoise(text, 3.0), "noise: $text")
        for (text in listOf(
            "Bên em gửi báo giá trước thứ Sáu nhé", "这个型号的价格是多少？", "Thank you, that works for us.",
            "OK", "Cảm ơn anh", "Can we move the delivery to next week?", "kênh phân phối của bên anh thế nào",
        )) c.check(!TranscriptFilter.isNoise(text, 3.0), "real: $text")
        c.check(TranscriptFilter.isImplausiblyFast("một hai ba bốn năm sáu bảy tám chín mười", 0.8), "ten words in 0.8 s")
        c.check(!TranscriptFilter.isImplausiblyFast("một hai ba bốn năm sáu bảy tám chín mười", 3.0), "ten words in 3 s")
        c.check(TranscriptFilter.syllableCount("这个型号 OK") == 5, "Chinese counts characters")

        val guard = EchoGuard()
        guard.spoke("We will send the quotation before Friday.", 10.0)
        guard.stopped(13.0)
        c.check(guard.isEcho("we will send the quotation before friday", 10.3, 13.2), "the phone's sentence heard back")
        c.check(!guard.isEcho("Can you lower the price a little?", 11.0, 13.0), "someone else speaking over it")
        c.check(!guard.isEcho("OK, so you send the quotation before Friday?", 15.0, 17.0),
            "said after the phone stopped: someone repeating it, to confirm")
        guard.spoke("我们周五之前发报价", 40.0)
        c.check(guard.isEcho("我们周五前发报价。", 40.2, 41.5), "Chinese echo by characters, the phone still speaking")
        c.check(!guard.isEcho("价格太高了", 40.5, 41.5), "Chinese reply is not echo")
        guard.stopped(42.0)
        c.check(guard.isEcho("周五前发报价", 42.5, 43.4), "a moment after the phone stopped: still its echo")
        c.check(!guard.isEcho("好的，周五之前发报价。", 44.0, 46.0), "a partner confirming it afterwards is kept")
        // By pairs of words: a colleague's sentence of common words is not the phone's echo.
        guard.spoke("Chúng tôi sẽ gửi báo giá cho anh trước thứ Sáu và giao hàng trong hai tuần.", 50.0)
        guard.stopped(54.0)
        guard.spoke("Giá này là giá tốt nhất của chúng tôi rồi.", 54.0)
        guard.stopped(57.0)
        c.check(guard.isEcho("giá này là giá tốt nhất của chúng tôi", 54.5, 57.0), "the phone's Vietnamese heard back")
        c.check(guard.isEcho("chúng tôi sẽ gửi báo giá cho anh trước thứ bảy và giao hàng trong hai tuần", 50.2, 54.0),
            "heard back with a word misheard")
        c.check(!guard.isEcho("Anh ơi giá của chúng tôi là tốt rồi", 55.0, 57.0), "a colleague's sentence of common words is not echo")
        c.check(!guard.isEcho("Cảm ơn", 56.0, 56.5) && guard.isEcho("giá tốt", 56.0, 56.5), "a word or two: all said by the phone")
        c.check(!guard.isEcho("giá tốt", 60.0, 60.5), "but not after it")
        guard.spoke("这个价格可以吗？", 60.0)
        guard.stopped(61.0)
        c.check(!guard.isEcho("这个呢", 60.2, 61.0) && guard.isEcho("这个价格", 60.2, 61.0), "a short sentence: all of it said by the phone")
        c.check(TranscriptFilter.isImplausiblyFast("我们这个价格可以吗", 0.5) && !TranscriptFilter.isImplausiblyFast("我们这个价格可以吗", 1.2),
            "a fast Chinese speaker (eight a second) is not noise")
        c.done()
    }

    @Test
    fun liveEventsAndServices() {
        val c = Checks()
        c.check(LiveEvent.parse("""{"type":"session.created","session":{}}""") == LiveEvent.Ready, "session.created")
        c.check(LiveEvent.parse("""{"type":"session.input_transcript.delta","delta":"你好"}""") == LiveEvent.Source("你好"), "source delta")
        c.check(LiveEvent.parse("""{"type":"session.output_transcript.delta","delta":"Xin chào"}""") == LiveEvent.Target("Xin chào"), "target delta")
        val audio = LiveEvent.parse("""{"type":"session.output_audio.delta","delta":"AQACAA=="}""")
        c.check(audio is LiveEvent.Audio && audio.pcm.contentEquals(byteArrayOf(1, 0, 2, 0)), "audio delta")
        c.check(LiveEvent.parse("""{"type":"error","error":{"code":"invalid_api_key","message":"Bad key"}}""") ==
            LiveEvent.Failed("invalid_api_key", "Bad key"), "error")
        c.check(LiveEvent.parse("""{"type":"session.closed"}""") == LiveEvent.Closed, "closed")
        c.check(LiveEvent.parse("not json") == null, "garbage ignored")
        c.check(LiveEvent.parse("""{"type":"something.new"}""") == null, "unknown events ignored")
        c.check(LiveEvent.appendMessage(PCM.data(shortArrayOf(1, 2))) == """{"type":"session.input_audio_buffer.append","audio":"AQACAA=="}""",
            "append message")
        c.check(runCatching { Json.parseToJsonElement(LiveEvent.languageMessage("vi")) }.isSuccess, "language message is JSON")
        c.check(LiveSession.outputCode(Language.named("zh-Hant")!!) == "zh" && LiveSession.outputCode(Language.named("th")!!) == null,
            "live output languages")

        c.check(SpeechServices.normalized("zh-TW") == "zh-Hant" && SpeechServices.normalized("vi-VN") == "vi" &&
            SpeechServices.normalized("EN") == "en", "language codes")
        c.check(Prompt.spoken("English").contains("speech recognition"), "spoken prompt")
        c.check(Prompt.spoken("Vietnamese").contains("repeats or confirms") && !Prompt.spoken("Vietnamese").contains("never repeat"),
            "a number confirmed again is translated again, not dropped as said before")
        val meeting = Prompt.meeting(Settings(MemoryStore()))
        c.check(meeting.contains("what one participant said") && !meeting.contains("written by the partner"),
            "the others' words in a meeting: said by one of several people, not a message to the user")
        // A meeting's sentence: who said it, and the sentences before it with who said them and how
        // they were translated; the topic goes with the instructions.
        val prompt = Prompt.spoken("Vietnamese", " báo giá máy CNC ")
        c.check(prompt.endsWith("\nThe meeting is about: báo giá máy CNC") && prompt.contains("never summarise"),
            "the meeting's topic is told, and nothing is to be left out")
        c.check(!Prompt.spoken("Vietnamese").contains("about:"), "no topic, none told")
        val spoken = SpokenContext(
            SpokenContext.speaker(false, "2"), listOf(
                SpokenContext.Line(SpokenContext.speaker(false, "1"), "这个价格\n可以吗？", "Giá này được không?"),
                SpokenContext.Line(SpokenContext.speaker(true, ""), "Được anh", "好的"),
                SpokenContext.Line(SpokenContext.speaker(false, ""), "那我们", ""),
            ), "x",
        )
        c.check(Prompt.spokenInput("下周一确认。", spoken) ==
            "<context>\nNgười 1: 这个价格 可以吗？ → Giá này được không?\nUser: Được anh → 好的\nParticipant: 那我们\n</context>\n" +
            "<message speaker=\"Người 2\">\n下周一确认。\n</message>", "the meeting's sentences, who said them and their translations")
        c.check(Prompt.spokenInput("好", SpokenContext()) == "<message speaker=\"Participant\">\n好\n</message>", "the first sentence: no context")
        c.check(SpeechServices.voiceInstructions(Language.vietnamese).contains("Vietnamese accent"), "the voice is told the language")
        c.check(PCM.wav(PCM.data(shortArrayOf(1, -2, 3))).contentEquals(PCM.wav(shortArrayOf(1, -2, 3))), "WAV around PCM bytes")
        c.check(PCM.seconds(ByteArray(48_000)) == 1.0, "a second of PCM")

        val body = Json.parseToJsonElement(GoogleVoice.requestBody("Giá này bao nhiêu?", GoogleVoice.FEMALE, "vi-VN")).jsonObject
        val voice = body["voice"]!!.jsonObject
        val config = body["audioConfig"]!!.jsonObject
        c.check(body["input"]!!.jsonObject["text"]!!.jsonPrimitive.content == "Giá này bao nhiêu?", "Google: the sentence")
        c.check(voice["languageCode"]!!.jsonPrimitive.content == "vi-VN" &&
            voice["name"]!!.jsonPrimitive.content == "vi-VN-Chirp3-HD-Aoede", "Google: Chirp 3 HD voice")
        c.check(config["audioEncoding"]!!.jsonPrimitive.content == "LINEAR16" && config["sampleRateHertz"]!!.jsonPrimitive.int == 24_000,
            "Google: 24 kHz PCM16")
        c.check(GoogleVoice.locale("zh") == "cmn-CN" && GoogleVoice.locale("zh-Hant") == "cmn-CN" && GoogleVoice.locale("ms") == null,
            "Google: Mandarin for both scripts, no Malay voice")
        c.check(GoogleVoice.nameOf(GoogleVoice.id(GoogleVoice.MALE)) == "Charon" && GoogleVoice.nameOf("marin") == null &&
            GoogleVoice.nameOf("phone") == null, "Google: voice setting")

        val samples = PCM.data(shortArrayOf(1, -2, 3, 300))
        val answer = """{"audioContent":"${Base64.getEncoder().encodeToString(PCM.wav(samples))}"}"""
        c.check(GoogleVoice.pcmFromAnswer(answer)?.contentEquals(samples) == true, "Google: the WAV header is taken off")
        c.check(PCM.bodyOfWav(samples + byteArrayOf(9)).contentEquals(samples), "bytes that are not WAV are taken as PCM16")
        val unsized = PCM.wav(samples)
        for (i in 40 until 44) unsized[i] = 0xFF.toByte()
        c.check(PCM.bodyOfWav(unsized).contentEquals(samples), "a WAV file without its length")
        c.check(GoogleVoice.pcmFromAnswer("{}") == null, "Google: no voice in the answer")

        fun kind(status: Int, json: String) = GoogleVoice.failure(status, json).kind
        val badKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"""
        c.check(kind(400, badKey) == GoogleVoice.Failure.Kind.BAD_KEY, "Google: a wrong key")
        c.check(kind(403, """{"error":{"code":403,"message":"Cloud Text-to-Speech API has not been used in project 1 before or it is disabled.","status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}""") ==
            GoogleVoice.Failure.Kind.NOT_ENABLED, "Google: the API not turned on")
        c.check(kind(403, """{"error":{"code":403,"message":"This API method requires billing to be enabled.","details":[{"reason":"BILLING_DISABLED"}]}}""") ==
            GoogleVoice.Failure.Kind.BILLING, "Google: no billing")
        c.check(kind(429, "{}") == GoogleVoice.Failure.Kind.QUOTA && kind(503, "") == GoogleVoice.Failure.Kind.BUSY, "Google: too many, busy")
        c.check(kind(400, """{"error":{"code":400,"message":"Voice 'ms-MY-Chirp3-HD-Aoede' does not exist."}}""") ==
            GoogleVoice.Failure.Kind.UNSUPPORTED, "Google: a language without the voice")
        val refused = GoogleVoice.failure(400, """{"error":{"code":400,"message":"Input is too long."}}""")
        c.check(refused.kind == GoogleVoice.Failure.Kind.REFUSED && refused.detail == "Input is too long.", "Google: anything else as Google said it")
        c.done()
    }

    @Test
    fun voices() {
        val c = Checks()
        val cache = VoiceCache(limit = 10)
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        c.check(cache.get("a")?.size == 4, "a voice made before is played again")
        cache.put("c", ByteArray(4))
        c.check(cache.get("b") == null && cache.get("a") != null && cache.get("c") != null, "the oldest unused goes first")
        cache.put("huge", ByteArray(11))
        c.check(cache.get("huge") == null && cache.count == 2, "a voice larger than the limit is not kept")
        cache.put("a", ByteArray(6))
        c.check(cache.get("a")?.size == 6 && cache.count == 2, "made again, replaced")
        cache.put("d", ByteArray(5))
        c.check(cache.get("c") == null && cache.get("a") == null && cache.get("d") != null, "over the limit, the oldest go")

        val stream = VoiceStream()
        stream.append(byteArrayOf(1, 2, 3))
        c.check(stream.sound(0).pcm.contentEquals(byteArrayOf(1, 2, 3)) && !stream.sound(0).finished, "the first part, still coming")
        stream.append(byteArrayOf(4, 5))
        c.check(stream.sound(2).pcm.contentEquals(byteArrayOf(3, 4, 5)) && stream.sound(9).pcm.isEmpty(), "what came after what was played")
        stream.finish(byteArrayOf(1, 2, 3, 4, 5, 6))
        stream.append(byteArrayOf(6))
        val end = stream.sound(4)
        c.check(end.pcm.contentEquals(byteArrayOf(5, 6)) && end.finished && end.failure == null, "finished, with the rest")
        val failed = VoiceStream()
        failed.append(byteArrayOf(7, 8))
        failed.finish(null, TranslateError.network())
        c.check(failed.sound(0).pcm.contentEquals(byteArrayOf(7, 8)) && failed.sound(0).failure != null, "cut short: what came, and why")
        c.check(VoiceStream(byteArrayOf(9)).sound(0).finished, "a voice made before is all there")
        val waiting = VoiceStream()
        Thread {
            waiting.append(byteArrayOf(1, 1))
            Thread.sleep(50)
            waiting.finish(byteArrayOf(1, 1, 2, 2))
        }.start()
        val whole = runBlocking { waiting.whole() }
        c.check(whole.getOrNull()?.contentEquals(byteArrayOf(1, 1, 2, 2)) == true, "all of it, once made")
        c.done()
    }

    /** Dịch liền's captions: cut into sentences in a room that talks on, each with its own translation. */
    @Test
    fun liveCaptions() {
        val c = Checks()
        val captions = LiveCaptions()
        c.check(captions.heard("这个价格", 0.0) == null && captions.heard("可以便宜一点吗？", 3.2) == null &&
            captions.current?.text == "这个价格可以便宜一点吗？", "a caption grows as it is said")
        c.check(captions.translated("Giá này", 3.5) == 1 && captions.current?.translation == "Giá này", "its translation too")
        val ended = captions.heard("我们", 4.0)
        c.check(ended?.text == "这个价格可以便宜一点吗？" && captions.current?.text == "我们", "the next words after a full stop: a new caption")
        c.check(captions.translated(" có bớt được không?", 4.3) == 1 && captions.takeReleased().isEmpty(),
            "the rest of its translation goes to the caption it belongs to")
        c.check(captions.translated("Chúng tôi", 4.6) == 2 && captions.current?.translation == "Chúng tôi",
            "once it has as many sentence ends as was said, pieces go to the next")
        c.check(captions.takeReleased().map { it.translation } == listOf("Giá này có bớt được không?"), "and the first is done")
        c.check(captions.tick(6.0) == null && captions.tick(7.2)?.text == "我们", "a quiet moment ends the caption")
        c.check(captions.takeReleased().isEmpty() && captions.tick(10.5) == null && captions.takeReleased().map { it.number } == listOf(2),
            "its translation is done when no more of it comes")

        val stopped = LiveCaptions()
        stopped.heard("好的，下周一确认。", 0.0)
        c.check(stopped.tick(0.5) == null && stopped.tick(0.9)?.text == "好的，下周一确认。",
            "after a full stop, a short quiet moment ends the caption (GPT waited 2.5 s for it)")

        val short = LiveCaptions()
        short.heard("好的。", 0.0)
        c.check(short.heard("那我们", 1.0) == null && short.current?.text == "好的。那我们", "a short caption is not cut at its full stop")

        val talk = LiveCaptions()
        val cut = ArrayList<String>()
        for (second in 0 until 35) talk.heard(if (second == 12) "四，" else "四", second.toDouble())?.let { cut.add(it.text) }
        c.check(cut.size == 2 && cut[0].length == 14 && cut[0].endsWith("，") && cut[1].length == 20,
            "a long speech is cut after a comma, and anywhere once very long: ${cut.map { it.length }}")
        c.check(talk.translated("x", 35.0) == 2, "a piece long after is not given to the caption before")
        val last = talk.finishAll(36.0)
        c.check(last != null && talk.current == null && talk.awaiting.isEmpty() && talk.takeReleased().size == 3,
            "listening stops: everything ends as it is")
        c.check(LiveCaptions.ends("Hello... OK? Yes!") == 3 && LiveCaptions.ends("没有") == 0, "sentence ends counted")
        c.done()
    }

    @Test
    fun liveGateAndCost() {
        val c = Checks()
        val gate = LiveGate()
        fun chunk(n: Int) = byteArrayOf(n.toByte())
        var sent = ArrayList<ByteArray>()
        for (n in 0 until 20) sent += gate.feed(chunk(n), false)
        c.check(sent.isEmpty(), "a quiet room is not sent")
        val opened = gate.feed(chunk(100), true)
        c.check(opened.map { it[0].toInt() } == listOf(15, 16, 17, 18, 19, 100), "speech goes with the half second before it")
        var after = 0
        repeat(100) { after += gate.feed(chunk(0), false).size }
        c.check(after == LiveGate.HOLD, "three seconds after the last word, then nothing, got $after")
        c.check(gate.feed(chunk(7), true).size == LiveGate.PREROLL + 1, "the next words open it again")
        gate.feed(chunk(1), true)
        gate.reset()
        c.check(gate.feed(chunk(2), false).isEmpty(), "reset closes it")

        fun near(a: Double, b: Double) = abs(a - b) < 1e-9
        var meter = CostMeter()
        meter.transcribed(60.0)
        c.check(near(meter.dollars, 0.0045), "a minute recognised")
        meter = CostMeter()
        meter.used(TokenUsage(1_000, 200, 100), TranslationModel.SOL)
        c.check(near(meter.dollars, (1_600 + 20 + 1_000) / 1_000_000.0), "Sol tokens, cached ones cheaper")
        meter = CostMeter()
        meter.used(TokenUsage(1_000, 0, 100), TranslationModel.LUNA)
        val luna = 150 / 1_000_000.0
        c.check(near(meter.dollars, luna), "Luna tokens")
        meter.used(null, TranslationModel.SOL)
        meter.voiced(60.0)
        meter.streamed(60.0)
        c.check(near(meter.dollars, luna + 0.015 + 0.034), "voice and live by the minute")
        c.check(CostMeter.text(0.004) == "dưới 0,01 USD" && CostMeter.text(0.034) == "khoảng 0,03 USD" &&
            CostMeter.text(2.04) == "khoảng 2,0 USD", "cost in Vietnamese")
        val economy = CostMeter.perHour(false, false, TranslationModel.LUNA, false, true)
        val best = CostMeter.perHour(false, false, TranslationModel.SOL, false, true)
        val voice = CostMeter.perHour(false, false, TranslationModel.LUNA, true, true)
        val phone = CostMeter.perHour(false, false, TranslationModel.LUNA, true, false)
        val live = CostMeter.perHour(true, false, TranslationModel.LUNA, true, true)
        c.check(economy > 0.3 && economy < 0.4, "Tiết kiệm: about 0.35 USD an hour, got $economy")
        c.check(best > 2 && best < 3, "Sol: about 2.5 USD an hour, got $best")
        c.check(near(voice - economy, 0.9) && near(phone, economy), "OpenAI's voice adds 0.9 an hour, the phone's nothing")
        c.check(near(live, 2.04), "Dịch liền: 2.04 USD an hour")
        val usage = Json.parseToJsonElement("""{"usage":{"input_tokens":812,"input_tokens_details":{"cached_tokens":0},"output_tokens":57}}""").jsonObject
        c.check(TokenUsage.from(usage) == TokenUsage(812, 0, 57), "usage read from OpenAI's answer")
        c.check(TokenUsage.from(Json.parseToJsonElement("{}").jsonObject) == null, "no usage, no cost")
        c.done()
    }

    @Test
    fun soniox() {
        val c = Checks()
        fun token(text: String, final: Boolean = true, translation: Boolean = false, language: String = "zh") =
            SonioxToken(text, final, translation, if (translation) "vi" else language)
        fun spoken(text: String, speaker: String, language: String = "zh", final: Boolean = true) =
            SonioxToken(text, final, false, language, speaker)
        val message = """{"tokens":[{"text":"你好","is_final":true,"language":"zh","translation_status":"original"},""" +
            """{"text":"Xin chào","is_final":false,"language":"vi","translation_status":"translation"},{"text":"<end>","is_final":true}],""" +
            """"final_audio_proc_ms":760,"total_audio_proc_ms":880}"""
        c.check(SonioxEvent.parse(message) == listOf(SonioxEvent.Tokens(listOf(token("你好"),
            token("Xin chào", final = false, translation = true), token("<end>", language = "")))), "tokens read")
        c.check(SonioxEvent.parse("""{"tokens":[],"finished":true}""") == listOf(SonioxEvent.Finished), "finished")
        c.check(SonioxEvent.parse("""{"error_code":401,"error_message":"Invalid API key."}""") ==
            listOf(SonioxEvent.Failed(401, "Invalid API key.")), "a wrong key")
        c.check(SonioxEvent.parse("""{"tokens":[],"total_audio_proc_ms":100}""").isEmpty() && SonioxEvent.parse("not json").isEmpty(),
            "nothing new, nothing told")

        val config = Json.parseToJsonElement(SonioxEvent.configMessage("k", "stt-rt-v5", "vi", listOf("zh", "vi"),
            listOf("报价"), listOf("报价" to "báo giá"))).jsonObject
        c.check(config["audio_format"]!!.jsonPrimitive.content == "pcm_s16le" && config["sample_rate"]!!.jsonPrimitive.int == 24_000 &&
            config["num_channels"]!!.jsonPrimitive.int == 1, "the sound as the microphone gives it")
        val translation = config["translation"]!!.jsonObject
        c.check(translation["type"]!!.jsonPrimitive.content == "one_way" && translation["target_language"]!!.jsonPrimitive.content == "vi",
            "translated into Vietnamese")
        c.check(config["enable_endpoint_detection"]!!.jsonPrimitive.boolean && config["max_endpoint_delay_ms"]!!.jsonPrimitive.int == 500,
            "a sentence ends half a second after the speaker stops")
        c.check(config["context"].toString().contains(""""translation_terms":[{"source":"报价","target":"báo giá"}]"""), "the glossary goes along")
        c.check(SonioxEvent.code(Language.named("zh-Hant")!!) == "zh" && SonioxEvent.code(Language.vietnamese) == "vi", "language codes")
        c.check(SonioxEvent.parse("""{"tokens":[{"text":"好","is_final":true,"language":"zh","speaker":"2","start_ms":1700,"end_ms":1840}]}""") ==
            listOf(SonioxEvent.Tokens(listOf(SonioxToken("好", true, false, "zh", "2", 1700, 1840)))), "who said it, and when, is read")
        val room = Json.parseToJsonElement(SonioxEvent.configMessage("k", "stt-rt-v5", "vi", listOf("zh", "vi"), emptyList(), emptyList(),
            speakers = true, about = listOf("setting" to "Business meeting", "topic" to "báo giá máy CNC"))).jsonObject
        c.check(room["enable_speaker_diarization"]!!.jsonPrimitive.boolean, "each word says who said it")
        c.check(room["context"].toString() == """{"general":[{"key":"setting","value":"Business meeting"},{"key":"topic","value":"báo giá máy CNC"}]}""",
            "what the meeting is about goes to Soniox's general context: ${room["context"]}")
        val alone = SonioxEvent.configMessage("k", "stt-rt-v5", "zh", listOf("vi"), emptyList(), emptyList())
        c.check(!alone.contains("diarization") && !alone.contains("context"), "the user's turn is one person, no glossary sent empty")

        val glossary = "báo giá = 报价\nMộc Sinh = Mộc Sinh\nFOB = giá FOB\n\n"
        val into = SonioxEvent.glossary(glossary, intoVietnamese = true)
        val from = SonioxEvent.glossary(glossary, intoVietnamese = false)
        c.check(into.first.contains("报价") && into.first.contains("FOB"), "every term is a word to hear right")
        c.check(into.second.map { "${it.first}>${it.second}" } == listOf("报价>báo giá", "FOB>giá FOB"), "into Vietnamese: ${into.second}")
        c.check(from.second.map { "${it.first}>${it.second}" } == listOf("báo giá>报价", "giá FOB>FOB"), "from Vietnamese: ${from.second}")

        val transcript = SonioxTranscript("vi")
        c.check(transcript.take(listOf(token("这个", final = false))).isEmpty() && transcript.current.text == "这个", "the guess shows at once")
        c.check(transcript.take(listOf(token("这个"), token("价格", final = false))).isEmpty() && transcript.current.text == "这个价格",
            "settled words, then the guess")
        c.check(transcript.take(listOf(token("价格"), token("Giá", translation = true), token(" này", final = false, translation = true))).isEmpty() &&
            transcript.current.translation == "Giá này", "its translation comes as it goes")
        val finished = transcript.take(listOf(token(" này", translation = true), token("<end>", language = "")))
        c.check(finished == listOf(SonioxTranscript.Sentence("这个价格", "Giá này", "zh")), "finished at its end: $finished")
        c.check(transcript.current.isEmpty && !transcript.ended, "the next starts empty")
        c.check(transcript.take(listOf(token("好"), token("<end>", language = ""))).isEmpty() && transcript.ended, "waits for the translation")
        c.check(transcript.take(listOf(token("Được", translation = true))) == listOf(SonioxTranscript.Sentence("好", "Được", "zh")),
            "the late translation finishes it")
        transcript.take(listOf(token("谢谢"), token("<end>", language = "")))
        val next = transcript.take(listOf(token("再见", final = false)))
        c.check(next == listOf(SonioxTranscript.Sentence("谢谢", "", "zh")) && transcript.current.text == "再见",
            "the next sentence begins: the last ends as it is")
        val vietnamese = SonioxTranscript("vi")
        val said = vietnamese.take(listOf(token("Chào anh", language = "vi"), token("<fin>", language = "")))
        c.check(said == listOf(SonioxTranscript.Sentence("Chào anh", "", "vi")), "Vietnamese needs no translation")
        c.check(vietnamese.take(listOf(token("<end>", language = ""))).isEmpty() && vietnamese.finish() == null, "an end with nothing said is nothing")

        // Soniox sends each piece of translation right after the words it translates, and settles the
        // stream from its start: a piece belongs to the sentence of the words just before it.
        fun sentence(text: String, translation: String = "", language: String = "zh", speaker: String = "") =
            SonioxTranscript.Sentence(text, translation, language, speaker)
        val split = SonioxTranscript("vi").apply { splits = true }
        c.check(split.take(listOf(spoken("这个价格", "1"), spoken("可以吗？", "1"))).isEmpty(), "a short question goes on until its end")
        split.take(listOf(token("Giá này", translation = true), token(" được không?", final = false, translation = true)))
        c.check(split.current.translation == "Giá này được không?", "Soniox's translation shows on the sentence being said, as it comes")
        val first = split.take(listOf(token(" được không?", translation = true), spoken("Được anh", "2", "vi")))
        c.check(first == listOf(sentence("这个价格可以吗？", "Giá này được không?", speaker = "1")) && split.current.text == "Được anh" &&
            split.current.speaker == "2" && split.current.translation.isEmpty(),
            "another speaker: the first sentence ends, with its translation (it came before the next words): $first")
        val long = split.take(listOf(spoken("那我们下周一确认订单", "3"), spoken("因为这个月比较忙。", "3")))
        c.check(long == listOf(sentence("Được anh", language = "vi", speaker = "2"), sentence("那我们下周一确认订单因为这个月比较忙。", speaker = "3")),
            "a third speaker, whose long speech is cut at its full stop: $long")
        split.take(listOf(token("Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng", translation = true)))
        c.check(split.previews.map { it.translation } == listOf("Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng") && split.current.translation.isEmpty(),
            "the translation after a cut at a full stop is the cut sentence's: ${split.previews}")
        split.take(listOf(token(" vì tháng này", final = false, translation = true), spoken("另外", "3", final = false),
            token("Ngoài ra", final = false, translation = true)))
        c.check(split.previews.firstOrNull()?.translation == "Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng vì tháng này" &&
            split.current.translation == "Ngoài ra", "guessed pieces: before the next guessed words, the cut sentence's; after them, the one being said")
        split.take(listOf(token(" vì tháng này khá bận.", translation = true), spoken("另外", "3")))
        c.check(split.previews.map { it.translation } == listOf("Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng vì tháng này khá bận.") &&
            split.current.text == "另外", "its last piece still shows with the next words settling: ${split.previews}")
        split.take(listOf(token("Ngoài ra", translation = true)))
        c.check(split.previews.isEmpty() && split.current.translation == "Ngoài ra", "after them, every piece is the new sentence's")
        val english = split.take(listOf(spoken("我觉得没问题。", "3"), spoken("Sounds", "3", "en"), spoken(" good", "3", "en")))
        c.check(english.size == 1 && english[0].text == "另外我觉得没问题。" && split.current.text == "Sounds good" && split.current.language == "en",
            "another language after a sentence: $english")
        // Soniox's end of sentence without a full stop is a pause: the sentence waits (the model ends it
        // after a quiet moment), and its translation is still its own.
        c.check(split.take(listOf(spoken("<end>", ""))).isEmpty() && split.ended, "an end without a full stop is a pause")
        split.take(listOf(token("Nghe được đấy.", translation = true)))
        c.check(split.current.translation == "Nghe được đấy." && split.previews.isEmpty(), "its translation, after the pause, is its own")
        c.check(split.finish() == sentence("Sounds good", "Nghe được đấy.", "en", "3"), "ended after a quiet moment, translation and all")
        // After a full stop, Soniox's end finishes it at once, without waiting for its translation.
        val done = split.take(listOf(spoken("好的。", "1"), spoken("<end>", "")))
        c.check(done == listOf(sentence("好的。", speaker = "1")), "a full stop, then the end: finished at once")
        split.take(listOf(token("Được.", translation = true)))
        c.check(split.previews.firstOrNull()?.translation == "Được." && split.current.isEmpty, "its translation, after the end, is its own")
        // A thought said in two breaths is one sentence: the condition is not translated apart from what it is the condition of.
        val pause = SonioxTranscript("vi").apply { splits = true }
        c.check(pause.take(listOf(spoken("如果订五百台，", "1"), spoken("<end>", ""))).isEmpty() && pause.ended, "a pause after a comma waits")
        c.check(pause.take(listOf(spoken("便宜一点吗？", "1"))).isEmpty() && !pause.ended && pause.current.text == "如果订五百台，便宜一点吗？",
            "the speaker goes on: the same sentence")
        c.check(pause.take(listOf(spoken("<end>", ""))).map { it.text } == listOf("如果订五百台，便宜一点吗？"), "and ends at its question mark")
        pause.take(listOf(spoken("那个", "1"), spoken("<end>", "")))
        val other = pause.take(listOf(spoken("好", "2"), spoken("的", "2")))
        c.check(other.map { it.text } == listOf("那个") && pause.current.text == "好的" && pause.current.speaker == "2",
            "someone else after a pause: the paused sentence ends at once")
        pause.take(listOf(spoken("<end>", "")))
        c.check(pause.ended && pause.take(listOf(spoken("<fin>", ""))).map { it.text } == listOf("好的"), "a turn ended on purpose ends it, full stop or not")
        // A word or two of another speaker in the middle of a sentence does not cut it: when the speaker goes on, they stay in it.
        val over = SonioxTranscript("vi").apply { splits = true }
        c.check(over.take(listOf(spoken("这个价格", "1"), spoken("可以", "2"))).isEmpty() && over.current.text == "这个价格可以",
            "another speaker's word in the middle of a sentence is held")
        c.check(over.take(listOf(spoken("便宜吗？", "1"))).isEmpty(), "the speaker goes on")
        c.check(over.take(listOf(spoken("<end>", ""))) == listOf(sentence("这个价格可以便宜吗？", speaker = "1")), "one sentence, the speaker's")
        // Theirs once they say three syllables: the sentence before ends without them.
        over.take(listOf(spoken("这个型号", "1"), spoken("好的", "2")))
        val theirs = over.take(listOf(spoken("没问题", "2")))
        c.check(theirs == listOf(sentence("这个型号", speaker = "1")) && over.current.text == "好的没问题" && over.current.speaker == "2",
            "three syllables: theirs, a sentence of their own")
        // A sentence's language is the one most of it is said in, not its first word's.
        val lead = SonioxTranscript("vi").apply { splits = true }
        val mostly = lead.take(listOf(spoken("Dạ, ", "1", "vi"), spoken("王总，我们下周一确认订单。", "1"), spoken("<end>", "")))
        c.check(mostly.firstOrNull()?.language == "zh", "a Vietnamese word, then Chinese: Chinese: $mostly")
        c.check(lead.take(listOf(spoken("OK，", "2", "en"), spoken("那我们下周一确认", "2"))).isEmpty() && lead.current.language == "zh",
            "an English word, then Chinese: Chinese")
        // Soniox's guess at the translation, in the message that ends the sentence, is not kept twice when it settles in the next.
        val twice = SonioxTranscript("vi").apply { splits = true }
        val ended = twice.take(listOf(spoken("好的。", "1"), spoken("<end>", ""), token("Được", final = false, translation = true)))
        c.check(ended.map { it.text } == listOf("好的。") && twice.previews.map { it.translation } == listOf("Được"), "its guessed translation shows")
        twice.take(listOf(token("Được rồi.", translation = true)))
        c.check(twice.previews.map { it.translation } == listOf("Được rồi."), "settled, it replaces the guess: ${twice.previews}")
        // The room's session hears the user's turns too: their voice is taken out, a partner's is not.
        val voices = MyVoiceFilter()
        fun voiced(text: String, speaker: String, language: String, at: Double) =
            SonioxToken(text, true, false, language, speaker, (at * 1000).toInt(), (at * 1000).toInt() + 300)
        c.check(voices.filter(listOf(voiced("这个价格", "1", "zh", 1.0)), 0.0).size == 1, "before any turn, everything passes")
        voices.began(10.0)
        val inTurn = voices.filter(listOf(voiced("Anh gửi", "2", "vi", 10.2), voiced("OK", "2", "en", 11.0), token("Được", translation = true),
            voiced("等一下", "1", "zh", 11.5), token("Đợi chút", translation = true), voiced("hello", "3", "en", 12.0)), 0.0)
        c.check(inTurn.map { it.text } == listOf("等一下", "Đợi chút"),
            "in a turn: the user's words and their translation go, a partner's voice stays, an unknown one goes: ${inTurn.map { it.text }}")
        voices.ended(13.0)
        c.check(voices.filter(listOf(voiced("好的", "3", "zh", 13.2)), 0.0).isEmpty(), "a moment after the turn, still the user's")
        c.check(voices.filter(listOf(voiced("好的", "3", "zh", 14.0)), 0.0).size == 1, "after it, everyone passes")
        c.check(voices.filter(listOf(voiced("vâng", "2", "vi", 1.5)), 0.0).size == 1 &&
            voices.filter(listOf(voiced("vâng", "2", "vi", 1.5)), 10.0).isEmpty(), "a session opened again: its times count from where its sound begins")
        val shortPhrase = SonioxTranscript("vi").apply { splits = true }
        shortPhrase.take(listOf(spoken("好", "1"), spoken("OK", "1", "en"), spoken("的", "1")))
        c.check(shortPhrase.current.text == "好OK的", "a word of English in a short phrase stays in it")
        // A speaker cut by another: the next speaker's first piece of translation is theirs at once.
        val cutShort = SonioxTranscript("vi").apply { splits = true }
        cutShort.take(listOf(spoken("我们这个价格", "1")))
        cutShort.take(listOf(token("Giá này của chúng tôi", translation = true)))
        c.check(cutShort.take(listOf(spoken("好的", "2"))).isEmpty(), "two syllables of another speaker: held")
        cutShort.take(listOf(token("Được", translation = true)))
        val cut = cutShort.take(listOf(spoken("没问题", "2")))
        cutShort.take(listOf(token(", không vấn đề", translation = true)))
        c.check(cut.firstOrNull()?.translation == "Giá này của chúng tôi" && cutShort.current.translation == "Được, không vấn đề" &&
            cutShort.previews.isEmpty(), "the next speaker's translation is theirs: ${cutShort.previews} / ${cutShort.current}")
        val timed = SonioxTranscript("vi")
        timed.take(listOf(SonioxToken("好", true, false, "zh", startMs = 1700, endMs = 1840), SonioxToken("的", false, false, "zh", startMs = 1900, endMs = 2100)))
        c.check(timed.current.startMs == 1700 && timed.current.endMs == 2100, "a sentence says where its words begin and end")
        // Talking on without a full stop: cut at a comma once very long.
        val monologue = SonioxTranscript("vi").apply { splits = true }
        val clause = monologue.take(listOf(spoken("我们".repeat(20) + "，", "1"), spoken("然后", "1")))
        c.check(clause.size == 1 && clause[0].text.endsWith("，") && monologue.current.text == "然后", "a very long speech is cut at a comma")
        c.check(monologue.take(listOf(spoken("我们，", "1"))).isEmpty(), "a short one is not")
        val mixed = SonioxTranscript("zh").apply { splits = true }
        val quote = mixed.take(listOf(spoken("Anh gửi giúp em cái", "", "vi"), spoken(" quotation", "", "en"), spoken(" nhé", "", "vi")))
        c.check(quote.isEmpty() && mixed.current.text == "Anh gửi giúp em cái quotation nhé", "an English word inside a Vietnamese sentence stays in it")

        val meter = CostMeter()
        meter.streamed(3_600.0, soniox = true)
        c.check(abs(meter.dollars - 0.18) < 1e-9, "Soniox: about 0.18 USD an hour sent")
        val sonioxHour = CostMeter.perHour(true, true, TranslationModel.LUNA, true, false)
        c.check(abs(sonioxHour - 0.18) < 1e-9 && sonioxHour < CostMeter.perHour(true, false, TranslationModel.LUNA, true, false) / 10,
            "over ten times cheaper than Dịch liền")
        val checked = CostMeter.perHour(true, true, TranslationModel.LUNA, true, false, translatesAgain = true)
        c.check(abs(checked - 0.18 - 0.0756) < 1e-9, "each sentence translated again by Luna: about 0.08 USD more an hour")
        c.done()
    }
}
