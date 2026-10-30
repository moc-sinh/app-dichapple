// A translation streamed from OpenAI (Translator with `progress`): the server-sent events read as
// they come (`ResponseStream`), and the translation read out of its JSON while it is being written
// (`Prompt.partialTranslation`), so it can go in the chat before the meaning check after it.
import Foundation

var failures = 0, checks = 0
func expect(_ ok: Bool, _ what: @autoclosure () -> String) {
    checks += 1
    if !ok { failures += 1; print("FAIL:", what()) }
}

func json(_ object: Any) -> String {
    String(data: try! JSONSerialization.data(withJSONObject: object, options: .sortedKeys), encoding: .utf8)!
}

// MARK: The translation read while its JSON is written

let answers: [(String, String?)] = [
    ("您好，我把本月冷冻榴莲的报价再发给您。\n海防FOB价格为每公斤3.2美元。", "Chào anh, em gửi lại báo giá sầu riêng đông lạnh tháng này."),
    ("He said \"OK\" — 50% off \\ C:\\path\t tab", nil),
    ("Emoji 😊👍🏽 and 𠜎 (beyond the first plane)", "Biểu tượng 😊"),
    ("", "trống"),
    ("  spaces around  ", nil),
]
for (translation, meaning) in answers {
    var object: [String: Any] = ["translation": translation]
    if let meaning { object[Prompt.meaningKey] = meaning }
    // As the model writes it: one key after the other, in the schema's order.
    var written = json(object)
    // And with the escapes OpenAI uses for anything beyond ASCII now and then.
    let escaped = written.unicodeScalars.map { $0.isASCII || $0.value > 0xFFFF ? String($0) : String(format: "\\u%04x", $0.value) }.joined()
    let utf16Escaped: String = {
        var out = ""
        for unit in written.utf16 {
            out += unit < 0x80 ? String(UnicodeScalar(UInt8(unit))) : String(format: "\\u%04x", unit)
        }
        return out
    }()
    for variant in [written, escaped, utf16Escaped, written.replacingOccurrences(of: "\":\"", with: "\" : \"")] {
        written = variant
        var completeAt: Int?
        var last = ""
        var characters: [Character] = []
        for character in written {
            characters.append(character)
            let soFar = String(characters)
            guard let partial = Prompt.partialTranslation(soFar) else {
                expect(last.isEmpty, "the translation went away: \(soFar.debugDescription)")
                continue
            }
            if !partial.complete {
                // Never more than what the final translation starts with (a character may be cut in two:
                // 👍 of 👍🏽).
                expect(translation.unicodeScalars.starts(with: partial.text.unicodeScalars), "partial \(partial.text.debugDescription) of \(translation.debugDescription)")
                last = partial.text
            } else if completeAt == nil {
                completeAt = characters.count
                expect(partial.text == translation.trimmingCharacters(in: .whitespacesAndNewlines),
                       "whole: \(partial.text.debugDescription) vs \(translation.debugDescription)")
            }
        }
        // Whole before the meaning check began (which is what saves the wait).
        let before = written.range(of: "\"\(Prompt.meaningKey)\"").map { written.distance(from: written.startIndex, to: $0.lowerBound) }
        expect(completeAt != nil, "never whole: \(written)")
        if let before, let completeAt { expect(completeAt <= before + 1, "whole only after the meaning began") }
    }
}
expect(Prompt.partialTranslation("") == nil, "empty")
expect(Prompt.partialTranslation("{") == nil, "brace only")
expect(Prompt.partialTranslation("{\"transla") == nil, "key not whole")
expect(Prompt.partialTranslation("{\"translation\":") == nil, "no value yet")
expect(Prompt.partialTranslation("{\"translation\":\"") ?? ("x", true) == ("", false), "value begun")
expect(Prompt.partialTranslation("{\"translation\":\"ab\\") ?? ("x", true) == ("ab", false), "escape not whole")
expect(Prompt.partialTranslation("{\"translation\":\"ab\\u00") ?? ("x", true) == ("ab", false), "unicode escape not whole")
expect(Prompt.partialTranslation("{\"translation\":\"\\ud83d\\ude0a\"") ?? ("x", false) == ("😊", true), "surrogate pair")
expect(Prompt.partialTranslation("{\"translation\":\"\\ud83d") ?? ("x", true) == ("", false), "surrogate pair not whole")
expect(Prompt.partialTranslation("Xin chào") == nil, "not JSON (a model without the format)")
// The meaning check's key starts like the translation's: never taken for it.
expect(Prompt.partialTranslation("{\"translation_back_vi\":\"Chào\"") == nil, "meaning first is not the translation")
expect(Prompt.partialTranslation("{\"translation\":\"Hi\",\"translation_back_vi\":\"Chào\"}") ?? ("x", false) == ("Hi", true), "both")

// MARK: The events

let created = json(["type": "response.created", "sequence_number": 0, "response": ["status": "in_progress"]])
func delta(_ text: String, _ n: Int) -> String {
    json(["type": "response.output_text.delta", "delta": text, "item_id": "msg_1", "output_index": 1, "content_index": 0,
          "logprobs": [Any](), "sequence_number": n])
}
let finalOutput = "{\"translation\":\"Hello\",\"translation_back_vi\":\"Xin chào\"}"
let completed = json(["type": "response.completed", "sequence_number": 9, "response": [
    "status": "completed",
    "output": [["type": "reasoning", "summary": [Any]()],
               ["type": "message", "content": [["type": "output_text", "text": finalOutput]]]],
    "usage": ["input_tokens": 120, "input_tokens_details": ["cached_tokens": 0], "output_tokens": 30],
]])
var stream = ResponseStream()
var grown: [String] = []
let lines = ["event: response.created", "data: " + created, "",
             "event: response.output_text.delta", "data: " + delta("{\"translation\":\"Hel", 1), "",
             ": a comment line", "data: " + delta("lo\",\"translation_back_vi\":\"Xin", 2), "",
             "data:" + delta(" chào\"}", 3), "", "event: response.completed", "data: " + completed, ""]
for line in lines where stream.feed(line) { grown.append(stream.text) }
expect(grown == ["{\"translation\":\"Hel", "{\"translation\":\"Hello\",\"translation_back_vi\":\"Xin", finalOutput],
       "text as it grows: \(grown)")
expect(stream.response?["status"] as? String == "completed", "the whole response")
expect(TokenUsage.from(stream.response ?? [:]) == TokenUsage(input: 120, cachedInput: 0, output: 30), "usage")
expect(stream.failure == nil, "no failure")

var failed = ResponseStream()
_ = failed.feed("data: " + json(["type": "response.failed", "response": ["status": "failed", "error": ["code": "server_error", "message": "x"]]]))
expect(failed.failure?["code"] as? String == "server_error", "failed response")
var errored = ResponseStream()
_ = errored.feed("data: " + json(["type": "error", "code": "rate_limit_exceeded", "message": "slow down", "param": NSNull()]))
expect(errored.failure?["code"] as? String == "rate_limit_exceeded", "error event")
var incomplete = ResponseStream()
_ = incomplete.feed("data: " + json(["type": "response.incomplete", "response": ["status": "incomplete"]]))
expect(incomplete.response?["status"] as? String == "incomplete", "incomplete response kept")
var junk = ResponseStream()
expect(!junk.feed("data: [DONE]") && !junk.feed("data: {not json") && junk.response == nil, "junk ignored")

print("Stream: \(checks - failures)/\(checks) checks passed")
exit(failures == 0 ? 0 : 1)
