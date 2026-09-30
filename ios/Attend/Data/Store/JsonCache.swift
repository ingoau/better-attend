import CryptoKit
import Foundation

/// Tiny encrypted key→JSON file cache. Files are AES-GCM sealed with a Keychain-held key (participant
/// data about minors never sits in plaintext), protected until first unlock, and excluded from backups.
/// Keys become file names, so keep them simple.
actor JsonCache {
    private let directory: URL
    private let key: SymmetricKey?

    init(directory: URL, key: SymmetricKey?) {
        self.directory = directory
        self.key = key
        var dir = directory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
    }

    /// The app's cache: Application Support/cache_v1 with a key from the Keychain (created on first use).
    static func standard(namespace: String = "cache_v1", keychain: Keychain) -> JsonCache {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let key: SymmetricKey
        if let raw = keychain.data("cache_key"), raw.count == 32 {
            key = SymmetricKey(data: raw)
        } else {
            key = SymmetricKey(size: .bits256)
            keychain.set(key.withUnsafeBytes { Data($0) }, for: "cache_key")
        }
        return JsonCache(directory: base.appending(path: namespace, directoryHint: .isDirectory), key: key)
    }

    private func file(_ key: String) -> URL {
        let safe = String(key.map { $0.isLetter || $0.isNumber || "_.-".contains($0) ? $0 : "_" })
        return directory.appending(path: safe + ".bin")
    }

    func read<T: Decodable & Sendable>(_ key: String, as type: T.Type = T.self) -> T? {
        guard let data = try? Data(contentsOf: file(key)), let plain = open(data) else { return nil }
        return try? AttendJSON.decoder().decode(T.self, from: plain)
    }

    func write<T: Encodable & Sendable>(_ key: String, _ value: T) {
        guard let plain = try? AttendJSON.encoder().encode(value), let sealed = seal(plain) else { return }
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

    private func seal(_ plain: Data) -> Data? {
        guard let key else { return plain }
        return try? AES.GCM.seal(plain, using: key).combined
    }

    private func open(_ data: Data) -> Data? {
        guard let key else { return data }
        return (try? AES.GCM.SealedBox(combined: data)).flatMap { try? AES.GCM.open($0, using: key) }
    }
}
