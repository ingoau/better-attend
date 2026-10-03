package au.ingo.betterattend.screenshots

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.data.store.ThemeMode
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.settings.SettingsActions
import au.ingo.betterattend.ui.settings.SettingsContent
import au.ingo.betterattend.ui.settings.SettingsUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenshots : ScreenshotTest() {
    private val state = SettingsUiState(
        user = SampleData.user.copy(globalAdmin = true),
        event = SampleData.event,
        settings = AppSettings(themeMode = ThemeMode.System, dynamicColor = false, sounds = true, haptics = true, keepScreenOn = false),
        pendingScans = 3,
        versionName = "1.0.0",
        defaultTokenName = "Google Pixel 9 (BetterAttend, copied token)",
    )

    @Test fun settings() = snap("settings") { SettingsContent(state, SettingsActions()) }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun settingsFull() = snap("settings_full") {
        SettingsContent(state.copy(pendingScans = 0, dynamicColorSupported = false, settings = state.settings.copy(themeMode = ThemeMode.Dark)), SettingsActions())
    }

    private fun scrollToBottom() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sign out"))
    }

    @Test fun settingsDeveloper() = snap("settings_developer", prepare = ::scrollToBottom) {
        SettingsContent(state, SettingsActions())
    }

    @Test fun settingsIssuingToken() = snap("settings_issuing_token", prepare = ::scrollToBottom) {
        SettingsContent(state.copy(issuingToken = true), SettingsActions())
    }

    @Test fun settingsIssueTokenDialog() = snap("settings_issue_token_dialog", screen = true, prepare = {
        scrollToBottom()
        // The dialog's text field never lets Compose go idle, so step a paused clock instead.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Copy a new mobile token").performClick()
        repeat(2) { compose.mainClock.advanceTimeBy(1_000); ShadowLooper.idleMainLooper() }
    }) {
        SettingsContent(state, SettingsActions())
    }
}
