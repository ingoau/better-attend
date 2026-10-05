package au.ingo.betterattend.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Full-screen "Edit details" form over the participant page. Asks before throwing away unsaved changes. */
@Composable
fun EditDetailsDialog(
    session: EditSession,
    name: String,
    canEditPii: Boolean,
    onChange: (EditForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val close = {
        when {
            session.saving -> Unit
            session.changed -> confirmDiscard = true
            else -> onDismiss()
        }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        EditDetailsContent(session, name, canEditPii, onChange, onSave, onClose = close)
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to ${name.ifBlank { "this person" }}'s details haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** The form itself, stateless so it can be screenshot-tested without a dialog window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDetailsContent(
    session: EditSession,
    name: String,
    canEditPii: Boolean,
    onChange: (EditForm) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val f = session.form
    val errors = session.fieldErrors
    val enabled = !session.saving
    var pickingDob by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Edit details")
                        if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose, enabled = enabled) { Icon(Icons.Outlined.Close, "Close") } },
                actions = {
                    if (session.saving) {
                        LoadingIndicator(Modifier.padding(end = 16.dp).size(36.dp))
                    } else {
                        Button(
                            onClick = onSave,
                            enabled = session.changed,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.padding(end = 12.dp),
                        ) { Text("Save") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            session.error?.let { msg ->
                Spacer(Modifier.height(8.dp))
                ErrorBanner(msg)
            }

            FormGroupTitle("Name")
            FormField(f.legalFirstName, { onChange(f.copy(legalFirstName = it)) }, "Legal first name", errors[EditField.LegalFirstName], enabled, words)
            FormField(f.legalLastName, { onChange(f.copy(legalLastName = it)) }, "Legal last name", errors[EditField.LegalLastName], enabled, words)
            FormField(
                f.preferredName, { onChange(f.copy(preferredName = it)) }, "Preferred name", errors[EditField.PreferredName], enabled, words,
                supporting = "Shown across Attend instead of their legal name",
            )
            FormField(
                f.pronouns, { onChange(f.copy(pronouns = it)) }, "Pronouns", errors[EditField.Pronouns], enabled,
                KeyboardOptions(imeAction = ImeAction.Next), supporting = "For example she/her, he/him or they/them",
            )

            FormGroupTitle("Contact")
            FormField(
                f.email, { onChange(f.copy(email = it)) }, "Email", errors[EditField.Email], enabled,
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            if (canEditPii) {
                FormField(
                    f.phone, { onChange(f.copy(phone = it)) }, "Phone", errors[EditField.Phone], enabled,
                    KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    supporting = "Include the country code, e.g. +61 412 345 678",
                )
            }

            FormGroupTitle("Details")
            if (canEditPii) {
                val error = errors[EditField.DateOfBirth]
                Box(Modifier.padding(bottom = 4.dp)) {
                    OutlinedTextField(
                        value = formatDate(f.dateOfBirth.ifBlank { null }).orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        enabled = enabled,
                        label = { Text("Date of birth") },
                        placeholder = { Text("Not set") },
                        trailingIcon = { Icon(Icons.Outlined.CalendarMonth, null) },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // A read-only field swallows taps, so a transparent layer on top opens the picker.
                    Box(Modifier.matchParentSize().clickable(enabled = enabled, onClickLabel = "Choose date of birth") { pickingDob = true })
                }
            }
            Text(
                "T-shirt size",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ParticipantEditLogic.tshirtOptions(session.original.tshirtSize).forEach { size ->
                    val on = f.tshirtSize.trim().equals(size, ignoreCase = true)
                    FilterChip(
                        selected = on,
                        enabled = enabled,
                        // Tapping the chosen size again clears it.
                        onClick = { onChange(f.copy(tshirtSize = if (on) "" else size)) },
                        label = { Text(size) },
                        leadingIcon = if (on) { { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                    )
                }
            }
            errors[EditField.TshirtSize]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (pickingDob) {
        DateOfBirthPicker(
            current = ParticipantEditLogic.parseDate(f.dateOfBirth),
            onPick = { onChange(f.copy(dateOfBirth = it.toString())); pickingDob = false },
            onDismiss = { pickingDob = false },
        )
    }
}

private val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)

@Composable
private fun DateOfBirthPicker(current: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current?.let(::millis),
        // No birthday on file: start around a typical attendee's, not today.
        initialDisplayedMonthMillis = millis(current ?: today.minusYears(16)),
        yearRange = 1900..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= millis(today)
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state, title = { Text("Date of birth", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) })
    }
}

@Composable
private fun FormGroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun FormField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    error: String?,
    enabled: Boolean,
    keyboard: KeyboardOptions,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

/** The server's (or network's) reason something wasn't saved, inline above a form. */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
