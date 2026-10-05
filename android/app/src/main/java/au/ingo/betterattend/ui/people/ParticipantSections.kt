package au.ingo.betterattend.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContactPhone
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Accessibility
import au.ingo.betterattend.data.model.Consent
import au.ingo.betterattend.data.model.EmergencyContact
import au.ingo.betterattend.data.model.Guardian
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Travel
import au.ingo.betterattend.data.model.TravelLeg
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Callbacks for things that leave the app (dialer, mail, browser). */
data class ContactActions(
    val call: (String) -> Unit = {},
    val sms: (String) -> Unit = {},
    val whatsApp: (String) -> Unit = {},
    val email: (String) -> Unit = {},
    /** Opens a DM with this Slack user ID in the Slack app (the web profile if Slack isn't installed). */
    val slack: (String) -> Unit = {},
    val openUrl: (String) -> Unit = {},
    /** Long-press on a value: copy it. [label] names what was copied ("Phone number", "Email"…). */
    val copy: (label: String, value: String) -> Unit = { _, _ -> },
)

/** Links into Hack Club's Slack, where Attend's Slack IDs live. */
object SlackLinks {
    const val TEAM_ID = "T0266FRGM"

    /** Slack's deep link: opens a DM with the user in the app. */
    fun app(userId: String) = "slack://user?team=$TEAM_ID&id=${URLEncoder.encode(userId.trim(), "UTF-8")}"

    /** Their profile on the web, for when the app isn't installed. */
    fun web(userId: String) = "https://hackclub.slack.com/team/${URLEncoder.encode(userId.trim(), "UTF-8")}"
}

private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
fun formatDate(iso: String?): String? = iso?.let { runCatching { LocalDate.parse(it.take(10)).format(dateFmt) }.getOrNull() ?: it }

// ---------------- Safety alerts ----------------

data class SafetyAlert(val title: String, val detail: String?, val icon: ImageVector, val danger: Boolean)

fun safetyAlerts(p: Participant, canViewSensitive: Boolean): List<SafetyAlert> = buildList {
    if (p.hasAnaphylaxisRisk) {
        val detail = if (canViewSensitive) {
            val lt = p.lifeThreateningAllergies?.takeIf { it.isNotBlank() }
            val all = p.allergies?.takeIf { it.isNotBlank() }
            when {
                lt == null -> all
                all == null || all.contains(lt, ignoreCase = true) -> all ?: lt
                lt.contains(all, ignoreCase = true) -> lt
                else -> "$lt · $all"
            }
        } else null
        add(SafetyAlert("Anaphylaxis risk", detail ?: "Check their allergy plan with first aid.", Icons.Outlined.Warning, danger = true))
    }
    if (p.requiresRefrigeration) {
        add(SafetyAlert("Medication must be refrigerated", if (canViewSensitive) p.medications else null, Icons.Outlined.AcUnit, danger = false))
    }
    if (p.highSupportFlag) {
        add(SafetyAlert("High support needs", if (canViewSensitive) p.safeguardingDetail?.highSupportNotes else null, Icons.Outlined.SupportAgent, danger = false))
    }
    val minor = p.personal?.age?.let { it < 18 } ?: (p.guardians?.isNotEmpty() == true)
    if (!p.canLeaveUnaccompanied && minor) {
        add(SafetyAlert(
            "Can't leave unaccompanied",
            p.safeguardingDetail?.authorizedPickupAdults?.let { "Pickup: $it" },
            Icons.AutoMirrored.Outlined.DirectionsWalk, danger = false,
        ))
    }
    val um = listOfNotNull(
        p.travelInbound?.takeIf { it.isUnaccompaniedMinor }?.let { "arriving" },
        p.travelOutbound?.takeIf { it.isUnaccompaniedMinor }?.let { "departing" },
    )
    if (um.isNotEmpty()) add(SafetyAlert("Unaccompanied minor", "Travelling alone when ${um.joinToString(" and ")}. Meet them at the gate.", Icons.Outlined.ChildCare, danger = false))
}

@Composable
fun SafetyAlertCard(alert: SafetyAlert, modifier: Modifier = Modifier) {
    val s = MaterialTheme.status
    Surface(
        color = if (alert.danger) s.dangerContainer else s.warningContainer,
        contentColor = if (alert.danger) s.onDangerContainer else s.onWarningContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (alert.danger) s.danger else s.warning),
                contentAlignment = Alignment.Center,
            ) {
                Icon(alert.icon, null, tint = if (alert.danger) s.onDanger else s.onWarning, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(alert.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                alert.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

// ---------------- Generic bits ----------------

/**
 * Tappable value row, e.g. a phone number that dials. 56dp tall for comfortable targets.
 * [onLongClick] (typically "copy") works even when there's no tap action.
 */
@Composable
fun LinkRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    actionDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (onClick == null && onLongClick == null) Modifier
                else Modifier.combinedClickable(
                    onClickLabel = actionDescription,
                    onLongClickLabel = if (onLongClick != null) "Copy" else null,
                    onLongClick = onLongClick,
                    onClick = onClick ?: {},
                ),
            ),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, actionDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CheckChip(label: String, value: Boolean?) {
    if (value == null) return
    val s = MaterialTheme.status
    Pill(
        label,
        if (value) s.successContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        if (value) s.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        icon = if (value) Icons.Outlined.Check else Icons.Outlined.Close,
        modifier = Modifier.semantics { contentDescription = "$label: ${if (value) "yes" else "no"}" },
    )
}

@Composable
private fun FlagChip(label: String) {
    Pill(label, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
}

// ---------------- Sections ----------------

@Composable
fun ContactSection(p: Participant, canViewPii: Boolean, actions: ContactActions) {
    val phone = p.phone?.takeIf { canViewPii && it.isNotBlank() }
    val email = p.email?.takeIf { it.isNotBlank() }
    if (phone == null && email == null && p.slackUserId == null) return
    SectionCard("Contact", Icons.Outlined.ContactPhone) {
        phone?.let { LinkRow(Icons.Outlined.Call, "Mobile", it, { actions.call(it) }, "Call", onLongClick = { actions.copy("Phone number", it) }) }
        email?.let {
            LinkRow(Icons.Outlined.Email, "Email", it, { actions.email(it) }, "Email", onLongClick = { actions.copy("Email", it) })
        }
        p.slackUserId?.takeIf { it.isNotBlank() }?.let {
            LinkRow(Icons.Outlined.Tag, "Slack", it, { actions.slack(it) }, "Message on Slack", onLongClick = { actions.copy("Slack ID", it) })
        }
    }
}

@Composable
fun PersonalSection(p: Participant) {
    val personal = p.personal ?: return
    val legal = listOfNotNull(personal.legalFirstName, personal.legalLastName).joinToString(" ").ifBlank { null }
    val dob = personal.dateOfBirth?.let { d -> formatDate(d) + (personal.age?.let { " (age $it)" } ?: "") }
    val address = personal.address?.let { a ->
        listOfNotNull(a.line1, a.line2, listOfNotNull(a.city, a.state, a.postalCode).joinToString(" ").ifBlank { null }, a.country)
            .joinToString("\n").ifBlank { null }
    }
    SectionCard("Personal", Icons.Outlined.Person) {
        SelectionContainer {
            Column {
                Field("Legal name", legal)
                Field("Preferred name", personal.preferredName?.takeIf { it != personal.legalFirstName })
                Field("Date of birth", dob)
                if (dob == null) Field("Age", personal.age?.toString())
                Field("T-shirt size", personal.tshirtSize ?: p.tshirtSize)
                Field("Engagement", humanize(personal.engagementPreference))
                Field("Engagement notes", personal.engagementNotes)
                Field("Address", address)
            }
        }
    }
}

@Composable
fun ScansSection(p: Participant, tz: String?) {
    if (p.scansByContext.isEmpty()) return
    val scans = p.scansByContext.sortedBy { Time.parse(it.firstScannedAt) }
    SectionCard("Scans", Icons.Outlined.History) {
        scans.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                // Timeline rail
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (s.checksIn) MaterialTheme.status.success else MaterialTheme.colorScheme.primary),
                    )
                    if (i < scans.lastIndex) {
                        Box(Modifier.width(2.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.isTravelPickup) {
                            Icon(Icons.Outlined.FlightLand, "Airport", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(s.scanContextName ?: "Scan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                        if (s.checksIn) {
                            Spacer(Modifier.width(8.dp))
                            Pill("Check-in", MaterialTheme.status.successContainer, MaterialTheme.status.onSuccessContainer)
                        }
                    }
                    val first = Time.dayTime(s.firstScannedAt, tz)
                    val last = Time.time(s.lastScannedAt, tz)
                    Text(
                        buildString {
                            append(first ?: "Unknown time")
                            if (s.scanCount > 1) append(" · ${s.scanCount} scans, last $last")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun liveStatusColors(status: String?): Pair<Color, Color> {
    val s = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    return when (status?.lowercase()) {
        "arrived", "landed" -> s.successContainer to s.onSuccessContainer
        "departed", "enroute", "en route", "active" -> s.infoContainer to s.onInfoContainer
        "delayed" -> s.warningContainer to s.onWarningContainer
        "cancelled", "canceled" -> s.dangerContainer to s.onDangerContainer
        "diverted" -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
}

@Composable
fun TravelSection(t: Travel, tz: String?, canViewPii: Boolean) {
    val inbound = t.direction != "outbound"
    val pickedUp = t.legs.lastOrNull()?.travelPickedUpAt
    SectionCard(
        if (inbound) "Arriving" else "Departing",
        if (inbound) Icons.Outlined.FlightLand else Icons.Outlined.FlightTakeoff,
        trailing = {
            if (t.isUnaccompaniedMinor) Pill("UM", MaterialTheme.status.warningContainer, MaterialTheme.status.onWarningContainer, icon = Icons.Outlined.ChildCare)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(travelModeIcon(t.mode), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(travelModeLabel(t.mode), t.carrier, t.flightNumber).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        val from = t.departureCity ?: t.trainDepartureStation ?: t.departureStation ?: t.busDepartureLocation
        val to = t.arrivalCity ?: t.trainArrivalStation ?: t.arrivalStation ?: t.busArrivalLocation
        if (from != null || to != null) {
            Text("${from ?: "?"} → ${to ?: "?"}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        }
        if (inbound && t.mode == "plane") {
            Spacer(Modifier.height(8.dp))
            when {
                pickedUp != null -> Pill("Picked up ${Time.time(pickedUp, tz) ?: ""}".trim(), MaterialTheme.status.successContainer, MaterialTheme.status.onSuccessContainer, icon = Icons.Outlined.Check)
                t.pickupDismissedAt != null -> Pill("Pickup not needed", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Pill("Awaiting pickup", MaterialTheme.status.warningContainer, MaterialTheme.status.onWarningContainer, icon = Icons.Outlined.History)
            }
        }
        t.legs.sortedBy { it.position }.forEach { leg -> LegRow(leg, tz) }
        SelectionContainer {
            Column {
                if (t.legs.isEmpty()) {
                    Field("Departs", Time.dayTime(t.departureTime, tz))
                    Field("Arrives", Time.dayTime(t.arrivalTime, tz))
                }
                Field("Expected arrival", Time.dayTime(t.expectedArrivalTime, tz))
                if (canViewPii) Field("Leaving from", t.originAddress)
                Field("Details", t.otherDetails)
                Field("Notes", t.notes)
                if (t.visaRequired == true || t.visaStatus != null || t.passportNationality != null) {
                    Field("Passport", t.passportNationality)
                    Field("Visa", listOfNotNull(humanize(t.visaStatus), t.visaType, t.visaNumber).joinToString(" · ").ifBlank { null })
                }
            }
        }
    }
}

@Composable
private fun LegRow(leg: TravelLeg, tz: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(leg.flightCode ?: "Flight", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                leg.liveStatus?.let { st ->
                    val (bg, fg) = liveStatusColors(st)
                    Pill(st, bg, fg)
                }
            }
            Text(
                "${leg.departureAirport ?: "?"} → ${leg.arrivalAirport ?: "?"}",
                style = MaterialTheme.typography.headlineSmall,
            )
            val dep = Time.dayTime(leg.liveDepartureTime ?: leg.departureTime, tz)
            val arr = Time.dayTime(leg.liveArrivalTime ?: leg.arrivalTime, tz)
            Text(
                listOfNotNull(dep?.let { "Departs $it" }, arr?.let { "Arrives $it" }).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AccommodationSection(p: Participant) {
    val a = p.accommodation ?: return
    SectionCard("Accommodation", Icons.Outlined.Bed) {
        SelectionContainer {
            Column {
                Field("Room", a.assignedRoom, highlight = true)
                Field("Venue", a.venueName)
                Field("Stay", listOfNotNull(formatDate(a.checkInDate), formatDate(a.checkOutDate)).joinToString(" – ").ifBlank { null })
                if (a.roomingExempt == true) Field("Rooming", "Exempt")
                Field("Gender identity", a.genderIdentityOther ?: humanize(a.genderIdentity))
                Field("Roommate genders", a.preferredRoommateGenders?.mapNotNull { humanize(it) }?.joinToString()?.ifBlank { null })
                Field("Roommate preferences", a.roommatePreferences)
                Field("Keep apart from", a.roommateExclusions, highlight = true)
                Field("Room type", humanize(a.roomTypePreference))
                if (a.quietRoomPreference == true) Field("Quiet room", "Preferred")
                Field("Accessibility", a.accessibilityNeeds)
                Field("Notes", a.notes)
            }
        }
    }
}

@Composable
fun MedicalSection(p: Participant) {
    val m = p.medicalDetail
    val d = p.dietaryDetail
    val any = listOf(p.allergies, p.medicalConditions, p.medications, p.dietType, p.lifeThreateningAllergies).any { !it.isNullOrBlank() } ||
        m != null || d != null || p.requiresRefrigeration || p.crossContaminationRisk
    if (!any) return
    SectionCard("Medical & dietary", Icons.Outlined.LocalHospital, container = MaterialTheme.colorScheme.surfaceContainer) {
        SelectionContainer {
            Column {
                Field("Allergies", p.allergies, highlight = true)
                Field("Allergy severity", humanize(m?.allergySeverity))
                Field("Life-threatening allergies", p.lifeThreateningAllergies, highlight = true)
                Field("Medical conditions", p.medicalConditions)
                Field("Medications", p.medications)
                if (p.requiresRefrigeration) Field("Refrigeration", "Medication must be kept cold")
                Field("Emergency action plan", m?.emergencyActionPlan, highlight = true)
                Field("Medical notes", m?.additionalNotes)
                Field("Diet", humanize(p.dietType))
                if (p.crossContaminationRisk) Field("Cross-contamination", "Risk: prepare separately")
                Field("Intolerances", d?.intolerances)
                Field("Dietary notes", d?.notes)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessibilitySection(a: Accessibility?) {
    a ?: return
    val flags = listOfNotNull(
        "Wheelchair user".takeIf { a.usesWheelchair == true },
        "Step-free access".takeIf { a.stepFreeRequired == true },
        "Captioning".takeIf { a.needsCaptioning == true },
        "Large print".takeIf { a.needsLargePrint == true },
        "Sign language".takeIf { a.needsSignLanguage == true },
        "Light sensitive".takeIf { a.lightSensitivity == true },
        "Noise sensitive".takeIf { a.noiseSensitivity == true },
        "Strobe sensitive".takeIf { a.strobeSensitivity == true },
        "ADHD".takeIf { a.hasAdhd == true },
        "Autism".takeIf { a.hasAutism == true },
        "Dyslexia".takeIf { a.hasDyslexia == true },
        "Prayer space".takeIf { a.prayerSpaceRequired == true },
        "Private space".takeIf { a.requiresPrivateSpace == true },
    )
    val texts = listOf(
        "Mobility" to a.mobilityNeeds, "Sensory" to a.sensoryNeeds, "Communication" to a.communicationNeeds,
        "Neurodivergence" to a.neurodivergentNotes, "Religious practice" to a.religiousPractices,
        "Distance limits" to a.distanceLimitations, "Unavailable times" to a.unavailableTimes, "Other" to a.otherNeeds,
    ).filter { !it.second.isNullOrBlank() }
    if (flags.isEmpty() && texts.isEmpty()) return
    SectionCard("Accessibility", Icons.Outlined.Accessibility) {
        if (flags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                flags.forEach { FlagChip(it) }
            }
        }
        SelectionContainer { Column { texts.forEach { (label, value) -> Field(label, value) } } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SafeguardingSection(p: Participant, canViewSensitive: Boolean) {
    val s = p.safeguardingDetail
    SectionCard("Safeguarding", Icons.Outlined.Shield) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            CheckChip("Waiver signed", p.waiverSigned)
            if (canViewSensitive) CheckChip("Freedom waiver", p.freedomWaiverGranted)
            CheckChip("Can leave alone", p.canLeaveUnaccompanied)
            if (p.highSupportFlag) CheckChip("High support", true)
        }
        SelectionContainer {
            Column {
                Field("High support notes", s?.highSupportNotes)
                Field("Authorised for pickup", s?.authorizedPickupAdults, highlight = true)
                Field("Other instructions", s?.otherInstructions)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuardiansSection(p: Participant, actions: ContactActions) {
    val guardians = p.guardians.orEmpty()
    val fallback = p.emergencyContacts.orEmpty()
    if (guardians.isEmpty() && fallback.isEmpty() && p.parentGuardianName == null) return
    SectionCard(if (guardians.size > 1) "Guardians (${guardians.size})" else if (guardians.isNotEmpty()) "Guardian" else "Emergency contacts", Icons.Outlined.FamilyRestroom) {
        guardians.sortedByDescending { it.isPrimary }.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            GuardianBlock(g, actions)
        }
        if (guardians.isEmpty()) {
            if (p.parentGuardianName != null) {
                Text(p.parentGuardianName, style = MaterialTheme.typography.titleSmall)
                p.parentGuardianPhone?.let { LinkRow(Icons.Outlined.Call, "Phone", it, { actions.call(it) }, "Call", onLongClick = { actions.copy("Phone number", it) }) }
                p.parentGuardianEmail?.let { LinkRow(Icons.Outlined.Email, "Email", it, { actions.email(it) }, "Email", onLongClick = { actions.copy("Email", it) }) }
            }
            fallback.forEach { EmergencyContactRow(it, actions) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuardianBlock(g: Guardian, actions: ContactActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(g.name ?: "Guardian", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
        if (g.isPrimary) {
            Spacer(Modifier.width(8.dp))
            Pill("Primary", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        }
        g.status?.let { st ->
            Spacer(Modifier.width(6.dp))
            val done = st == "completed"
            Pill(
                if (done) "Complete" else humanize(st) ?: st,
                if (done) MaterialTheme.status.successContainer else MaterialTheme.status.warningContainer,
                if (done) MaterialTheme.status.onSuccessContainer else MaterialTheme.status.onWarningContainer,
                icon = if (done) Icons.Outlined.Check else Icons.Outlined.History,
            )
        }
    }
    g.relationship?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    g.phone?.let { LinkRow(Icons.Outlined.Call, "Phone", it, { actions.call(it) }, "Call", onLongClick = { actions.copy("Phone number", it) }) }
    g.email?.let { LinkRow(Icons.Outlined.Email, "Email", it, { actions.email(it) }, "Email", onLongClick = { actions.copy("Email", it) }) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        CheckChip("Media", g.mediaPermission)
        CheckChip("Photos", g.photoPermission)
        CheckChip("Travel", g.travelPermission)
        CheckChip("Emergency medical", g.emergencyMedicalConsent)
        CheckChip("OTC meds", g.otcMedicationConsent)
    }
    g.emergencyContacts.filter { it.name != g.name || it.phone != g.phone }.forEach { EmergencyContactRow(it, actions) }
}

@Composable
private fun EmergencyContactRow(c: EmergencyContact, actions: ContactActions) {
    val label = listOfNotNull("Emergency contact", c.relationship).joinToString(" · ")
    val phone = c.phone
    if (phone != null) {
        LinkRow(Icons.Outlined.HealthAndSafety, "${c.name ?: "Contact"} · $label", phone, { actions.call(phone) }, "Call",
            onLongClick = { actions.copy("Phone number", phone) })
    }
    else Field(label, c.name)
}

@Composable
fun ConsentsSection(consents: List<Consent>?, tz: String?, actions: ContactActions) {
    if (consents.isNullOrEmpty()) return
    SectionCard("Consents", Icons.Outlined.Description) {
        consents.forEach { c -> ConsentRow(c, tz, actions) }
    }
}

@Composable
private fun ConsentRow(c: Consent, tz: String?, actions: ContactActions) {
    val s = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (c.status) {
        "signed" -> s.successContainer to s.onSuccessContainer
        "sent", "viewed" -> s.infoContainer to s.onInfoContainer
        "failed", "voided" -> s.dangerContainer to s.onDangerContainer
        else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    val sub = when {
        c.signedAt != null -> "Signed ${Time.day(c.signedAt, tz)}"
        c.pendingOn != null -> "Waiting on ${humanize(c.pendingOn)?.lowercase()}"
        c.sentAt != null -> "Sent ${Time.day(c.sentAt, tz)}"
        else -> null
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(consentLabel(c.consentType), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Pill(humanize(c.status) ?: "Unknown", bg, fg, icon = if (c.status == "signed") Icons.Outlined.Check else null)
            }
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            c.failureReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
        }
        c.documentUrl?.let { url ->
            IconButton(onClick = { actions.openUrl(url) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open ${consentLabel(c.consentType)} document")
            }
        }
    }
}

fun consentLabel(type: String?): String = when (type) {
    "event_consent" -> "Event consent"
    "medical_release" -> "Medical release"
    "code_of_conduct" -> "Code of conduct"
    "media" -> "Media release"
    "waiver" -> "Waiver"
    "participant_agreement" -> "Participant agreement"
    "freedom_waiver" -> "Freedom waiver"
    "custom_document" -> "Custom document"
    else -> humanize(type) ?: "Document"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupsSection(p: Participant) {
    if (p.groups.isEmpty()) return
    SectionCard("Groups", Icons.Outlined.Groups) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.groups.forEach { g ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // The group's own colour is data from Attend; it's only a small swatch next to a text label.
                        val swatch = parseHex(g.color) ?: MaterialTheme.colorScheme.primary
                        Box(Modifier.size(12.dp).clip(CircleShape).background(swatch))
                        Spacer(Modifier.width(8.dp))
                        Text(g.name, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private fun parseHex(hex: String?): Color? = hex?.removePrefix("#")?.takeIf { it.length == 6 }
    ?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/** Big NFC status line inside the actions area. */
@Composable
fun BadgeStatusLine(p: Participant) {
    if (p.nfcBadgeToken == null && !p.nfcBadgeAssigned) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Badge, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            if (p.nfcBadgeAssigned) "NFC badge assigned" else "No NFC badge yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
