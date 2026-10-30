import Foundation

/// How long each sentence of Hội thoại took, for the user to send when it feels slow: how far the
/// recognizer lagged behind the speaker, how long the translation took, how long a voice waited.
/// Numbers, languages and lengths only, never what was said. The last few hundred lines.
struct SpeedLog {
    private(set) var lines: [String] = []
    private static let limit = 400
    private static let clock: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    mutating func add(_ line: String, at date: Date = Date()) {
        lines.append("\(Self.clock.string(from: date)) \(line)")
        if lines.count > Self.limit { lines.removeFirst(lines.count - Self.limit) }
    }

    var text: String { lines.joined(separator: "\n") }

    /// "1,2 s".
    static func seconds(_ value: Double) -> String {
        String(format: "%.1f s", max(0, value)).replacingOccurrences(of: ".", with: ",")
    }
}
