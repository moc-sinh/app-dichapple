import Foundation

/// One chat message used as translation context.
struct ChatLine: Codable, Equatable {
    enum Side: String, Codable { case partner, me }
    var side: Side
    var text: String
    var date: Date
}

/// Settings and context shared by the app and the keyboard (App Group).
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
        static let memory = "memory"
        static let incomingCache = "incomingCache"
        static let keyboardHasFullAccess = "keyboardHasFullAccess"
        static let handledPasteboard = "handledPasteboard"
        static let keyboardStage = "keyboardStage"
        static let keyboardStageAt = "keyboardStageAt"
    }

    // MARK: Settings

    /// The OpenAI key, kept in the App Group container on this iPhone only.
    static var apiKey: String? {
        get {
            let key = defaults.string(forKey: Key.apiKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return key.isEmpty ? nil : key
        }
        set { defaults.set(newValue, forKey: Key.apiKey) }
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

    // MARK: State

    static var keyboardHasFullAccess: Bool {
        get { defaults.bool(forKey: Key.keyboardHasFullAccess) }
        set { defaults.set(newValue, forKey: Key.keyboardHasFullAccess) }
    }
    /// How far the keyboard got the last time iOS started it, for the app's diagnostics.
    static func noteKeyboard(_ stage: String) {
        defaults.set(stage, forKey: Key.keyboardStage)
        defaults.set(Date(), forKey: Key.keyboardStageAt)
    }

    static var keyboardStage: (stage: String, at: Date)? {
        guard let stage = defaults.string(forKey: Key.keyboardStage),
              let at = defaults.object(forKey: Key.keyboardStageAt) as? Date else { return nil }
        return (stage, at)
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

    /// What the keyboard remembers of the chat, trimmed for a request.
    static func context() -> [ChatLine] {
        let lines = recentMemory()
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
