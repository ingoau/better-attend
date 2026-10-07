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
 * One notification per event, replaced by the next batch; opens People.
 */
object SignupNotifier {
    private const val CHANNEL = "new_signups"
    private const val ID = 4202

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

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
            context, ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "people").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = SignupLogic.title(signups.size, eventName)
        // Participants are often minors: names stay off the lock screen.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_person)
            .setContentTitle(title)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_person)
            .setContentTitle(title)
            .setContentText(SignupLogic.summary(signups))
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    signups.take(6).forEach { style.addLine(it.name) }
                    if (signups.size > 6) style.setSummaryText("+${signups.size - 6} more")
                },
            )
            .setNumber(signups.size)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(tag(eventId), ID, notification) }
    }

    /** Clears every signup notification (sign-out): they name participants. */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications.filter { it.id == ID && it.tag?.startsWith(TAG_PREFIX) == true }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    private const val TAG_PREFIX = "signups:"
    private fun tag(eventId: String) = TAG_PREFIX + eventId
}
