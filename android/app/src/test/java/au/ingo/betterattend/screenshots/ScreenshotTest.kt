package au.ingo.betterattend.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import au.ingo.betterattend.data.store.ThemeMode
import au.ingo.betterattend.ui.theme.AttendTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.robolectric.annotation.GraphicsMode

/**
 * Base for Roborazzi screenshot tests. Renders on the JVM (no emulator) into
 * app/build/outputs/roborazzi/<name>_{light,dark}.png. Run with:
 *   ./gradlew :app:testDebugUnitTest --tests '*Screenshots*'
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /**
     * Captures [content] in both light and dark themes. [prepare] runs once before capturing (scrolling,
     * opening a dialog); [screen] captures the whole window stack so dialogs are included.
     */
    fun snap(name: String, screen: Boolean = false, prepare: () -> Unit = {}, content: @Composable () -> Unit) {
        var mode by mutableStateOf(ThemeMode.Light)
        compose.setContent {
            AttendTheme(themeMode = mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { Box { content() } }
            }
        }
        compose.waitForIdle()
        prepare()
        for (m in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            mode = m
            compose.waitForIdle()
            val path = "build/outputs/roborazzi/${name}_${m.name.lowercase()}.png"
            if (screen) captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
        }
    }
}
