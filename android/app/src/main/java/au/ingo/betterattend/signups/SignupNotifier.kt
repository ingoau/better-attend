package au.ingo.betterattend.signups

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

/**
 * Tells organizers who opted in (Settings → Notifications) when people sign up for their event.
 * Each batch is its own notification, bundled per event so earlier names aren't lost; opens People.
 */
object SignupNotifier {
    private const val CHANNEL = "new_signups"
    private const val SUMMARY_ID = 4202
    private const val TAG_PREFIX = "signups:"

    /** Permission granted (Android 13+) and notifications not switched off for the app. */
    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun notify(context: Context, eventId: String, eventName: String?, signups: List<Participant>) {
        if (signups.isEmpty() || !canPost(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "New signups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When people sign up for the event you're working on"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, SUMMARY_ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "people").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val tag = TAG_PREFIX + eventId
        val title = SignupLogic.title(signups.size, eventName)
        val notification = builder(context, title, open, tag)
            .setContentText(SignupLogic.summary(signups))
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    signups.take(6).forEach { style.addLine(it.name) }
                    if (signups.size > 6) style.setSummaryText("+${signups.size - 6} more")
                },
            )
            .setNumber(signups.size)
            .build()
        // Older Android versions only bundle a group that has a summary.
        val summary = builder(context, SignupLogic.groupTitle(eventName), open, tag)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        runCatching {
            manager.notify(tag, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
            manager.notify(tag, SUMMARY_ID, summary)
        }
    }

    private fun builder(context: Context, title: String, open: PendingIntent, group: String): NotificationCompat.Builder {
        // Participants are often minors: names stay off the lock screen.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_person)
            .setContentTitle(title)
            .build()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_person)
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(group)
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    /** Clears every signup notification (sign-out): they name participants. */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
