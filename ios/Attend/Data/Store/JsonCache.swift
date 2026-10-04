import CryptoKit
import Foundation
import os

/// Seals bytes for disk. The app uses `AESGCMCipher` with a Keychain-held key; tests may inject their own.
protocol CacheCipher: Sendable {
    func seal(_ plain: Data) -> Data?
    /// nil if `sealed` wasn't sealed by this cipher (or was tampered with).
    func open(_ sealed: Data) -> Data?
}

/// AES-GCM with a 256-bit key.
struct AESGCMCipher: CacheCipher, @unchecked Sendable { // SymmetricKey is immutable
    let key: SymmetricKey

    func seal(_ plain: Data) -> Data? { try? AES.GCM.seal(plain, using: key).combined }

    func open(_ sealed: Data) -> Data? {
        (try? AES.GCM.SealedBox(combined: sealed)).flatMap { try? AES.GCM.open($0, using: key) }
    }
}

/// Tiny encrypted key→JSON file cache. Files are AES-GCM sealed with a Keychain-held key (participant
/// data about minors never sits in plaintext), protected until first unlock, and excluded from backups.
/// Keys become file names, so keep them simple.
///
/// Fails closed: without a cipher (the Keychain key couldn't be created or read) nothing is written and
/// nothing is read, so the repositories' in-memory state keeps the app working online only.
/// `isEncrypted` drives the warning shown in the app.
actor JsonCache {
    private let directory: URL
    private let cipher: (any CacheCipher)?

    /// False when there's no encryption key: nothing is being saved on this device.
    nonisolated let isEncrypted: Bool

    private static let log = Logger(subsystem: "au.ingo.betterattend", category: "cache")

    /// - Parameter cipher: nil fails closed (no disk access). Tests inject a cipher explicitly.
    init(directory: URL, cipher: (any CacheCipher)?) {
        self.directory = directory
        self.cipher = cipher
        self.isEncrypted = cipher != nil
        var dir = directory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
    }

    /// The app's cache: Application Support/cache_v1 with a key from the Keychain (created on first use).
    /// If the key can't be stored or read back, the cache fails closed rather than writing plaintext.
    static func standard(namespace: String = "cache_v1", keychain: Keychain) -> JsonCache {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let cipher = keychainKey(keychain).map(AESGCMCipher.init(key:))
        if cipher == nil { log.error("Cache key unavailable: not caching anything on disk") }
        return JsonCache(directory: base.appending(path: namespace, directoryHint: .isDirectory), cipher: cipher)
    }

    /// Demo mode: fake data, wiped every launch, sealed with a key that only lives for this launch
    /// (so it works where the Keychain doesn't, e.g. unsigned simulator builds). Still never plaintext.
    static func ephemeral(namespace: String) -> JsonCache {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return JsonCache(directory: base.appending(path: namespace, directoryHint: .isDirectory),
                         cipher: AESGCMCipher(key: SymmetricKey(size: .bits256)))
    }

    /// The stored key, or a new one once it's safely in the Keychain. nil if the Keychain can't be used
    /// right now (e.g. locked before first unlock): we never replace a key we just failed to read.
    static func keychainKey(_ keychain: Keychain) -> SymmetricKey? {
        let found = keychain.lookup("cache_key")
        if let raw = found.data, raw.count == 32 { return SymmetricKey(data: raw) }
        guard found.data != nil || found.status == errSecItemNotFound else { return nil }
        let key = SymmetricKey(size: .bits256)
        let raw = key.withUnsafeBytes { Data($0) }
        guard keychain.set(raw, for: "cache_key"), keychain.data("cache_key") == raw else { return nil }
        return key
    }

    private func file(_ key: String) -> URL {
        let safe = String(key.map { $0.isLetter || $0.isNumber || "_.-".contains($0) ? $0 : "_" })
        return directory.appending(path: safe + ".bin")
    }

    func read<T: Decodable & Sendable>(_ key: String, as type: T.Type = T.self) -> T? {
        guard let cipher, let data = try? Data(contentsOf: file(key)), let plain = cipher.open(data) else { return nil }
        return try? AttendJSON.decoder().decode(T.self, from: plain)
    }

    func write<T: Encodable & Sendable>(_ key: String, _ value: T) {
        guard let cipher, let plain = try? AttendJSON.encoder().encode(value), let sealed = cipher.seal(plain) else { return }
        // Atomic write: e.g. a full disk keeps the previous cache rather than a truncated file.
        try? sealed.write(to: file(key), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func remove(_ key: String) {
        try? FileManager.default.removeItem(at: file(key))
    }

    func clear() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for f in files { try? FileManager.default.removeItem(at: f) }
    }
}
