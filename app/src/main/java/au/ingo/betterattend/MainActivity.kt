package au.ingo.betterattend

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import au.ingo.betterattend.ui.AttendRoot
import au.ingo.betterattend.ui.ExternalNavRequests
import au.ingo.betterattend.ui.nav.Tab
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var authLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // Auth Tab returns the redirect straight to us; older browsers fall back to a Custom Tab
        // and the redirect then arrives through the attend://oauth/callback intent filter.
        authLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
            when (result.resultCode) {
                AuthTabIntent.RESULT_OK -> result.resultUri?.let(::completeSignIn)
                AuthTabIntent.RESULT_CANCELED -> container.auth.cancelled()
                else -> container.auth.cancelled("Sign-in couldn't be verified. Please try again.")
            }
        }
        handleIntent(intent)
        setContent { AttendRoot(container, onSignIn = ::startSignIn) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (container.auth.isOAuthCallback(data)) {
            completeSignIn(data!!)
            return
        }
        val tab = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: data?.takeIf { it.scheme == "attend" }?.host
        when (tab?.lowercase()) {
            "scan", "scanner" -> ExternalNavRequests.tab.value = Tab.Scan
            "home", "events" -> ExternalNavRequests.tab.value = Tab.Home
            "people", "search" -> ExternalNavRequests.tab.value = Tab.People
            "travel" -> ExternalNavRequests.tab.value = Tab.Travel
            "tickets", "my-tickets" -> ExternalNavRequests.tab.value = Tab.Tickets
        }
    }

    private fun completeSignIn(uri: Uri) {
        lifecycleScope.launch { container.auth.handleCallback(uri) }
    }

    private fun startSignIn() {
        val uri = container.auth.buildAuthorizeUri()
        try {
            AuthTabIntent.Builder().build().launch(authLauncher, uri, "attend")
        } catch (_: ActivityNotFoundException) {
            try {
                CustomTabsIntent.Builder().build().launchUrl(this, uri)
            } catch (_: ActivityNotFoundException) {
                container.auth.cancelled("No web browser found. Install Chrome or another browser to sign in.")
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
    }
}
