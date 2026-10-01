package au.ingo.betterattend.ui.share

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.ui.graphics.vector.ImageVector
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.ui.dashboard.ArrivalsSummary
import au.ingo.betterattend.ui.dashboard.ContextProgress
import au.ingo.betterattend.ui.dashboard.DashboardLogic
import au.ingo.betterattend.ui.dashboard.ScanFeedSummary
import au.ingo.betterattend.ui.dashboard.contextIcon
import au.ingo.betterattend.util.Time
import java.text.NumberFormat
import java.time.Instant

/** One number that can go on a share card, e.g. "Checked in 84 of 120". */
data class ShareStat(
    val id: String,
    val label: String,
    val value: Int,
    val icon: ImageVector,
    /** Shown as "of 120" with a progress bar, when the card's totals are on. */
    val total: Int? = null,
    val prefix: String = "",
    val suffix: String = "",
) {
    val display: String get() = prefix + NumberFormat.getIntegerInstance().format(value) + suffix
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (value.toFloat() / it).coerceIn(0f, 1f) }
}

/** Every number Home shows, in Home's order, so a long-press on any of them can open the share sheet. */
object ShareStats {
    const val CHECKED_IN = "checked_in"
    const val NOT_HERE = "not_here"
    const val LAST_HOUR = "last_hour"
    const val REGISTRATIONS = "registrations"
    const val REGISTERED = "registered"
    const val CONFIRMED = "confirmed"
    const val NOT_COMPLETE = "not_complete"
    const val WITHDRAWN = "withdrawn"
    const val TO_COLLECT = "to_collect"
    const val PICKED_UP = "picked_up"
    const val ARRIVED = "arrived"
    const val SCANS_TODAY = "scans_today"
    const val PEOPLE_SCANNED = "people_scanned"

    /** At most this many numbers fit on one card. */
    const val MAX_ON_CARD = 6

    fun context(contextId: String) = "context:$contextId"

    /** The id Home's hero number shares: registrations before the event, check-ins once it's on. */
    fun heroId(event: Event, now: Instant): String =
        if (Time.phase(event.startsAt, event.endsAt, now) == Time.Phase.Upcoming) REGISTRATIONS else CHECKED_IN

    fun available(
        event: Event,
        stats: EventStats?,
        contexts: List<ContextProgress>,
        arrivals: ArrivalsSummary?,
        feed: ScanFeedSummary?,
        now: Instant,
    ): List<ShareStat> = buildList {
        val phase = Time.phase(event.startsAt, event.endsAt, now)
        if (stats != null) {
            if (phase == Time.Phase.Upcoming) {
                add(ShareStat(REGISTRATIONS, "Registrations complete", stats.confirmed, Icons.Outlined.Verified, total = stats.registered))
            } else {
                add(ShareStat(CHECKED_IN, "Checked in", DashboardLogic.checkedInConfirmed(stats), Icons.Outlined.HowToReg, total = stats.expected))
                add(ShareStat(NOT_HERE, "Not here yet", stats.notArrived, Icons.Outlined.PersonSearch))
                if (phase != Time.Phase.Past) {
                    add(ShareStat(LAST_HOUR, "In the last hour", stats.checkedInLastHour, Icons.AutoMirrored.Outlined.TrendingUp, prefix = "+"))
                }
            }
            add(ShareStat(REGISTERED, "Registered", stats.registered, Icons.Outlined.Groups))
            add(ShareStat(CONFIRMED, "Confirmed", stats.confirmed, Icons.Outlined.Verified))
            add(ShareStat(NOT_COMPLETE, "Not complete", DashboardLogic.notComplete(stats), Icons.Outlined.HourglassTop))
            add(ShareStat(WITHDRAWN, "Withdrawn", stats.withdrawn, Icons.Outlined.PersonOff))
            contexts.forEach { add(ShareStat(context(it.context.id), it.context.name, it.count, contextIcon(it.context), total = it.total)) }
        }
        if (arrivals != null) {
            add(ShareStat(TO_COLLECT, "To collect", arrivals.awaitingPickup, Icons.Outlined.FlightLand))
            add(ShareStat(PICKED_UP, "Picked up", arrivals.collected, Icons.Outlined.DirectionsCar))
            add(ShareStat(ARRIVED, "Arrived", arrivals.checkedIn, Icons.Outlined.CheckCircle))
        }
        if (feed != null) {
            add(ShareStat(SCANS_TODAY, "Scans today", feed.today, Icons.Outlined.QrCodeScanner, suffix = if (feed.capped) "+" else ""))
            add(ShareStat(PEOPLE_SCANNED, "People scanned", feed.uniquePeopleToday, Icons.Outlined.Groups))
        }
    }

    /** Adds or removes [id], keeping the pick order, never emptying the card and never going past [MAX_ON_CARD]. */
    fun toggle(selected: List<String>, id: String): List<String> = when {
        id in selected -> if (selected.size > 1) selected - id else selected
        selected.size >= MAX_ON_CARD -> selected
        else -> selected + id
    }
}
