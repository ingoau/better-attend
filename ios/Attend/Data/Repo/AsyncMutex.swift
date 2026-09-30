/// A FIFO lock for async MainActor code: `await mutex.withLock { … }` runs bodies one at a time,
/// even across suspension points (like kotlinx's `Mutex`).
@MainActor
final class AsyncMutex {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func withLock<T>(_ body: () async throws -> T) async rethrows -> T {
        if locked {
            await withCheckedContinuation { waiters.append($0) }
        } else {
            locked = true
        }
        defer {
            if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
        }
        return try await body()
    }
}
