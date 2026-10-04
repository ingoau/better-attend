package au.ingo.betterattend.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.scan.CameraAccess
import au.ingo.betterattend.ui.scan.CameraPlaceholder
import au.ingo.betterattend.ui.scan.KioskContent
import au.ingo.betterattend.ui.scan.KioskExitContent
import au.ingo.betterattend.ui.scan.KioskSetupContent
import au.ingo.betterattend.ui.scan.KioskUiState
import au.ingo.betterattend.ui.scan.ResultKind
import au.ingo.betterattend.ui.scan.ScanCard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class KioskScreenshots : ScreenshotTest() {
    private val base = KioskUiState(eventName = "Campfire Canberra", contextName = "Check-in desk", contextIcon = Icons.AutoMirrored.Outlined.Login, nfcReady = true)
    private val maya = SampleData.participants[2]

    @Composable
    private fun Kiosk(state: KioskUiState) = KioskContent(state, onFlipCamera = {}, onExit = {}, camera = { CameraPlaceholder() })

    @Test fun prompt() = snap("kiosk_prompt") { Kiosk(base) }

    @Test fun welcome() = snap("kiosk_result_scanned") {
        Kiosk(base.copy(card = ScanCard("k", ResultKind.Scanned, "Scanned", participant = maya)))
    }

    @Test fun already() = snap("kiosk_result_already") {
        Kiosk(base.copy(card = ScanCard("k", ResultKind.AlreadyScanned, "Already scanned", participant = maya)))
    }

    @Test fun notFound() = snap("kiosk_result_not_found") {
        Kiosk(base.copy(card = ScanCard("k", ResultKind.Rejected, "Not registered")))
    }

    @Test fun checking() = snap("kiosk_result_checking") {
        Kiosk(base.copy(card = ScanCard("k", ResultKind.Checking, "Checking…", participant = maya)))
    }

    @Test fun permission() = snap("kiosk_permission") { Kiosk(base.copy(camera = CameraAccess.NeedsRequest)) }

    @Test fun setup() = snap("kiosk_setup") {
        KioskSetupContent("Campfire Canberra", "Check-in desk", Icons.AutoMirrored.Outlined.Login, confirming = true, entered = 2, error = null, onDigit = {}, onBackspace = {}, onSubmit = {}, onCancel = {})
    }

    @Test fun setupLongPin() = snap("kiosk_setup_long_pin") {
        KioskSetupContent("Campfire Canberra", "Check-in desk", Icons.AutoMirrored.Outlined.Login, confirming = false, entered = 14, error = null, onDigit = {}, onBackspace = {}, onSubmit = {}, onCancel = {})
    }

    @Test fun exitLocked() = snap("kiosk_exit_locked") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KioskExitContent(entered = 0, error = true, wrongTries = 3, lockoutSeconds = 27, onDigit = {}, onBackspace = {}, onSubmit = {}, onCancel = {})
        }
    }

    @Test fun exitWrong() = snap("kiosk_exit_wrong") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            KioskExitContent(entered = 1, error = true, wrongTries = 1, lockoutSeconds = 0, onDigit = {}, onBackspace = {}, onSubmit = {}, onCancel = {})
        }
    }
}
