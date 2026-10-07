package au.ingo.betterattend

import android.app.Application
import android.content.Context
import au.ingo.betterattend.notifications.NotificationWatch

class AttendApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scans.onQueued = { au.ingo.betterattend.scan.ScanSyncWorker.enqueue(this) }
        container.scans.onRejected = { au.ingo.betterattend.scan.RejectionNotifier.notify(this, it) }
        NotificationWatch.start(this, container)
        au.ingo.betterattend.widget.WidgetUpdater.start(this, container)
    }
}

val Context.container: AppContainer get() = (applicationContext as AttendApp).container
