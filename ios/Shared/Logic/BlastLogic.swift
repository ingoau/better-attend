import Foundation

/// Slack announcements ("blasts"): text conversion, delivery progress and recipient estimates.
enum BlastLogic {
    /// Slack recommends keeping messages under 4,000 characters; longer ones get truncated in clients.
    static let maxLength = 4000

    static func isActive(_ b: SlackBlast) -> Bool { b.status == "pending" || b.status == "in_progress" }

    /// The server accepts HTML and converts it to Slack mrkdwn, where raw newlines would collapse.
    /// Escapes the text and keeps the organizer's line breaks: blank lines become paragraphs, single ones `<br>`.
    static func toHtml(_ plain: String) -> String {
        let normalized = plain.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\r\n", with: "\n")
        let paragraphs = normalized
            .split(separator: /\n\s*\n/, omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let escaped = paragraphs.map { p in
            escape(p).split(separator: "\n", omittingEmptySubsequences: false).map { trimEnd(String($0)) }.joined(separator: "<br>")
        }
        guard escaped.count > 1 else { return escaped.first ?? "" }
        return escaped.map { "<p>\($0)</p>" }.joined()
    }

    /// Readable text for history rows from whatever HTML was sent (ours or the web dashboard's).
    static func toPlain(_ html: String) -> String {
        var s = html
        func re(_ pattern: String, _ with: String) {
            s = s.replacingOccurrences(of: pattern, with: with, options: [.regularExpression, .caseInsensitive])
        }
        re("<br\\s*/?>", "\n")
        re("</p>\\s*<p[^>]*>", "\n\n")
        re("</p>", "\n\n")
        re("</(li|div|h[1-6])>", "\n")
        re("<li[^>]*>", "• ")
        re("<[^>]+>", "")
        s = s.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&#x27;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
        re("\n{3,}", "\n\n")
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// "118/120 sent · 2 failed", "71 of 116 sent", "Queued…".
    static func progressText(_ b: SlackBlast) -> String {
        switch b.status {
        case "pending":
            return b.recipientCount > 0 ? "Queued for \(b.recipientCount) people…" : "Queued…"
        case "in_progress":
            return "\(b.sentCount) of \(b.recipientCount) sent" + (b.failedCount > 0 ? " · \(b.failedCount) failed" : "")
        case "failed":
            return b.recipientCount > 0 ? "\(b.sentCount)/\(b.recipientCount) sent · \(b.failedCount) failed" : "Couldn't send"
        default:
            if b.failedCount > 0 { return "\(b.sentCount)/\(b.recipientCount) sent · \(b.failedCount) failed" }
            if b.recipientCount > 0 && b.sentCount == b.recipientCount { return "Sent to all \(b.recipientCount)" }
            return "\(b.sentCount)/\(b.recipientCount) sent"
        }
    }

    /// Delivered (or failed) share of recipients, 0…1.
    static func fraction(_ b: SlackBlast) -> Double {
        guard b.recipientCount > 0 else { return 0 }
        return min(max(Double(b.sentCount + b.failedCount) / Double(b.recipientCount), 0), 1)
    }

    /// Who a blast will reach, per the server's rule: confirmed registrations with a linked Slack account.
    static func estimateRecipients(_ participants: [Participant]) -> Int {
        participants.count(where: { $0.status == "complete" && !($0.slackUserId ?? "").isBlank })
    }

    /// Replaces a blast in the list by id, or puts it first if new.
    static func upsert(_ list: [SlackBlast], _ blast: SlackBlast) -> [SlackBlast] {
        guard list.contains(where: { $0.id == blast.id }) else { return [blast] + list }
        return list.map { $0.id == blast.id ? blast : $0 }
    }

    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }

    private static func trimEnd(_ s: String) -> String {
        var s = Substring(s)
        while let last = s.last, last.isWhitespace { s.removeLast() }
        return String(s)
    }
}
