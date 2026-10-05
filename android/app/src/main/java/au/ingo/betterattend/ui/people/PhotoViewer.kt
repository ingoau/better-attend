package au.ingo.betterattend.ui.people

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

/**
 * [name]'s photo on black, edge to edge. Pinch or double-tap to zoom, drag to pan; tap, back or ✕ to close.
 */
@Composable
fun PhotoViewer(url: String, name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PhotoViewerContent(name, onDismiss) { modifier ->
            AsyncImage(model = url, contentDescription = "Photo of $name", contentScale = ContentScale.Fit, modifier = modifier)
        }
    }
}

/** The viewer itself; [image] draws the photo into the modifier it's given (zoom, pan and taps live there). */
@Composable
fun PhotoViewerContent(name: String, onDismiss: () -> Unit, image: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Keeps the zoomed photo covering the screen: it can move at most the amount it overhangs.
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1) / 2
        val maxY = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    val shownScale by animateFloatAsState(scale, MaterialTheme.motionScheme.fastSpatialSpec(), label = "photoZoom")
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = clamp(offset + pan, scale)
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }) {
        image(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        },
                    )
                }
                .transformable(transform)
                .graphicsLayer {
                    scaleX = shownScale
                    scaleY = shownScale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        FilledTonalIconButton(
            onClick = onDismiss,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            shapes = IconButtonDefaults.shapes(),
        ) { Icon(Icons.Outlined.Close, "Close") }
        Text(
            name,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
    }
}
