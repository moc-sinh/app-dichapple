import Foundation

var failed = 0
@MainActor func check(_ ok: Bool, _ what: String) {
    if !ok { failed += 1; print("FAIL:", what) } else { print("ok:", what) }
}

func speech(_ seconds: Double) -> [Int16] {
    (0..<Int(seconds * 24_000)).map { i in
        let t = Double(i) / 24_000
        return Int16(sin(2 * .pi * 180 * t) * 2000 * max(0.08, abs(sin(.pi * 4 * t))))
    }
}

func w(_ text: String, _ speaker: String, _ language: String = "zh", final: Bool = true, start: Int? = nil, end: Int? = nil) -> SonioxToken {
    SonioxToken(text: text, isFinal: final, isTranslation: false, language: language, speaker: speaker, startMs: start, endMs: end)
}

func t(_ text: String, final: Bool = true) -> SonioxToken {
    SonioxToken(text: text, isFinal: final, isTranslation: true, language: "vi")
}

final class Calls: @unchecked Sendable {
    let lock = NSLock()
    var translated: [(text: String, target: String, context: [String], speaker: String, topic: String, model: TranslationModel)] = []
    var spares: [String] = []
    var spoken: [String] = []
    var translateDelay: UInt64 = 300_000_000
    var voiceBytes = 96_000
    var warmUps = 0
    /// Returns how many times `text` was asked before.
    func add(_ text: String, _ target: String, _ context: SpokenContext, _ model: TranslationModel, spare: Bool) -> Int {
        let lines = context.lines.map { "\($0.speaker): \($0.text)" + ($0.translation.isEmpty ? "" : " → \($0.translation)") }
        lock.lock(); defer { lock.unlock() }
        if spare { spares.append(text) }
        let before = translated.filter { $0.text == text }.count
        translated.append((text, target, lines, context.speaker, context.topic, model))
        return before
    }
    func say(_ text: String) { lock.lock(); spoken.append(text); lock.unlock() }
}

let fixed: [String: String] = [
    "那我们下周一确认订单。": "Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng.",
    "Anh gửi giúp em báo giá nhé": "请把报价发给我。",
]

@main
struct Simulation {
    @MainActor
    static func main() async {
        func sleep(_ seconds: Double) async { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
        func feed(_ samples: [Int16]) async {
            var offset = 0
            while offset < samples.count {
                AudioIO.current?.feed(Array(samples[offset..<min(samples.count, offset + 2_400)]))
                offset += 2_400
                await sleep(0.1)
            }
        }

        SharedStore.apiKey = "sk-sim-0000000000000000000000"
        SharedStore.sonioxKey = "soniox-sim"
        SharedStore.defaults.set("gpt-6.1-sol", forKey: SharedStore.Key.model)
        let calls = Calls()
        let model = ConversationModel()
        model.engine = .soniox
        model.readAloud = true
        model.partnerCode = "zh"
        model.voice = "marin"
        model.quality = .economy
        model.services = ConversationServices(
            transcribe: { _, _, _ in .init(text: "", language: nil) },
            translate: { _, text, target, context, model, spare in
                let before = calls.add(text, target.code, context, model, spare: spare)
                // The first request for a sentence marked STALL hangs, as on a stalled phone network.
                try await Task.sleep(nanoseconds: text.contains("STALL") && before == 0 ? 30_000_000_000 : calls.translateDelay)
                if text.contains("FAIL") { throw URLError(.timedOut) }
                return Translation(text: fixed[text] ?? "[\(target.code)] \(text)", meaning: nil, note: nil, language: target)
            },
            speak: { _, text, _, _, _ in
                calls.say(text)
                return Data(count: calls.voiceBytes)
            },
            warmUp: { calls.warmUps += 1 })
        Sim.speed = 4
        model.setTopic("  báo giá máy CNC ")

        // The transcript in blocks: one person's sentences said one after another under their name once.
        do {
            typealias E = ConversationModel.Entry
            let t0 = Date()
            func e(_ seconds: Double, mine: Bool = false, speaker: String = "", language: String = "zh", target: String = "vi") -> E {
                E(id: UUID(), at: t0.addingTimeInterval(seconds), mine: mine, language: language, text: "x", translation: "",
                  target: target, state: .done, speaker: speaker)
            }
            let entries = [e(0, speaker: "1"), e(2, speaker: "1", language: ""), e(4, speaker: "2"), e(5, speaker: "2", language: "en"),
                           e(6, mine: true, language: "vi", target: "zh"), e(9, mine: true, language: "vi", target: "zh"),
                           e(10), e(11), e(80, mine: true, language: "vi", target: "zh"), e(200, mine: true, language: "vi", target: "zh")]
            let blocks = ConversationModel.blocks(entries).map { $0.map { entry in entries.firstIndex { $0.id == entry.id }! } }
            check(blocks == [[0, 1], [2], [3], [4, 5], [6], [7], [8], [9]],
                  "same speaker and language in a row together; another language, a speaker not told, a long pause apart: \(blocks)")
            let long = (0..<8).map { e(Double($0), speaker: "3") }
            check(ConversationModel.blocks(long).map(\.count) == [6, 2], "six sentences at most a block")
        }

        await model.start()
        check(model.running, "listening")
        guard let others = SonioxSession.all.first(where: { $0.target == "vi" }),
              let partner = SonioxSession.all.first(where: { $0.target == "zh" }) else {
            print("FAIL: sessions not opened \(SonioxSession.all.map(\.target))")
            exit(1)
        }
        check(others.speakers && !partner.speakers, "the room's session tells speakers apart, the user's does not")
        check(others.about.contains { $0 == ("topic", "báo giá máy CNC") } && others.about.contains { $0.key == "setting" },
              "Soniox is told what the meeting is about: \(others.about)")
        check(calls.warmUps >= 1, "OpenAI's connection is opened as listening starts")
        check(AudioIO.current?.echo == [true], "Đọc to with Soniox: echo cancelling on")
        let quietBefore = others.sent
        await feed(Array(repeating: 0, count: 24_000))
        check(others.sent - quietBefore > 0.8, "Soniox hears the quiet room too: a far voice is never held back")
        // The microphone's pieces are not even tenths of a second: each goes on whole, at once (the end of
        // one used to wait a tenth of a second for the next).
        let unevenBefore = others.sent
        AudioIO.current?.feed(Array(repeating: 0, count: 2_390))
        await sleep(0.05)
        check(abs(others.sent - unevenBefore - 2_390.0 / 24_000) < 0.0001,
              "the sound goes to Soniox as it is heard, none held back: \(others.sent - unevenBefore) s")

        // Three people talk on without pausing.
        await feed(speech(2))
        check(others.sent > 1.5, "the room's sound goes to Soniox (\(others.sent) s)")
        // Soniox sends each piece of translation right after the words it translates.
        others.say([w("这个价格", "1", start: 1000, end: 1500), w("可以吗？", "1", start: 1600, end: 1900)])
        check(model.entries.count == 1 && model.entries[0].speaker == "1" && model.entries[0].state == .hearing,
              "the first speaker's words show as they come")
        check(calls.warmUps >= 2, "someone starts speaking: OpenAI's connection is made ready for the end of the sentence")
        others.say([t("Giá này"), t(" được không?", final: false)])
        check(model.entries[0].translation == "Giá này được không?" && model.entries[0].state == .hearing,
              "Soniox's translation shows on the sentence as it is said")
        others.say([t(" được không?"), w("Được anh", "2", "vi", start: 2100, end: 2400)])
        check(model.entries.count == 2 && model.entries[0].state == .translating && model.entries[0].text == "这个价格可以吗？"
              && model.entries[0].translation == "Giá này được không?" && model.entries[1].translation.isEmpty,
              "another speaker starts: the first sentence goes to GPT, Soniox's translation shown meanwhile: \(model.entries.map(\.translation))")
        await sleep(0.5)
        check(model.entries[0].state == .done && model.entries[0].translation == "[vi] 这个价格可以吗？",
              "GPT's translation replaces it: \(model.entries[0].translation)")
        check(calls.translated.first?.model == .luna, "the others' words with the economy model")
        others.say([w("那我们下周一", "3", start: 2800, end: 3200), w("确认订单。", "3", end: 3800), w("<end>", "")])
        check(model.entries.count == 3, "a third speaker: \(model.entries.map(\.text))")
        check(model.entries[1].isVietnamese && model.entries[1].translation.isEmpty && model.entries[1].state == .done,
              "a Vietnamese colleague is shown as said")
        check(model.entries[2].state == .translating && model.entries[2].speaker == "3", "the third sentence ends at Soniox's end")
        await sleep(0.6)
        check(model.entries[2].translation == "Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng.", "translated")
        let third = calls.translated.first { $0.text == "那我们下周一确认订单。" }
        check(third?.context == ["Người 1: 这个价格可以吗？ → [vi] 这个价格可以吗？", "Người 2: Được anh"],
              "GPT gets the meeting so far, with who said it and how it was translated: \(third?.context ?? [])")
        check(third?.speaker == "Người 3" && third?.topic == "báo giá máy CNC", "and who says this one, and the topic")
        await sleep(1.5)
        check(calls.spoken == ["[vi] 这个价格可以吗？", "Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng."],
              "Đọc to reads GPT's translations, in order, once each: \(calls.spoken)")

        // Someone is cut by another speaker: the new speaker's first piece of Soniox's translation shows
        // on their sentence at once (it used to go to the sentence before for a few seconds).
        others.say([w("交货期", "1", start: 4000, end: 4400)])
        others.say([t("Thời hạn giao hàng")])
        others.say([w("没问题。", "2", start: 4600, end: 5000), w("<end>", "")])
        others.say([t("Không vấn đề.")])
        let cutBy = Array(model.entries.suffix(2))
        check(cutBy.count == 2 && cutBy[1].translation == "Không vấn đề." && !cutBy[0].translation.contains("Không vấn đề"),
              "the second speaker's translation shows on their sentence: \(cutBy.map(\.translation))")
        await sleep(0.5)
        check(cutBy.allSatisfy { entry in model.entries.first { $0.id == entry.id }?.state == .done }, "both translated by GPT")

        // The phone's own voice heard back while it reads (echo cancelling leaves some): dropped.
        await sleep(1.5)
        calls.voiceBytes = 480_000   // 10 s of voice: 2.5 s at this speed
        others.say([w("那我们下周一确认订单。", "3"), w("<end>", "")])
        // The microphone's sound goes on meanwhile, as it always does.
        await feed(Array(repeating: 0, count: 2_400 * 7))
        check(model.speaking, "the phone reads it")
        let echoAt = Int(others.sent * 1000)
        others.say([w("Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng.", "4", "vi", start: echoAt - 900, end: echoAt), w("<end>", "")])
        check(!model.entries.contains { $0.text.hasPrefix("Vậy thứ Hai") }, "the phone's voice heard back is not someone speaking")
        check(model.entries.allSatisfy { $0.state != .hearing }, "nothing left half-heard")
        await sleep(2.5)
        calls.voiceBytes = 96_000

        // A partner asks, and the user answers at once: the question ends as the button goes down (Soniox
        // sends no end of sentence while the user talks on: the room's session hears their turn too).
        // Voices long enough that the room's would still be read when the user's translation is ready.
        calls.voiceBytes = 480_000
        others.say([w("交货期多长？", "1")])
        // The user speaks.
        model.talk(true)
        check(model.entries.last?.text == "交货期多长？" && model.entries.last?.state == .translating,
              "the partner's question goes to GPT as the user begins: \(String(describing: model.entries.last))")
        let roomBefore = others.sent
        await feed(speech(1.5))
        check(partner.sent > 1, "the user's sound goes to their own session")
        check(others.sent - roomBefore > 1.4, "and the room's session still hears the room")
        // The user's voice, heard by the room's session too, is taken out of it; a partner speaking
        // meanwhile is not.
        let turnAt = Int(others.sent * 1000)
        others.say([w("Anh gửi giúp em", "5", "vi", start: turnAt - 1300, end: turnAt - 800),
                    w("等一下。", "1", start: turnAt - 600, end: turnAt - 200), w("<end>", "")])
        check(!model.entries.contains { $0.text.contains("Anh gửi giúp em") && !$0.mine },
              "the user's voice in the room's session is not someone else")
        check(model.entries.last?.text == "等一下。" && model.entries.last?.mine == false,
              "a partner speaking during the user's turn is heard: \(model.entries.suffix(2).map(\.text))")
        partner.say([w("Anh gửi giúp em báo giá nhé", "", "vi", end: 1400)])
        model.talk(false)
        let mineSent = partner.sent
        await feed(speech(0.2))
        check(partner.sent - mineSent > 0.15, "the sound said just before letting go still goes to the user's session")
        check(partner.finalized == 0, "and only then are their last words settled")
        await sleep(0.3)
        check(partner.finalized == 1, "a moment after letting go")
        partner.say([t("请", final: true), w("<fin>", "")])
        let mine = model.entries.last
        check(mine?.mine == true && mine?.state == .translating && mine?.speaker == "", "the user's sentence goes to GPT")
        await sleep(0.6)
        check(model.entries.last?.translation == "请把报价发给我。", "into the partners' Chinese")
        check(calls.translated.last?.model == .sol, "the user's words with the app's model")
        await sleep(0.1)
        // The room's sentences translated during the user's turn would have been read first (Đọc to), then theirs.
        check(model.speakingMine, "the user's words are read as soon as they are translated, before the room's")
        check(model.speedLogText.contains("bỏ đọc: câu của bạn đọc trước"), "the room's sentences from the user's turn stay on the screen unread")
        calls.voiceBytes = 96_000
        await sleep(2.5)
        await sleep(1)
        check(calls.spoken.last == "请把报价发给我。", "read aloud to the partners")
        // A partner confirming it in the words the phone read it in is someone speaking: the room's
        // session was not even sent the phone's voice reading the user's words.
        let confirmAt = Int(others.sent * 1000)
        others.say([w("好的，我把报价发给你。", "1", start: confirmAt - 1500, end: confirmAt), w("<end>", "")])
        check(model.entries.last?.text == "好的，我把报价发给你。" && model.entries.last?.state == .translating,
              "a partner confirming what the phone read is kept, not taken for its echo")
        await sleep(0.6)

        // GPT fails: asked again a moment later, Soniox's translation still coming in meanwhile (it was
        // kept as it was when GPT failed, cut short); failing again, Soniox's stays, and is read.
        others.say([w("FAIL 这个。", "1"), w("<end>", "")])
        others.say([t("Cái")])
        await sleep(0.6)
        others.say([t(" này.")])
        check(model.entries.last?.state == .translating && model.entries.last?.translation == "Cái này.",
              "GPT failed once: Soniox's translation still comes in")
        await sleep(1.8)
        let failedEntry = model.entries.last
        check(failedEntry?.state == .done && failedEntry?.translation == "Cái này.", "GPT failed again: Soniox's whole translation kept")
        check(calls.translated.filter { $0.text == "FAIL 这个。" }.count == 2, "GPT asked twice")
        await sleep(1)
        check(calls.spoken.last == "Cái này.", "and read")

        // GPT very slow: Soniox's translation is read after five seconds, and not again for GPT's.
        calls.translateDelay = 6_500_000_000
        others.say([w("我们再考虑一下。", "2"), w("<end>", "")])
        others.say([t("Chúng tôi sẽ cân nhắc thêm.")])
        await sleep(5.6)
        check(calls.spoken.last == "Chúng tôi sẽ cân nhắc thêm.", "slow GPT: Soniox's translation is read meanwhile")
        let readBefore = calls.spoken.count
        await sleep(1.5)
        check(model.entries.last?.translation == "[vi] 我们再考虑一下。" && calls.spoken.count == readBefore,
              "GPT's comes later: shown, not read again")
        calls.translateDelay = 100_000_000

        // GPT's connection stalls: asked again after 2.5 s, the second answer is taken.
        others.say([w("STALL 我们明天发货。", "1"), w("<end>", "")])
        await sleep(3.2)
        check(model.entries.last?.state == .done && model.entries.last?.translation == "[vi] STALL 我们明天发货。",
              "a stalled GPT is asked again, and its second answer comes in time: \(String(describing: model.entries.last))")
        check(calls.translated.filter { $0.text.hasPrefix("STALL") }.count == 2, "asked twice, no more")
        check(calls.spares.filter { $0.hasPrefix("STALL") } == ["STALL 我们明天发货。"]
              && calls.translated.filter { $0.text.hasPrefix("STALL") }.count == 2,
              "the second time on a connection of its own, the first on the usual one: \(calls.spares)")
        check(model.speedLogText.contains("hỏi thêm lần nữa"), "noted in the speed log")

        // A thought said in two breaths is one sentence: Soniox's end after a comma is a pause.
        others.say([w("如果你们订五百台，", "2"), w("<end>", "")])
        await feed(Array(repeating: 0, count: 2_400 * 6))
        check(model.entries.last?.text == "如果你们订五百台，" && model.entries.last?.state == .hearing,
              "a pause after a comma: the sentence waits for the rest")
        others.say([w("我们可以便宜百分之五。", "2"), w("<end>", "")])
        check(model.entries.last?.text == "如果你们订五百台，我们可以便宜百分之五。" && model.entries.last?.state == .translating,
              "the speaker goes on: one sentence, translated whole: \(model.entries.suffix(2).map(\.text))")
        // Nothing more said: it ends after a quiet moment, full stop or not.
        others.say([w("那个", "1"), w("<end>", "")])
        await feed(Array(repeating: 0, count: 2_400 * 6))
        check(model.entries.last?.text == "那个" && model.entries.last?.state == .hearing, "still waiting after 0.6 s")
        await feed(Array(repeating: 0, count: 2_400 * 9))
        check(model.entries.last?.text == "那个" && model.entries.last?.state != .hearing, "ended after a quiet moment")
        await sleep(0.5)
        // The speaker goes on after a pause, Soniox settling the new words late: the pause is not ended
        // with them still guessed (they came again as the next sentence once settled).
        others.say([w("如果你们订一千台，", "1"), w("<end>", "")])
        await feed(Array(repeating: 0, count: 2_400 * 3))
        others.say([w("价格", "1", final: false)])
        await feed(Array(repeating: 0, count: 2_400 * 16))
        check(model.entries.last?.state == .hearing, "a pause with the next words still guessed: not ended")
        others.say([w("价格再低一点。", "1"), w("<end>", "")])
        check(model.entries.last?.text == "如果你们订一千台，价格再低一点。" && model.entries.filter { $0.text.contains("价格再低") }.count == 1,
              "one sentence, the words not twice: \(model.entries.suffix(2).map(\.text))")
        // A word guessed, then taken back (an empty list): nothing of it is kept.
        others.say([w("嗯", "2", final: false)])
        check(model.entries.last?.text == "嗯" && model.entries.last?.state == .hearing, "a guess shows at once")
        others.say([])
        check(!model.entries.contains { $0.text == "嗯" }, "taken back, it is gone (it was kept as a sentence 8 s later)")
        await sleep(0.5)
        let mineContext = calls.translated.last { $0.text == "Anh gửi giúp em báo giá nhé" }
        check(mineContext?.speaker == "User" && mineContext?.context.last?.hasPrefix("Người 1: 等一下。") == true,
              "the user's own sentence: GPT is told it is theirs: \(String(describing: mineContext))")

        // Soniox's translation lags behind the words (a noisy room, several people): once words have waited
        // 1.2 s on the screen, GPT translates the sentence so far (dịch trước); GPT's showing, it is asked
        // again as more is said; the sentence ending as GPT was last asked it, that answer is its final
        // translation, at once.
        func entry(_ id: UUID?) -> ConversationModel.Entry? { model.entries.first { $0.id == id } }
        others.onEvent?(.processed(finalMs: Int(others.sent * 1000) - 2_000, totalMs: Int(others.sent * 1000) - 500))
        check(model.speedLogText.contains("Soniox nửa phút: nghe chậm TB 0,5 s, tối đa 0,5 s · chốt chậm TB 1,5 s, tối đa 1,5 s"),
              "the speed log says how far behind Soniox hears and settles")
        calls.translateDelay = 400_000_000
        var s = Int(others.sent * 1000)
        others.say([w("这个月的订单", "4", final: false, start: s, end: s + 900)])
        let lagging = model.entries.last?.id
        await feed(speech(1))
        others.say([w("这个月的订单", "4", start: s, end: s + 900), w("我们已经安排", "4", final: false, start: s + 1000, end: s + 1900)])
        check(entry(lagging)?.translation.isEmpty == true && !calls.translated.contains { $0.text.hasPrefix("这个月的订单") },
              "a second without a translation: not yet")
        await feed(speech(1))
        check(entry(lagging)?.translation == "[vi] 这个月的订单我们已经安排" && entry(lagging)?.state == .hearing,
              "1.2 s without one: GPT's translation of the sentence so far: \(entry(lagging)?.translation ?? "")")
        check(calls.translated.last?.model == .luna, "with Luna, the quick one")
        others.say([t("Đơn hàng tháng này")])
        check(entry(lagging)?.translation == "[vi] 这个月的订单我们已经安排", "Soniox's, reaching fewer words, does not replace it")
        others.say([w("我们已经安排生产了，", "4", start: s + 1000, end: s + 2400), w("预计下周三", "4", final: false, start: s + 2500, end: s + 3000)])
        await sleep(0.05)
        check(calls.translated.last?.text == "这个月的订单我们已经安排生产了，预计下周三", "more said: GPT asked again at once")
        await feed(speech(0.5))
        check(entry(lagging)?.translation == "[vi] 这个月的订单我们已经安排生产了，预计下周三", "and its answer shows")
        // Soniox's reaching a little past GPT's: the text does not jump back to it.
        others.say([w("预计下周三可以", "4", final: false, start: s + 2500, end: s + 3300), t(" dự kiến thứ Tư có thể", final: false)])
        check(entry(lagging)?.translation == "[vi] 这个月的订单我们已经安排生产了，预计下周三",
              "Soniox's a little further: GPT's stays shown: \(entry(lagging)?.translation ?? "")")
        await sleep(0.05)
        check(calls.translated.last?.text == "这个月的订单我们已经安排生产了，预计下周三", "and GPT is not asked for words Soniox has translated")
        await feed(speech(0.5))
        others.say([w("预计下周三可以发货。", "4", final: false, start: s + 2500, end: s + 3600)])
        await sleep(0.1)
        others.say([w("预计下周三可以发货。", "4", start: s + 2500, end: s + 3600), w("<end>", "")])
        let whole = "这个月的订单我们已经安排生产了，预计下周三可以发货。"
        check(entry(lagging)?.state == .translating, "the sentence ends")
        await sleep(0.45)
        check(entry(lagging)?.state == .done && entry(lagging)?.translation == "[vi] \(whole)",
              "GPT, asked for exactly its words while they were said, is its translation at once: \(String(describing: entry(lagging)))")
        check(calls.translated.filter { $0.text == whole }.count == 1, "not asked again")
        check(model.speedLogText.contains("đã dịch trước, dùng ngay") && model.speedLogText.contains("GPT dịch trước chậm tối đa")
              && model.speedLogText.contains("hỏi GPT dịch trước 3 lần") && model.speedLogText.contains("chữ chậm tối đa"),
              "noted in the speed log")
        await sleep(1.5)
        // Soniox's translation keeping up with the words: GPT is not asked before the sentence ends.
        let askedBefore = calls.translated.count
        s = Int(others.sent * 1000)
        others.say([w("好的，", "5", start: s, end: s + 500), t("Được,")])
        await feed(speech(1.5))
        others.say([w("没问题。", "5", start: s + 600, end: s + 1200), t(" không vấn đề."), w("<end>", "")])
        await sleep(0.6)
        check(calls.translated.count == askedBefore + 1 && model.entries.last?.state == .done, "Soniox keeping up: GPT once, at the end")
        check(model.speedLogText.contains("bản dịch Soniox chậm tối đa"), "how late Soniox's translation came is in the speed log")
        await sleep(1.5)
        // A pause: GPT translates the sentence so far at once; nothing more said, that is its translation
        // the moment it ends.
        s = Int(others.sent * 1000)
        others.say([w("我们考虑一下", "5", start: s, end: s + 900), t("Chúng tôi sẽ cân nhắc"), w("<end>", "")])
        let paused = model.entries.last?.id
        await sleep(0.05)
        check(calls.translated.last?.text == "我们考虑一下", "a pause: GPT asked at once")
        await feed(Array(repeating: 0, count: 2_400 * 14))
        check(entry(paused)?.state == .done && entry(paused)?.translation == "[vi] 我们考虑一下"
              && calls.translated.filter { $0.text == "我们考虑一下" }.count == 1,
              "ended after the quiet moment, translated already: \(String(describing: entry(paused)))")
        await sleep(1.5)
        // GPT asked ahead hangs (a stalled connection): given up after 4 s, and asked again.
        s = Int(others.sent * 1000)
        others.say([w("STALL 价格还可以", "4", final: false, start: s, end: s + 1200)])
        let stalled = model.entries.last?.id
        await feed(Array(repeating: 0, count: 2_400 * 30))
        check(entry(stalled)?.translation.isEmpty == true && calls.translated.filter { $0.text == "STALL 价格还可以" }.count == 1,
              "asked, and hanging")
        await feed(Array(repeating: 0, count: 2_400 * 30))
        check(entry(stalled)?.translation == "[vi] STALL 价格还可以" && calls.translated.filter { $0.text == "STALL 价格还可以" }.count == 2,
              "given up and asked again: \(entry(stalled)?.translation ?? "")")
        others.say([w("STALL 价格还可以", "4", start: s, end: s + 1200), w("<end>", "")])
        await feed(Array(repeating: 0, count: 2_400 * 14))
        check(entry(stalled)?.state == .done && calls.translated.filter { $0.text == "STALL 价格还可以" }.count == 2,
              "and that answer is its translation")
        calls.translateDelay = 100_000_000
        await sleep(1.5)

        // A room that talks on: the voice catches up.
        Sim.speed = 1
        calls.voiceBytes = 144_000   // 3 s each
        let ratesBefore = AudioIO.current?.rates.count ?? 0
        let burst = ["第一句话说完了。", "第二句话也说完了。", "第三句马上来了。", "第四句还在继续。", "第五句最后一句。", "第六句真的最后。",
                     "第七句又来一句。", "第八句终于结束。"]
        for (index, text) in burst.enumerated() {
            others.say([w(text, String(index % 3 + 1)), w("<end>", "")])
            await sleep(0.5)
        }
        await sleep(20)
        let rates = AudioIO.current?.rates.dropFirst(ratesBefore) ?? []
        print("rates:", Array(rates))
        check(rates.contains { $0 > 1 }, "behind, the voice reads faster")
        let log = model.speedLogText
        check(log.contains("bỏ đọc"), "an old sentence with newer ones waiting is left unread")
        check(log.contains("nhận chậm") && log.contains("GPT Luna") && log.contains("đọc sau"), "the speed log has the timings")
        check(log.contains("chữ hiện sau") && log.contains("bản dịch đầu tiên sau") && log.contains("từ lúc ngừng nói"),
              "and how soon the words, the first translation and GPT's showed")
        check(!log.contains("这个价格") && !log.contains("Được anh"), "and never what was said")

        model.readAloud = false
        check(AudioIO.current?.echo.last == false, "Chỉ chữ: echo cancelling off, the microphone hears far voices better")
        model.readAloud = true
        check(AudioIO.current?.echo.last == true, "Đọc to again: on")

        // The room's session drops in the middle of a sentence: what it settled stays, and the next
        // one is sent again what it had not, and what is said meanwhile.
        await feed(speech(1))
        let dropAt = others.sent
        others.say([w("我们的价格", "2", start: Int((dropAt - 0.9) * 1000), end: Int((dropAt - 0.5) * 1000)),
                    w("很好", "2", final: false)])
        others.onEvent?(.closed)
        check(model.entries.last?.text == "我们的价格" && model.entries.last?.state == .translating,
              "dropped: its settled words are a sentence, translated: \(String(describing: model.entries.last))")
        await sleep(0.1)
        guard let again = SonioxSession.all.last(where: { $0.target == "vi" }), again !== others, again.isOpen else {
            print("FAIL: not opened again")
            exit(1)
        }
        check(again.sent > 0.45 && again.sent < 0.7, "opened again at once, and sent again what was not settled: \(again.sent) s")
        await feed(speech(0.5))
        check(again.sent > 0.9, "the sound goes on to it")
        check(model.speedLogText.contains("gửi lại"), "noted in the speed log")
        again.say([w("很好。", "2"), w("<end>", "")])
        check(model.entries.last?.text == "很好。", "the words not settled are heard again")
        await sleep(0.5)
        // Soniox numbers the new session's speakers anew: the user is now "1", a partner "2". The user's
        // English word is not a partner's, and the partner answering during the turn is.
        again.say([w("好的，没问题。", "2"), w("<end>", "")])
        await sleep(0.5)
        model.talk(true)
        await feed(speech(1.5))
        let anewAt = Int(again.sent * 1000)
        again.say([w("OK", "1", "en", start: anewAt - 1400, end: anewAt - 1200), w(" anh", "1", "vi", start: anewAt - 1100, end: anewAt - 900),
                   w("等一下，我问一下。", "2", start: anewAt - 800, end: anewAt - 100), w("<end>", "")])
        check(!model.entries.contains { !$0.mine && $0.text == "OK" }, "the user's own English word is not someone else's")
        check(model.entries.last?.text == "等一下，我问一下。", "the partner answering during the turn is heard")
        partner.say([w("Anh chờ chút", "", "vi", end: 1400)])
        model.talk(false)
        await feed(speech(0.2))
        await sleep(0.3)
        partner.say([w("<fin>", "")])
        await sleep(0.8)

        // Stop while Soniox has not settled the last words: they still come in.
        let partnerFinalized = partner.finalized
        again.say([w("我们下周", "1"), w("一确认", "1", final: false)])
        model.stop()
        await sleep(0.2)
        check(again.finalized == 1 && partner.finalized == partnerFinalized + 1, "stopped: Soniox is asked to settle what it heard")
        check(again.isOpen, "and kept open for its answer")
        again.say([w("一确认订单，价格不变。", "1"), w("<fin>", "")])
        partner.say([w("<fin>", "")])
        check(model.entries.last?.text == "我们下周一确认订单，价格不变。", "the last words said before Stop are kept: \(model.entries.last?.text ?? "")")
        check(!again.isOpen && !partner.isOpen, "then the sessions close")
        await sleep(0.5)
        let lines = model.meeting.lines
        check(lines.contains { $0.speaker == "3" } && model.meeting.transcript.contains("Người 1 · "),
              "the minutes say who spoke")
        check(model.meeting.transcript.contains("\nChủ đề: báo giá máy CNC\n"), "and what the meeting was about")
        check(lines.map(\.at) == lines.map(\.at).sorted(), "the minutes in order")
        check(model.entries.allSatisfy { $0.state == .done }, "every sentence finished: \(model.entries.map(\.state))")
        print(log)

        // MARK: Dịch liền
        print("--- Dịch liền")
        Sim.speed = 1
        calls.translated = []
        calls.translateDelay = 300_000_000
        let live = ConversationModel()
        live.engine = .live
        live.readAloud = true
        live.partnerCode = "zh"
        live.services = model.services
        await live.start()
        check(AudioIO.current?.echo.last == true, "Dịch liền always cancels echo (it speaks while the user talks)")
        guard let room = LiveSession.all.first(where: { $0.language == "vi" }),
              let mineLive = LiveSession.all.first(where: { $0.language == "zh" }) else {
            print("FAIL: live sessions \(LiveSession.all.map(\.language))")
            exit(1)
        }
        room.onEvent?(.source("这个价格"))
        await sleep(1.5)
        room.onEvent?(.source("可以便宜一点吗？"))
        room.onEvent?(.target("Giá này"))
        await sleep(1.8)
        room.onEvent?(.source("我们"))
        check(live.entries.count == 2 && live.entries[0].state == .translating && live.entries[0].text == "这个价格可以便宜一点吗？",
              "the next words after a full stop: the first caption is done and goes to GPT: \(live.entries.map(\.text))")
        room.onEvent?(.target(" có bớt được không?"))
        check(live.entries[0].translation == "Giá này có bớt được không?" && live.entries[1].translation.isEmpty,
              "the rest of its live translation goes to it, not to the next caption")
        room.onEvent?(.target("Chúng tôi"))
        check(live.entries[1].translation == "Chúng tôi", "and the next piece to the next")
        await sleep(0.5)
        check(live.entries[0].state == .done && live.entries[0].translation == "[vi] 这个价格可以便宜一点吗？", "GPT's text replaces it")
        check(calls.translated.first?.model == .luna, "with the economy model")
        room.onEvent?(.source("下周一确认"))
        await feed(Array(repeating: 0, count: 24_000 * 3))   // the room goes quiet
        check(live.entries.count == 2 && live.entries[1].text == "我们下周一确认" && live.entries[1].state != .hearing,
              "a quiet moment ends the caption: \(live.entries.map(\.text))")
        await sleep(0.5)
        check(live.entries[1].state == .done, "translated")

        // The live voice falls behind: faster, then let go of past six seconds.
        let ratesBefore2 = AudioIO.current?.rates.count ?? 0
        for _ in 0..<12 { room.onEvent?(.audio(Data(count: 48_000))) }   // 12 s of voice at once
        let liveRates = AudioIO.current?.rates.dropFirst(ratesBefore2) ?? []
        check(liveRates.contains(1.35), "behind, the live voice reads faster: \(Array(liveRates))")
        check((AudioIO.current?.backlog ?? 0) <= 7.1, "and never more than about six seconds behind: \(AudioIO.current?.backlog ?? 0)")
        check(live.speedLogText.contains("bỏ bớt tiếng đọc"), "noted in the speed log")
        AudioIO.current?.stopPlayback()

        // The user speaks: kept as the partners heard it, no GPT.
        let asked = calls.translated.count
        live.talk(true)
        mineLive.onEvent?(.source(" Anh gửi báo giá nhé"))
        mineLive.onEvent?(.target("请发"))
        mineLive.onEvent?(.target("报价"))
        live.talk(false)
        await feed(Array(repeating: 0, count: 24_000 * 6))
        let mineEntry = live.entries.last
        check(mineEntry?.mine == true && mineEntry?.state == .done && mineEntry?.translation == "请发报价"
              && mineEntry?.text == "Anh gửi báo giá nhé", "the user's words kept as the partners heard them: \(String(describing: mineEntry))")
        check(calls.translated.count == asked, "no GPT for the user's live words")
        live.stop()
        await sleep(0.5)
        check(live.entries.allSatisfy { $0.state == .done }, "every caption finished: \(live.entries.map(\.state))")
        check(live.meeting.lines.count == live.entries.count, "all in the minutes")
        print(live.speedLogText)
        print(failed == 0 ? "SIMULATION PASSED" : "SIMULATION FAILED \(failed)")
        exit(failed == 0 ? 0 : 1)
    }
}
