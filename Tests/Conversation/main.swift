// Checks of the Hội thoại tab's pure parts, run in CI with:
// swiftc -O Shared/Config.swift Shared/SharedStore.swift Shared/History.swift Shared/LanguageDetector.swift \
//   Shared/Translator.swift App/Conversation/PCM.swift App/Conversation/SpeechSegmenter.swift \
//   App/Conversation/TranscriptFilter.swift App/Conversation/LiveTranslation.swift App/Conversation/SonioxTranslation.swift App/Conversation/SpeechServices.swift \
//   App/Conversation/GoogleVoice.swift App/Conversation/ConversationCost.swift App/Conversation/Trade.swift Tests/Conversation/main.swift \
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
// A voice too quiet to begin an utterance over a noisy room is still sound above it: Dịch liền sends
// it on (it sent nothing, and the voice was never translated).
do {
    var segmenter = SpeechSegmenter()
    _ = segmenter.push(quiet(3, db: -50))
    check(!segmenter.audible && !segmenter.speaking, "the room's own noise is neither")
    let far = zip(speech(2, db: -42), quiet(2, db: -50)).map { Int16(clamping: Int($0) + Int($1)) }
    var audible = 0
    var speaking = 0
    for start in stride(from: 0, to: far.count, by: 2_400) {
        _ = segmenter.push(Array(far[start..<min(far.count, start + 2_400)]))
        if segmenter.audible { audible += 1 }
        if segmenter.speaking { speaking += 1 }
    }
    check(speaking == 0 && audible >= 16, "a far, quiet voice: audible \(audible) tenths of 20, an utterance \(speaking)")
    _ = segmenter.push(quiet(1, db: -50))
    check(!segmenter.audible, "and quiet again after it")
}

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
    guardEcho.stopped(at: 13)
    check(guardEcho.isEcho("we will send the quotation before friday", from: 10.3, to: 13.2), "the phone's sentence heard back")
    check(!guardEcho.isEcho("Can you lower the price a little?", from: 11, to: 13), "someone else speaking over it")
    check(!guardEcho.isEcho("OK, so you send the quotation before Friday?", from: 15, to: 17),
          "said after the phone stopped: someone repeating it, to confirm")
    guardEcho.spoke("我们周五之前发报价", at: 40)
    check(guardEcho.isEcho("我们周五前发报价。", from: 40.2, to: 41.5), "Chinese echo by characters, the phone still speaking")
    check(!guardEcho.isEcho("价格太高了", from: 40.5, to: 41.5), "Chinese reply is not echo")
    guardEcho.stopped(at: 42)
    check(guardEcho.isEcho("周五前发报价", from: 42.5, to: 43.4), "a moment after the phone stopped: still its echo")
    check(!guardEcho.isEcho("好的，周五之前发报价。", from: 44, to: 46), "a partner confirming it afterwards is kept")
    guardEcho.spoke("Chúng tôi sẽ gửi báo giá cho anh trước thứ Sáu và giao hàng trong hai tuần.", at: 50)
    guardEcho.stopped(at: 54)
    guardEcho.spoke("Giá này là giá tốt nhất của chúng tôi rồi.", at: 54)
    guardEcho.stopped(at: 57)
    check(guardEcho.isEcho("giá này là giá tốt nhất của chúng tôi", from: 54.5, to: 57), "the phone's Vietnamese heard back")
    check(guardEcho.isEcho("chúng tôi sẽ gửi báo giá cho anh trước thứ bảy và giao hàng trong hai tuần", from: 50.2, to: 54),
          "heard back with a mistake")
    check(!guardEcho.isEcho("Anh ơi giá của chúng tôi là tốt rồi", from: 55, to: 57), "a colleague's sentence of common words is not echo")
    check(guardEcho.isEcho("Cảm ơn", from: 56, to: 56.5) == false && guardEcho.isEcho("giá tốt", from: 56, to: 56.5),
          "a word or two: all said by the phone")
    check(!guardEcho.isEcho("giá tốt", from: 60, to: 60.5), "but not after it")
    guardEcho.spoke("这个价格可以吗？", at: 60)
    guardEcho.stopped(at: 61)
    check(!guardEcho.isEcho("这个呢", from: 60.2, to: 61) && guardEcho.isEcho("这个价格", from: 60.2, to: 61),
          "a short sentence: all of it said by the phone")
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

    var stopped = LiveCaptions()
    _ = stopped.heard("好的，下周一确认。", now: 0)
    check(stopped.tick(now: 0.5) == nil && stopped.tick(now: 0.9)?.text == "好的，下周一确认。",
          "after a full stop, a short quiet moment ends the caption (GPT waited 2.5 s for it)")

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
check(Prompt.spoken(into: "Vietnamese").contains("repeats or confirms") && !Prompt.spoken(into: "Vietnamese").contains("never repeat"),
      "a number confirmed again is translated again, not dropped as said before")
check(Prompt.meeting().contains("what one participant said") && !Prompt.meeting().contains("written by the partner"),
      "the others' words in a meeting: said by one of several people, not a message to the user")
// What goes wrong most with Chinese and English partners: amounts counted by 万, names, a 斤.
for prompt in [Prompt.meeting(), Prompt.incoming()] {
    check(prompt.contains("100万 = 1 triệu, 1亿 = 100 triệu") && prompt.contains("million, billion = nghìn, triệu, tỷ")
          && prompt.contains("(Hán Việt)") && prompt.contains("cân (0,5 kg)") && prompt.contains("\n- Chinese names"),
          "into Vietnamese: amounts converted exactly, Chinese names in Hán Việt, as a list item of their own")
}
check(Prompt.outgoing(target: Language.named("zh")!).contains("1 triệu = 100万") && Prompt.outgoing(target: Language.named("zh-Hant")!).contains("1 tỷ = 10亿"),
      "into Chinese: a Vietnamese million is 100万")
check(Prompt.outgoing(target: Language.named("en")!).contains("triệu = million") && !Prompt.outgoing(target: Language.named("en")!).contains("万"),
      "into English: Vietnamese amounts, the English way")
check(Prompt.spoken(into: "Vietnamese").contains("sounds the same"), "Chinese misheard: a character that sounds the same")
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
    /// A tenth of a second of sound, marked `n`.
    func chunk(_ n: UInt8, seconds: Double = 0.1) -> Data { Data(repeating: n, count: Int(seconds * 48_000)) }
    var sent: [Data] = []
    for n in 0..<20 { sent += gate.feed(chunk(UInt8(n)), speech: false) }
    check(sent.isEmpty, "a quiet room is not sent")
    sent = gate.feed(chunk(100), speech: true)
    check(sent == [15, 16, 17, 18, 19].map { chunk($0) } + [chunk(100)], "speech goes with the half second before it")
    var after = 0
    for _ in 0..<100 { after += gate.feed(chunk(0), speech: false).count }
    check(after == 30, "three seconds after the last word, then nothing, got \(after) tenths")
    check(gate.feed(chunk(7), speech: true).count == 6, "the next words open it again, with the half second before")
    _ = gate.feed(chunk(1), speech: true)
    gate.reset()
    check(gate.feed(chunk(2), speech: false).isEmpty, "reset closes it")
    // The microphone's pieces are not always a tenth of a second: the gate counts the sound itself.
    gate.reset()
    for n in 0..<30 { _ = gate.feed(chunk(UInt8(n), seconds: 0.05), speech: false) }
    sent = gate.feed(chunk(200, seconds: 0.05), speech: true)
    check(sent.count == 11 && sent.dropLast().reduce(0) { $0 + $1.count } == 24_000,
          "half a second before, in pieces of a twentieth: \(sent.count) pieces")
    after = 0
    for _ in 0..<100 { after += gate.feed(chunk(0, seconds: 0.05), speech: false).count }
    check(after == 60, "three seconds after, in pieces of a twentieth: \(after)")
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
    check(SonioxEvent.parse(message) == [.processed(finalMs: 760, totalMs: 880),
                                         .tokens([token("你好"), token("Xin chào", final: false, translation: true), token("<end>", language: "")])],
          "tokens read: said, translated, the end of a sentence; and how far Soniox has heard and settled, first")
    check(SonioxEvent.parse(#"{"tokens":[],"finished":true}"#) == [.finished], "finished")
    check(SonioxEvent.parse(#"{"error_code":401,"error_message":"Invalid API key."}"#) == [.failed(code: 401, message: "Invalid API key.")],
          "a wrong key")
    check(SonioxEvent.parse(#"{"tokens":[],"total_audio_proc_ms":100}"#) == [.tokens([])] && SonioxEvent.parse("not json").isEmpty,
          "an empty list is told (Soniox's guess taken back), garbage is not")

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

    // The trade's terms (frozen seafood, meat and fruit): all of them for GPT; for Soniox, those of the
    // partners' language, after the user's own glossary, while its context has room.
    check(Set(Trade.terms.map(\.vietnamese)).count == Trade.terms.count && Set(Trade.terms.map(\.chinese)).count == Trade.terms.count
          && Trade.terms.allSatisfy { !$0.vietnamese.isEmpty && !$0.chinese.isEmpty }, "each term once, in Vietnamese and Chinese")
    check(Trade.prompt.contains("\n冻品 / frozen goods = hàng đông lạnh") && Trade.prompt.contains("\n浦寨 = Pò Chài (đối diện Tân Thanh)")
          && Trade.prompt.components(separatedBy: "\n").count == Trade.terms.count + 4, "GPT gets every term, one a line")
    check(Trade.pairs(for: "zh").count == Trade.terms.count && Trade.pairs(for: "en").allSatisfy { !$0.other.isEmpty }
          && Trade.pairs(for: "en").count < Trade.terms.count && Trade.pairs(for: "ja").isEmpty,
          "Soniox gets the terms of the partners' language, Chinese or English")
    let roomTerms = SonioxEvent.glossary(glossary, intoVietnamese: true, extra: Trade.pairs(for: "zh"))
    check(roomTerms.pairs.first.map { "\($0.source)>\($0.target)" } == "报价>báo giá" && roomTerms.pairs.filter { $0.source == "报价" }.count == 1
          && roomTerms.pairs.contains { $0.source == "冻品" && $0.target == "hàng đông lạnh" } && roomTerms.pairs.count == Trade.terms.count + 1,
          "the room's session: the user's glossary first, then every trade term not in it: \(roomTerms.pairs.count)")
    check(roomTerms.terms.contains("榴莲") && !roomTerms.terms.contains("sầu riêng"), "the partners' words to hear in the room")
    let mineTerms = SonioxEvent.glossary(glossary, intoVietnamese: false, extra: Trade.pairs(for: "zh"))
    check(mineTerms.pairs.contains { $0.source == "sầu riêng" && $0.target == "榴莲" } && mineTerms.terms.contains("sầu riêng"),
          "the user's session: their Vietnamese to hear, and into Chinese")
    // Soniox refuses a context over about 10,000 characters: however long the user's glossary, it stays well under.
    let about = [(key: "setting", value: String(repeating: "x", count: 90)), Trade.about,
                 (key: "topic", value: String(repeating: "chủ đề ", count: 28)), (key: "user", value: String(repeating: "u", count: 300))]
    for extra in [Trade.pairs(for: "zh"), Trade.pairs(for: "en")] {
        for intoVietnamese in [true, false] {
            let full = SonioxEvent.glossary(glossary, intoVietnamese: intoVietnamese, extra: extra)
            let config = SonioxEvent.configMessage(apiKey: "k", model: "stt-rt-v5", target: "vi", hints: ["zh", "vi"], terms: full.terms,
                                                   pairs: full.pairs, speakers: true, about: about)
            let sources = Set(full.pairs.map { $0.source })
            check(config.count < 8_000 && extra.allSatisfy { sources.contains(intoVietnamese ? $0.other : $0.vietnamese) },
                  "the whole trade fits: \(config.count) characters")
        }
    }
    let longGlossary = (1...400).map { "thuật ngữ số \($0) = 术语\($0)" }.joined(separator: "\n")
    let crowded = SonioxEvent.glossary(longGlossary, intoVietnamese: true, extra: Trade.pairs(for: "en"))
    let crowdedConfig = SonioxEvent.configMessage(apiKey: "k", model: "stt-rt-v5", target: "vi", hints: ["en", "vi"], terms: crowded.terms,
                                                  pairs: crowded.pairs, speakers: true, about: about)
    check(crowdedConfig.count < 8_500 && crowded.pairs.first?.source == "术语1" && crowded.terms.first == "thuật ngữ số 1",
          "a very long glossary: its first lines kept, the context under Soniox's limit: \(crowdedConfig.count) characters")

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
    // Soniox sends each piece of translation right after the words it translates, and settles the
    // stream from its start: a piece belongs to the sentence of the words just before it.
    var room = SonioxTranscript(target: "vi")
    room.splits = true
    check(room.take([spoken("这个价格", "1"), spoken("可以吗？", "1")]).isEmpty, "a short question goes on until its end")
    _ = room.take([token("Giá này", translation: true), token(" được không?", final: false, translation: true)])
    check(room.current.translation == "Giá này được không?", "Soniox's translation shows on the sentence being said, as it comes")
    let first = room.take([token(" được không?", translation: true), spoken("Được anh", "2", language: "vi")])
    check(first == [SonioxTranscript.Sentence(text: "这个价格可以吗？", translation: "Giá này được không?", language: "zh", speaker: "1")]
          && room.current.text == "Được anh" && room.current.speaker == "2" && room.current.translation.isEmpty,
          "another speaker: the first sentence ends, with its translation (it came before the next words): \(first)")
    let long = room.take([spoken("那我们下周一确认订单", "3"), spoken("因为这个月比较忙。", "3")])
    check(long == [SonioxTranscript.Sentence(text: "Được anh", language: "vi", speaker: "2"),
                   SonioxTranscript.Sentence(text: "那我们下周一确认订单因为这个月比较忙。", language: "zh", speaker: "3")],
          "a third speaker, whose long speech is cut at its full stop: \(long)")
    _ = room.take([token("Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng", translation: true)])
    check(room.previews.map(\.translation) == ["Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng"] && room.current.translation.isEmpty,
          "the translation after a cut at a full stop is the cut sentence's: \(room.previews)")
    _ = room.take([token(" vì tháng này", final: false, translation: true), spoken("另外", "3", final: false),
                   token("Ngoài ra", final: false, translation: true)])
    check(room.previews.first?.translation == "Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng vì tháng này"
          && room.current.translation == "Ngoài ra",
          "guessed pieces: before the next guessed words, the cut sentence's; after them, the one being said")
    _ = room.take([token(" vì tháng này khá bận.", translation: true), spoken("另外", "3")])
    check(room.previews.map(\.translation) == ["Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng vì tháng này khá bận."]
          && room.current.text == "另外",
          "its last piece still shows with the next words settling: \(room.previews)")
    _ = room.take([token("Ngoài ra", translation: true)])
    check(room.previews.isEmpty && room.current.translation == "Ngoài ra", "after them, every piece is the new sentence's")
    let english = room.take([spoken("我觉得没问题。", "3"), spoken("Sounds", "3", language: "en"), spoken(" good", "3", language: "en")])
    check(english.count == 1 && english[0].text == "另外我觉得没问题。" && room.current.text == "Sounds good" && room.current.language == "en",
          "another language after a sentence: \(english)")
    // Soniox's end of sentence without a full stop is a pause: the sentence waits (the model ends it
    // after a quiet moment), and its translation is still its own.
    check(room.take([spoken("<end>", "")]).isEmpty && room.ended, "an end without a full stop is a pause")
    _ = room.take([token("Nghe được đấy.", translation: true)])
    check(room.current.translation == "Nghe được đấy." && room.previews.isEmpty, "its translation, after the pause, is its own")
    check(room.finish() == SonioxTranscript.Sentence(text: "Sounds good", translation: "Nghe được đấy.", language: "en", speaker: "3"),
          "ended after a quiet moment, translation and all")
    // After a full stop, Soniox's end finishes it at once, without waiting for its translation.
    let done = room.take([spoken("好的。", "1"), spoken("<end>", "")])
    check(done == [SonioxTranscript.Sentence(text: "好的。", language: "zh", speaker: "1")], "a full stop, then the end: finished at once")
    _ = room.take([token("Được.", translation: true)])
    check(room.previews.first?.translation == "Được." && room.current.isEmpty, "its translation, after the end, is its own")
    // A thought said in two breaths ("if you order five hundred, … we can give five percent") is one
    // sentence: the condition is not translated apart from what it is the condition of.
    var pause = SonioxTranscript(target: "vi")
    pause.splits = true
    check(pause.take([spoken("如果订五百台，", "1"), spoken("<end>", "")]).isEmpty && pause.ended, "a pause after a comma waits")
    check(pause.take([spoken("便宜一点吗？", "1")]).isEmpty && !pause.ended && pause.current.text == "如果订五百台，便宜一点吗？",
          "the speaker goes on: the same sentence")
    check(pause.take([spoken("<end>", "")]).map(\.text) == ["如果订五百台，便宜一点吗？"], "and ends at its question mark")
    _ = pause.take([spoken("那个", "1"), spoken("<end>", "")])
    let other = pause.take([spoken("好", "2"), spoken("的", "2")])
    check(other.map(\.text) == ["那个"] && pause.current.text == "好的" && pause.current.speaker == "2",
          "someone else after a pause: the paused sentence ends at once")
    _ = pause.take([spoken("<end>", "")])
    check(pause.ended && pause.take([spoken("<fin>", "")]).map(\.text) == ["好的"], "a turn ended on purpose ends it, full stop or not")
    // A word or two of another speaker in the middle of a sentence (one Soniox gave to the wrong
    // person, or said over the speaker) does not cut it: when the speaker goes on, they stay in it.
    var over = SonioxTranscript(target: "vi")
    over.splits = true
    check(over.take([spoken("这个价格", "1"), spoken("可以", "2")]).isEmpty && over.current.text == "这个价格可以",
          "another speaker's word in the middle of a sentence is held")
    check(over.take([spoken("便宜吗？", "1")]).isEmpty, "the speaker goes on")
    check(over.take([spoken("<end>", "")]) == [SonioxTranscript.Sentence(text: "这个价格可以便宜吗？", language: "zh", speaker: "1")],
          "one sentence, the speaker's")
    // Theirs once they say three syllables: the sentence before ends without them.
    _ = over.take([spoken("这个型号", "1"), spoken("好的", "2")])
    let theirs = over.take([spoken("没问题", "2")])
    check(theirs == [SonioxTranscript.Sentence(text: "这个型号", language: "zh", speaker: "1")]
          && over.current.text == "好的没问题" && over.current.speaker == "2", "three syllables: theirs, a sentence of their own")
    _ = over.take([spoken("<end>", "")])
    // A sentence's language is the one most of it is said in, not its first word's.
    var lead = SonioxTranscript(target: "vi")
    lead.splits = true
    let mostly = lead.take([spoken("Dạ, ", "1", language: "vi"), spoken("王总，我们下周一确认订单。", "1"), spoken("<end>", "")])
    check(mostly.first?.language == "zh", "a Vietnamese word, then Chinese: Chinese: \(mostly)")
    check(lead.take([spoken("OK，", "2", language: "en"), spoken("那我们下周一确认", "2")]).isEmpty && lead.current.language == "zh",
          "an English word, then Chinese: Chinese")
    // Soniox's guess at the translation, in the message that ends the sentence, is not kept twice
    // when it settles in the next.
    var twice = SonioxTranscript(target: "vi")
    twice.splits = true
    let ended = twice.take([spoken("好的。", "1"), spoken("<end>", ""), token("Được", final: false, translation: true)])
    check(ended.map(\.text) == ["好的。"] && twice.previews.map(\.translation) == ["Được"], "its guessed translation shows")
    _ = twice.take([token("Được rồi.", translation: true)])
    check(twice.previews.map(\.translation) == ["Được rồi."], "settled, it replaces the guess: \(twice.previews)")
    // How far into the words said the translation has come (each piece follows the words it
    // translates): the words after that are still waiting for theirs.
    var reach = SonioxTranscript(target: "vi")
    reach.splits = true
    func at(_ text: String, _ start: Int, final: Bool = true) -> SonioxToken {
        SonioxToken(text: text, isFinal: final, isTranslation: false, language: "zh", speaker: "1", startMs: start, endMs: start + 400)
    }
    _ = reach.take([at("这个月的订单", 0), at("我们已经", 500, final: false)])
    check(reach.current.coveredMs == nil && reach.current.endMs == 900, "words without a translation yet: none of them reached")
    _ = reach.take([token("Đơn hàng tháng này", translation: true), at("我们已经", 500, final: false)])
    check(reach.current.coveredMs == 400, "a settled piece reaches the words settled before it: \(reach.current.coveredMs ?? -1)")
    _ = reach.take([at("我们已经", 500), at("安排生产了，", 1000, final: false), token(" chúng tôi đã", final: false, translation: true)])
    check(reach.current.coveredMs == 1400, "a guessed piece reaches the guessed words before it: \(reach.current.coveredMs ?? -1)")
    _ = reach.take([at("安排生产了，", 1000), token(" chúng tôi đã sắp xếp sản xuất,", translation: true)])
    check(reach.current.coveredMs == 1400, "settled, as far")
    let longSpeech = reach.take([at("预计下周三可以发货。", 1500)])
    check(longSpeech.count == 1 && reach.previews.first?.coveredMs == 1400, "a long speech cut at its full stop: its translation still to come")
    _ = reach.take([token(" dự kiến thứ Tư tuần sau giao hàng.", translation: true)])
    check(reach.previews.first?.coveredMs == 1900, "its last piece reaches its last word: \(reach.previews.first?.coveredMs ?? -1)")
    // The room's session hears the user's turns too: their voice is taken out, a partner's is not.
    var voices = MyVoiceFilter()
    func voiced(_ text: String, _ speaker: String, _ language: String, at second: Double) -> SonioxToken {
        SonioxToken(text: text, isFinal: true, isTranslation: false, language: language, speaker: speaker,
                    startMs: Int(second * 1000), endMs: Int(second * 1000) + 300)
    }
    check(voices.filter([voiced("这个价格", "1", "zh", at: 1)], origin: 0).count == 1, "before any turn, everything passes")
    voices.began(at: 10)
    let inTurn = voices.filter([voiced("Anh gửi", "2", "vi", at: 10.2), voiced("OK", "2", "en", at: 11), token("Được", translation: true),
                                voiced("等一下", "1", "zh", at: 11.5), token("Đợi chút", translation: true),
                                voiced("hello", "3", "en", at: 12)], origin: 0)
    check(inTurn.map(\.text) == ["等一下", "Đợi chút"],
          "in a turn: the user's words and their translation go, a partner's voice stays, an unknown one goes: \(inTurn.map(\.text))")
    voices.ended(at: 13)
    check(voices.filter([voiced("好的", "3", "zh", at: 13.2)], origin: 0).isEmpty, "a moment after the turn, still the user's")
    check(voices.filter([voiced("好的", "3", "zh", at: 14)], origin: 0).count == 1, "after it, everyone passes")
    check(voices.filter([voiced("vâng", "2", "vi", at: 1.5)], origin: 0).count == 1
          && voices.filter([voiced("vâng", "2", "vi", at: 1.5)], origin: 10).isEmpty,
          "a session opened again: its times count from where its sound begins")
    // A partner's word in Vietnamese in a turn ("vâng") does not make them the user for the rest of the
    // meeting; a voice heard saying one word in another language is not yet known for a partner's.
    var vâng = MyVoiceFilter()
    _ = vâng.filter([voiced("这个价格可以吗", "1", "zh", at: 1), voiced("OK", "5", "en", at: 3)], origin: 0)
    vâng.began(at: 10)
    _ = vâng.filter([voiced("vâng", "1", "vi", at: 10.2)], origin: 0)
    check(vâng.filter([voiced("等一下", "1", "zh", at: 11)], origin: 0).count == 1, "a partner's 'vâng' in a turn: still a partner")
    check(vâng.filter([voiced("deal", "5", "en", at: 11.5)], origin: 0).isEmpty, "one English word outside the turns: not yet a partner")
    vâng.ended(at: 12)
    vâng.began(at: 20)
    check(vâng.filter([voiced("价格不能再低了", "1", "zh", at: 21)], origin: 0).count == 1, "in a later turn too")
    // A voice that said more in Vietnamese in the turns than in another language outside them is the user's.
    _ = vâng.filter([voiced("Dạ bên em đồng ý với giá này", "1", "vi", at: 22)], origin: 0)
    check(vâng.filter([voiced("OK", "1", "en", at: 23)], origin: 0).isEmpty, "the user's own voice, whatever number it had")
    // The room's session opened again numbers its speakers anew: nobody is known yet.
    vâng.newSession()
    _ = vâng.filter([voiced("好的", "3", "zh", at: 1)], origin: 30)
    vâng.began(at: 35)
    check(vâng.filter([voiced("等一下", "1", "zh", at: 6)], origin: 30).isEmpty, "after it is opened again, numbers known before mean nothing")
    // A pause is not ended while the speaker's next words are still Soniox's guess: ended with them,
    // they came again as the next sentence once settled.
    var resumed = SonioxTranscript(target: "vi")
    resumed.splits = true
    _ = resumed.take([spoken("如果你们订五百台，", "1"), spoken("<end>", "")])
    check(!resumed.hasGuess, "paused, nothing guessed")
    _ = resumed.take([spoken("我们可以", "1", final: false)])
    check(resumed.ended && resumed.hasGuess && resumed.showsGuess, "the speaker goes on: guessed")
    check(resumed.take([spoken("我们可以便宜。", "1"), spoken("<end>", "")]).map(\.text) == ["如果你们订五百台，我们可以便宜。"],
          "settled: one sentence")
    // A guess taken back (an empty list) shows nothing more.
    _ = resumed.take([spoken("嗯", "2", final: false)])
    check(resumed.take([]).isEmpty && resumed.current.isEmpty && !resumed.showsGuess, "a guess taken back is gone")
    // The user begins to speak: the room's sentence ends as at Soniox's end of a sentence (which Soniox
    // does not send while the user talks on: the room's session hears their turn too).
    var pressed = SonioxTranscript(target: "vi")
    pressed.splits = true
    _ = pressed.take([spoken("这个价格可以吗？", "1")])
    check(pressed.endHere().map(\.text) == ["这个价格可以吗？"], "a question ends as the user begins to speak")
    _ = pressed.take([spoken("那个", "2")])
    check(pressed.endHere().isEmpty && pressed.ended, "without a full stop: a pause")
    var stillGuessed = SonioxTranscript(target: "vi")
    stillGuessed.splits = true
    _ = stillGuessed.take([spoken("这个价格", "1"), spoken("可以吗？", "1", final: false)])
    check(stillGuessed.endHere().isEmpty && !stillGuessed.ended, "words still guessed: they settle first")
    var short = SonioxTranscript(target: "vi")
    short.splits = true
    _ = short.take([spoken("好", "1"), spoken("OK", "1", language: "en"), spoken("的", "1")])
    check(short.current.text == "好OK的", "a word of English in a short phrase stays in it")
    // A speaker cut by another: the next speaker's first piece of translation is theirs at once. It
    // used to go to the sentence before for a few seconds, and the new one showed no Vietnamese.
    var cutShort = SonioxTranscript(target: "vi")
    cutShort.splits = true
    _ = cutShort.take([spoken("我们这个价格", "1")])
    _ = cutShort.take([token("Giá này của chúng tôi", translation: true)])
    check(cutShort.take([spoken("好的", "2")]).isEmpty, "two syllables of another speaker: held")
    _ = cutShort.take([token("Được", translation: true)])
    let cut = cutShort.take([spoken("没问题", "2")])
    _ = cutShort.take([token(", không vấn đề", translation: true)])
    check(cut.first?.translation == "Giá này của chúng tôi" && cutShort.current.translation == "Được, không vấn đề"
          && cutShort.previews.isEmpty, "the next speaker's translation is theirs: \(cutShort.previews) / \(cutShort.current)")
    check(SonioxEvent.parse(#"{"tokens":[{"text":"好","is_final":true,"start_ms":1700,"end_ms":1840}]}"#)
          == [.tokens([SonioxToken(text: "好", isFinal: true, isTranslation: false, language: "", startMs: 1700, endMs: 1840)])],
          "where a word begins is read")
    var timed = SonioxTranscript(target: "vi")
    _ = timed.take([SonioxToken(text: "好", isFinal: true, isTranslation: false, language: "zh", startMs: 1700, endMs: 1840),
                    SonioxToken(text: "的", isFinal: false, isTranslation: false, language: "zh", startMs: 1900, endMs: 2100)])
    check(timed.current.startMs == 1700 && timed.current.endMs == 2100, "a sentence says where its words begin and end")
    // Talking on without a full stop: cut at a comma once very long.
    var monologue = SonioxTranscript(target: "vi")
    monologue.splits = true
    let clause = monologue.take([spoken(String(repeating: "我们", count: 20) + "，", "1"), spoken("然后", "1")])
    check(clause.count == 1 && clause[0].text.hasSuffix("，") && monologue.current.text == "然后", "a very long speech is cut at a comma")
    check(monologue.take([spoken("我们，", "1")]).isEmpty, "a short one is not")
    var mixed = SonioxTranscript(target: "zh")
    mixed.splits = true
    let quote = mixed.take([spoken("Anh gửi giúp em cái", "", language: "vi"), spoken(" quotation", "", language: "en"),
                            spoken(" nhé", "", language: "vi")])
    check(quote.isEmpty && mixed.current.text == "Anh gửi giúp em cái quotation nhé",
          "an English word inside a Vietnamese sentence stays in it: \(quote)")

    var meter = CostMeter()
    meter.streamed(seconds: 3_600, soniox: true)
    check(abs(meter.dollars - 0.18) < 1e-9, "Soniox: about 0.18 USD an hour sent")
    let soniox = CostMeter.perHour(live: true, soniox: true, model: .luna, readAloud: true, openAIVoice: false)
    let checked = CostMeter.perHour(live: true, soniox: true, translatesAgain: true, model: .luna, readAloud: true, openAIVoice: false)
    check(abs(checked - 0.18 - 2 * 0.0756) < 1e-9, "each sentence translated again by Luna, and about once ahead: about 0.15 USD more an hour")
    check(abs(soniox - 0.18) < 1e-9 && soniox < CostMeter.perHour(live: true, model: .luna, readAloud: true, openAIVoice: false) / 10,
          "over ten times cheaper than Dịch liền")
}

print(failed == 0 ? "Conversation: \(total) checks passed" : "Conversation: \(failed) of \(total) failed")
exit(failed == 0 ? 0 : 1)
