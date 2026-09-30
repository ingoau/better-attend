package au.ingo.betterattend.ui.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.ingo.betterattend.BuildConfig
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.nav.AppNavigator
import kotlinx.coroutines.launch

data class SettingsUiState(
    val user: User? = null,
    /** The event being worked on, to show the user's role there. */
    val event: Event? = null,
    val settings: AppSettings = AppSettings(),
    val pendingScans: Int = 0,
    val syncing: Boolean = false,
    /** Result of the last "Sync now" / "Clear cached data", shown in a snackbar. */
    val message: String? = null,
    val clearing: Boolean = false,
    val dynamicColorSupported: Boolean = true,
    val versionName: String = "",
)

/** Callbacks for [SettingsContent]; grouped so the stateless content stays readable. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onThemeMode: (au.ingo.betterattend.data.store.ThemeMode) -> Unit = {},
    val onDynamicColor: (Boolean) -> Unit = {},
    val onSounds: (Boolean) -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
fun SettingsScreen(nav: AppNavigator) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val event by c.events.selectedEvent.collectAsStateWithLifecycle()
    val pending by c.scans.pending.collectAsStateWithLifecycle()
    var syncing by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val state = SettingsUiState(
        user = (auth as? AuthState.SignedIn)?.user,
        event = event,
        settings = settings ?: AppSettings(),
        pendingScans = pending.size,
        syncing = syncing,
        clearing = clearing,
        message = message,
        dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        versionName = BuildConfig.VERSION_NAME,
    )

    val actions = SettingsActions(
        onBack = nav::back,
        onThemeMode = { scope.launch { c.settings.setThemeMode(it) } },
        onDynamicColor = { scope.launch { c.settings.setDynamicColor(it) } },
        onSounds = { scope.launch { c.settings.setSounds(it) } },
        onHaptics = { scope.launch { c.settings.setHaptics(it) } },
        onKeepScreenOn = { scope.launch { c.settings.setKeepScreenOn(it) } },
        onSyncNow = {
            scope.launch {
                syncing = true
                message = try {
                    when (val left = c.scans.flush()) {
                        0 -> "All scans synced"
                        else -> "$left scan${if (left == 1) "" else "s"} still waiting. We'll keep trying when you're back online."
                    }
                } catch (e: Exception) {
                    e.friendlyMessage
                }
                syncing = false
            }
        },
        onClearCache = {
            scope.launch {
                clearing = true
                // Wipes the encrypted roster/travel/ticket cache; the signed-in session and settings stay.
                c.participants.clear()
                c.travel.clear()
                c.tickets.clear()
                launch { c.events.refresh() }
                launch { c.tickets.refresh() }
                clearing = false
                message = "Cached data cleared. It'll download again as you use the app."
            }
        },
        onSignOut = { scope.launch { c.auth.signOut() } },
        onOpenUrl = { runCatching { uri.openUri(it) } },
        onMessageShown = { message = null },
    )

    SettingsContent(state, actions)
}
