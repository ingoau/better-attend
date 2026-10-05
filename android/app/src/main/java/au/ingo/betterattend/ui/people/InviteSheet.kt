package au.ingo.betterattend.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.ScheduleSend
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.InviteResult
import au.ingo.betterattend.util.Validation

/** The "Invite someone" sheet: what's typed, and how sending went. */
data class InviteState(
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val sending: Boolean = false,
    /** Inline problem: our validation, or the server's 409 / 422 message. */
    val error: String? = null,
    val result: InviteResult? = null,
)

object InviteLogic {
    /** An error to show, or null when the invite can be sent. */
    fun validate(email: String): String? = when {
        email.isBlank() -> "Enter their email address"
        !Validation.looksLikeEmail(email) -> "Enter a valid email address"
        else -> null
    }

    fun resultTitle(result: InviteResult): String = if (result.held) "Invitation saved" else "Invitation sent"

    fun resultBody(result: InviteResult, email: String): String =
        if (result.held) "It'll be emailed to $email when the event releases invitations. They're on the roster as invited."
        else "$email is on the roster as invited, and has been emailed a link to finish registering."
}

@Composable
fun InviteSheet(
    state: InviteState,
    onChange: (InviteState) -> Unit,
    onSend: () -> Unit,
    onView: (participantEventId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = { if (!state.sending) onDismiss() }, sheetState = sheet) {
        InviteSheetContent(state, onChange, onSend, onView, onDismiss)
    }
}

@Composable
fun InviteSheetContent(
    state: InviteState,
    onChange: (InviteState) -> Unit,
    onSend: () -> Unit,
    onView: (participantEventId: String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
    ) {
        val result = state.result
        if (result == null) {
            Text("Invite someone", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "They'll be added to the roster as invited and emailed a link to finish registering.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            state.error?.let {
                ErrorBanner(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(12.dp))
            }
            val enabled = !state.sending
            OutlinedTextField(
                value = state.email,
                onValueChange = { onChange(state.copy(email = it, error = null)) },
                label = { Text("Email") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
                OutlinedTextField(
                    value = state.firstName, onValueChange = { onChange(state.copy(firstName = it)) },
                    label = { Text("First name") }, singleLine = true, enabled = enabled, keyboardOptions = words,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.lastName, onValueChange = { onChange(state.copy(lastName = it)) },
                    label = { Text("Last name") }, singleLine = true, enabled = enabled,
                    keyboardOptions = words.copy(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Optional. They can correct their name when they register.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone, enabled = enabled) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, enabled = enabled && state.email.isNotBlank(), shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                    if (state.sending) {
                        LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Sending…")
                    } else {
                        Text("Send invite")
                    }
                }
            }
        } else {
            val email = state.email.trim().lowercase()
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(88.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (result.held) Icons.Outlined.ScheduleSend else Icons.Outlined.MarkEmailRead, null,
                        Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(InviteLogic.resultTitle(result), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    InviteLogic.resultBody(result, email),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Done") }
                    val id = result.participantEventId
                    if (id != null) {
                        Button(onClick = { onView(id) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("View") }
                    }
                }
            }
        }
    }
}
