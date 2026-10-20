// Checks of the Hội thoại tab's pure parts, run in CI with:
// swiftc -O Shared/Config.swift Shared/SharedStore.swift Shared/History.swift Shared/LanguageDetector.swift \
//   Shared/Translator.swift App/Conversation/PCM.swift App/Conversation/SpeechSegmenter.swift \
//   App/Conversation/TranscriptFilter.swift App/Conversation/LiveTranslation.swift App/Conversation/SpeechServices.swift \
//   Tests/Conversation/main.swift -o conversation-tests && ./conversation-tests
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

print(failed == 0 ? "Conversation: \(total) checks passed" : "Conversation: \(failed) of \(total) failed")
exit(failed == 0 ? 0 : 1)
