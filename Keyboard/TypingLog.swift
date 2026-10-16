import Foundation
import QuartzCore

/// The last few hundred typing events: keys and the edits they made, what the app reported, taps
/// iOS cancelled, and when the keyboard took the field's text. Cài đặt › Chẩn đoán copies it when
/// keys go wrong. It stays on this iPhone and holds the end of what was typed recently (password
/// fields always get the Apple keyboard, never this one).
enum TypingLog {
    private static var lines: [String] = []
    private static var unsaved = false
    private static let limit = 600
    private static let started = CACurrentMediaTime()

    static func add(_ line: String) {
        let ms = Int((CACurrentMediaTime() - started) * 1000)
        let fraction = ms % 1000
        lines.append("\(ms / 1000).\(fraction < 10 ? "00" : fraction < 100 ? "0" : "")\(fraction) \(line)")
        // Trimmed now and then, not on every line.
        if lines.count > limit + 100 { lines.removeFirst(lines.count - limit) }
        unsaved = true
    }

    /// Into the App Group, where the app reads it. Done when typing pauses, never per key, and off
    /// the main thread unless the keyboard is closing.
    static func save(now: Bool = false) {
        guard unsaved else { return }
        unsaved = false
        let snapshot = Array(lines.suffix(limit))
        if now {
            SharedStore.typingLog = snapshot
        } else {
            DispatchQueue.global(qos: .utility).async { SharedStore.typingLog = snapshot }
        }
    }

    private static var watching = false

    /// Notes each time the keyboard's main thread stays busy long enough to hold up a key (over
    /// 12 ms, more than a frame): with the app's own delay, it tells a slow keyboard from a slow app.
    static func watchMainThread() {
        guard !watching else { return }
        watching = true
        var wokeAt = CACurrentMediaTime()
        let wake = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.afterWaiting.rawValue, true, -2_000_000) { _, _ in
            wokeAt = CACurrentMediaTime()
        }
        // After Core Animation's commit and the key click: all the keyboard's work for the event.
        let sleep = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.beforeWaiting.rawValue, true, 3_000_000) { _, _ in
            let busy = CACurrentMediaTime() - wokeAt
            if busy > 0.012 { TypingLog.add("⏱ bàn phím bận \(Int(busy * 1000))ms") }
        }
        CFRunLoopAddObserver(CFRunLoopGetMain(), wake, .commonModes)
        CFRunLoopAddObserver(CFRunLoopGetMain(), sleep, .commonModes)
    }

    /// The end of a text, on one line.
    static func tail(_ text: String, _ count: Int = 24) -> String {
        visible(String(text.suffix(count)))
    }

    /// The start of a text, on one line.
    static func head(_ text: String, _ count: Int = 12) -> String {
        visible(String(text.prefix(count)))
    }

    private static func visible(_ text: String) -> String {
        text.contains("\n") ? text.replacingOccurrences(of: "\n", with: "⏎") : text
    }
}
