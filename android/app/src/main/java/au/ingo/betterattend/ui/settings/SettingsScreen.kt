package au.ingo.betterattend.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.ingo.betterattend.BuildConfig
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.auth.TokenIssueState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.rememberHaptics
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
    /** A freshly issued mobile token is being fetched after the browser sign-in. */
    val issuingToken: Boolean = false,
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
    val onIssueToken: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
fun SettingsScreen(nav: AppNavigator, onIssueToken: () -> Unit = {}) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val haptics = rememberHaptics()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val event by c.events.selectedEvent.collectAsStateWithLifecycle()
    val pending by c.scans.pending.collectAsStateWithLifecycle()
    var syncing by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val tokenIssue by c.auth.tokenIssue.collectAsStateWithLifecycle()

    // The browser sign-in came back with a new token: copy it, then drop it from memory.
    LaunchedEffect(tokenIssue) {
        when (val s = tokenIssue) {
            is TokenIssueState.Issued -> {
                copySensitive(context, "Attend mobile token", s.token)
                haptics.confirm()
                message = "New mobile token for ${s.user.email} copied. Treat it like a password."
                c.auth.tokenIssueHandled()
            }
            is TokenIssueState.Failed -> {
                haptics.reject()
                message = s.message
                c.auth.tokenIssueHandled()
            }
            else -> Unit
        }
    }

    val state = SettingsUiState(
        user = (auth as? AuthState.SignedIn)?.user,
        event = event,
        settings = settings ?: AppSettings(),
        pendingScans = pending.size,
        syncing = syncing,
        clearing = clearing,
        issuingToken = tokenIssue == TokenIssueState.Exchanging,
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
                        0 -> "All scans synced".also { haptics.confirm() }
                        else -> "$left scan${if (left == 1) "" else "s"} still waiting. We'll keep trying when you're back online."
                            .also { haptics.reject() }
                    }
                } catch (e: Exception) {
                    haptics.reject()
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
                haptics.confirm()
                message = "Cached data cleared. It'll download again as you use the app."
            }
        },
        onSignOut = {
            scope.launch {
                c.auth.signOut()
                haptics.confirm()
            }
        },
        onIssueToken = { haptics.click(); onIssueToken() },
        onOpenUrl = { haptics.click(); runCatching { uri.openUri(it) }.onFailure { haptics.reject() } },
        onMessageShown = { message = null },
    )

    SettingsContent(state, actions)
}

/** Copies [value], flagged sensitive so Android hides it from clipboard previews. */
private fun copySensitive(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value).apply {
        description.extras = PersistableBundle().apply {
            // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the key is honoured by some older keyboards too.
            putBoolean(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE",
                true,
            )
        }
    }
    cm.setPrimaryClip(clip)
}
