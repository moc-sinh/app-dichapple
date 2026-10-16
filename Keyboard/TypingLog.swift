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

    /// Into the App Group, where the app reads it. Done when typing pauses, never per key.
    static func save() {
        guard unsaved else { return }
        unsaved = false
        SharedStore.typingLog = Array(lines.suffix(limit))
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
        text.replacingOccurrences(of: "\n", with: "⏎")
    }
}
