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
        guard let data = defaults.data(forKey: historyKey) else { return [] }
        return (try? JSONDecoder().decode([HistoryItem].self, from: data)) ?? []
    }

    static func addHistory(_ item: HistoryItem) {
        guard keepHistory else { return }
        var items = history()
        if let first = items.first, first.source == item.source, first.translation == item.translation { return }
        items.insert(item, at: 0)
        // Starred items are never dropped to make room.
        while items.count > historyLimit, let last = items.lastIndex(where: { !$0.starred }) {
            items.remove(at: last)
        }
        saveHistory(items)
    }

    static func saveHistory(_ items: [HistoryItem]) {
        defaults.set(try? JSONEncoder().encode(items), forKey: historyKey)
    }

    static func updateHistory(_ item: HistoryItem) {
        var items = history()
        guard let index = items.firstIndex(where: { $0.id == item.id }) else { return }
        items[index] = item
        saveHistory(items)
    }

    static func removeHistory(id: UUID) {
        saveHistory(history().filter { $0.id != id })
    }
}
