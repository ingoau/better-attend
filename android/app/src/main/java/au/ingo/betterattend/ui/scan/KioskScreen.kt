package au.ingo.betterattend.ui.scan

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.scan.NfcStatus
import au.ingo.betterattend.scan.RejectReason
import au.ingo.betterattend.scan.ScanFeedback
import au.ingo.betterattend.scan.rememberNfcReader
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.ui.theme.status
import kotlinx.coroutines.delay
import java.security.MessageDigest

/** How long a final kiosk result stays up before it hides itself (privacy). */
private const val KIOSK_RESULT_MS = 3_000L
private const val MAX_PIN_TRIES = 3
private const val LOCKOUT_MS = 30_000L

/** State for the attendee-facing kiosk UI. */
data class KioskUiState(
    val eventName: String,
    val contextName: String?,
    val contextIcon: ImageVector? = null,
    val card: ScanCard? = null,
    val camera: CameraAccess = CameraAccess.Granted,
    val frontCamera: Boolean = true,
    val nfcReady: Boolean = false,
    val ready: Boolean = true,
)

private fun hashPin(pin: String): String =
    MessageDigest.getInstance("SHA-256").digest("attend-kiosk:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

@Composable
fun KioskScreen(eventId: String, scanContextId: String?, nav: AppNavigator) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val vm: ScanViewModel = viewModel(key = "kiosk:$eventId:$scanContextId", factory = ScanViewModel.Factory(container, eventId, scanContextId))
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val event by vm.event.collectAsStateWithLifecycle()
    val eventsLoading by vm.eventsLoading.collectAsStateWithLifecycle()
    val contexts by vm.contexts.collectAsStateWithLifecycle()
    val contextsError by vm.contextsError.collectAsStateWithLifecycle()
    val selectedContextId by vm.selectedContextId.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()

    // PIN (hashed) and lockout survive rotation; the PIN itself is never stored.
    var pinHash by rememberSaveable { mutableStateOf<String?>(null) }
    var showExit by rememberSaveable { mutableStateOf(false) }
    var frontCamera by rememberSaveable { mutableStateOf(true) }
    val permission = rememberCameraPermission()
    val running = pinHash != null

    val ctx = contexts?.firstOrNull { it.id == selectedContextId }
    val haptics = rememberHaptics()

    if (!running) {
        BackHandler { nav.back() }
        var first by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf("") }
        var confirming by rememberSaveable { mutableStateOf(false) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        KioskSetupContent(
            eventName = event?.name ?: "",
            contextName = ctx?.name,
            contextIcon = ctx?.icon(),
            confirming = confirming,
            entered = if (confirming) confirm.length else first.length,
            error = error,
            onDigit = { d ->
                error = null
                haptics.tick()
                if (confirming) confirm += d else first += d
            },
            onBackspace = {
                haptics.tick()
                if (confirming) confirm = confirm.dropLast(1) else first = first.dropLast(1)
            },
            onSubmit = {
                if (!confirming) { haptics.tick(); confirming = true }
                else if (confirm == first) { haptics.confirm(); pinHash = hashPin(first) }
                else { haptics.reject(); error = "PINs don't match. Try again."; first = ""; confirm = ""; confirming = false }
            },
            onCancel = nav::back,
        )
        return
    }

    // ---- running ----
    val feedback = remember { ScanFeedback(context) }
    DisposableEffect(feedback) { onDispose { feedback.release() } }
    val currentSettings by rememberUpdatedState(settings)
    LaunchedEffect(vm) { vm.feedback.collect { feedback.play(it, currentSettings.sounds, currentSettings.haptics) } }

    val view = LocalView.current
    DisposableEffect(activity) {
        view.keepScreenOn = true
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        runCatching { activity?.startLockTask() }
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
            runCatching {
                val am = activity?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) activity?.stopLockTask()
            }
        }
    }
    BackHandler { showExit = true }

    // Privacy: final results disappear on their own. The gate isn't released, so a ticket left in
    // front of the camera isn't scanned again until it's been taken away.
    val current = card
    LaunchedEffect(current?.key, current?.kind) {
        if (current != null && current.kind != ResultKind.Checking && current.kind != ResultKind.Confirming) {
            delay(KIOSK_RESULT_MS)
            vm.hide(current.key)
        }
    }

    val nfc = rememberNfcReader(enabled = event != null && !showExit, onResult = vm::onNfc)

    if (event == null) {
        if (eventsLoading) LoadingState(message = "Starting kiosk…")
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = nav::back) { Text("This event isn't available. Go back") }
        }
        return
    }

    KioskContent(
        state = KioskUiState(
            eventName = event!!.name,
            contextName = ctx?.name,
            contextIcon = ctx?.icon(),
            card = card,
            camera = permission.access,
            frontCamera = frontCamera,
            nfcReady = nfc == NfcStatus.Ready,
            ready = contexts != null || contextsError != null,
        ),
        onFlipCamera = { haptics.click(); frontCamera = !frontCamera },
        onExit = { showExit = true },
        onRequestCamera = permission.request,
        onOpenAppSettings = permission.openSettings,
        camera = {
            CameraScanner(
                onCodes = { if (!showExit) vm.onCameraCodes(it) },
                lensFacing = if (frontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )

    if (showExit) {
        KioskExitDialog(
            check = { hashPin(it) == pinHash },
            onUnlocked = { showExit = false; nav.back() },
            onDismiss = { showExit = false },
        )
    }
}

/** First name only: kiosks are public, so we show as little as possible. */
private fun Participant.firstName(): String =
    (displayName?.takeIf { it.isNotBlank() } ?: fullName ?: "").trim().substringBefore(' ').ifEmpty { "there" }

private data class KioskMessage(val title: String, val body: String)

private fun ScanCard.kioskMessage(): KioskMessage {
    val first = participant?.firstName()
    return when (kind) {
        ResultKind.Checking, ResultKind.Confirming -> KioskMessage(if (first != null) "Hi $first!" else "One moment…", "Checking your ticket. Hold steady.")
        ResultKind.Scanned, ResultKind.SavedOffline -> KioskMessage(if (first != null) "Welcome, $first!" else "Welcome!", "You're all set. Enjoy the event!")
        ResultKind.AlreadyScanned -> KioskMessage(if (first != null) "You're already in, $first" else "Already scanned", "No need to scan again. Have fun!")
        ResultKind.Rejected -> if (title == "Still loading") KioskMessage("Just a moment", "The kiosk is still getting ready. Try again in a few seconds.")
        else if (title == "Not an Attend code" || title == "Not an Attend badge")
            KioskMessage("That's not an Attend ticket", "Scan the QR code on your Attend ticket, or ask a staff member.")
        // Withdrawn, missing consent, wrong event…: never say why on a public screen.
        else if (RejectReason.entries.any { it.title == title && it != RejectReason.NotRegistered })
            KioskMessage("Please see a staff member", "They'll help you get checked in.")
        else KioskMessage("We couldn't find your ticket", "Please see a staff member and they'll sort it out.")
        ResultKind.Undone -> KioskMessage("Ready", "Scan your ticket")
    }
}

/** Attendee-facing self check-in: camera, one huge prompt, big friendly results. */
@Composable
fun KioskContent(
    state: KioskUiState,
    onFlipCamera: () -> Unit,
    onExit: () -> Unit,
    camera: @Composable () -> Unit,
    onRequestCamera: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(CameraBackdrop)) {
        if (state.camera == CameraAccess.Granted) {
            camera()
            val card = state.card
            val accent = if (card == null || card.kind == ResultKind.Checking || card.kind == ResultKind.Confirming) Color.White else card.kind.colors().strong
            val frameColor by animateColorAsState(accent, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "frameColor")
            ScanFrame(accent = frameColor, maxSize = 360.dp, verticalBias = 0.4f)
        } else {
            CameraPermissionPanel(state.camera, onRequestCamera, onOpenAppSettings, alternatives = "Staff: allow the camera, then restart kiosk mode.")
        }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            // Header: faint exit (staff only) · event + context · camera flip
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onExit, modifier = Modifier.alpha(0.55f)) {
                    Icon(Icons.Outlined.Lock, "Exit kiosk mode (staff PIN required)", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), shape = CircleShape) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(state.contextIcon ?: Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(listOfNotNull(state.eventName, state.contextName).joinToString(" · "), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onFlipCamera, enabled = state.camera == CameraAccess.Granted) {
                    Icon(Icons.Outlined.FlipCameraAndroid, if (state.frontCamera) "Switch to rear camera" else "Switch to front camera", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            val motion = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = state.card,
                contentKey = { it?.key to it?.kind },
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f)) togetherWith
                        fadeOut(motion.fastEffectsSpec()) using SizeTransform(clip = false)
                },
                label = "kiosk",
                modifier = Modifier.fillMaxWidth(),
            ) { card ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (card == null) KioskPrompt(state) else KioskResult(card)
                }
            }
        }
    }
}

@Composable
private fun KioskPrompt(state: KioskUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        shape = RoundedCornerShape(36.dp),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state.ready) "Scan your ticket" else "Getting ready…",
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Hold the QR code on your ticket up to the camera" + if (state.nfcReady) ", or tap your badge on the back." else ".",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun KioskResult(card: ScanCard) {
    val c = card.kind.colors()
    val msg = card.kioskMessage()
    Surface(
        color = c.container,
        contentColor = c.onContainer,
        shape = RoundedCornerShape(40.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 32.dp).clearAndSetSemantics {
                liveRegion = if (card.kind == ResultKind.Checking || card.kind == ResultKind.Confirming) LiveRegionMode.Polite else LiveRegionMode.Assertive
                contentDescription = "${msg.title}. ${msg.body}"
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutcomeBadge(card.kind, size = 112.dp)
            Spacer(Modifier.height(20.dp))
            Text(msg.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(msg.body, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- PIN

/** One dot per digit entered (PINs can be any length) and a big numeric keypad, shared by setup and exit. */
@Composable
fun PinPad(entered: Int, error: Boolean, enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 300.dp).heightIn(min = 18.dp).semantics { contentDescription = "$entered ${if (entered == 1) "digit" else "digits"} entered" },
        ) {
            val pop = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
            repeat(entered) {
                val dot by animateColorAsState(
                    if (error) MaterialTheme.status.danger else MaterialTheme.colorScheme.primary,
                    MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "pinDot",
                )
                val scale = remember { Animatable(0.6f) }
                LaunchedEffect(Unit) { scale.animateTo(1f, pop) }
                Box(Modifier.size(18.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clip(CircleShape).background(dot))
            }
        }
        Spacer(Modifier.height(28.dp))
        val rows = listOf("123", "456", "789", "<0>")
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { key ->
                    when (key) {
                        '<' -> Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                            IconButton(onClick = onBackspace, enabled = enabled && entered > 0, modifier = Modifier.size(64.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.Backspace, "Delete digit")
                            }
                        }
                        '>' -> Surface(
                            onClick = onSubmit,
                            enabled = enabled && entered > 0,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(76.dp).alpha(if (enabled && entered > 0) 1f else 0.38f),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, "Done") }
                        }
                        else -> Surface(
                            onClick = { onDigit(key) },
                            enabled = enabled,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(76.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(key.toString(), style = MaterialTheme.typography.headlineMedium) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KioskSetupContent(
    eventName: String,
    contextName: String?,
    contextIcon: ImageVector?,
    confirming: Boolean,
    entered: Int,
    error: String?,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel kiosk mode") }
            }
            Spacer(Modifier.weight(0.4f))
            Box(Modifier.size(88.dp).clip(au.ingo.betterattend.ui.components.MaterialShapesCookie).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(16.dp))
            Text("Kiosk mode", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Attendees scan their own tickets. The screen is pinned, and staff need this PIN to get out.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(contextIcon ?: Icons.Outlined.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(listOfNotNull(eventName.ifBlank { null }, contextName ?: "No checkpoint").joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                error ?: if (confirming) "Enter the PIN again to confirm" else "Choose an exit PIN",
                style = MaterialTheme.typography.titleMedium,
                color = if (error != null) MaterialTheme.status.danger else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(20.dp))
            PinPad(entered, error != null, enabled = true, onDigit = onDigit, onBackspace = onBackspace, onSubmit = onSubmit)
            Spacer(Modifier.weight(0.6f))
        }
    }
}

/** Staff PIN prompt to leave kiosk mode, with a 30 s lockout after 3 wrong tries. */
@Composable
fun KioskExitDialog(check: (String) -> Boolean, onUnlocked: () -> Unit, onDismiss: () -> Unit, onWrong: () -> Unit = {}) {
    // Taken outside the Dialog: its window has its own LocalHapticFeedback that ignores the Haptics setting.
    val haptics = rememberHaptics()
    var pin by remember { mutableStateOf("") }
    var wrong by rememberSaveable { mutableIntStateOf(0) }
    var lockedUntil by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(lockedUntil) {
        while (System.currentTimeMillis() < lockedUntil) { now = System.currentTimeMillis(); delay(250) }
        now = System.currentTimeMillis()
        if (lockedUntil != 0L && now >= lockedUntil) { wrong = 0; lockedUntil = 0L }
    }
    val secondsLeft = ((lockedUntil - now + 999) / 1000).coerceAtLeast(0)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KioskExitContent(
            entered = pin.length,
            error = error,
            wrongTries = wrong,
            lockoutSeconds = if (lockedUntil > now) secondsLeft.toInt() else 0,
            onDigit = { d -> error = false; haptics.tick(); pin += d },
            onBackspace = { haptics.tick(); pin = pin.dropLast(1) },
            onSubmit = {
                if (check(pin)) { haptics.confirm(); onUnlocked() }
                else {
                    haptics.reject(); onWrong(); error = true; wrong++; pin = ""
                    if (wrong >= MAX_PIN_TRIES) lockedUntil = System.currentTimeMillis() + LOCKOUT_MS
                }
            },
            onCancel = onDismiss,
        )
    }
}

@Composable
fun KioskExitContent(entered: Int, error: Boolean, wrongTries: Int, lockoutSeconds: Int, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(16.dp).widthIn(max = 420.dp)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Exit kiosk mode", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            val locked = lockoutSeconds > 0
            Text(
                when {
                    locked -> "Too many wrong tries. Try again in $lockoutSeconds s."
                    error -> "Wrong PIN. ${MAX_PIN_TRIES - wrongTries} ${if (MAX_PIN_TRIES - wrongTries == 1) "try" else "tries"} left."
                    else -> "Staff: enter the PIN."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (locked || error) MaterialTheme.status.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(20.dp))
            PinPad(entered, error, enabled = !locked, onDigit = onDigit, onBackspace = onBackspace, onSubmit = onSubmit)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text("Keep kiosk running") }
        }
    }
}
