package au.ingo.betterattend.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.ScanContext

@Composable
fun PeopleFilterSheet(
    options: FilterOptions,
    sort: SortOrder,
    contexts: List<ScanContext>,
    statuses: List<String>,
    dietTypes: List<String>,
    canViewSensitive: Boolean,
    resultCount: Int,
    onOptions: (FilterOptions) -> Unit,
    onSort: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        FilterSheetContent(options, sort, contexts, statuses, dietTypes, canViewSensitive, resultCount, onOptions, onSort, onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheetContent(
    options: FilterOptions,
    sort: SortOrder,
    contexts: List<ScanContext>,
    statuses: List<String>,
    dietTypes: List<String>,
    canViewSensitive: Boolean,
    resultCount: Int,
    onOptions: (FilterOptions) -> Unit,
    onSort: (SortOrder) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Filter & sort", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onOptions(FilterOptions()) }, enabled = options.activeCount > 0, shapes = ButtonDefaults.shapes()) { Text("Clear all") }
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            SheetLabel("Sort by")
            ConnectedChoice(SortOrder.entries.map { it to it.label }, sort, onSort)

            if (contexts.isNotEmpty()) {
                SheetLabel("Scanned at")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    contexts.forEach { c ->
                        val on = options.scannedAtContextId == c.id
                        FilterChip(
                            selected = on,
                            onClick = { onOptions(options.copy(scannedAtContextId = if (on) null else c.id)) },
                            label = { Text(c.name) },
                            leadingIcon = when {
                                on -> { { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) } }
                                c.isAirport || c.isTravelPickup -> { { Icon(Icons.Outlined.FlightTakeoff, "Airport", Modifier.size(18.dp)) } }
                                else -> null
                            },
                        )
                    }
                }
                if (options.scannedAtContextId != null) {
                    Spacer(Modifier.height(8.dp))
                    ConnectedChoice(
                        listOf(false to "Scanned there", true to "Not scanned there"),
                        options.notScannedAtContext,
                        { onOptions(options.copy(notScannedAtContext = it)) },
                    )
                }
            }

            if (statuses.isNotEmpty()) {
                SheetLabel("Registration status")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statuses.forEach { s ->
                        val on = s in options.statuses
                        FilterChip(
                            selected = on,
                            onClick = { onOptions(options.copy(statuses = if (on) options.statuses - s else options.statuses + s)) },
                            label = { Text(statusLabel(s)) },
                            leadingIcon = if (on) { { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }

            SheetLabel("Inbound travel")
            ConnectedChoice(triOptions("Has travel", "None"), options.inboundTravel, { onOptions(options.copy(inboundTravel = it)) })
            SheetLabel("Outbound travel")
            ConnectedChoice(triOptions("Has travel", "None"), options.outboundTravel, { onOptions(options.copy(outboundTravel = it)) })
            SheetLabel("Travel mode")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("plane" to "Plane", "train" to "Train", "car" to "Car", "bus" to "Bus", "other" to "Other").forEach { (m, label) ->
                    val on = m in options.travelModes
                    FilterChip(
                        selected = on,
                        onClick = { onOptions(options.copy(travelModes = if (on) options.travelModes - m else options.travelModes + m)) },
                        label = { Text(label) },
                        leadingIcon = { Icon(if (on) Icons.Outlined.Check else travelModeIcon(m), null, Modifier.size(18.dp)) },
                    )
                }
            }

            SheetLabel("Waiver")
            ConnectedChoice(triOptions("Signed", "Not signed"), options.waiverSigned, { onOptions(options.copy(waiverSigned = it)) })
            SheetLabel("NFC badge")
            ConnectedChoice(triOptions("Assigned", "No badge"), options.nfcAssigned, { onOptions(options.copy(nfcAssigned = it)) })

            if (canViewSensitive && dietTypes.isNotEmpty()) {
                SheetLabel("Diet")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dietTypes.forEach { d ->
                        val on = d in options.dietTypes
                        FilterChip(
                            selected = on,
                            onClick = { onOptions(options.copy(dietTypes = if (on) options.dietTypes - d else options.dietTypes + d)) },
                            label = { Text(humanize(d) ?: d) },
                            leadingIcon = if (on) { { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) } } else null,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = onDone,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).navigationBarsPadding().height(56.dp),
        ) {
            Text(if (resultCount == 1) "Show 1 person" else "Show $resultCount people", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun triOptions(yes: String, no: String) = listOf(TriState.Any to "Any", TriState.Yes to yes, TriState.No to no)

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
