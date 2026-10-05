package au.ingo.betterattend.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.SupervisorAccount
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.auth.AuthRepository
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.store.ThemeMode
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.SegmentedItem
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.dashboard.DashboardLogic
import au.ingo.betterattend.ui.theme.status

object SettingsLinks {
    const val ATTEND = "https://attend.hackclub.com"
    const val INCIDENT = "https://hack.club/incident"
    const val HOTLINE = "tel:+18556254225"
    const val HOTLINE_DISPLAY = "+1 (855) 625-4225"
    const val SOURCE = "https://github.com/ingoau/better-attend-mobile"
}

@Composable
fun SettingsContent(state: SettingsUiState, actions: SettingsActions) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmIssueToken by rememberSaveable { mutableStateOf(false) }
    var tokenName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.onMessageShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = actions.onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item { AccountCard(state) }

            // Staff management is admin-only upstream (GET /staff included): hidden for everyone else.
            if (state.event != null && EventPermissions.canManageStaff(state.event)) {
                item { GroupTitle("Event") }
                item {
                    Segment(0, 1, onClick = actions.onOpenStaff) {
                        ListItem(
                            headlineContent = { Text("Event staff") },
                            supportingContent = { Text("Who works on ${state.event.name}, and their roles") },
                            leadingContent = { Icon(Icons.Outlined.SupervisorAccount, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }

            item { GroupTitle("Appearance") }
            item {
                Segment(0, 2) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text("Theme", style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.height(12.dp))
                        ThemePicker(state.settings.themeMode, actions.onThemeMode)
                    }
                }
            }
            item {
                SwitchRow(
                    1, 2, Icons.Outlined.Palette, "Use wallpaper colours",
                    if (state.dynamicColorSupported) "Match your phone's colours instead of Hack Club red" else "Needs Android 12 or newer",
                    checked = state.settings.dynamicColor && state.dynamicColorSupported,
                    enabled = state.dynamicColorSupported,
                    onChange = actions.onDynamicColor,
                )
            }

            item { GroupTitle("Scanning") }
            item { SwitchRow(0, 3, Icons.Outlined.VolumeUp, "Sounds", "A different sound for each scan result", state.settings.sounds, onChange = actions.onSounds) }
            item { SwitchRow(1, 3, Icons.Outlined.Vibration, "Haptics", "Feel scans, taps and gestures", state.settings.haptics, onChange = actions.onHaptics) }
            item { SwitchRow(2, 3, Icons.Outlined.ScreenLockPortrait, "Keep screen on", "While the scanner is open", state.settings.keepScreenOn, onChange = actions.onKeepScreenOn) }

            item { GroupTitle("Data") }
            item {
                val pending = state.pendingScans
                Segment(0, 2) {
                    ListItem(
                        headlineContent = { Text("Offline scans") },
                        supportingContent = {
                            Text(
                                if (pending == 0) "Everything's synced" else "$pending scan${if (pending == 1) "" else "s"} waiting to sync",
                                color = if (pending > 0) MaterialTheme.status.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = { Icon(if (pending == 0) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload, null) },
                        trailingContent = {
                            if (state.syncing) LoadingIndicator(Modifier.size(32.dp))
                            else FilledTonalButton(onClick = actions.onSyncNow, enabled = pending > 0, shapes = ButtonDefaults.shapes()) { Text("Sync now") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                Segment(1, 2, onClick = { confirmClear = true }, enabled = state.pendingScans == 0 && !state.clearing) {
                    ListItem(
                        headlineContent = { Text("Clear cached data") },
                        supportingContent = {
                            Text(
                                if (state.pendingScans > 0) "Sync your offline scans first"
                                else "Removes saved participants, travel and tickets from this phone. You stay signed in.",
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.DeleteSweep, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item { GroupTitle("Safety & help") }
            item { LinkRow(0, 3, Icons.Outlined.ReportProblem, "Report an incident", "hack.club/incident") { actions.onOpenUrl(SettingsLinks.INCIDENT) } }
            item { LinkRow(1, 3, Icons.Outlined.Phone, "24/7 safety hotline", SettingsLinks.HOTLINE_DISPLAY, external = false) { actions.onOpenUrl(SettingsLinks.HOTLINE) } }
            item { LinkRow(2, 3, Icons.Outlined.Public, "Attend on the web", "attend.hackclub.com") { actions.onOpenUrl(SettingsLinks.ATTEND) } }

            item { GroupTitle("About") }
            item {
                Segment(0, 2) {
                    ListItem(
                        headlineContent = { Text("BetterAttend") },
                        supportingContent = { Text("Version ${state.versionName}") },
                        leadingContent = { Icon(Icons.Outlined.Info, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item { LinkRow(1, 2, Icons.Outlined.Code, "Source code", "github.com/ingoau/better-attend-mobile") { actions.onOpenUrl(SettingsLinks.SOURCE) } }

            item { GroupTitle("Developer") }
            item {
                Segment(0, 1, onClick = { tokenName = state.defaultTokenName; confirmIssueToken = true }, enabled = !state.issuingToken) {
                    ListItem(
                        headlineContent = { Text("Copy a new mobile token") },
                        supportingContent = { Text("Sign in again to get a separate 14-day token for scripts and tools. This phone keeps its own.") },
                        leadingContent = { Icon(Icons.Outlined.Key, null) },
                        trailingContent = { if (state.issuingToken) LoadingIndicator(Modifier.size(32.dp)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("Clear cached data?") },
            text = { Text("Saved participants, travel and tickets are removed from this phone and downloaded again when needed. The first sync of a big event can take a moment.") },
            confirmButton = { Button(onClick = { confirmClear = false; actions.onClearCache() }, shapes = ButtonDefaults.shapes()) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        )
    }

    if (confirmIssueToken) {
        AlertDialog(
            onDismissRequest = { confirmIssueToken = false },
            icon = { Icon(Icons.Outlined.Key, null) },
            title = { Text("Copy a new mobile token?") },
            text = {
                Column {
                    Text(
                        "You'll sign in on auth.hackclub.com again. Attend then issues a brand-new token, which is copied to " +
                            "your clipboard. Anyone with it can act as you for 14 days, so keep it private.",
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = tokenName,
                        onValueChange = { tokenName = it.take(AuthRepository.MAX_DEVICE_NAME) },
                        label = { Text("Device name") },
                        supportingText = { Text("Shown in Attend's list of signed-in devices") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirmIssueToken = false; actions.onIssueToken(tokenName.ifBlank { state.defaultTokenName }) }, shapes = ButtonDefaults.shapes()) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { confirmIssueToken = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
            title = { Text("Sign out?") },
            text = {
                Text(
                    buildString {
                        append("Participant data saved on this phone will be wiped.")
                        if (state.pendingScans > 0) {
                            append(" ${state.pendingScans} offline scan${if (state.pendingScans == 1) " hasn't" else "s haven't"} synced yet and will be lost.")
                        }
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmSignOut = false; actions.onSignOut() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountCard(state: SettingsUiState) {
    val user = state.user
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(user?.displayName ?: "?", null, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(user?.displayName ?: "Signed in", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (user != null) Text(user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val s = MaterialTheme.status
            val badges = buildList {
                if (user?.globalAdmin == true) add(Triple("Global admin", s.dangerContainer, s.onDangerContainer))
                if (user?.isOrganizer == true || user?.globalAdmin == true) add(Triple("Organizer", s.infoContainer, s.onInfoContainer))
                if (user?.isParticipant == true) add(Triple("Participant", s.successContainer, s.onSuccessContainer))
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { (label, bg, fg) -> Pill(label, bg, fg) }
                }
            }
            val role = DashboardLogic.roleLabel(state.event?.role)
            if (state.event != null && role != null) {
                Spacer(Modifier.height(12.dp))
                Text("$role at ${state.event.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp).semantics { heading() },
    )
}

/** One item in an expressive segmented group; its corners spring rounder while pressed. */
@Composable
private fun Segment(
    index: Int,
    count: Int,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = SegmentedItem(index, count, modifier, onClick = onClick, enabled = enabled, role = role, content = content)

@Composable
private fun SwitchRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    // The whole segment toggles (and morphs on press); the switch is just its indicator.
    Segment(
        index, count,
        onClick = { haptics.toggle(!checked); onChange(!checked) },
        enabled = enabled,
        role = Role.Switch,
        modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, null) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun LinkRow(index: Int, count: Int, icon: ImageVector, title: String, subtitle: String, external: Boolean = true, onClick: () -> Unit) {
    Segment(index, count, onClick = onClick) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, null) },
            trailingContent = { if (external) Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Opens in browser", Modifier.size(20.dp)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun ThemePicker(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val options = listOf(
        Triple(ThemeMode.System, "System", Icons.Outlined.BrightnessAuto),
        Triple(ThemeMode.Light, "Light", Icons.Outlined.LightMode),
        Triple(ThemeMode.Dark, "Dark", Icons.Outlined.DarkMode),
    )
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (value, label, icon) ->
            ToggleButton(
                checked = mode == value,
                onCheckedChange = { if (mode != value) haptics.tick(); onChange(value) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "$label theme" },
            ) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, maxLines = 1)
            }
        }
    }
}
