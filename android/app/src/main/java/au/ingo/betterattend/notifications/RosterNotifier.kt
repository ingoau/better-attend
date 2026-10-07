package au.ingo.betterattend.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import au.ingo.betterattend.MainActivity
import au.ingo.betterattend.R
import au.ingo.betterattend.data.model.Participant

/** The notifications this app posts for organizers who opted in (Settings → Notifications). */
object AlertNotifications {
    /** Every tag starts with this, so sign-out can clear them all: they name participants. */
    const val TAG_PREFIX = "alerts:"

    /** Permission granted (Android 13+) and notifications not switched off for the app. */
    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun channel(context: Context, id: String, name: String, description: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT) {
        if (Build.VERSION.SDK_INT < 26) return
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannel(id, name, importance).apply { this.description = description },
        )
    }

    /** Opens a tab ("people", "travel"). */
    fun openTab(context: Context, tab: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A notification whose details stay off the lock screen (participants are often minors). */
    fun builder(context: Context, channel: String, title: String, open: PendingIntent, group: String): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_widget_person)
            .setContentTitle(title)
            .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_widget_person)
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setGroup(group)
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    /** A unique id per post, so a new batch doesn't replace one that hasn't been read. */
    fun nextId(): Int = (System.nanoTime() and 0x3fffffff).toInt()

    /** Clears every alert notification (sign-out). */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}

/**
 * Signups and withdrawals at the event the organizer is working on. Each batch is its own
 * notification, bundled per event so earlier names aren't lost; opens People.
 */
object RosterNotifier {
    enum class Kind(val channel: String, val channelName: String, val channelDescription: String, val summaryId: Int) {
        Signups("new_signups", "New signups", "When people sign up for the event you're working on", 4202),
        Withdrawals("withdrawals", "Withdrawals", "When people withdraw from the event you're working on", 4203),
    }

    fun notify(context: Context, kind: Kind, eventId: String, eventName: String?, people: List<Participant>) {
        if (people.isEmpty() || !AlertNotifications.canPost(context)) return
        AlertNotifications.channel(context, kind.channel, kind.channelName, kind.channelDescription)
        val open = AlertNotifications.openTab(context, "people", kind.summaryId)
        val tag = "${AlertNotifications.TAG_PREFIX}${kind.channel}:$eventId"
        val (title, groupTitle) = when (kind) {
            Kind.Signups -> RosterAlerts.signupTitle(people.size, eventName) to RosterAlerts.signupGroupTitle(eventName)
            Kind.Withdrawals -> RosterAlerts.withdrawalTitle(people.size, eventName) to RosterAlerts.withdrawalGroupTitle(eventName)
        }
        val category = if (kind == Kind.Signups) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_STATUS
        val names = people.map { it.name }
        val notification = AlertNotifications.builder(context, kind.channel, title, open, tag)
            .setContentText(RosterAlerts.summary(names))
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    names.take(6).forEach(style::addLine)
                    if (names.size > 6) style.setSummaryText("+${names.size - 6} more")
                },
            )
            .setNumber(people.size)
            .setCategory(category)
            .build()
        // Older Android versions only bundle a group that has a summary.
        val summary = AlertNotifications.builder(context, kind.channel, groupTitle, open, tag)
            .setCategory(category)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        val manager = NotificationManagerCompat.from(context)
        runCatching {
            manager.notify(tag, AlertNotifications.nextId(), notification)
            manager.notify(tag, kind.summaryId, summary)
        }
    }
}
