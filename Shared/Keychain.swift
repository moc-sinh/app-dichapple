import Foundation
import Security

/// The OpenAI API key, stored once in a keychain group shared by the app and the keyboard.
enum Keychain {
    private static let service = "DichApple.OpenAI"
    private static let account = "apiKey"

    private static var accessGroup: String? {
        guard let prefix = Bundle.main.object(forInfoDictionaryKey: "AppIdentifierPrefix") as? String,
              !prefix.isEmpty, !prefix.hasPrefix("$(") else { return nil }
        return prefix + Config.keychainGroupSuffix
    }

    private static func query() -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group = accessGroup { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    static func apiKey() -> String? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let key = String(data: data, encoding: .utf8), !key.isEmpty else { return nil }
        return key
    }

    @discardableResult
    static func setAPIKey(_ key: String) -> Bool {
        _ = SecItemDelete(query() as CFDictionary)
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return true }
        var q = query()
        q[kSecValueData as String] = Data(trimmed.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }
}
