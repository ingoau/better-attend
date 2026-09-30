package au.ingo.betterattend.scan

/**
 * Stops a continuously-running scanner from submitting the same code on every frame.
 *
 * A value is accepted the first time it's seen, then ignored until it has been *absent* (not
 * offered) for at least [windowMs]. Every sighting refreshes the timer, so a ticket held in front
 * of the camera for a minute is still one scan. Values are tracked independently, so two tickets
 * in frame at once are each accepted once instead of flip-flopping.
 *
 * Not thread-safe: call from one thread (the main thread in the app).
 */
class SameCodeGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lastSeen = HashMap<String, Long>()

    /** Records a sighting of [value] and returns true if it should be acted on. */
    fun offer(value: String, now: Long = clock()): Boolean {
        prune(now)
        val previous = lastSeen[value]
        lastSeen[value] = now
        return previous == null || now - previous >= windowMs
    }

    /** Forget [value] (e.g. its result was dismissed) so the next sighting is accepted immediately. */
    fun release(value: String) {
        lastSeen.remove(value)
    }

    /** Forget everything (event or context changed, card dismissed). */
    fun reset() = lastSeen.clear()

    private fun prune(now: Long) {
        if (lastSeen.size < 32) return
        lastSeen.entries.removeAll { now - it.value >= windowMs }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_500L
    }
}
