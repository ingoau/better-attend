package au.ingo.betterattend.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.ingo.betterattend.ui.components.MaterialShapesCookie
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlin.math.min

/** The camera viewport background: a camera is dark in both themes, so this is deliberately fixed. */
val CameraBackdrop = Color(0xFF0B0B0F)

/**
 * Live camera preview with continuous ML Kit QR decoding. Never pauses for results: every frame's
 * decoded values go to [onCodes] (the caller gates duplicates). The camera is bound to the current
 * lifecycle owner, so it only runs while the screen is started, and is unbound when this leaves
 * composition (e.g. switching tabs).
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScanner(
    onCodes: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    torchOn: Boolean = false,
    onTorchAvailable: (Boolean) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val codes by rememberUpdatedState(onCodes)
    val errors by rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lensFacing, lifecycleOwner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val media = proxy.image
            if (media == null || disposed) { proxy.close(); return@setAnalyzer }
            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener(main) { barcodes ->
                    val values = barcodes.mapNotNull { it.rawValue?.takeIf(String::isNotBlank) }
                    if (values.isNotEmpty() && !disposed) codes(values)
                }
                .addOnCompleteListener { proxy.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get().also { provider = it }
                val preferred = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val selector = if (p.hasCamera(preferred)) preferred
                    else if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                errors("Couldn't start the camera. ${e.message ?: ""}".trim())
            }
        }, main)
        onDispose {
            disposed = true
            camera = null
            runCatching { provider?.unbind(preview, analysis) }
            analysis.clearAnalyzer()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(camera) { onTorchAvailable(camera?.cameraInfo?.hasFlashUnit() == true) }
    LaunchedEffect(camera, torchOn) {
        camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(torchOn)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Stand-in for the camera in previews/screenshot tests (the real preview can't render there). */
@Composable
fun CameraPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(CameraBackdrop))
}

/**
 * Dims everything but a rounded square target, with corner brackets tinted by the last outcome.
 * Placed slightly above centre so the result card doesn't cover it.
 */
@Composable
fun ScanFrame(modifier: Modifier = Modifier, accent: Color = Color.White, maxSize: Dp = 300.dp, verticalBias: Float = 0.38f) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = min(min(maxWidth.value * 0.68f, maxHeight.value * 0.46f), maxSize.value).dp
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val s = side.toPx()
            val left = (size.width - s) / 2
            val top = (size.height * verticalBias - s / 2).coerceAtLeast(16.dp.toPx())
            val radius = 28.dp.toPx()
            drawRect(Color.Black.copy(alpha = 0.45f))
            drawRoundRect(Color.Transparent, Offset(left, top), GSize(s, s), CornerRadius(radius), blendMode = BlendMode.Clear)
            val arm = s * 0.18f
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                val path = Path().apply {
                    moveTo(x, y + dy * arm)
                    lineTo(x, y + dy * radius)
                    quadraticTo(x, y, x + dx * radius, y)
                    lineTo(x + dx * arm, y)
                }
                drawPath(path, accent, style = stroke)
            }
            corner(left, top, 1f, 1f)
            corner(left + s, top, -1f, 1f)
            corner(left, top + s, 1f, -1f)
            corner(left + s, top + s, -1f, -1f)
        }
    }
}

// ---------------------------------------------------------------- permission

fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Camera permission state plus the actions to change it. Re-checked every time the screen resumes. */
class CameraPermissionController(val access: CameraAccess, val request: () -> Unit, val openSettings: () -> Unit)

@Composable
fun rememberCameraPermission(): CameraPermissionController {
    val context = LocalContext.current
    val activity: Activity? = LocalActivity.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var denials by rememberSaveable { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) denials++
    }
    LifecycleResumeEffect(Unit) {
        granted = context.hasCameraPermission()
        onPauseOrDispose { }
    }
    val permanentlyDenied = !granted && denials > 0 &&
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    val access = when {
        granted -> CameraAccess.Granted
        permanentlyDenied -> CameraAccess.PermanentlyDenied
        else -> CameraAccess.NeedsRequest
    }
    return CameraPermissionController(
        access = access,
        request = { launcher.launch(Manifest.permission.CAMERA) },
        openSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
    )
}

/** Friendly explanation shown in the viewport when the camera isn't available. */
@Composable
fun CameraPermissionPanel(
    access: CameraAccess,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    alternatives: String? = "You can still check people in with Find person or an NFC badge.",
) {
    Box(modifier.fillMaxSize().background(CameraBackdrop).padding(20.dp), contentAlignment = Alignment.Center) {
        if (access == CameraAccess.Unknown) { LoadingIndicator(); return@Box }
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 420.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val blocked = access == CameraAccess.PermanentlyDenied
                Box(Modifier.size(80.dp).clip(MaterialShapesCookie).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (blocked) Icons.Outlined.NoPhotography else Icons.Outlined.PhotoCamera, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(16.dp))
                Text(if (blocked) "Camera is turned off for Attend" else "Let Attend use the camera",
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (blocked) "Turn on camera access in Settings › Permissions to scan ticket QR codes."
                    else "The camera is only used to read ticket QR codes while this screen is open. Nothing is recorded.") +
                        (alternatives?.let { "\n\n$it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = if (blocked) onOpenSettings else onRequest, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(48.dp)) {
                    Text(if (blocked) "Open settings" else "Allow camera")
                }
            }
        }
    }
}
