// Translator's streamed requests end to end against a scripted OpenAI (Shim.swift): progress as
// the translation is written, whole before the meaning check, retries, and what a failure costs.
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

var failures = 0, checks = 0
func expect(_ ok: Bool, _ what: @autoclosure () -> String) {
    checks += 1
    if !ok { failures += 1; print("FAIL:", what()) }
}

func json(_ object: Any) -> String {
    String(data: try! JSONSerialization.data(withJSONObject: object, options: .sortedKeys), encoding: .utf8)!
}

/// The events of an answer whose text is `output`, written a few characters at a time.
func events(_ output: String, piece: Int = 4, end: String = "response.completed", status: String = "completed") -> [String] {
    var lines = ["event: response.created", "data: " + json(["type": "response.created", "response": ["status": "in_progress"]]), ""]
    var rest = Substring(output)
    var n = 1
    while !rest.isEmpty {
        let chunk = rest.prefix(piece)
        rest = rest.dropFirst(piece)
        lines += ["event: response.output_text.delta",
                  "data: " + json(["type": "response.output_text.delta", "delta": String(chunk), "output_index": 1, "sequence_number": n]), ""]
        n += 1
    }
    lines += ["event: \(end)", "data: " + json(["type": end, "response": [
        "status": status,
        "output": [["type": "message", "content": [["type": "output_text", "text": output]]]],
        "usage": ["input_tokens": 300, "input_tokens_details": ["cached_tokens": 0], "output_tokens": 40],
    ]]), ""]
    return lines
}

final class Heard: @unchecked Sendable {
    let lock = NSLock()
    var events: [(String, Bool)] = []
    func add(_ text: String, _ whole: Bool) { lock.lock(); events.append((text, whole)); lock.unlock() }
}

let english = Language.named("en")!
let both = json(["translation": "Hello, I am sending this month's price again.", Prompt.meaningKey: "Chào anh, em gửi lại giá tháng này."])

func translate(_ heard: Heard, _ text: String = "Chào anh, em gửi lại giá tháng này.", to target: Language = english,
               meaning: Bool = true) async -> Result<Translation, Error> {
    do {
        return .success(try await Translator(apiKey: "sk-test", model: .sol)
            .translate(text, to: target, checkMeaning: meaning) { heard.add($0, $1) })
    } catch {
        return .failure(error)
    }
}

// 1. The user's message with its meaning check: the translation is whole while the meaning is still being written.
do {
    FakeOpenAI.bodies = []
    let lines = events(both)
    FakeOpenAI.answers = [.init(lines: lines)]
    let heard = Heard()
    let result = await translate(heard)
    let translation = try? result.get()
    expect(translation?.text == "Hello, I am sending this month's price again.", "translation \(String(describing: translation))")
    expect(translation?.meaning == "Chào anh, em gửi lại giá tháng này.", "meaning")
    expect(translation?.usage?.input == 300, "usage")
    let wholeAt = heard.events.firstIndex { $0.1 }
    expect(wholeAt != nil && heard.events[wholeAt!].0 == translation?.text, "whole event: \(heard.events.last.map { "\($0)" } ?? "")")
    // How many deltas came after the whole translation: all of the meaning check's.
    let deltas = lines.filter { $0.contains("output_text.delta\",") || $0.contains("\"response.output_text.delta\"") && $0.hasPrefix("data:") }.count
    expect(wholeAt.map { heard.events.count - $0 } == 1 && deltas > heard.events.count, "whole came before the meaning (\(heard.events.count) events, \(deltas) deltas)")
    expect(heard.events.dropLast().allSatisfy { !$0.1 }, "only one whole event")
    let body = FakeOpenAI.bodies.first ?? [:]
    expect(body["stream"] as? Bool == true, "streamed")
    expect((body["text"] as? [String: Any])?["format"] != nil, "JSON format asked")
}

// 2. The partner's message: the translation as it is written, then whole.
do {
    let output = json(["translation": "Anh ơi, giá FOB là 3,2 đô một ký."])
    FakeOpenAI.answers = [.init(lines: events(output, piece: 3))]
    let heard = Heard()
    let result = try? await translate(heard, "哥，FOB价格是每公斤3.2美元。", to: .vietnamese, meaning: false).get()
    expect(result?.text == "Anh ơi, giá FOB là 3,2 đô một ký.", "incoming")
    let texts = heard.events.map(\.0)
    expect(texts.count > 5 && zip(texts, texts.dropFirst()).allSatisfy { $1.hasPrefix($0) || $0.unicodeScalars.count <= $1.unicodeScalars.count },
           "grows: \(texts)")
}

// 3. Server busy (500): one quiet retry.
do {
    FakeOpenAI.bodies = []
    FakeOpenAI.answers = [.init(status: 500, lines: ["{\"error\":{\"message\":\"busy\"}}"]), .init(lines: events(both))]
    let result = try? await translate(Heard()).get()
    expect(result?.meaning != nil && FakeOpenAI.bodies.count == 2, "retried after 500")
}

// 4. The connection drops before the translation is whole: one retry.
do {
    FakeOpenAI.bodies = []
    FakeOpenAI.answers = [.init(lines: events(both), dropBefore: 6), .init(lines: events(both))]
    let result = try? await translate(Heard()).get()
    expect(result?.meaning != nil && FakeOpenAI.bodies.count == 2, "retried after a drop")
}

// 5. It drops during the meaning check: the whole translation is kept, nothing asked again.
do {
    FakeOpenAI.bodies = []
    let lines = events(both)
    FakeOpenAI.answers = [.init(lines: lines, dropBefore: lines.count - 6)]
    let heard = Heard()
    let result = await translate(heard)
    let translation = try? result.get()
    expect(translation?.text == "Hello, I am sending this month's price again." && translation?.meaning == nil,
           "kept without meaning: \(result)")
    expect(FakeOpenAI.bodies.count == 1, "not asked again")
}

// 6. Cut off during the meaning check (max_output_tokens): the translation is whole, kept.
do {
    let cut = String(both.prefix(both.count - 12))
    FakeOpenAI.answers = [.init(lines: events(cut, end: "response.incomplete", status: "incomplete"))]
    let translation = try? await translate(Heard()).get()
    expect(translation?.text == "Hello, I am sending this month's price again." && translation?.meaning == nil, "incomplete meaning")
}

// 7. Cut off inside the translation: never half a translation.
do {
    let cut = String(both.prefix(20))
    FakeOpenAI.answers = [.init(lines: events(cut, end: "response.incomplete", status: "incomplete"))]
    let result = await translate(Heard())
    if case .failure(let error) = result {
        expect(error.localizedDescription.contains("quá dài"), "too long: \(error.localizedDescription)")
    } else {
        expect(false, "half a translation came back: \(result)")
    }
}

// 8. A model without the JSON format: asked again without it, the text is the translation.
do {
    FakeOpenAI.bodies = []
    FakeOpenAI.answers = [
        .init(status: 400, lines: ["{", "  \"error\": {", "    \"message\": \"Invalid value for text.format: json_schema\",",
                                   "    \"code\": null", "  }", "}"]),
        .init(lines: events("Hello, I am sending this month's price again.")),
    ]
    let heard = Heard()
    let translation = try? await translate(heard).get()
    expect(translation?.text == "Hello, I am sending this month's price again.", "plain text: \(String(describing: translation))")
    expect(FakeOpenAI.bodies.count == 2 && FakeOpenAI.bodies.last?["text"] == nil, "asked again without the format")
    expect(!heard.events.isEmpty && heard.events.allSatisfy { !$0.1 }, "plain text is never called whole before the end")
}

// 9. Refused key: the same message as without streaming.
do {
    FakeOpenAI.answers = [.init(status: 401, lines: ["{\"error\":{\"message\":\"Incorrect API key\",\"code\":\"invalid_api_key\"}}"])]
    let result = await translate(Heard())
    if case .failure(let error) = result {
        expect(error.localizedDescription == "API key không đúng. Kiểm tra lại trong app MSTrans.", "401: \(error.localizedDescription)")
    } else { expect(false, "401 passed") }
}

// 10. An error event in the stream.
do {
    FakeOpenAI.answers = [.init(lines: ["data: " + json(["type": "error", "code": "rate_limit_exceeded", "message": "Slow down", "param": NSNull()])])]
    let result = await translate(Heard())
    if case .failure(let error) = result {
        expect(error.localizedDescription.contains("giới hạn"), "rate limit: \(error.localizedDescription)")
    } else { expect(false, "error event passed") }
}

// 11. A failed response (server error) before anything was written: one retry.
do {
    FakeOpenAI.bodies = []
    FakeOpenAI.answers = [
        .init(lines: ["data: " + json(["type": "response.failed", "response": ["status": "failed", "error": ["code": "server_error", "message": "x"]]])]),
        .init(lines: events(both)),
    ]
    let result = try? await translate(Heard()).get()
    expect(result?.meaning != nil && FakeOpenAI.bodies.count == 2, "retried after a failed response")
}

// 12. Without progress (Hội thoại, the shortcut): not streamed, as before.
do {
    FakeOpenAI.bodies = []
    FakeOpenAI.answers = []
    _ = try? await Translator(apiKey: "sk-test", model: .luna).translate("x", to: english)
    expect(FakeOpenAI.bodies.isEmpty, "no stream without progress")
}

print("Stream end to end: \(checks - failures)/\(checks) checks passed")
exit(failures == 0 ? 0 : 1)
