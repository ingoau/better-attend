package au.ingo.betterattend

import android.app.Application
import android.content.Context

class AttendApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as AttendApp).container
