package au.ingo.betterattend.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.ingo.betterattend.ui.components.MaterialShapesClover
import au.ingo.betterattend.ui.components.MaterialShapesCookie
import au.ingo.betterattend.ui.components.MaterialShapesSoftBurst
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.theme.HackClub

@Composable
fun LoginScreen(loading: Boolean, error: String?, onSignIn: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    // Sign-in failed (or was cancelled on the web): a short buzz alongside the error card.
    androidx.compose.runtime.LaunchedEffect(error) { if (error != null) haptics.reject() }
    Box(Modifier.fillMaxSize().background(cs.surface)) {
        // Decorative expressive shapes.
        Box(Modifier.size(280.dp).offset(x = (-90).dp, y = (-60).dp).rotate(12f).clip(MaterialShapesCookie).background(cs.primaryContainer))
        Box(Modifier.size(180.dp).align(Alignment.TopEnd).offset(x = 50.dp, y = 120.dp).rotate(-8f).clip(MaterialShapesClover).background(cs.tertiaryContainer))
        Box(Modifier.size(120.dp).align(Alignment.CenterStart).offset(x = (-30).dp, y = 40.dp).clip(MaterialShapesSoftBurst).background(cs.secondaryContainer))

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = HackClub.Red, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Badge, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
            }
            Spacer(Modifier.height(24.dp))
            Text("FOR HACK CLUB ATTEND", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            Text(
                "BetterAttend",
                style = MaterialTheme.typography.displayLargeEmphasized,
                color = cs.onSurface,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 32.sp, maxFontSize = MaterialTheme.typography.displayLargeEmphasized.fontSize),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Check people in, keep track of who's here, and carry your event pass — all in one place.",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Feature(Icons.Outlined.QrCode2, "Scan tickets and NFC badges, even offline")
            Feature(Icons.Outlined.Groups, "Live check-in counts on your home screen")
            Spacer(Modifier.height(28.dp))
            AnimatedVisibility(visible = error != null) {
                Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = cs.onErrorContainer)
                        Spacer(Modifier.size(12.dp))
                        Text(error.orEmpty(), color = cs.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Button(
                onClick = { haptics.click(); onSignIn() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).widthIn(max = 480.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.MediumContentPadding,
            ) {
                if (loading) LoadingIndicator(Modifier.size(28.dp), color = cs.onPrimary)
                else Text("Sign in with Hack Club", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "For attendees and event staff. You'll sign in on auth.hackclub.com.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
