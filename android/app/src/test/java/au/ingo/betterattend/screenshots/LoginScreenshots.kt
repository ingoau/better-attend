package au.ingo.betterattend.screenshots

import au.ingo.betterattend.ui.components.EventPickerContent
import au.ingo.betterattend.ui.login.LoginScreen
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LoginScreenshots : ScreenshotTest() {
    @Test fun login() = snap("login") { LoginScreen(loading = false, error = null, onSignIn = {}) }
    @Test fun loginError() = snap("login_error") {
        LoginScreen(loading = false, error = "We couldn't find an Attend account for that Hack Club login.", onSignIn = {})
    }
    @Test fun eventPicker() = snap("event_picker") { EventPickerContent(SampleData.events, SampleData.event.id) {} }
}
