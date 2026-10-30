// Replacing the whole text field after Dịch (FieldEraser), against a pretend field that shows the
// keyboard only the paragraph at the cursor and reports changes late, as Zalo and WeChat do.
import Foundation

final class Field {
    var text: [Character]
    var cursor: Int
    /// Looks at the field that still see it as it was before the last change.
    let lag: Int
    /// While catching up, the field shows nothing around the cursor (instead of the old text).
    let blankWhileBusy: Bool
    /// Most characters shown on each side of the cursor (apps cut long paragraphs).
    let limit: Int
    private var pending = 0
    private var shown: (String, String, Bool) = ("", "", false)

    init(_ s: String, cursor: Int? = nil, lag: Int = 0, blankWhileBusy: Bool = false, limit: Int = 100_000) {
        text = Array(s)
        self.cursor = cursor ?? text.count
        self.lag = lag
        self.blankWhileBusy = blankWhileBusy
        self.limit = limit
        shown = truth()
    }

    private func truth() -> (String, String, Bool) {
        var before = String(text[..<cursor])
        if let nl = before.lastIndex(of: "\n") { before = String(before[before.index(after: nl)...]) }
        var after = String(text[cursor...])
        if let nl = after.firstIndex(of: "\n") { after = String(after[..<nl]) }
        return (String(before.suffix(limit)), String(after.prefix(limit)), !text.isEmpty)
    }

    func read() -> (before: String, after: String, hasText: Bool) {
        if pending > 0 {
            pending -= 1
            return blankWhileBusy ? ("", "", true) : shown
        }
        shown = truth()
        return shown
    }

    private func changed() { pending = lag }
    func deleteBackward() { if cursor > 0 { text.remove(at: cursor - 1); cursor -= 1 }; changed() }
    func insert(_ s: String) { text.insert(contentsOf: Array(s), at: cursor); cursor += s.count; changed() }
    func moveToEnd() { cursor = text.count; changed() }
    var whole: String { String(text) }
}

/// The keyboard's side, as `replaceDocument` drives it.
func replace(_ field: Field, with new: String, current: String, checked: Bool = false) {
    var eraser = FieldEraser(expected: current)
    // At the end of the text read already: no step past the end first.
    let start = field.read()
    if !(start.after.isEmpty && !start.before.isEmpty && current.hasSuffix(start.before)) { field.moveToEnd() }
    func waitMove(_ before: String, _ after: String, _ ticks: Int) {
        for _ in 0..<ticks {
            let r = field.read()
            if r.before != before || r.after != after { return }
        }
    }
    while true {
        let r = field.read()
        switch eraser.next(before: r.before, after: r.after, hasText: r.hasText) {
        case .toEnd: field.moveToEnd()
        case let .delete(n, wait):
            for _ in 0..<n { field.deleteBackward() }
            waitMove(r.before, r.after, wait)
        case .wait: waitMove(r.before, r.after, 4)
        case .done:
            field.insert(new)
            guard !checked, FieldEraser.worthChecking(current, new) else { return }
            // readDocument reads the whole field (it walks over every paragraph).
            if let left = FieldEraser.leftover(found: field.whole, inserted: new) {
                replace(field, with: new, current: left, checked: true)
            }
            return
        }
    }
}

/// Up to 1.16.2: empty after three looks with nothing around the cursor.
func replaceOld(_ field: Field, with new: String) {
    var steps = 0, emptyRounds = 0
    field.moveToEnd()
    while true {
        steps += 1
        let r = field.read()
        emptyRounds = r.before.isEmpty && r.after.isEmpty ? emptyRounds + 1 : 0
        guard r.hasText, emptyRounds < 3, steps < 80 else { field.insert(new); return }
        for _ in 0..<max(1, r.before.count) { field.deleteBackward() }
    }
}

var failures = 0, checks = 0
func expect(_ ok: Bool, _ what: @autoclosure () -> String) {
    checks += 1
    if !ok { failures += 1; print("FAIL:", what()) }
}

let vi = [
    "Chào anh, em gửi lại báo giá sầu riêng đông lạnh tháng này.",
    "Giá FOB Hải Phòng là 3,2 đô một ký, hàng giao trong 15 ngày sau khi nhận cọc.",
    "",
    "Nếu anh lấy từ hai container trở lên thì bên em giảm thêm 5%.",
    "Anh xem rồi báo em sớm nhé 😊👍🏽",
]
let translation = "您好，我把本月冷冻榴莲的报价再发给您。\n海防FOB价格为每公斤3.2美元。\n\n如果您订两个以上的集装箱，我们再优惠5%。"

var oldFailed = 0
var cases = 0
for paragraphs in 1...vi.count {
    for blanks in [false, true] {
        let source = vi.prefix(paragraphs).joined(separator: blanks ? "\n\n" : "\n")
        for lag in 0...8 {
            for blank in [false, true] {
                for limit in [100_000, 40] {
                    cases += 1
                    let f = Field(source, lag: lag, blankWhileBusy: blank, limit: limit)
                    replace(f, with: translation, current: source)
                    expect(f.whole == translation, "\(paragraphs) đoạn, lag \(lag), blank \(blank), limit \(limit): \(f.whole.debugDescription)")
                    let old = Field(source, lag: lag, blankWhileBusy: blank, limit: limit)
                    replaceOld(old, with: translation)
                    if old.whole != translation { oldFailed += 1 }
                    // Hoàn tác: the translation back to the Vietnamese.
                    let back = Field(translation, lag: lag, blankWhileBusy: blank, limit: limit)
                    replace(back, with: source, current: translation)
                    expect(back.whole == source, "hoàn tác, lag \(lag), blank \(blank), limit \(limit)")
                }
            }
        }
    }
}
// The old way does leave Vietnamese behind in these fields (the bug), the new way never.
expect(oldFailed > 0, "the pretend fields should reproduce the 1.16.2 bug")
print("old replace left text behind in \(oldFailed)/\(cases) fields")

// Cursor in the middle; the user typed more after the text was read; the read missed a paragraph.
for lag in [0, 3, 8] {
    let s = vi.joined(separator: "\n")
    let mid = Field(s, cursor: 20, lag: lag, blankWhileBusy: true)
    replace(mid, with: translation, current: s)
    expect(mid.whole == translation, "cursor in the middle, lag \(lag): \(mid.whole.debugDescription)")
    let more = Field(s + "\nThêm một dòng gõ sau.", lag: lag, blankWhileBusy: true)
    replace(more, with: translation, current: s)
    expect(more.whole == translation, "text typed after reading, lag \(lag): \(more.whole.debugDescription)")
    let empty = Field("", lag: lag)
    replace(empty, with: "xin chào", current: "")
    expect(empty.whole == "xin chào", "empty field")
}

// The cursor was put at the end of a paragraph just like the last one: taken for the end, the
// text after it stays, and the read afterwards finds it and erases it again.
for lag in [0, 3] {
    let twice = "Giá 3,2 đô.\nGiá 3,2 đô."
    let f = Field(twice, cursor: 11, lag: lag)
    replace(f, with: translation, current: twice)
    expect(f.whole == translation, "cursor at a paragraph like the last, lag \(lag): \(f.whole.debugDescription)")
}

// leftover: only when the field holds the new text and more.
expect(FieldEraser.leftover(found: "abc\nxyz", inserted: "xyz") == "abc\nxyz", "leftover found")
expect(FieldEraser.leftover(found: "xyz\n", inserted: "xyz") == nil, "same text with a line break")
expect(FieldEraser.leftover(found: "Xyz", inserted: "xyz") == nil, "changed by the app")

print("\(checks - failures)/\(checks) checks passed")
exit(failures == 0 ? 0 : 1)
