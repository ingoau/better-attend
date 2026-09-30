import Foundation
import Security

/// Minimal generic-password Keychain wrapper. Items are this-device-only and readable after first
/// unlock (so background refresh works), and never sync to iCloud.
struct Keychain: Sendable {
    let service: String

    func data(_ account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    func string(_ account: String) -> String? { data(account).map { String(decoding: $0, as: UTF8.self) } }

    func set(_ value: Data?, for account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        guard let value else {
            SecItemDelete(query as CFDictionary)
            return
        }
        let attrs: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if SecItemUpdate(query as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            SecItemAdd(query.merging(attrs) { $1 } as CFDictionary, nil)
        }
    }

    func set(_ value: String?, for account: String) { set(value.map { Data($0.utf8) }, for: account) }
}

/// The session token and signed-in user, kept in the Keychain.
@MainActor
final class SecureTokenStore: TokenStore {
    private let keychain: Keychain
    private(set) var token: String?

    init(keychain: Keychain) {
        self.keychain = keychain
        token = keychain.string("token")
    }

    func update(token: String?, expiresAt: String?) {
        self.token = token
        keychain.set(token, for: "token")
        keychain.set(token == nil ? nil : expiresAt, for: "expires_at")
    }

    var user: User? {
        get { keychain.data("user").flatMap { try? AttendJSON.decoder().decode(User.self, from: $0) } }
        set { keychain.set(newValue.flatMap { try? AttendJSON.encoder().encode($0) }, for: "user") }
    }
}

/// In-memory token store for tests and previews.
@MainActor
final class MemoryTokenStore: TokenStore {
    var token: String?
    init(token: String? = nil) { self.token = token }
    func update(token: String?, expiresAt: String?) { self.token = token }
}
