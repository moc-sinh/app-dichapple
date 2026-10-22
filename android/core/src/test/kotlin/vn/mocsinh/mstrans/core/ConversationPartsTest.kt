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
import vn.mocsinh.mstrans.core.conversation.LiveGate
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
        c.check(guard.isEcho("we will send the quotation before friday", 13.0), "the phone's sentence heard back")
        c.check(!guard.isEcho("Can you lower the price a little?", 13.0), "someone else speaking")
        c.check(!guard.isEcho("we will send the quotation before friday", 30.0), "long after, it is someone saying it")
        guard.spoke("我们周五之前发报价", 40.0)
        c.check(guard.isEcho("我们周五前发报价。", 42.0), "Chinese echo by characters")
        c.check(!guard.isEcho("价格太高了", 42.0), "Chinese reply is not echo")
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

        val meter = CostMeter()
        meter.streamed(3_600.0, soniox = true)
        c.check(abs(meter.dollars - 0.18) < 1e-9, "Soniox: about 0.18 USD an hour sent")
        val sonioxHour = CostMeter.perHour(true, true, TranslationModel.LUNA, true, false)
        c.check(abs(sonioxHour - 0.18) < 1e-9 && sonioxHour < CostMeter.perHour(true, false, TranslationModel.LUNA, true, false) / 10,
            "over ten times cheaper than Dịch liền")
        c.done()
    }
}
