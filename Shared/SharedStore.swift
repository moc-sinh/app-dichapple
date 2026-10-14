import Foundation

/// One chat message used as translation context.
struct ChatLine: Codable, Equatable {
    enum Side: String, Codable { case partner, me }
    var side: Side
    var text: String
    var date: Date
}

/// Settings and context shared by the app, the keyboard and the screen reader (App Group).
enum SharedStore {
    static let defaults = UserDefaults(suiteName: Config.appGroup) ?? .standard

    enum Key {
        static let apiKey = "apiKey"
        static let targetCode = "targetCode"
        static let profile = "profile"
        static let glossary = "glossary"
        static let useScreen = "useScreen"
        static let memory = "memory"
        static let screenLines = "screenLines"
        static let screenUpdatedAt = "screenUpdatedAt"
        static let screenActive = "screenActive"
        static let keyboardVisible = "keyboardVisible"
        static let keyboardHasFullAccess = "keyboardHasFullAccess"
        static let handledPasteboard = "handledPasteboard"
    }

    /// The OpenAI key, kept in the App Group container on this iPhone only.
    static var apiKey: String? {
        let key = defaults.string(forKey: Key.apiKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return key.isEmpty ? nil : key
    }

    static var targetCode: String {
        get { defaults.string(forKey: Key.targetCode) ?? "zh" }
        set { defaults.set(newValue, forKey: Key.targetCode) }
    }
    static var profile: String { defaults.string(forKey: Key.profile) ?? "" }
    static var glossary: String { defaults.string(forKey: Key.glossary) ?? "" }
    static var useScreen: Bool { defaults.object(forKey: Key.useScreen) as? Bool ?? true }

    static var screenActive: Bool {
        get { defaults.bool(forKey: Key.screenActive) }
        set { defaults.set(newValue, forKey: Key.screenActive) }
    }
    static var keyboardVisible: Bool {
        get { defaults.bool(forKey: Key.keyboardVisible) }
        set { defaults.set(newValue, forKey: Key.keyboardVisible) }
    }
    static var keyboardHasFullAccess: Bool {
        get { defaults.bool(forKey: Key.keyboardHasFullAccess) }
        set { defaults.set(newValue, forKey: Key.keyboardHasFullAccess) }
    }
    static var handledPasteboard: Int {
        get { defaults.integer(forKey: Key.handledPasteboard) }
        set { defaults.set(newValue, forKey: Key.handledPasteboard) }
    }

    // MARK: Context

    /// Lines the keyboard saw: messages the user copied and translations it inserted.
    static func remember(_ line: ChatLine) {
        var lines = recentMemory()
        if lines.last?.text != line.text { lines.append(line) }
        save(Array(lines.suffix(Config.contextLimit)), Key.memory)
    }

    /// Lines the screen reader saw on screen.
    static func saveScreen(_ lines: [ChatLine]) {
        save(lines, Key.screenLines)
        defaults.set(Date(), forKey: Key.screenUpdatedAt)
    }

    /// The best context available now: what is on screen, else what the keyboard remembers.
    static func context() -> [ChatLine] {
        var lines = recentMemory()
        if useScreen, let at = defaults.object(forKey: Key.screenUpdatedAt) as? Date,
           Date().timeIntervalSince(at) < Config.screenMaxAge {
            let screen = load(Key.screenLines)
            if !screen.isEmpty { lines = screen }
        }
        // Keep the newest lines within the character budget.
        var kept: [ChatLine] = []
        var characters = 0
        for line in lines.suffix(Config.contextLimit).reversed() {
            characters += line.text.count
            if characters > Config.contextCharacterLimit { break }
            kept.insert(line, at: 0)
        }
        return kept
    }

    static func clearContext() {
        defaults.removeObject(forKey: Key.memory)
        defaults.removeObject(forKey: Key.screenLines)
        defaults.removeObject(forKey: Key.screenUpdatedAt)
    }

    private static func recentMemory() -> [ChatLine] {
        load(Key.memory).filter { Date().timeIntervalSince($0.date) < Config.memoryMaxAge }
    }

    private static func load(_ key: String) -> [ChatLine] {
        guard let data = defaults.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([ChatLine].self, from: data)) ?? []
    }

    private static func save(_ lines: [ChatLine], _ key: String) {
        defaults.set(try? JSONEncoder().encode(lines), forKey: key)
    }
}
