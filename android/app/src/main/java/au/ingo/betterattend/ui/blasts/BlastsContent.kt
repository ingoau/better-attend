package au.ingo.betterattend.ui.blasts

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.SlackBlast
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.HapticPullToRefreshBox
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import java.time.Instant

/** UI-only state for the Announcements screen: composer sheet, draft, confirm dialog and snackbar. */
@Stable
class BlastsController(
    val snackbar: SnackbarHostState,
    draft: MutableState<String>,
    composerOpen: MutableState<Boolean>,
    confirmOpen: MutableState<Boolean>,
) {
    var draft by draft
    var composerOpen by composerOpen
    var confirmOpen by confirmOpen

    suspend fun onSent(blast: SlackBlast) {
        confirmOpen = false
        composerOpen = false
        draft = ""
        snackbar.showSnackbar(
            if (blast.recipientCount > 0) "Sending to ${blast.recipientCount} people…" else "Announcement queued",
        )
    }
}

@Composable
fun rememberBlastsController(initialDraft: String = "", composerOpen: Boolean = false): BlastsController {
    val draft = rememberSaveable { mutableStateOf(initialDraft) }
    val open = rememberSaveable { mutableStateOf(composerOpen) }
    val confirm = rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    return remember { BlastsController(snackbar, draft, open, confirm) }
}

const val RECIPIENT_RULE = "Sends a Slack DM to every confirmed participant with a linked Slack account."

@Composable
fun BlastsContent(
    state: BlastsUiState,
    controller: BlastsController,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onDismissSendError: () -> Unit,
    now: Instant = Instant.now(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val haptics = rememberHaptics()
    val openComposer = { haptics.click(); controller.composerOpen = true }

    // A blast we watched go out finishing: one confirm when it's delivered, a reject if it failed.
    val watching = remember { HashSet<String>() }
    LaunchedEffect(state.blasts) {
        val list = state.blasts ?: return@LaunchedEffect
        val finished = list.filter { it.id in watching && !BlastLogic.isActive(it) }
        when {
            finished.any { it.status == "failed" } -> haptics.reject()
            finished.isNotEmpty() -> haptics.confirm()
        }
        watching.clear()
        list.filter(BlastLogic::isActive).mapTo(watching) { it.id }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("Announcements") },
                subtitle = { state.event?.let { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (state.blasts != null) {
                ExtendedFloatingActionButton(
                    text = { Text("New announcement") },
                    icon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = openComposer,
                    expanded = !listState.canScrollBackward,
                )
            }
        },
        snackbarHost = { SnackbarHost(controller.snackbar) },
    ) { padding ->
        val phase = when {
            state.blasts == null && state.error != null -> BlastsPhase.Failed
            state.blasts == null -> BlastsPhase.Loading
            else -> BlastsPhase.Content
        }
        Crossfade(phase, Modifier.fillMaxSize().padding(padding), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "blasts") { shown ->
          Box(Modifier.fillMaxSize()) {
            val blasts = state.blasts
            when {
                shown == BlastsPhase.Failed || (blasts == null && state.error != null) -> EmptyState(
                    Icons.Outlined.CloudOff, "Couldn't load announcements", body = state.error ?: "Something went wrong.",
                    actionLabel = "Try again", onAction = onRefresh,
                )
                shown == BlastsPhase.Loading || blasts == null -> LoadingState(message = "Loading announcements…")
                else -> HapticPullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.error != null) item(key = "offline") { OfflineBanner(state.error, Modifier.animateItem(), onRetry = onRefresh) }
                        if (blasts.isEmpty()) {
                            item(key = "empty") {
                                EmptyState(
                                    Icons.Outlined.Campaign, "No announcements yet",
                                    body = "Reach everyone at once with a Slack DM, perfect for “lunch is ready” or “buses leave at 9”.",
                                    actionLabel = "Write one", onAction = openComposer,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        items(blasts, key = { it.id }) { blast -> BlastCard(blast, now, Modifier.animateItem()) }
                    }
                }
            }
          }
        }
    }

    if (controller.composerOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { if (!state.sending) controller.composerOpen = false },
            sheetState = sheetState,
        ) {
            BlastComposer(
                text = controller.draft,
                onTextChange = { controller.draft = it; if (state.sendError != null) onDismissSendError() },
                recipientEstimate = state.recipientEstimate,
                sending = state.sending,
                error = state.sendError,
                onCancel = { controller.composerOpen = false },
                onReview = { haptics.click(); controller.confirmOpen = true },
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        }
    }

    // A failed send drops back to the composer, where the error is shown next to the text.
    LaunchedEffect(state.sendError) {
        if (state.sendError != null) {
            haptics.reject()
            controller.confirmOpen = false
        }
    }

    if (controller.confirmOpen) {
        ConfirmSendDialog(
            message = controller.draft,
            recipientEstimate = state.recipientEstimate,
            sending = state.sending,
            onConfirm = { haptics.click(); onSend(controller.draft) },
            onDismiss = { if (!state.sending) controller.confirmOpen = false },
        )
    }
}

private enum class BlastsPhase { Failed, Loading, Content }

// ---------------------------------------------------------------- history

@Composable
internal fun BlastCard(blast: SlackBlast, now: Instant, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(blast.id) { mutableStateOf(false) }
    val text = remember(blast.message) { BlastLogic.toPlain(blast.message) }
    val active = BlastLogic.isActive(blast)
    // Polls land every 10 s; ease the bar between them instead of stepping.
    val progress by animateFloatAsState(BlastLogic.fraction(blast), MaterialTheme.motionScheme.slowEffectsSpec(), label = "delivery")
    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(blast.status)
                Spacer(Modifier.weight(1f))
                Text(
                    Time.ago(blast.createdAt, now) ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                Spacer(Modifier.height(12.dp))
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = BlastLogic.progressText(blast) },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    BlastLogic.progressText(blast),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (blast.failedCount > 0 || blast.status == "failed") MaterialTheme.status.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                blast.sentBy?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val s = MaterialTheme.status
    val (label, icon, colors) = when (status) {
        "pending" -> Triple("Queued", Icons.Outlined.Schedule, s.infoContainer to s.onInfoContainer)
        "in_progress" -> Triple("Sending", Icons.Outlined.Sync, s.infoContainer to s.onInfoContainer)
        "completed" -> Triple("Sent", Icons.Outlined.CheckCircle, s.successContainer to s.onSuccessContainer)
        "failed" -> Triple("Failed", Icons.Outlined.ErrorOutline, s.dangerContainer to s.onDangerContainer)
        else -> Triple(status.replaceFirstChar { it.uppercase() }, null as ImageVector?, MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Pill(label, colors.first, colors.second, icon = icon)
}

// ---------------------------------------------------------------- composer

@Composable
fun BlastComposer(
    text: String,
    onTextChange: (String) -> Unit,
    recipientEstimate: Int?,
    sending: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val over = text.length > BlastLogic.MAX_LENGTH
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
        Text("New announcement", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        RecipientsCard(recipientEstimate)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("What does everyone need to know?") },
            minLines = 5,
            maxLines = 12,
            isError = over || error != null,
            enabled = !sending,
            supportingText = {
                Row {
                    Text(
                        error ?: if (over) "Too long for one Slack message" else "Line breaks are kept.",
                        modifier = Modifier.weight(1f),
                    )
                    Text("${text.length} / ${BlastLogic.MAX_LENGTH}", textAlign = TextAlign.End)
                }
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, enabled = !sending, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onReview,
                enabled = text.isNotBlank() && !over && !sending,
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(ButtonDefaults.MediumIconSize))
                Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
                Text("Review & send", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
        }
    }
}

@Composable
private fun RecipientsCard(estimate: Int?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Groups, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (estimate != null) "About $estimate people" else "Confirmed participants",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    RECIPIENT_RULE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ConfirmSendDialog(
    message: String,
    recipientEstimate: Int?,
    sending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Campaign, null) },
        title = { Text(if (recipientEstimate != null) "Send to about $recipientEstimate people?" else "Send this announcement?") },
        text = {
            Column {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        message.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Everyone gets a Slack DM straight away. Announcements can't be edited or unsent.")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !sending, shapes = ButtonDefaults.shapes()) {
                if (sending) {
                    LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Sending…")
                } else Text("Send now")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending, shapes = ButtonDefaults.shapes()) { Text("Keep editing") } },
    )
}
