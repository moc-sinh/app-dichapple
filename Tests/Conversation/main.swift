// Checks of the Hội thoại tab's pure parts, run in CI with:
// swiftc -O Shared/Config.swift Shared/SharedStore.swift Shared/History.swift Shared/LanguageDetector.swift \
//   Shared/Translator.swift App/Conversation/PCM.swift App/Conversation/SpeechSegmenter.swift \
//   App/Conversation/TranscriptFilter.swift App/Conversation/LiveTranslation.swift App/Conversation/SonioxTranslation.swift App/Conversation/SpeechServices.swift \
//   App/Conversation/GoogleVoice.swift App/Conversation/ConversationCost.swift Tests/Conversation/main.swift \
//   -o conversation-tests && ./conversation-tests
import Foundation

var failed = 0
var total = 0
func check(_ ok: Bool, _ what: String) {
    total += 1
    if !ok {
        failed += 1
        print("FAIL: \(what)")
    }
}

// MARK: Sound

/// Room noise at `db` dBFS, deterministic.
struct Noise {
    var state: UInt64 = 7
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double(Int64(bitPattern: state >> 11) % 2000) / 1000 - 1
    }
}
var noise = Noise()

func quiet(_ seconds: Double, db: Double = -62) -> [Int16] {
    let amplitude = pow(10, db / 20) * 32768 * 1.7
    return (0..<Int(seconds * 24_000)).map { _ in Int16(max(-32767, min(32767, noise.next() * amplitude))) }
}

/// Speech-like sound: a voice at `db` dBFS whose loudness rises and falls four times a second
/// (syllables), with short dips between them, over room noise.
func speech(_ seconds: Double, db: Double = -26) -> [Int16] {
    let amplitude = pow(10, db / 20) * 32768 * 1.41
    let floor = quiet(seconds)
    return (0..<floor.count).map { i in
        let t = Double(i) / 24_000
        let syllable = max(0.08, abs(sin(.pi * 4 * t)))
        let voice = sin(2 * .pi * 180 * t) * amplitude * syllable
        return Int16(max(-32767, min(32767, voice + Double(floor[i]))))
    }
}

func segments(_ samples: [Int16], silence: Double = 0.7, maxLength: Double = 15, chunk: Int = 2_400) -> [SpeechSegmenter.Utterance] {
    var segmenter = SpeechSegmenter(silence: silence, maxLength: maxLength)
    var found: [SpeechSegmenter.Utterance] = []
    var offset = 0
    while offset < samples.count {
        found += segmenter.push(Array(samples[offset..<min(samples.count, offset + chunk)]))
        offset += chunk
    }
    if let last = segmenter.flush() { found.append(last) }
    return found
}

// Two sentences with a pause between them: two utterances, each starting just before its speech.
do {
    let found = segments(quiet(1) + speech(2) + quiet(1.2) + speech(1.5) + quiet(1))
    check(found.count == 2, "two sentences give two utterances, got \(found.count)")
    if found.count == 2 {
        check(abs(found[0].start - 0.7) < 0.12, "first starts with 0.3 s before speech, got \(found[0].start)")
        check(found[0].duration > 2 && found[0].duration < 2.8, "first holds its speech, got \(found[0].duration)")
        check(abs(found[1].start - 3.9) < 0.15, "second starts before its speech, got \(found[1].start)")
    }
}

// A pause shorter than the setting stays inside one utterance.
check(segments(quiet(1) + speech(1) + quiet(0.4) + speech(1) + quiet(1)).count == 1, "a 0.4 s pause does not end a sentence")
check(segments(quiet(1) + speech(1) + quiet(0.5) + speech(1) + quiet(1), silence: 0.4).count == 2,
      "with 0.4 s set, a 0.5 s pause ends it")

// The pause that ends a sentence gets shorter as someone talks on: a room that never pauses long
// is cut at its breaths, not every ten seconds.
do {
    let segmenter = SpeechSegmenter(silence: 0.8)
    check(segmenter.pause(after: 2) == 0.8 && abs(segmenter.pause(after: 5.5) - 0.575) < 1e-9
          && abs(segmenter.pause(after: 12) - 0.35) < 1e-9, "the pause: as set, then down to 0.35 s at eight seconds")
    check(SpeechSegmenter(silence: 0.3).pause(after: 12) == 0.3, "never longer than the one set")
    check(segments(quiet(1) + speech(9) + quiet(0.5) + speech(2) + quiet(1), silence: 0.8).count == 2,
          "after nine seconds, a breath of half a second ends the sentence")
    check(segments(quiet(1) + speech(2) + quiet(0.5) + speech(2) + quiet(1), silence: 0.8).count == 1,
          "after two seconds, it does not")
}

// Noise alone, loud or not, is never speech; a click is too short.
check(segments(quiet(5)).isEmpty, "a quiet room gives nothing")
check(segments(quiet(6, db: -38)).isEmpty, "a noisy room (projector, air conditioning) gives nothing")
check(segments(quiet(1) + speech(0.1) + quiet(1)).isEmpty, "a click is not speech")
check(segments(quiet(1, db: -40) + speech(1.5, db: -22) + quiet(1, db: -40)).count == 1, "speech over a noisy room is heard")
check(segments(quiet(1) + speech(1.5, db: -42) + quiet(1)).count == 1, "a far voice is heard")

// A long talk is cut as it goes, near a breath, never in tiny pieces.
do {
    let talk = speech(7) + quiet(0.25) + speech(6) + quiet(0.25) + speech(9) + quiet(1)
    let found = segments(quiet(0.5) + talk, maxLength: 12)
    check(found.count >= 2, "a 22 s talk is cut, got \(found.count)")
    check(found.count >= 2 && abs(found[1].start - 7.75) < 0.3, "cut at the breath, got \(found.count >= 2 ? found[1].start : -1)")
    check(found.allSatisfy { $0.duration <= 12.05 }, "no piece longer than the limit")
    check(found.dropLast().allSatisfy { $0.duration >= 3 }, "no tiny piece")
    let total = found.reduce(0) { $0 + $1.duration }
    check(total > 21 && total < 24, "nothing lost when cut, got \(total)")
}

// The user's long turn (Tôi nói held) goes piece by piece, split at a breath, not mid-word.
do {
    let held = speech(16) + quiet(0.3) + speech(4)
    let seconds = Double(SpeechSegmenter.split(held)) / 24_000
    check(seconds > 16 && seconds < 16.35, "a long turn split at the breath, got \(seconds)")
    check(SpeechSegmenter.split([1, 2, 3]) == 3, "a scrap of sound is not split")
}

// Muted while the phone speaks: what was said goes out, the rest is not heard.
do {
    var segmenter = SpeechSegmenter()
    _ = segmenter.push(quiet(1))
    _ = segmenter.push(speech(1))
    let cutShort = segmenter.mute()
    check(cutShort != nil, "speech in progress goes out on mute")
    check(segmenter.push(speech(2) + quiet(1)).isEmpty, "nothing heard while muted")
    segmenter.unmute()
    let after = segmenter.push(speech(1.2) + quiet(1))
    check(after.count == 1, "heard again after unmute")
}

check(PCM.wav([1, -2, 3]).count == 44 + 6, "WAV header is 44 bytes")
check(PCM.samples(PCM.data([1, -2, 32767, -32768])) == [1, -2, 32767, -32768], "PCM round trip")

// MARK: Phantom text

let noiseTexts = [
    "Cảm ơn các bạn đã theo dõi và hẹn gặp lại", "Hãy subscribe cho kênh Ghiền Mì Gõ để không bỏ lỡ",
    "Thanks for watching!", "字幕由Amara.org社区提供", "ご視聴ありがとうございました",
    "ha ha ha ha ha ha ha ha", "的的的的的的的的的的的的的的", "...", "",
]
for text in noiseTexts { check(TranscriptFilter.isNoise(text, seconds: 3), "noise: \(text)") }
let realTexts = [
    "Bên em gửi báo giá trước thứ Sáu nhé", "这个型号的价格是多少？", "Thank you, that works for us.",
    "OK", "Cảm ơn anh", "Can we move the delivery to next week?", "kênh phân phối của bên anh thế nào",
]
for text in realTexts { check(!TranscriptFilter.isNoise(text, seconds: 3), "real: \(text)") }
check(TranscriptFilter.isImplausiblyFast("một hai ba bốn năm sáu bảy tám chín mười", seconds: 0.8), "ten words in 0.8 s")
check(!TranscriptFilter.isImplausiblyFast("một hai ba bốn năm sáu bảy tám chín mười", seconds: 3), "ten words in 3 s")
check(TranscriptFilter.syllableCount("这个型号 OK") == 5, "Chinese counts characters")

// MARK: Echo of the phone's own voice

do {
    var guardEcho = EchoGuard()
    guardEcho.spoke("We will send the quotation before Friday.", at: 10)
    check(guardEcho.isEcho("we will send the quotation before friday", at: 13), "the phone's sentence heard back")
    check(!guardEcho.isEcho("Can you lower the price a little?", at: 13), "someone else speaking")
    check(!guardEcho.isEcho("we will send the quotation before friday", at: 30), "long after, it is someone saying it")
    guardEcho.spoke("我们周五之前发报价", at: 40)
    check(guardEcho.isEcho("我们周五前发报价。", at: 42), "Chinese echo by characters")
    check(!guardEcho.isEcho("价格太高了", at: 42), "Chinese reply is not echo")
    guardEcho.spoke("Chúng tôi sẽ gửi báo giá cho anh trước thứ Sáu và giao hàng trong hai tuần.", at: 50)
    guardEcho.spoke("Giá này là giá tốt nhất của chúng tôi rồi.", at: 54)
    check(guardEcho.isEcho("giá này là giá tốt nhất của chúng tôi", at: 57), "the phone's Vietnamese heard back")
    check(guardEcho.isEcho("chúng tôi sẽ gửi báo giá cho anh trước thứ bảy và giao hàng trong hai tuần", at: 57),
          "heard back with a word misheard")
    check(!guardEcho.isEcho("Anh ơi giá của chúng tôi là tốt rồi", at: 57), "a colleague's sentence of common words is not echo")
    check(guardEcho.isEcho("Cảm ơn", at: 58) == false && guardEcho.isEcho("giá tốt", at: 58), "a word or two: all said just before")
    guardEcho.spoke("这个价格可以吗？", at: 60)
    check(!guardEcho.isEcho("这个呢", at: 61) && guardEcho.isEcho("这个价格", at: 61), "a short sentence: all of it said by the phone")
}

// MARK: Live translation events

check(LiveEvent.parse(#"{"type":"session.created","session":{}}"#) == .ready, "session.created")
check(LiveEvent.parse(#"{"type":"session.input_transcript.delta","delta":"你好"}"#) == .source("你好"), "source delta")
check(LiveEvent.parse(#"{"type":"session.output_transcript.delta","delta":"Xin chào"}"#) == .target("Xin chào"), "target delta")
check(LiveEvent.parse(#"{"type":"session.output_audio.delta","delta":"AQACAA=="}"#) == .audio(Data([1, 0, 2, 0])), "audio delta")
check(LiveEvent.parse(#"{"type":"error","error":{"code":"invalid_api_key","message":"Bad key"}}"#) == .failed(code: "invalid_api_key", message: "Bad key"),
      "error")
check(LiveEvent.parse(#"{"type":"session.closed"}"#) == .closed, "closed")
check(LiveEvent.parse("not json") == nil, "garbage ignored")
check(LiveEvent.parse(#"{"type":"something.new"}"#) == nil, "unknown events ignored")

// Dịch liền's captions: cut into sentences in a room that talks on, each with its own translation.
do {
    var captions = LiveCaptions()
    check(captions.heard("这个价格", now: 0) == nil && captions.heard("可以便宜一点吗？", now: 3.2) == nil
          && captions.current?.text == "这个价格可以便宜一点吗？", "a caption grows as it is said")
    check(captions.translated("Giá này", now: 3.5) == 1 && captions.current?.translation == "Giá này", "its translation too")
    let ended = captions.heard("我们", now: 4)
    check(ended?.text == "这个价格可以便宜一点吗？" && captions.current?.text == "我们", "the next words after a full stop: a new caption")
    check(captions.translated(" có bớt được không?", now: 4.3) == 1 && captions.takeReleased().isEmpty,
          "the rest of its translation goes to the caption it belongs to")
    check(captions.translated("Chúng tôi", now: 4.6) == 2 && captions.current?.translation == "Chúng tôi",
          "once it has as many sentence ends as was said, pieces go to the next")
    check(captions.takeReleased().map(\.translation) == ["Giá này có bớt được không?"], "and the first is done")
    check(captions.tick(now: 6) == nil && captions.tick(now: 7.2)?.text == "我们", "a quiet moment ends the caption")
    check(captions.takeReleased().isEmpty && captions.tick(now: 10.5) == nil && captions.takeReleased().map(\.number) == [2],
          "its translation is done when no more of it comes")

    var short = LiveCaptions()
    _ = short.heard("好的。", now: 0)
    check(short.heard("那我们", now: 1) == nil && short.current?.text == "好的。那我们", "a short caption is not cut at its full stop")

    var talk = LiveCaptions()
    var cut: [String] = []
    for second in 0..<35 { if let caption = talk.heard(second == 12 ? "四，" : "四", now: Double(second)) { cut.append(caption.text) } }
    check(cut.count == 2 && cut[0].count == 14 && cut[0].hasSuffix("，") && cut[1].count == 20,
          "a long speech is cut after a comma, and anywhere once very long: \(cut.map(\.count))")
    check(talk.translated("x", now: 35) == 2, "a piece long after is not given to the caption before")
    let last = talk.finishAll(now: 36)
    check(last != nil && talk.current == nil && talk.awaiting.isEmpty && talk.takeReleased().count == 3,
          "listening stops: everything ends as it is")
    check(LiveCaptions.ends("Hello... OK? Yes!") == 3 && LiveCaptions.ends("没有") == 0, "sentence ends counted")
}
check(LiveEvent.appendMessage(PCM.data([1, 2])) == #"{"type":"session.input_audio_buffer.append","audio":"AQACAA=="}"#, "append message")
check((try? JSONSerialization.jsonObject(with: Data(LiveEvent.languageMessage("vi").utf8))) != nil, "language message is JSON")
check(LiveSession.outputCode(for: Language.named("zh-Hant")!) == "zh" && LiveSession.outputCode(for: Language.named("th")!) == nil,
      "live output languages")

// MARK: Speech services

check(SpeechServices.normalized("zh-TW") == "zh-Hant" && SpeechServices.normalized("vi-VN") == "vi"
      && SpeechServices.normalized("EN") == "en", "language codes")
do {
    var form = MultipartForm()
    form.add("model", "gpt-transcribe")
    form.addFile("file", filename: "speech.wav", contentType: "audio/wav", data: Data([1, 2]))
    let body = String(decoding: form.finished, as: UTF8.self)
    check(body.hasPrefix("--\(form.boundary)\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-transcribe\r\n"),
          "form field")
    check(body.hasSuffix("\u{1}\u{2}\r\n--\(form.boundary)--\r\n"), "form closes after the file")
}
check(Prompt.spoken(into: "English").contains("speech recognition"), "spoken prompt")
do {
    // A meeting's sentence: who said it, and the sentences before it with who said them and how
    // they were translated; the topic goes with the instructions.
    let prompt = Prompt.spoken(into: "Vietnamese", topic: " báo giá máy CNC ")
    check(prompt.hasSuffix("\nThe meeting is about: báo giá máy CNC") && prompt.contains("never summarise"),
          "the meeting's topic is told, and nothing is to be left out")
    check(!Prompt.spoken(into: "Vietnamese").contains("about:"), "no topic, none told")
    let context = SpokenContext(speaker: SpokenContext.speaker(mine: false, number: "2"), lines: [
        .init(speaker: SpokenContext.speaker(mine: false, number: "1"), text: "这个价格\n可以吗？", translation: "Giá này được không?"),
        .init(speaker: SpokenContext.speaker(mine: true, number: ""), text: "Được anh", translation: "好的"),
        .init(speaker: SpokenContext.speaker(mine: false, number: ""), text: "那我们", translation: ""),
    ], topic: "x")
    check(Prompt.spokenInput(text: "下周一确认。", context: context) == """
        <context>
        Người 1: 这个价格 可以吗？ → Giá này được không?
        User: Được anh → 好的
        Participant: 那我们
        </context>
        <message speaker="Người 2">
        下周一确认。
        </message>
        """, "the meeting's sentences, who said them and their translations: \(Prompt.spokenInput(text: "下周一确认。", context: context))")
    check(Prompt.spokenInput(text: "好", context: SpokenContext()) == "<message speaker=\"Participant\">\n好\n</message>",
          "the first sentence: no context")
}
check(SpeechServices.voiceInstructions(.vietnamese).contains("Vietnamese accent"), "the voice is told the language")
check(PCM.wav(pcm: PCM.data([1, -2, 3])) == PCM.wav([1, -2, 3]), "WAV around PCM bytes")
check(PCM.seconds(Data(count: 48_000)) == 1, "a second of PCM")

// MARK: Google's voices

do {
    let request = GoogleVoice.request("Giá này bao nhiêu?", voice: GoogleVoice.female, locale: "vi-VN", apiKey: "key")
    check(request.url?.absoluteString == "https://texttospeech.googleapis.com/v1/text:synthesize" && request.httpMethod == "POST"
          && request.value(forHTTPHeaderField: "X-Goog-Api-Key") == "key", "Google: synthesize with the key in a header")
    let body = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any] ?? [:]
    let voice = body["voice"] as? [String: Any] ?? [:]
    let audio = body["audioConfig"] as? [String: Any] ?? [:]
    check((body["input"] as? [String: Any])?["text"] as? String == "Giá này bao nhiêu?", "Google: the sentence")
    check(voice["languageCode"] as? String == "vi-VN" && voice["name"] as? String == "vi-VN-Chirp3-HD-Aoede", "Google: Chirp 3 HD voice")
    check(audio["audioEncoding"] as? String == "LINEAR16" && audio["sampleRateHertz"] as? Int == 24_000, "Google: 24 kHz PCM16")
    check(GoogleVoice.locale(for: "zh") == "cmn-CN" && GoogleVoice.locale(for: "zh-Hant") == "cmn-CN"
          && GoogleVoice.locale(for: "ms") == nil, "Google: Mandarin for both scripts, no Malay voice")
    check(GoogleVoice.name(of: GoogleVoice.id(GoogleVoice.male)) == "Charon" && GoogleVoice.name(of: "marin") == nil
          && GoogleVoice.name(of: "iphone") == nil, "Google: voice setting")

    // The answer: a WAV file in base64; the samples are what is played.
    let samples = PCM.data([1, -2, 3, 300])
    let answer = Data(#"{"audioContent":"\#(PCM.wav(pcm: samples).base64EncodedString())"}"#.utf8)
    check(GoogleVoice.pcm(fromAnswer: answer) == samples, "Google: the WAV header is taken off")
    check(PCM.body(ofWAV: samples + Data([9])) == samples, "bytes that are not WAV are taken as PCM16")
    var unsized = PCM.wav(pcm: samples)
    unsized.replaceSubrange(40..<44, with: [0xFF, 0xFF, 0xFF, 0xFF])
    check(PCM.body(ofWAV: unsized) == samples, "a WAV file without its length")
    check(GoogleVoice.pcm(fromAnswer: Data("{}".utf8)) == nil, "Google: no voice in the answer")

    func failure(_ status: Int, _ json: String) -> GoogleVoice.Failure { GoogleVoice.failure(status: status, data: Data(json.utf8)) }
    let badKey = #"{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"#
    check(failure(400, badKey) == .badKey, "Google: a wrong key")
    check(failure(403, #"{"error":{"code":403,"message":"Cloud Text-to-Speech API has not been used in project 1 before or it is disabled.","status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}"#)
          == .notEnabled, "Google: the API not turned on")
    check(failure(403, #"{"error":{"code":403,"message":"This API method requires billing to be enabled.","details":[{"reason":"BILLING_DISABLED"}]}}"#)
          == .billing, "Google: no billing")
    check(failure(429, "{}") == .quota && failure(503, "") == .busy, "Google: too many, busy")
    check(failure(400, #"{"error":{"code":400,"message":"Voice 'ms-MY-Chirp3-HD-Aoede' does not exist."}}"#) == .unsupported,
          "Google: a language without the voice")
    check(failure(400, #"{"error":{"code":400,"message":"Input is too long."}}"#) == .refused("Input is too long."),
          "Google: anything else is told as Google said it")
}

// MARK: Voices kept for Đọc lại

do {
    var cache = VoiceCache(limit: 10)
    cache.put("a", Data(count: 4))
    cache.put("b", Data(count: 4))
    check(cache.get("a") == Data(count: 4), "a voice made before is played again")
    cache.put("c", Data(count: 4))
    check(cache.get("b") == nil && cache.get("a") != nil && cache.get("c") != nil, "the oldest unused goes first")
    cache.put("huge", Data(count: 11))
    check(cache.get("huge") == nil && cache.count == 2, "a voice larger than the limit is not kept")
    cache.put("a", Data(count: 6))
    check(cache.get("a")?.count == 6 && cache.count == 2, "made again, replaced")
    cache.put("d", Data(count: 5))
    check(cache.get("c") == nil && cache.get("a") == nil && cache.get("d") != nil, "over the limit, the oldest go")
    check(VoiceCache.key(text: "Xin chào", voice: "marin", language: "vi")
          != VoiceCache.key(text: "Xin chào", voice: "cedar", language: "vi"), "each voice kept apart")
}

// MARK: A voice read as it is made

do {
    let stream = VoiceStream()
    stream.append(Data([1, 2, 3]))
    check(stream.sound(after: 0).pcm == Data([1, 2, 3]) && !stream.sound(after: 0).finished, "the first part, still coming")
    stream.append(Data([4, 5]))
    check(stream.sound(after: 2).pcm == Data([3, 4, 5]) && stream.sound(after: 9).pcm.isEmpty, "what came after what was played")
    // All of it begins with the parts already come; a part told after the end is not added again.
    stream.finish(Data([1, 2, 3, 4, 5, 6]))
    stream.append(Data([6]))
    let end = stream.sound(after: 4)
    check(end.pcm == Data([5, 6]) && end.finished && end.failure == nil, "finished, with the rest")
    let failed = VoiceStream()
    failed.append(Data([7, 8]))
    failed.finish(nil, failure: TranslateError.network)
    check(failed.sound(after: 0).pcm == Data([7, 8]) && failed.sound(after: 0).failure != nil, "cut short: what came, and why")
    check(VoiceStream(made: Data([9])).sound(after: 0).finished, "a voice made before is all there")

    final class Box: @unchecked Sendable { var result: Result<Data, Error>? }
    let box = Box()
    let waiting = VoiceStream()
    let done = DispatchSemaphore(value: 0)
    Task {
        box.result = await waiting.whole()
        done.signal()
    }
    waiting.append(Data([1, 1]))
    Thread.sleep(forTimeInterval: 0.05)
    waiting.finish(Data([1, 1, 2, 2]))
    check(done.wait(timeout: .now() + 2) == .success && (try? box.result?.get()) == Data([1, 1, 2, 2]),
          "all of it, once made (Đọc lại after Dừng)")
}

// MARK: Dịch liền does not send a quiet room

do {
    var gate = LiveGate()
    func chunk(_ n: UInt8) -> Data { Data([n]) }
    var sent: [Data] = []
    for n in 0..<20 { sent += gate.feed(chunk(UInt8(n)), speech: false) }
    check(sent.isEmpty, "a quiet room is not sent")
    sent = gate.feed(chunk(100), speech: true)
    check(sent == [15, 16, 17, 18, 19].map(chunk) + [chunk(100)], "speech goes with the half second before it")
    var after = 0
    for _ in 0..<100 { after += gate.feed(chunk(0), speech: false).count }
    check(after == LiveGate.hold, "three seconds after the last word, then nothing, got \(after)")
    check(gate.feed(chunk(7), speech: true).count == LiveGate.preroll + 1, "the next words open it again, with the half second before")
    _ = gate.feed(chunk(1), speech: true)
    gate.reset()
    check(gate.feed(chunk(2), speech: false).isEmpty, "reset closes it")
}

// MARK: What it costs

do {
    func near(_ a: Double, _ b: Double) -> Bool { abs(a - b) < 1e-9 }
    var meter = CostMeter()
    meter.transcribed(seconds: 60)
    check(near(meter.dollars, 0.0045), "a minute recognised")
    meter = CostMeter()
    meter.used(TokenUsage(input: 1_000, cachedInput: 200, output: 100), by: .sol)
    let solExpected: Double = (1_600 + 20 + 1_000) / 1_000_000
    check(near(meter.dollars, solExpected), "Sol tokens, cached ones cheaper")
    meter = CostMeter()
    meter.used(TokenUsage(input: 1_000, cachedInput: 0, output: 100), by: .luna)
    let lunaExpected: Double = 150 / 1_000_000
    check(near(meter.dollars, lunaExpected), "Luna tokens")
    meter.used(nil, by: .sol)
    meter.voiced(seconds: 60)
    meter.streamed(seconds: 60)
    check(near(meter.dollars, lunaExpected + 0.015 + 0.034), "voice and live by the minute")
    check(CostMeter.text(0.004) == "dưới 0,01 USD" && CostMeter.text(0.034) == "khoảng 0,03 USD"
          && CostMeter.text(2.04) == "khoảng 2,0 USD", "cost in Vietnamese: \(CostMeter.text(0.034)), \(CostMeter.text(2.04))")
    let economy = CostMeter.perHour(live: false, model: .luna, readAloud: false, openAIVoice: true)
    let best = CostMeter.perHour(live: false, model: .sol, readAloud: false, openAIVoice: true)
    let voice = CostMeter.perHour(live: false, model: .luna, readAloud: true, openAIVoice: true)
    let iPhone = CostMeter.perHour(live: false, model: .luna, readAloud: true, openAIVoice: false)
    let live = CostMeter.perHour(live: true, model: .luna, readAloud: true, openAIVoice: true)
    check(economy > 0.3 && economy < 0.4, "Tiết kiệm, text only: about 0.35 USD an hour, got \(economy)")
    check(best > 2 && best < 3, "Sol for everything: about 2.5 USD an hour, got \(best)")
    check(near(voice - economy, 0.9) && near(iPhone, economy), "OpenAI's voice adds 0.9 an hour, the iPhone's nothing")
    check(near(live, 2.04), "Dịch liền: 2.04 USD an hour")
    let json: [String: Any] = ["usage": ["input_tokens": 812, "input_tokens_details": ["cached_tokens": 0], "output_tokens": 57]]
    let decoded = (try? JSONSerialization.jsonObject(with: JSONSerialization.data(withJSONObject: json))) as? [String: Any] ?? [:]
    check(TokenUsage.from(decoded) == TokenUsage(input: 812, cachedInput: 0, output: 57), "usage read from OpenAI's answer")
    check(TokenUsage.from([:]) == nil, "no usage, no cost")
    let saved = try? JSONDecoder().decode(CostMeter.self, from: JSONEncoder().encode(meter))
    check(saved == meter, "the cost is kept with the meeting")
}

// MARK: Soniox

do {
    func token(_ text: String, final: Bool = true, translation: Bool = false, language: String = "zh") -> SonioxToken {
        SonioxToken(text: text, isFinal: final, isTranslation: translation, language: translation ? "vi" : language)
    }
    let message = #"{"tokens":[{"text":"你好","is_final":true,"language":"zh","translation_status":"original"},"#
        + #"{"text":"Xin chào","is_final":false,"language":"vi","translation_status":"translation"},{"text":"<end>","is_final":true}],"#
        + #""final_audio_proc_ms":760,"total_audio_proc_ms":880}"#
    check(SonioxEvent.parse(message) == [.tokens([token("你好"), token("Xin chào", final: false, translation: true), token("<end>", language: "")])],
          "tokens read: said, translated, the end of a sentence")
    check(SonioxEvent.parse(#"{"tokens":[],"finished":true}"#) == [.finished], "finished")
    check(SonioxEvent.parse(#"{"error_code":401,"error_message":"Invalid API key."}"#) == [.failed(code: 401, message: "Invalid API key.")],
          "a wrong key")
    check(SonioxEvent.parse(#"{"tokens":[],"total_audio_proc_ms":100}"#).isEmpty && SonioxEvent.parse("not json").isEmpty,
          "nothing new, nothing told")

    let config = SonioxEvent.configMessage(apiKey: "k", model: "stt-rt-v5", target: "vi", hints: ["zh", "vi"],
                                           terms: ["报价"], pairs: [("报价", "báo giá")], speakers: true)
    let object = (try? JSONSerialization.jsonObject(with: Data(config.utf8))) as? [String: Any] ?? [:]
    check(object["audio_format"] as? String == "pcm_s16le" && object["sample_rate"] as? Int == 24_000
          && object["num_channels"] as? Int == 1, "the sound as the microphone gives it")
    check((object["translation"] as? [String: String]) == ["type": "one_way", "target_language": "vi"], "translated into Vietnamese")
    check(object["enable_endpoint_detection"] as? Bool == true && object["max_endpoint_delay_ms"] as? Int == 500,
          "a sentence ends half a second after the speaker stops")
    let context = object["context"] as? [String: Any] ?? [:]
    check((context["translation_terms"] as? [[String: String]]) == [["source": "报价", "target": "báo giá"]], "the glossary goes along")
    check(SonioxEvent.code(for: Language.named("zh-Hant")!) == "zh" && SonioxEvent.code(for: .vietnamese) == "vi", "language codes")

    let glossary = "báo giá = 报价\nMộc Sinh = Mộc Sinh\nFOB = giá FOB\n\n"
    let into = SonioxEvent.glossary(glossary, intoVietnamese: true)
    let from = SonioxEvent.glossary(glossary, intoVietnamese: false)
    check(into.terms.contains("报价") && into.terms.contains("FOB"), "every term is a word to hear right")
    check(into.pairs.map { "\($0.source)>\($0.target)" } == ["报价>báo giá", "FOB>giá FOB"], "into Vietnamese: \(into.pairs)")
    check(from.pairs.map { "\($0.source)>\($0.target)" } == ["báo giá>报价", "giá FOB>FOB"], "from Vietnamese: \(from.pairs)")

    // A sentence shown word by word as it is said, then finished at its end, translation and all.
    var transcript = SonioxTranscript(target: "vi")
    check(transcript.take([token("这个", final: false)]).isEmpty && transcript.current.text == "这个", "the guess shows at once")
    check(transcript.take([token("这个"), token("价格", final: false)]).isEmpty && transcript.current.text == "这个价格",
          "settled words, then the guess")
    check(transcript.take([token("价格"), token("Giá", translation: true), token(" này", final: false, translation: true)]).isEmpty
          && transcript.current.translation == "Giá này", "its translation comes as it goes")
    let finished = transcript.take([token(" này", translation: true), token("<end>", language: "")])
    check(finished == [SonioxTranscript.Sentence(text: "这个价格", translation: "Giá này", language: "zh")],
          "finished at its end: \(finished)")
    check(transcript.current.isEmpty && !transcript.ended, "the next starts empty")

    // Ended before its translation came: it waits for it, unless the next sentence begins.
    check(transcript.take([token("好"), token("<end>", language: "")]).isEmpty && transcript.ended, "waits for the translation")
    check(transcript.take([token("Được", translation: true)]) == [SonioxTranscript.Sentence(text: "好", translation: "Được", language: "zh")],
          "the late translation finishes it")
    _ = transcript.take([token("谢谢"), token("<end>", language: "")])
    let next = transcript.take([token("再见", final: false)])
    check(next == [SonioxTranscript.Sentence(text: "谢谢", translation: "", language: "zh")] && transcript.current.text == "再见",
          "the next sentence begins: the last ends as it is")

    // Vietnamese said to a session translating into Vietnamese: nothing to wait for.
    var vietnamese = SonioxTranscript(target: "vi")
    let said = vietnamese.take([token("Chào anh", language: "vi"), token("<fin>", language: "")])
    check(said == [SonioxTranscript.Sentence(text: "Chào anh", translation: "", language: "vi")], "Vietnamese needs no translation")
    check(vietnamese.take([token("<end>", language: "")]).isEmpty && vietnamese.finish() == nil, "an end with nothing said is nothing")

    // A room of several people (`splits`, translated by GPT): a sentence ends where another person
    // starts, where the language changes, and after a long speech's full stop, without waiting for
    // Soniox's end of sentence; pieces of Soniox's translation still coming go to the sentence they
    // belong to, not to the one being said.
    func spoken(_ text: String, _ speaker: String, language: String = "zh", final: Bool = true) -> SonioxToken {
        SonioxToken(text: text, isFinal: final, isTranslation: false, language: language, speaker: speaker)
    }
    check(SonioxEvent.parse(#"{"tokens":[{"text":"好","is_final":true,"language":"zh","speaker":"2","end_ms":1840}]}"#)
          == [.tokens([spoken("好", "2")])], "who said it is read")
    check(object["enable_speaker_diarization"] as? Bool == true, "each word says who said it")
    let alone = SonioxEvent.configMessage(apiKey: "k", model: "stt-rt-v5", target: "zh", hints: ["vi"], terms: [], pairs: [],
                                          speakers: false)
    check(!alone.contains("diarization") && !alone.contains("context"), "the user's turn is one person, no glossary sent empty")
    let told = SonioxEvent.configMessage(apiKey: "k", model: "stt-rt-v5", target: "vi", hints: ["zh", "vi"], terms: [], pairs: [],
                                         speakers: true, about: [("setting", "Business meeting"), ("topic", "báo giá máy CNC")])
    let toldObject = (try? JSONSerialization.jsonObject(with: Data(told.utf8))) as? [String: Any] ?? [:]
    check(((toldObject["context"] as? [String: Any])?["general"] as? [[String: String]])
          == [["key": "setting", "value": "Business meeting"], ["key": "topic", "value": "báo giá máy CNC"]],
          "what the meeting is about goes to Soniox's general context: \(told)")
    var room = SonioxTranscript(target: "vi")
    room.splits = true
    check(room.take([spoken("这个价格", "1"), spoken("可以吗？", "1")], now: 1).isEmpty, "a short question goes on until its end")
    let first = room.take([spoken("Được anh", "2", language: "vi")], now: 2)
    check(first == [SonioxTranscript.Sentence(text: "这个价格可以吗？", language: "zh", speaker: "1")]
          && room.current.text == "Được anh" && room.current.speaker == "2", "another speaker: the first sentence ends \(first)")
    _ = room.take([token("Giá này", translation: true), token(" được không?", final: false, translation: true)], now: 2.3)
    check(room.previews.first?.translation == "Giá này được không?" && room.current.translation.isEmpty,
          "its translation goes to it, not to the next speaker's sentence: \(room.previews)")
    _ = room.take([token(" được không?", translation: true)], now: 2.4)
    let third = room.take([spoken("那我们", "3"), spoken("下周一", "3")], now: 3)
    check(third == [SonioxTranscript.Sentence(text: "Được anh", language: "vi", speaker: "2")], "a third speaker")
    _ = room.take([token("Vậy chúng ta", translation: true)], now: 3.2)
    check(room.previews.isEmpty && room.current.translation == "Vậy chúng ta",
          "once a sentence's translation has its full stop, the next pieces are the current one's")
    let long = room.take([spoken("我们这个月的订单比较多所以交货时间可能要晚一点。", "3"), spoken("另外", "3", final: false)], now: 4)
    check(long.count == 1 && long[0].text.hasPrefix("那我们下周一我们这个月") && room.current.text == "另外",
          "a long speech is translated at its full stop: \(long)")
    let english = room.take([spoken("我觉得没问题。", "3"), spoken("Sounds", "3", language: "en"), spoken(" good", "3", language: "en")], now: 5)
    check(english.count == 1 && english[0].text == "我觉得没问题。" && room.current.text == "Sounds good" && room.current.language == "en",
          "another language after a sentence: \(english)")
    let ok = room.take([spoken("<end>", "")], now: 6)
    check(ok == [SonioxTranscript.Sentence(text: "Sounds good", language: "en", speaker: "3")],
          "the end of a sentence finishes it without waiting for Soniox's translation")
    _ = room.take([token("Tôi thấy", translation: true)], now: 9)
    check(room.previews.isEmpty, "pieces long after are not given to an old sentence")
    var short = SonioxTranscript(target: "vi")
    short.splits = true
    _ = short.take([spoken("好", "1"), spoken("OK", "1", language: "en"), spoken("的", "1")], now: 1)
    check(short.current.text == "好OK的", "a word of English in a short phrase stays in it")
    // A sentence cut where another person began has no full stop in its translation: the pieces
    // after a moment are the next sentence's, even while they keep coming.
    var cutShort = SonioxTranscript(target: "vi")
    cutShort.splits = true
    _ = cutShort.take([spoken("我们这个价格", "1")], now: 1)
    _ = cutShort.take([spoken("好的", "2")], now: 1.2)
    _ = cutShort.take([token("Giá", translation: true)], now: 1.5)
    _ = cutShort.take([token(" của", translation: true)], now: 2.5)
    check(cutShort.previews.first?.translation == "Giá của", "its translation goes to it at first")
    _ = cutShort.take([token(" Được", translation: true)], now: 3.4)
    check(cutShort.previews.isEmpty && cutShort.current.translation == " Được",
          "two seconds on, the next sentence's: \(cutShort.previews) / \(cutShort.current.translation)")
    // Talking on without a full stop: cut at a comma once very long.
    var monologue = SonioxTranscript(target: "vi")
    monologue.splits = true
    let clause = monologue.take([spoken(String(repeating: "我们", count: 20) + "，", "1"), spoken("然后", "1")], now: 1)
    check(clause.count == 1 && clause[0].text.hasSuffix("，") && monologue.current.text == "然后", "a very long speech is cut at a comma")
    check(monologue.take([spoken("我们，", "1")], now: 2).isEmpty, "a short one is not")
    var mixed = SonioxTranscript(target: "zh")
    mixed.splits = true
    let quote = mixed.take([spoken("Anh gửi giúp em cái", "", language: "vi"), spoken(" quotation", "", language: "en"),
                            spoken(" nhé", "", language: "vi")], now: 1)
    check(quote.isEmpty && mixed.current.text == "Anh gửi giúp em cái quotation nhé",
          "an English word inside a Vietnamese sentence stays in it: \(quote)")

    var meter = CostMeter()
    meter.streamed(seconds: 3_600, soniox: true)
    check(abs(meter.dollars - 0.18) < 1e-9, "Soniox: about 0.18 USD an hour sent")
    let soniox = CostMeter.perHour(live: true, soniox: true, model: .luna, readAloud: true, openAIVoice: false)
    let checked = CostMeter.perHour(live: true, soniox: true, translatesAgain: true, model: .luna, readAloud: true, openAIVoice: false)
    check(abs(checked - 0.18 - 0.0756) < 1e-9, "each sentence translated again by Luna: about 0.08 USD more an hour")
    check(abs(soniox - 0.18) < 1e-9 && soniox < CostMeter.perHour(live: true, model: .luna, readAloud: true, openAIVoice: false) / 10,
          "over ten times cheaper than Dịch liền")
}

print(failed == 0 ? "Conversation: \(total) checks passed" : "Conversation: \(failed) of \(total) failed")
exit(failed == 0 ? 0 : 1)
