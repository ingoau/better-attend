import Foundation
import Security

/// Minimal generic-password Keychain wrapper. Items are this-device-only and readable after first
/// unlock (so background refresh works), and never sync to iCloud.
struct Keychain: Sendable {
    let service: String

    func data(_ account: String) -> Data? { lookup(account).data }

    /// The item and the Keychain's status, so "not there" (`errSecItemNotFound`) can be told apart
    /// from "can't read it right now" (e.g. locked before first unlock).
    func lookup(_ account: String) -> (data: Data?, status: OSStatus) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        return (status == errSecSuccess ? out as? Data : nil, status)
    }

    func string(_ account: String) -> String? { data(account).map { String(decoding: $0, as: UTF8.self) } }

    /// Stores (or with nil, deletes) an item. Returns whether the Keychain accepted it.
    @discardableResult
    func set(_ value: Data?, for account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        guard let value else {
            let status = SecItemDelete(query as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        let attrs: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            return SecItemAdd(query.merging(attrs) { $1 } as CFDictionary, nil) == errSecSuccess
        }
        return status == errSecSuccess
    }

    @discardableResult
    func set(_ value: String?, for account: String) -> Bool { set(value.map { Data($0.utf8) }, for: account) }
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
