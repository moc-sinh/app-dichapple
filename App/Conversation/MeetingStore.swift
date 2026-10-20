import Foundation

/// One meeting or conversation of the Hội thoại tab, as it is kept on the phone.
struct Meeting: Codable, Identifiable, Equatable {
    struct Line: Codable, Equatable {
        var at: Date
        /// Said by the user (with the Tôi nói button).
        var mine: Bool
        /// What was spoken in ("vi", "zh"…).
        var language: String
        var text: String
        var translation: String
        /// What it was translated into.
        var target: String
    }

    var id = UUID()
    var started = Date()
    var lines: [Line] = []
    var summary: String?
    /// What it cost with OpenAI, estimated (meetings saved before 1.8.0 have none).
    var cost: CostMeter?

    var ended: Date { lines.last?.at ?? started }

    var title: String {
        Self.dateFormatter.string(from: started)
    }

    /// "32 phút", "1 giờ 5 phút".
    var length: String {
        let minutes = max(1, Int(ended.timeIntervalSince(started) / 60))
        return minutes < 60 ? "\(minutes) phút" : "\(minutes / 60) giờ \(minutes % 60) phút"
    }

    /// The minutes as text: each sentence, who spoke which language, and its translation.
    var transcript: String {
        var text = "Cuộc họp \(title) (\(length))\n"
        for line in lines {
            let who = line.mine ? "Tôi" : Self.languageName(line.language)
            text += "\n[\(Self.timeFormatter.string(from: line.at))] \(who): \(line.text)\n"
            if !line.translation.isEmpty { text += "  → \(line.translation)\n" }
        }
        if let summary, !summary.isEmpty { text += "\nTÓM TẮT\n\(summary)\n" }
        return text
    }

    static func languageName(_ code: String) -> String {
        if code == Language.vietnamese.code { return Language.vietnamese.label }
        if let known = Language.named(code) { return known.label }
        return Locale(identifier: "vi").localizedString(forLanguageCode: code)?.capitalized ?? code
    }

    static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "vi_VN")
        formatter.dateFormat = "dd/MM/yyyy HH:mm"
        return formatter
    }()

    static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()
}

/// Meetings are files in the app's own Documents folder (not the App Group: the keyboard never
/// needs them), one JSON file each, written as the meeting goes so an app closed by iOS loses
/// nothing.
enum MeetingStore {
    private static var folder: URL? {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let folder = documents.appendingPathComponent("Meetings", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    private static let queue = DispatchQueue(label: "MSTrans.meetings", qos: .utility)

    /// Written off the main thread, in order.
    static func save(_ meeting: Meeting) {
        guard !meeting.lines.isEmpty else { return }
        queue.async {
            guard let url = folder?.appendingPathComponent("\(meeting.id.uuidString).json"),
                  let data = try? JSONEncoder().encode(meeting) else { return }
            try? data.write(to: url, options: .atomic)
        }
    }

    /// Newest first.
    static func all() -> [Meeting] {
        queue.sync {
            guard let folder, let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            else { return [] }
            return files.filter { $0.pathExtension == "json" }
                .compactMap { try? JSONDecoder().decode(Meeting.self, from: Data(contentsOf: $0)) }
                .sorted { $0.started > $1.started }
        }
    }

    static func delete(_ meeting: Meeting) {
        queue.async {
            guard let url = folder?.appendingPathComponent("\(meeting.id.uuidString).json") else { return }
            try? FileManager.default.removeItem(at: url)
        }
    }
}
