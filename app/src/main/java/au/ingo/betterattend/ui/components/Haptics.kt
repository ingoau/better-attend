package au.ingo.betterattend.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Wraps the platform haptics so the in-app "Haptics" setting silences everything, including Material components. */
private class SettingsAwareHapticFeedback(
    private val base: HapticFeedback,
    private val enabled: () -> Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) base.performHapticFeedback(hapticFeedbackType)
    }
}

@Composable
fun ProvideAppHaptics(enabled: Boolean, content: @Composable () -> Unit) {
    val base = LocalHapticFeedback.current
    val on by rememberUpdatedState(enabled)
    val wrapped = remember(base) { SettingsAwareHapticFeedback(base) { on } }
    CompositionLocalProvider(LocalHapticFeedback provides wrapped, content = content)
}

/**
 * Semantic haptics for the app. Use the lightest one that fits:
 * - [tick]: selection changes (tabs, chips, segmented buttons, pickers)
 * - [click]: pressing a button that does something (open, send, call)
 * - [toggle]: switches and on/off toggles
 * - [confirm] / [reject]: an action succeeded / failed
 * - [threshold]: a gesture crossed its commit point (pull-to-refresh, swipe-to-dismiss)
 * - [longPress]: a long-press action fired (copy, context menu)
 */
@Immutable
class Haptics(private val feedback: HapticFeedback) {
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun frequentTick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    fun click() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
    fun threshold() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    fun gestureEnd() = feedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
