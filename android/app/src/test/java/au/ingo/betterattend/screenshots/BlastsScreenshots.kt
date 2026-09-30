package au.ingo.betterattend.screenshots

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.ui.blasts.BlastComposer
import au.ingo.betterattend.ui.blasts.BlastsContent
import au.ingo.betterattend.ui.blasts.BlastsUiState
import au.ingo.betterattend.ui.blasts.rememberBlastsController
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BlastsScreenshots : ScreenshotTest() {
    private val loaded = BlastsUiState(event = SampleData.event, blasts = OrganizerSamples.blasts, recipientEstimate = 116)

    @Composable
    private fun Content(state: BlastsUiState) = BlastsContent(
        state = state, controller = rememberBlastsController(), onBack = {}, onRefresh = {}, onSend = {},
        onDismissSendError = {}, now = OrganizerSamples.now,
    )

    @Test fun list() = snap("blasts_list") { Content(loaded) }

    @Test fun empty() = snap("blasts_empty") { Content(loaded.copy(blasts = emptyList())) }

    @Test fun loading() = snap("blasts_loading") { Content(BlastsUiState(event = SampleData.event)) }

    @Test fun error() = snap("blasts_error") { Content(BlastsUiState(event = SampleData.event, error = "You're offline. Check your connection.")) }

    @Test fun composer() = snap("blasts_composer") {
        BlastComposer(
            text = "Buses to the hotel leave from the main entrance at 9pm sharp.\n\nBring everything with you!",
            onTextChange = {}, recipientEstimate = 116, sending = false, error = null, onCancel = {}, onReview = {},
            modifier = Modifier.padding(top = 24.dp),
        )
    }

    @Test fun composerError() = snap("blasts_composer_error") {
        BlastComposer(
            text = "", onTextChange = {}, recipientEstimate = null, sending = false,
            error = "No participants with linked Slack accounts", onCancel = {}, onReview = {},
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
