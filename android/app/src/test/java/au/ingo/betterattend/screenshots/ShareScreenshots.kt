package au.ingo.betterattend.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.ui.dashboard.DashboardLogic
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.share.ShareCard
import au.ingo.betterattend.ui.share.ShareCardColour
import au.ingo.betterattend.ui.share.ShareCardLayout
import au.ingo.betterattend.ui.share.ShareCardOptions
import au.ingo.betterattend.ui.share.ShareCardStyle
import au.ingo.betterattend.ui.share.ShareStats
import au.ingo.betterattend.ui.share.ShareStatsEditor
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import au.ingo.betterattend.data.store.ThemeMode
import au.ingo.betterattend.ui.dashboard.DashboardContent
import au.ingo.betterattend.ui.dashboard.DashboardState
import au.ingo.betterattend.ui.theme.AttendTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ShareScreenshots : ScreenshotTest() {
    private val now = OrganizerSamples.now
    private val event = SampleData.event
    private val stats = EventStats.from(OrganizerSamples.participants, now)
    private val available = ShareStats.available(
        event, stats, DashboardLogic.contextProgress(SampleData.contexts, stats, now),
        DashboardLogic.arrivals(OrganizerSamples.travel, now), null, now,
    )

    private fun pick(vararg ids: String) = ids.map { id -> available.single { it.id == id } }

    /** The sheet's contents, on the sheet's own surface colour. */
    @Composable
    private fun Editor(selected: List<String>, options: ShareCardOptions = ShareCardOptions()) {
        var picked by remember { mutableStateOf(selected) }
        var opts by remember { mutableStateOf(options) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ShareStatsEditor(
                event = event, available = available, selected = picked, onSelectedChange = { picked = it },
                options = opts, onOptionsChange = { opts = it }, now = now, onShare = {}, onSave = {},
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    @Composable
    private fun Card(options: ShareCardOptions, vararg ids: String, dark: Boolean? = null) {
        Box(Modifier.padding(16.dp)) {
            val appDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            ShareCard(event, pick(*ids), options, options.dark ?: dark ?: appDark, now, Modifier.fillMaxWidth())
        }
    }

    @Test fun editorFromRegistered() = snap("share_editor") { Editor(listOf(ShareStats.REGISTERED)) }

    @Test @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun editorTwoNumbersFull() = snap("share_editor_full") { Editor(listOf(ShareStats.REGISTERED, ShareStats.CONFIRMED)) }

    @Test fun cardSingle() = snap("share_card_single") { Card(ShareCardOptions(), ShareStats.REGISTERED) }

    @Test fun cardPair() = snap("share_card_pair") { Card(ShareCardOptions(style = ShareCardStyle.Outline), ShareStats.REGISTERED, ShareStats.CONFIRMED) }

    @Test fun cardHero() = snap("share_card_checkins") {
        Card(ShareCardOptions(style = ShareCardStyle.Bold), ShareStats.CHECKED_IN, ShareStats.NOT_HERE, ShareStats.LAST_HOUR)
    }

    @Test fun cardPlayfulGrid() = snap("share_card_playful_grid") {
        Card(
            ShareCardOptions(style = ShareCardStyle.Playful, layout = ShareCardLayout.Grid, colour = ShareCardColour.Blue),
            ShareStats.CHECKED_IN, ShareStats.REGISTERED, ShareStats.CONFIRMED, ShareStats.TO_COLLECT,
        )
    }

    @Test fun cardList() = snap("share_card_list") {
        Card(
            ShareCardOptions(layout = ShareCardLayout.List, colour = ShareCardColour.Green, style = ShareCardStyle.Tonal),
            ShareStats.CHECKED_IN, ShareStats.context(SampleData.contexts.last().id), ShareStats.PICKED_UP,
        )
    }

    @Test fun cardMinimal() = snap("share_card_minimal") {
        Card(
            ShareCardOptions(colour = ShareCardColour.Purple, style = ShareCardStyle.Outline, showIcons = false, showDetails = false, showTotals = false),
            ShareStats.CHECKED_IN, ShareStats.REGISTERED, ShareStats.CONFIRMED, ShareStats.NOT_COMPLETE, ShareStats.WITHDRAWN, ShareStats.TO_COLLECT,
        )
    }

    /** The real flow: long-press "Registered" on Home and the share sheet slides up over it. */
    @Test fun longPressOpensSheet() {
        var mode by mutableStateOf(ThemeMode.Light)
        compose.setContent {
            AttendTheme(themeMode = mode) {
                DashboardContent(
                    state = DashboardState(
                        user = SampleData.user, events = SampleData.events, event = event,
                        roster = OrganizerSamples.roster, contexts = SampleData.contexts, travel = OrganizerSamples.travel,
                        lastUpdated = now.minusSeconds(20),
                    ),
                    onRefresh = {}, onPickEvent = {}, onAccount = {}, onSwitchTab = {},
                    onOpenParticipant = {}, onAnnounce = {}, onKiosk = {}, now = now,
                )
            }
        }
        compose.onNodeWithContentDescription("Registered: 132. Not withdrawn").performTouchInput { longClick() }
        for (m in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            mode = m
            compose.waitForIdle()
            captureScreenRoboImage("build/outputs/roborazzi/share_sheet_${m.name.lowercase()}.png")
        }
    }

    /** Share image renders the preview to a PNG and hands it to the system share sheet through the FileProvider. */
    @Test fun shareButtonSendsPng() {
        compose.setContent {
            AttendTheme(themeMode = ThemeMode.Light) {
                var opts by remember { mutableStateOf(ShareCardOptions()) }
                au.ingo.betterattend.ui.share.ShareStatsSheet(
                    event = event, available = available, startId = ShareStats.CHECKED_IN,
                    options = opts, onOptionsChange = { opts = it }, onDismiss = {}, now = now,
                )
            }
        }
        // Robolectric only draws on capture; the app needs the preview drawn before it can export it.
        captureScreenRoboImage("build/outputs/roborazzi/share_sheet_before_send.png")
        compose.onNodeWithText("Share image").performClick()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // The PNG is written off the main thread, so wait for the share sheet to be launched.
        compose.waitUntil(10_000) { shadowOf(app).peekNextStartedActivity() != null }
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("image/png", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        val png = java.io.File(app.cacheDir, "shared/better-attend-stats.png")
        val bitmap = BitmapFactory.decodeFile(png.absolutePath)
        assertTrue("card image is ${bitmap.width}x${bitmap.height}", bitmap.width > 900 && bitmap.height > 500)
    }
}
