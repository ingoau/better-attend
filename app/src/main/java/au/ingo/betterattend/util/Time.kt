package au.ingo.betterattend.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Date/time helpers. Server timestamps are ISO-8601; event-local display uses the event's IANA zone. */
object Time {
    fun parse(iso: String?): Instant? = iso?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(it) }.getOrNull()
    }

    fun zone(tz: String?): ZoneId = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    fun nowIso(): String = Instant.now().toString()

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val dayYearFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
    private val longDayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

    /** "9:41 AM" in the given zone. */
    fun time(iso: String?, tz: String? = null): String? = parse(iso)?.atZone(zone(tz))?.format(timeFmt)

    /** "Sat 4 Oct" (year added when not the current year). */
    fun day(iso: String?, tz: String? = null): String? = parse(iso)?.atZone(zone(tz))?.let { d ->
        if (d.year == LocalDate.now().year) d.format(dayFmt) else d.format(dayYearFmt)
    }

    fun longDay(date: LocalDate): String = date.format(longDayFmt)

    /** "Sat 4 Oct, 9:41 AM" */
    fun dayTime(iso: String?, tz: String? = null): String? {
        val z = parse(iso)?.atZone(zone(tz)) ?: return null
        val day = if (z.year == LocalDate.now().year) z.format(dayFmt) else z.format(dayYearFmt)
        return "$day, ${z.format(timeFmt)}"
    }

    /** "Sat 4 – Mon 6 Oct" style range for event cards. */
    fun range(startIso: String?, endIso: String?, tz: String?): String? {
        val s = parse(startIso)?.atZone(zone(tz)) ?: return null
        val e = parse(endIso)?.atZone(zone(tz))
        if (e == null || s.toLocalDate() == e.toLocalDate()) return day(startIso, tz)
        val sameMonth = s.month == e.month && s.year == e.year
        val left = if (sameMonth) s.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())) else s.format(dayFmt)
        return "$left – ${day(endIso, tz)}"
    }

    /** "just now", "5 min ago", "2 h ago", "3 d ago". */
    fun ago(iso: String?, now: Instant = Instant.now()): String? {
        val t = parse(iso) ?: return null
        val d = Duration.between(t, now)
        return when {
            d.isNegative || d.seconds < 45 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} d ago"
        }
    }

    enum class Phase { Upcoming, Live, Past, Unknown }

    fun phase(startIso: String?, endIso: String?, now: Instant = Instant.now()): Phase {
        val s = parse(startIso) ?: return Phase.Unknown
        val e = parse(endIso) ?: s.plus(Duration.ofDays(1))
        return when {
            now.isBefore(s) -> Phase.Upcoming
            now.isAfter(e) -> Phase.Past
            else -> Phase.Live
        }
    }

    fun zoned(iso: String?, tz: String?): ZonedDateTime? = parse(iso)?.atZone(zone(tz))
}
