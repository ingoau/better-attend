package au.ingo.betterattend.scan

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
import au.ingo.betterattend.data.repo.ScanRejection
import au.ingo.betterattend.ui.scan.offlineRejectionsTitle

/**
 * Tells staff when scans saved offline are turned down as they sync (they may already have let the
 * person in). Opens Home, where the banner lists each person and links to them.
 */
object RejectionNotifier {
    private const val CHANNEL = "offline_rejections"
    private const val ID = 4201

    fun notify(context: Context, rejected: List<ScanRejection>) {
        if (rejected.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return // Home's banner still shows them.
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Rejected offline check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When scans saved offline are turned down by Attend as they sync"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TAB, "home").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lines = rejected.map { it.headline }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_widget_qr_scan)
            .setContentTitle(offlineRejectionsTitle(rejected.size))
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(6).forEach(style::addLine) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ID, notification) }
    }
}
