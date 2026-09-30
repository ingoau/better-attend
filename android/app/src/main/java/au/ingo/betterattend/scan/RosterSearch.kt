package au.ingo.betterattend.scan

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanRepository

/** Instant, offline search over the cached roster for the scanner's "Find person" sheet. */
object RosterSearch {
    private val SHORT_CODE = Regex("[A-Za-z0-9]{8}")

    /**
     * Every whitespace-separated term must match the start of a word in the name/email, or the
     * short code / id prefix. Name-prefix matches rank first. Withdrawn/rejected people sort last.
     */
    fun filter(participants: List<Participant>, query: String, limit: Int = 50): List<Participant> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return participants.asSequence()
            .mapNotNull { p -> score(p, terms)?.let { p to it } }
            .sortedWith(compareBy<Pair<Participant, Int>>({ !it.first.isActive }, { -it.second }, { it.first.name.lowercase() }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(p: Participant, terms: List<String>): Int? {
        val words = listOfNotNull(p.displayName, p.fullName, p.email?.substringBefore('@'))
            .flatMap { it.lowercase().split(' ', '.', '-', '_', '\'') }
            .filter { it.isNotEmpty() }
        val email = p.email?.lowercase().orEmpty()
        val ids = listOf(p.participantId.lowercase(), p.participantEventId.lowercase())
        var total = 0
        for (t in terms) {
            total += when {
                words.any { it.startsWith(t) } -> if (p.name.lowercase().startsWith(t)) 3 else 2
                t.length >= 3 && email.contains(t) -> 1
                t.length >= 4 && ids.any { it.startsWith(t) } -> 1
                else -> return null
            }
        }
        return total
    }

    /** Typed/pasted text that can be submitted directly (QR payload or UUID), as a manual scan. */
    fun directInput(query: String): ScanInput? =
        ScanRepository.parseCode(query, "manual")?.copy(source = "manual")

    /** An 8-character ticket short code (first block of the participant id). Resolved via search. */
    fun looksLikeShortCode(query: String): Boolean = SHORT_CODE.matches(query.trim())
}
