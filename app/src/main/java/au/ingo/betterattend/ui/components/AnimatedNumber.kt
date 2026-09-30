package au.ingo.betterattend.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** One digit slot: the digit shown plus the whole value, so a slot can tell which way the number moved. */
private data class DigitSlot(val digit: Char, val value: Int)

/**
 * A number whose digits roll (slide vertically) when it changes, like an odometer: only the digits that
 * actually change move, up when the number grows and down when it shrinks. Reads as plain text to
 * accessibility services. [suffix] (e.g. "%") is drawn after the digits without animating.
 */
@Composable
fun AnimatedNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    suffix: String = "",
) {
    val text = value.toString()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    // Tabular figures so digits don't shuffle sideways as they roll.
    val digitStyle = style.merge(TextStyle(fontFeatureSettings = "tnum"))
    Row(
        modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())
            .clearAndSetSemantics { this.text = AnnotatedString(text + suffix) },
    ) {
        text.forEachIndexed { i, ch ->
            // Key by position from the right so units stay units when the number gains a digit.
            key(text.length - i) {
                AnimatedContent(
                    targetState = DigitSlot(ch, value),
                    contentKey = { it.digit },
                    transitionSpec = {
                        val up = targetState.value >= initialState.value
                        (slideInVertically(spatial) { if (up) it else -it } + fadeIn(effects)) togetherWith
                            (slideOutVertically(spatial) { if (up) -it else it } + fadeOut(effects)) using
                            SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { slot -> Text(slot.digit.toString(), style = digitStyle, color = color, fontWeight = fontWeight) }
            }
        }
        if (suffix.isNotEmpty()) Text(suffix, style = digitStyle, color = color, fontWeight = fontWeight)
    }
}
