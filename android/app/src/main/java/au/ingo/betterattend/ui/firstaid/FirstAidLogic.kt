package au.ingo.betterattend.ui.firstaid

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.scan.RosterSearch
import au.ingo.betterattend.util.Time
import java.time.Instant

enum class FirstAidFilter(val label: String) { HereNow("Here now"), Everyone("Everyone") }

/** One flag pill on a first-aid card. [danger] ones are life-threatening. */
data class FirstAidFlag(val label: String, val danger: Boolean)

/** A medical / safety detail line, e.g. "Allergies: Peanuts". */
data class FirstAidDetail(val label: String, val value: String, val danger: Boolean = false)

/** Someone to call: an emergency contact or the primary guardian. */
data class FirstAidContact(val name: String, val relationship: String?, val phone: String?, val email: String?)

/** Pure first-aid sheet rules: who's on it, in what order, and what each card says. */
object FirstAidLogic {
    private fun String?.present(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun hasFlag(p: Participant) = p.hasAnaphylaxisRisk || p.requiresRefrigeration || p.crossContaminationRisk || p.highSupportFlag

    private fun hasMedicalText(p: Participant) =
        listOf(p.lifeThreateningAllergies, p.allergies, p.medicalConditions, p.medications).any { it.present() != null }

    /**
     * On the sheet: active participants with a safety flag, or (only when [sensitive], i.e. the role may
     * see medical data) any medical text.
     */
    fun include(p: Participant, sensitive: Boolean): Boolean =
        p.isActive && (hasFlag(p) || (sensitive && hasMedicalText(p)))

    /** 0 = anaphylaxis / life-threatening, 1 = refrigerated medication, 2 = high support, 3 = anything else. */
    fun priority(p: Participant, sensitive: Boolean): Int = when {
        p.hasAnaphylaxisRisk || (sensitive && p.lifeThreateningAllergies.present() != null) -> 0
        p.requiresRefrigeration -> 1
        p.highSupportFlag -> 2
        else -> 3
    }

    /** Everyone on the sheet, most urgent first, then by name. */
    fun people(participants: List<Participant>, sensitive: Boolean): List<Participant> =
        participants.filter { include(it, sensitive) }
            .sortedWith(compareBy<Participant>({ priority(it, sensitive) }, { it.name.lowercase() }))

    /** [people] narrowed by the chip and search, keeping the urgency order. */
    fun filter(people: List<Participant>, filter: FirstAidFilter, query: String): List<Participant> {
        val shown = if (filter == FirstAidFilter.HereNow) people.filter { it.isCheckedIn } else people
        if (query.isBlank()) return shown
        val matching = RosterSearch.filter(shown, query, limit = Int.MAX_VALUE).map { it.participantEventId }.toSet()
        return shown.filter { it.participantEventId in matching }
    }

    fun counts(people: List<Participant>): Map<FirstAidFilter, Int> =
        mapOf(FirstAidFilter.HereNow to people.count { it.isCheckedIn }, FirstAidFilter.Everyone to people.size)

    /** "Here now" once anyone on the sheet has arrived, otherwise everyone. */
    fun defaultFilter(people: List<Participant>): FirstAidFilter =
        if (people.any { it.isCheckedIn }) FirstAidFilter.HereNow else FirstAidFilter.Everyone

    fun flags(p: Participant): List<FirstAidFlag> = buildList {
        if (p.hasAnaphylaxisRisk) add(FirstAidFlag("Anaphylaxis risk", danger = true))
        if (p.requiresRefrigeration) add(FirstAidFlag("Refrigerated medication", danger = false))
        if (p.crossContaminationRisk) add(FirstAidFlag("Cross-contamination risk", danger = false))
        if (p.highSupportFlag) add(FirstAidFlag("High support needs", danger = false))
    }

    /** Medical lines, only ever for roles that may see them. */
    fun details(p: Participant, sensitive: Boolean): List<FirstAidDetail> {
        if (!sensitive) return emptyList()
        return listOfNotNull(
            p.lifeThreateningAllergies.present()?.let { FirstAidDetail("Life-threatening allergies", it, danger = true) },
            p.allergies.present()?.let { FirstAidDetail("Allergies", it) },
            p.medicalConditions.present()?.let { FirstAidDetail("Conditions", it) },
            p.medications.present()?.let { FirstAidDetail("Medications", it) },
            // "Omnivore" / "none" tell a first-aider nothing.
            p.dietType.present()?.takeUnless { it.lowercase() in TRIVIAL_DIETS }?.let { FirstAidDetail("Diet", dietLabel(it)) },
        )
    }

    /** Emergency contacts (by priority), then the primary guardian unless they're already listed. */
    fun contacts(p: Participant, sensitive: Boolean): List<FirstAidContact> {
        if (!sensitive) return emptyList()
        val emergency = p.emergencyContacts.orEmpty()
            .sortedBy { it.priority ?: Int.MAX_VALUE }
            .mapNotNull { c ->
                val name = c.name.present() ?: c.phone.present() ?: return@mapNotNull null
                FirstAidContact(name, c.relationship.present(), c.phone.present(), c.email.present())
            }
        val guardianName = p.parentGuardianName.present()
        val guardianPhone = p.parentGuardianPhone.present()
        val guardian = if (guardianName != null || guardianPhone != null) {
            FirstAidContact(guardianName ?: "Parent / guardian", "Parent / guardian", guardianPhone, p.parentGuardianEmail.present())
        } else null
        val duplicate = guardian != null && emergency.any { e ->
            (guardian.phone != null && digits(e.phone) == digits(guardian.phone)) || e.name.equals(guardian.name, ignoreCase = true)
        }
        return if (guardian == null || duplicate) emergency else emergency + guardian
    }

    private fun digits(s: String?) = s?.filter(Char::isDigit)

    fun dietLabel(raw: String): String = raw.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** "Arrived 9:41 AM" / "Hasn't arrived". */
    fun presence(p: Participant, tz: String?): String =
        if (p.isCheckedIn) Time.time(p.checkedInAt, tz)?.let { "Arrived $it" } ?: "Here" else "Hasn't arrived"

    // ------------------------------------------------------------ print

    /** HTML-escapes user data for the printable sheet. */
    fun escape(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    const val PRINT_FOOTER = "Contains sensitive medical information. Handle and dispose of securely."

    /** A plain, printer-friendly page for [people] (already filtered and sorted). */
    fun html(
        eventName: String,
        people: List<Participant>,
        sensitive: Boolean,
        tz: String?,
        filterLabel: String,
        now: Instant = Instant.now(),
    ): String {
        val e = ::escape
        val generated = Time.dayTime(now.toString(), tz) ?: now.toString()
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<title>").append(e("$eventName — First-aid sheet")).append("</title>")
            append(
                "<style>" +
                    "body{font-family:sans-serif;font-size:11pt;color:#111;margin:0}" +
                    "h1{font-size:18pt;margin:0 0 4px}" +
                    ".meta{color:#555;margin:0 0 16px}" +
                    ".person{border:1px solid #bbb;border-radius:6px;padding:8px 10px;margin:0 0 10px;page-break-inside:avoid}" +
                    ".name{font-size:13pt;font-weight:bold}" +
                    ".sub{color:#555;font-weight:normal;font-size:10pt}" +
                    ".flags{margin:4px 0}" +
                    ".flag{display:inline-block;border:1px solid #999;border-radius:10px;padding:1px 8px;margin:2px 4px 2px 0;font-size:9pt}" +
                    ".flag.danger{border-color:#b00020;color:#b00020;font-weight:bold}" +
                    "table{border-collapse:collapse;margin-top:4px}" +
                    "td{vertical-align:top;padding:1px 10px 1px 0}" +
                    "td.k{color:#555;white-space:nowrap}" +
                    "td.danger{color:#b00020;font-weight:bold}" +
                    ".notice{color:#555;font-style:italic;margin:0 0 12px}" +
                    "footer{margin-top:16px;border-top:1px solid #bbb;padding-top:6px;font-size:9pt;color:#555}" +
                    "</style>",
            )
            append("</head><body>")
            append("<h1>").append(e("$eventName — First-aid sheet")).append("</h1>")
            append("<p class=\"meta\">").append(e("Generated $generated · $filterLabel · ${people.size} ${if (people.size == 1) "person" else "people"}")).append("</p>")
            if (!sensitive) append("<p class=\"notice\">").append(e(NO_SENSITIVE_NOTICE)).append("</p>")
            if (people.isEmpty()) append("<p>").append(e(EMPTY)).append("</p>")
            people.forEach { p ->
                append("<div class=\"person\">")
                append("<div class=\"name\">").append(e(p.fullName?.takeIf { it.isNotBlank() } ?: p.name))
                val sub = listOfNotNull(p.pronouns?.takeIf { it.isNotBlank() }, presence(p, tz)).joinToString(" · ")
                append(" <span class=\"sub\">").append(e(sub)).append("</span></div>")
                val flags = flags(p)
                if (flags.isNotEmpty()) {
                    append("<div class=\"flags\">")
                    flags.forEach { f -> append("<span class=\"flag").append(if (f.danger) " danger" else "").append("\">").append(e(f.label)).append("</span>") }
                    append("</div>")
                }
                val details = details(p, sensitive)
                val contacts = contacts(p, sensitive)
                if (details.isNotEmpty() || contacts.isNotEmpty()) {
                    append("<table>")
                    details.forEach { d ->
                        append("<tr><td class=\"k\">").append(e(d.label)).append("</td><td")
                        if (d.danger) append(" class=\"danger\"")
                        append(">").append(e(d.value)).append("</td></tr>")
                    }
                    contacts.forEach { c ->
                        val who = listOfNotNull(c.name, c.relationship?.let { "($it)" }).joinToString(" ")
                        val reach = listOfNotNull(c.phone, c.email).joinToString(" · ")
                        append("<tr><td class=\"k\">Contact</td><td>").append(e(listOf(who, reach).filter { it.isNotEmpty() }.joinToString(" — "))).append("</td></tr>")
                    }
                    append("</table>")
                }
                append("</div>")
            }
            append("<footer>").append(e(PRINT_FOOTER)).append("</footer>")
            append("</body></html>")
        }
    }

    const val NO_SENSITIVE_NOTICE = "Medical details are visible to safeguarding leads and admins."
    private val TRIVIAL_DIETS = setOf("omnivore", "none", "no_restrictions", "no restrictions", "standard")

    const val EMPTY = "No one has medical or safety flags."
}
