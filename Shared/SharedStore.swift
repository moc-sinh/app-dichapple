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
        static let model = "model"
        static let targetCode = "targetCode"
        static let lastPartnerCode = "lastPartnerCode"
        static let tone = "tone"
        static let profile = "profile"
        static let addressing = "addressing"
        static let gender = "gender"
        static let glossary = "glossary"
        static let checkMeaning = "checkMeaning"
        static let autoIncoming = "autoIncoming"
        static let haptics = "haptics"
        static let useScreen = "useScreen"
        static let memory = "memory"
        static let incomingCache = "incomingCache"
        static let screenLines = "screenLines"
        static let screenUpdatedAt = "screenUpdatedAt"
        static let screenActive = "screenActive"
        static let keyboardVisible = "keyboardVisible"
        static let keyboardHasFullAccess = "keyboardHasFullAccess"
        static let handledPasteboard = "handledPasteboard"
    }

    // MARK: Settings

    /// The OpenAI key, kept in the App Group container on this iPhone only.
    static var apiKey: String? {
        let key = defaults.string(forKey: Key.apiKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return key.isEmpty ? nil : key
    }

    static var model: TranslationModel {
        TranslationModel(rawValue: defaults.string(forKey: Key.model) ?? "") ?? .sol
    }

    /// A language code, or `Language.autoCode` to follow the partner's language.
    static var targetCode: String {
        get { defaults.string(forKey: Key.targetCode) ?? Language.autoCode }
        set { defaults.set(newValue, forKey: Key.targetCode) }
    }

    /// The partner language seen most recently, used when "auto" has no context yet.
    static var lastPartnerCode: String {
        get { defaults.string(forKey: Key.lastPartnerCode) ?? "zh" }
        set { defaults.set(newValue, forKey: Key.lastPartnerCode) }
    }

    static var tone: Tone { Tone(rawValue: defaults.integer(forKey: Key.tone)) ?? .polite }
    static var profile: String { defaults.string(forKey: Key.profile) ?? "" }
    static var addressing: String { defaults.string(forKey: Key.addressing) ?? "" }
    /// 0 not given, 1 male, 2 female. Some languages (Thai, Japanese, Vietnamese) word things by gender.
    static var gender: Int { defaults.integer(forKey: Key.gender) }
    static var glossary: String { defaults.string(forKey: Key.glossary) ?? "" }
    static var checkMeaning: Bool { defaults.object(forKey: Key.checkMeaning) as? Bool ?? true }
    static var autoIncoming: Bool { defaults.object(forKey: Key.autoIncoming) as? Bool ?? true }
    static var haptics: Bool { defaults.object(forKey: Key.haptics) as? Bool ?? true }
    static var useScreen: Bool { defaults.object(forKey: Key.useScreen) as? Bool ?? true }

    // MARK: State

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

    /// When the screen reader last saw the chat, if recently.
    static var screenUpdatedAt: Date? {
        guard let at = defaults.object(forKey: Key.screenUpdatedAt) as? Date,
              Date().timeIntervalSince(at) < Config.screenMaxAge else { return nil }
        return at
    }

    /// Chat lines currently on screen, if the screen reader is running.
    static func screenLines() -> [ChatLine] {
        guard useScreen, screenUpdatedAt != nil else { return [] }
        return load(Key.screenLines)
    }

    /// The best context available now: what is on screen, else what the keyboard remembers.
    static func context() -> [ChatLine] {
        var lines = screenLines()
        if lines.isEmpty { lines = recentMemory() }
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

    // MARK: Incoming translations (never pay twice for the same message)

    static func cachedIncoming(_ text: String) -> String? {
        let key = cacheKey(text)
        return incomingCache().first { $0.count == 2 && $0[0] == key }?[1]
    }

    static func cacheIncoming(_ text: String, translation: String) {
        let key = cacheKey(text)
        var pairs = incomingCache().filter { $0.first != key }
        pairs.append([key, translation])
        defaults.set(Array(pairs.suffix(40)), forKey: Key.incomingCache)
    }

    private static func incomingCache() -> [[String]] {
        defaults.array(forKey: Key.incomingCache) as? [[String]] ?? []
    }

    private static func cacheKey(_ text: String) -> String {
        String(text.lowercased().filter { !$0.isWhitespace })
    }

    // MARK: Storage

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
