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
        static let sonioxKey = "sonioxKey"
        static let googleKey = "googleKey"
        static let model = "model"
        static let targetCode = "targetCode"
        static let lastPartnerCode = "lastPartnerCode"
        static let tone = "tone"
        static let profile = "profile"
        static let addressing = "addressing"
        static let gender = "gender"
        static let glossary = "glossary"
        static let checkMeaning = "checkMeaning"
        /// A new key: a copied message is translated by itself unless turned off (the user wants it on).
        static let instantIncoming = "autoTranslateCopied"
        static let haptics = "haptics"
        static let memory = "memory"
        static let incomingCache = "incomingCache"
        static let keyboardHasFullAccess = "keyboardHasFullAccess"
        static let handledPasteboard = "handledPasteboard"
        static let keyboardStage = "keyboardStage"
        static let keyboardStageAt = "keyboardStageAt"
        static let contextClearedAt = "contextClearedAt"
        static let typingLog = "typingLog"
        static let typesOnTouchDown = "typesOnTouchDown"
        static let keySounds = "keySounds"
        static let keyPopups = "keyPopups"
        static let keyboardSize = "keyboardSize"
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

    /// The Soniox key (the Hội thoại tab's cheapest live translation), on this iPhone only.
    static var sonioxKey: String? {
        get {
            let key = defaults.string(forKey: Key.sonioxKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return key.isEmpty ? nil : key
        }
        set { defaults.set(newValue, forKey: Key.sonioxKey) }
    }

    /// The Google Cloud key (Google's voices in the Hội thoại tab), on this iPhone only.
    static var googleKey: String? {
        get {
            let key = defaults.string(forKey: Key.googleKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return key.isEmpty ? nil : key
        }
        set { defaults.set(newValue, forKey: Key.googleKey) }
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
    /// Read the copied message as the keyboard opens, no tap. Needs paste permission set to
    /// "Allow" for MSTrans, or iOS asks every time.
    static var instantIncoming: Bool { defaults.object(forKey: Key.instantIncoming) as? Bool ?? true }
    static var haptics: Bool { defaults.object(forKey: Key.haptics) as? Bool ?? true }
    /// Keys type as the finger lands (see `KeyboardLayoutView.typesOnTouchDown`); off, as the
    /// finger lifts, like the Apple keyboard.
    static var typesOnTouchDown: Bool { defaults.object(forKey: Key.typesOnTouchDown) as? Bool ?? true }
    /// Key sounds. With Full Access the keyboard plays them itself, so iOS's keyboard sound
    /// setting does not reach it; this one does (and the silent switch mutes them).
    static var keySounds: Bool { defaults.object(forKey: Key.keySounds) as? Bool ?? true }
    /// The enlarged letter above a pressed key (see `KeyboardLayoutView.showsPopups`); off by
    /// default, the key lights up instead, like the Apple keyboard with Character Preview off.
    static var keyPopups: Bool { defaults.bool(forKey: Key.keyPopups) }
    static var keyboardSize: KeyboardSize {
        KeyboardSize(rawValue: defaults.object(forKey: Key.keyboardSize) as? Int ?? KeyboardSize.medium.rawValue) ?? .medium
    }

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

    /// The keyboard's recent typing events (see `TypingLog`), for the app's diagnostics.
    static var typingLog: [String] {
        get { defaults.stringArray(forKey: Key.typingLog) ?? [] }
        set { defaults.set(newValue.isEmpty ? nil : newValue, forKey: Key.typingLog) }
    }

    // MARK: Context

    /// Lines the keyboard saw: messages the user copied and translations it inserted.
    static func remember(_ line: ChatLine) {
        var lines = recentMemory()
        if lines.last?.text != line.text { lines.append(line) }
        save(Array(lines.suffix(Config.contextLimit)), Key.memory)
    }

    /// What the keyboard remembers of the chat, trimmed for a request: the newest lines within the
    /// character budget. A line longer than what is left keeps its end (where the question usually
    /// is): one long message from the partner used to leave the reply with no context at all.
    static func context() -> [ChatLine] {
        var kept: [ChatLine] = []
        var room = Config.contextCharacterLimit
        for var line in recentMemory().suffix(Config.contextLimit).reversed() where room > 0 {
            if line.text.count > room { line.text = "…" + line.text.suffix(room) }
            room -= line.text.count
            kept.insert(line, at: 0)
        }
        return kept
    }

    /// Takes back the line remembered last, if it is `text`: a translation undone was never sent.
    static func forgetLast(_ text: String) {
        var lines = recentMemory()
        guard lines.last?.text == text else { return }
        lines.removeLast()
        save(lines, Key.memory)
    }

    /// "Cuộc mới": a new conversation, with nothing from the one before.
    static func clearContext() {
        defaults.removeObject(forKey: Key.memory)
        defaults.removeObject(forKey: Key.incomingCache)
        defaults.set(Date(), forKey: Key.contextClearedAt)
    }

    /// When "Cuộc mới" last started a new conversation.
    static var contextClearedAt: Date? { defaults.object(forKey: Key.contextClearedAt) as? Date }

    // MARK: Incoming translations (never pay twice for the same message)

    static func cachedIncoming(_ text: String) -> String? {
        let key = cacheKey(text)
        return incomingCache().first { $0.count == 2 && $0[0] == key }?[1]
    }

    static func cacheIncoming(_ text: String, translation: String) {
        // Partner messages are not kept on the phone when history is off.
        guard keepHistory else { return }
        let key = cacheKey(text)
        var pairs = incomingCache().filter { $0.first != key }
        pairs.append([key, translation])
        defaults.set(Array(pairs.suffix(40)), forKey: Key.incomingCache)
    }

    /// The glossary or the user's details changed: a message copied again is translated again.
    static func clearIncomingCache() {
        defaults.removeObject(forKey: Key.incomingCache)
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
