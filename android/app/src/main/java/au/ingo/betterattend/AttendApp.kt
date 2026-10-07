package au.ingo.betterattend

import android.app.Application
import android.content.Context
import au.ingo.betterattend.signups.SignupLogic
import au.ingo.betterattend.signups.SignupNotifier
import au.ingo.betterattend.signups.SignupWatch

class AttendApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scans.onQueued = { au.ingo.betterattend.scan.ScanSyncWorker.enqueue(this) }
        container.scans.onRejected = { au.ingo.betterattend.scan.RejectionNotifier.notify(this, it) }
        container.participants.onNewSignups = { eventId, signups, previousSyncAt ->
            if (SignupLogic.shouldNotify(container.settings.current(), previousSyncAt)) {
                val eventName = container.events.events.value?.firstOrNull { it.id == eventId }?.name
                SignupNotifier.notify(this, eventId, eventName, signups)
            }
        }
        SignupWatch.start(this, container)
        au.ingo.betterattend.widget.WidgetUpdater.start(this, container)
    }
}

val Context.container: AppContainer get() = (applicationContext as AttendApp).container
