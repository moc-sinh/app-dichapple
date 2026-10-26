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

func w(_ text: String, _ speaker: String, _ language: String = "zh", final: Bool = true, end: Int? = nil) -> SonioxToken {
    SonioxToken(text: text, isFinal: final, isTranslation: false, language: language, speaker: speaker, endMs: end)
}

func t(_ text: String, final: Bool = true) -> SonioxToken {
    SonioxToken(text: text, isFinal: final, isTranslation: true, language: "vi")
}

final class Calls: @unchecked Sendable {
    let lock = NSLock()
    var translated: [(text: String, target: String, context: [String], speaker: String, topic: String, model: TranslationModel)] = []
    var spoken: [String] = []
    var translateDelay: UInt64 = 300_000_000
    var voiceBytes = 96_000
    var warmUps = 0
    /// Returns how many times `text` was asked before.
    func add(_ text: String, _ target: String, _ context: SpokenContext, _ model: TranslationModel) -> Int {
        let lines = context.lines.map { "\($0.speaker): \($0.text)" + ($0.translation.isEmpty ? "" : " → \($0.translation)") }
        lock.lock(); defer { lock.unlock() }
        let before = translated.filter { $0.text == text }.count
        translated.append((text, target, lines, context.speaker, context.topic, model))
        return before
    }
    func say(_ text: String) { lock.lock(); spoken.append(text); lock.unlock() }
}

let fixed: [String: String] = [
    "那我们下周一确认订单。": "Vậy thứ Hai tuần sau chúng ta xác nhận đơn hàng.",
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
            translate: { _, text, target, context, model in
                let before = calls.add(text, target.code, context, model)
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

        // Three people talk on without pausing.
        await feed(speech(2))
        check(others.sent > 1.5, "the room's sound goes to Soniox (\(others.sent) s)")
        others.say([w("这个价格", "1", end: 1500), w("可以吗？", "1", end: 1900)])
        check(model.entries.count == 1 && model.entries[0].speaker == "1" && model.entries[0].state == .hearing,
              "the first speaker's words show as they come")
        check(calls.warmUps >= 2, "someone starts speaking: OpenAI's connection is made ready for the end of the sentence")
        others.say([w("Được anh", "2", "vi", end: 2400)])
        check(model.entries.count == 2 && model.entries[0].state == .translating && model.entries[0].text == "这个价格可以吗？",
              "another speaker starts: the first sentence goes to GPT")
        others.say([t("Giá này"), t(" được không?", final: false)])
        check(model.entries[0].translation == "Giá này được không?" && model.entries[1].translation.isEmpty,
              "Soniox's translation shows on its own sentence meanwhile, not on the next: \(model.entries.map(\.translation))")
        await sleep(0.5)
        check(model.entries[0].state == .done && model.entries[0].translation == "[vi] 这个价格可以吗？",
              "GPT's translation replaces it: \(model.entries[0].translation)")
        check(calls.translated.first?.model == .luna, "the others' words with the economy model")
        others.say([t(" được không?"), w("那我们下周一", "3", end: 3200), w("确认订单。", "3", end: 3800), w("<end>", "")])
        check(model.entries.count == 3, "a third speaker: \(model.entries.map(\.text))")
        check(model.entries[1].isVietnamese && model.entries[1].translation.isEmpty && model.entries[1].state == .done,
              "a Vietnamese colleague is shown as said, and a late piece of the first sentence's translation is not on it")
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

        // The phone's own voice heard again: dropped.
        others.say([w("vậy thứ hai tuần sau chúng ta xác nhận đơn hàng", "2", "vi"), w("<end>", "")])
        check(!model.entries.contains { $0.text.hasPrefix("vậy thứ hai") }, "the phone's voice heard back is not someone speaking")
        check(model.entries.allSatisfy { $0.state != .hearing }, "nothing left half-heard")

        // The user speaks.
        model.talk(true)
        await feed(speech(1.5))
        check(partner.sent > 1, "the user's sound goes to their own session")
        partner.say([w("Anh gửi giúp em báo giá nhé", "", "vi", end: 1400)])
        model.talk(false)
        check(partner.finalized == 1, "letting go settles the user's last words at once")
        partner.say([t("请", final: true), w("<fin>", "")])
        let mine = model.entries.last
        check(mine?.mine == true && mine?.state == .translating && mine?.speaker == "", "the user's sentence goes to GPT")
        await sleep(0.6)
        check(model.entries.last?.translation == "[zh] Anh gửi giúp em báo giá nhé", "into the partners' Chinese")
        check(calls.translated.last?.model == .sol, "the user's words with the app's model")
        await sleep(1)
        check(calls.spoken.last == "[zh] Anh gửi giúp em báo giá nhé", "read aloud to the partners")

        // GPT fails: Soniox's translation stays, and is read.
        others.say([w("FAIL 这个", "1"), w("<end>", "")])
        others.say([t("Cái này.")])
        await sleep(0.6)
        let failedEntry = model.entries.last
        check(failedEntry?.state == .done && failedEntry?.translation == "Cái này.", "GPT failed: Soniox's translation kept")
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
        check(model.speedLogText.contains("hỏi thêm lần nữa"), "noted in the speed log")
        let mineContext = calls.translated.last { $0.text == "Anh gửi giúp em báo giá nhé" }
        check(mineContext?.speaker == "User" && mineContext?.context.last?.hasPrefix("Người 3: ") == true,
              "the user's own sentence: GPT is told it is theirs: \(String(describing: mineContext))")

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
        check(!log.contains("这个价格") && !log.contains("Được anh"), "and never what was said")

        model.readAloud = false
        check(AudioIO.current?.echo.last == false, "Chỉ chữ: echo cancelling off, the microphone hears far voices better")
        model.readAloud = true
        check(AudioIO.current?.echo.last == true, "Đọc to again: on")
        model.stop()
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
