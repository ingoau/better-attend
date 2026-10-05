package au.ingo.betterattend.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** The rounded search field used above filterable lists (matches People). */
@Composable
fun PillSearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") } }
        } else null,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

/** A row of single-select filter chips with live counts, e.g. "Missing 15 · Accounted 37 · All 52". */
@Composable
fun <T> CountFilterChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    count: (T) -> Int?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(options, key = { label(it) }) { option ->
            val on = option == selected
            val n = count(option)
            FilterChip(
                selected = on,
                onClick = { if (!on) haptics.tick(); onSelect(option) },
                label = {
                    Text(label(option))
                    if (n != null) {
                        Spacer(Modifier.width(6.dp))
                        AnimatedNumber(n, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }
                },
                leadingIcon = if (on) { { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = if (n != null) "${label(option)}, $n" else label(option) },
            )
        }
    }
}

/** Calm state for a screen the user's role can't use (never a raw 403). */
@Composable
fun NoAccessState(modifier: Modifier = Modifier, body: String = "Your role on this event doesn't include the participant list.", onBack: (() -> Unit)? = null) {
    EmptyState(
        Icons.Outlined.Lock, "You don't have access to this",
        modifier = modifier,
        body = body,
        actionLabel = if (onBack != null) "Go back" else null,
        onAction = onBack,
    )
}
