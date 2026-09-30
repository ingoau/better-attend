package au.ingo.betterattend.ui.blasts

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.SlackBlast

object BlastLogic {
    /** Slack recommends keeping messages under 4,000 characters; longer ones get truncated in clients. */
    const val MAX_LENGTH = 4000

    fun isActive(b: SlackBlast) = b.status == "pending" || b.status == "in_progress"

    /**
     * The server accepts HTML and converts it to Slack mrkdwn, where raw newlines would collapse.
     * Escapes the text and keeps the organizer's line breaks: blank lines become paragraphs, single ones `<br>`.
     */
    fun toHtml(plain: String): String {
        val paragraphs = plain.trim().replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val escaped = paragraphs.map { p -> escape(p).split('\n').joinToString("<br>") { it.trimEnd() } }
        return if (escaped.size <= 1) escaped.firstOrNull().orEmpty() else escaped.joinToString("") { "<p>$it</p>" }
    }

    /** Readable text for history cards from whatever HTML was sent (ours or the web dashboard's). */
    fun toPlain(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>\\s*<p[^>]*>"), "\n\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("(?i)</(li|div|h[1-6])>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "• ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&amp;", "&")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** "118/120 sent · 2 failed", "71 of 116 sent", "Queued…". */
    fun progressText(b: SlackBlast): String = when (b.status) {
        "pending" -> if (b.recipientCount > 0) "Queued for ${b.recipientCount} people…" else "Queued…"
        "in_progress" -> "${b.sentCount} of ${b.recipientCount} sent" + if (b.failedCount > 0) " · ${b.failedCount} failed" else ""
        "failed" -> if (b.recipientCount > 0) "${b.sentCount}/${b.recipientCount} sent · ${b.failedCount} failed" else "Couldn't send"
        else -> when {
            b.failedCount > 0 -> "${b.sentCount}/${b.recipientCount} sent · ${b.failedCount} failed"
            b.recipientCount > 0 && b.sentCount == b.recipientCount -> "Sent to all ${b.recipientCount}"
            else -> "${b.sentCount}/${b.recipientCount} sent"
        }
    }

    fun fraction(b: SlackBlast): Float =
        if (b.recipientCount <= 0) 0f else ((b.sentCount + b.failedCount).toFloat() / b.recipientCount).coerceIn(0f, 1f)

    /** Who a blast will reach, per the server's rule: confirmed registrations with a linked Slack account. */
    fun estimateRecipients(participants: List<Participant>): Int =
        participants.count { it.status == "complete" && !it.slackUserId.isNullOrBlank() }

    /** Replaces a blast in the list by id, or puts it first if new. */
    fun upsert(list: List<SlackBlast>, blast: SlackBlast): List<SlackBlast> =
        if (list.any { it.id == blast.id }) list.map { if (it.id == blast.id) blast else it } else listOf(blast) + list
}
