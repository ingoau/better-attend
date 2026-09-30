package au.ingo.betterattend.screenshots

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

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenshots : ScreenshotTest() {
    private val state = SettingsUiState(
        user = SampleData.user.copy(globalAdmin = true),
        event = SampleData.event,
        settings = AppSettings(themeMode = ThemeMode.System, dynamicColor = false, sounds = true, haptics = true, keepScreenOn = false),
        pendingScans = 3,
        versionName = "1.0.0",
    )

    @Test fun settings() = snap("settings") { SettingsContent(state, SettingsActions()) }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun settingsFull() = snap("settings_full") {
        SettingsContent(state.copy(pendingScans = 0, dynamicColorSupported = false, settings = state.settings.copy(themeMode = ThemeMode.Dark)), SettingsActions())
    }
}
