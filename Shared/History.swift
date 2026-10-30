import Foundation

/// One translation kept for the History tab.
struct HistoryItem: Codable, Identifiable, Equatable {
    var id = UUID()
    /// `.me`: a message you sent; `.partner`: one you received.
    var side: ChatLine.Side
    var source: String
    var translation: String
    /// The partner's language: what you translated into, or what they wrote in.
    var languageCode: String
    var meaning: String?
    var note: String?
    var date = Date()
    var starred = false

    var language: Language? { Language.named(languageCode) }

    /// "Tiếng Anh", for labels; empty when the language was not recognised.
    var languageName: String {
        let name = language?.name ?? Locale(identifier: "vi").localizedString(forLanguageCode: languageCode) ?? ""
        return name.prefix(1).uppercased() + name.dropFirst()
    }

    /// What the user reads first: their own words, or the partner's in Vietnamese.
    var vietnamese: String { side == .me ? source : translation }
    var foreign: String { side == .me ? translation : source }
}

extension HistoryItem {
    /// Reads items saved by any version, with a field added or gone: one item that failed to read
    /// made the whole history unreadable, and every translation after it went unsaved.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        side = try c.decode(ChatLine.Side.self, forKey: .side)
        source = try c.decode(String.self, forKey: .source)
        translation = try c.decode(String.self, forKey: .translation)
        languageCode = try c.decodeIfPresent(String.self, forKey: .languageCode) ?? ""
        meaning = try c.decodeIfPresent(String.self, forKey: .meaning)
        note = try c.decodeIfPresent(String.self, forKey: .note)
        date = try c.decodeIfPresent(Date.self, forKey: .date) ?? Date()
        starred = try c.decodeIfPresent(Bool.self, forKey: .starred) ?? false
    }
}

/// History lives in the App Group so the keyboard and the app share it. Newest first.
extension SharedStore {
    private static let historyKey = "history"
    static let keepHistoryKey = "keepHistory"
    private static let historyLimit = 300

    /// Off: nothing new is saved (the user may not want chats kept on the phone).
    static var keepHistory: Bool {
        get { defaults.object(forKey: keepHistoryKey) as? Bool ?? true }
        set { defaults.set(newValue, forKey: keepHistoryKey) }
    }

    static func history() -> [HistoryItem] {
        (try? storedHistory()) ?? []
    }

    /// Translations from the conversation going on: as recent as the context, and since "Cuộc mới".
    static func recentHistory() -> [HistoryItem] {
        let since = max(Date().addingTimeInterval(-Config.memoryMaxAge), contextClearedAt ?? .distantPast)
        return history().filter { $0.date > since }
    }

    /// Throws when saved history cannot be read, so the writers below leave it untouched
    /// instead of replacing it with an empty list. One item that cannot be read is left out
    /// rather than costing the whole list (and every translation saved after it).
    /// Read again only when it changed: decoding a few hundred items each time the Dịch tab came
    /// back, or a translation was saved, held up the screen.
    private static func storedHistory() throws -> [HistoryItem] {
        guard let data = defaults.data(forKey: historyKey) else { return [] }
        if let items = decoded.items(for: data) { return items }
        let items = try JSONDecoder().decode([LossyItem].self, from: data).compactMap(\.item)
        decoded.keep(items, for: data)
        return items
    }

    /// The history as last decoded, with the bytes it came from (the keyboard writes it too).
    private static let decoded = DecodedHistory()

    private final class DecodedHistory: @unchecked Sendable {
        private let lock = NSLock()
        private var data: Data?
        private var items: [HistoryItem] = []

        func items(for data: Data) -> [HistoryItem]? {
            lock.lock()
            defer { lock.unlock() }
            return data == self.data ? items : nil
        }

        func keep(_ items: [HistoryItem], for data: Data) {
            lock.lock()
            defer { lock.unlock() }
            self.data = data
            self.items = items
        }
    }

    private struct LossyItem: Decodable {
        let item: HistoryItem?
        init(from decoder: Decoder) throws { item = try? HistoryItem(from: decoder) }
    }

    static func addHistory(_ item: HistoryItem) {
        guard keepHistory, var items = try? storedHistory() else { return }
        if let first = items.first, first.source == item.source, first.translation == item.translation { return }
        items.insert(item, at: 0)
        // Starred items are never dropped to make room.
        while items.count > historyLimit, let last = items.lastIndex(where: { !$0.starred }) {
            items.remove(at: last)
        }
        saveHistory(items)
    }

    static func saveHistory(_ items: [HistoryItem]) {
        let data = try? JSONEncoder().encode(items)
        defaults.set(data, forKey: historyKey)
        if let data { decoded.keep(items, for: data) }
    }

    static func updateHistory(_ item: HistoryItem) {
        guard var items = try? storedHistory(), let index = items.firstIndex(where: { $0.id == item.id }) else { return }
        items[index] = item
        saveHistory(items)
    }

    static func removeHistory(id: UUID) {
        guard let items = try? storedHistory() else { return }
        saveHistory(items.filter { $0.id != id })
    }
}
