package au.ingo.betterattend.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** Top-level tabs. Which ones show depends on the user's roles and the selected event. */
enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Scan("Scan", Icons.Outlined.QrCodeScanner, Icons.Filled.QrCodeScanner),
    People("People", Icons.Outlined.Groups, Icons.Filled.Groups),
    Travel("Travel", Icons.Outlined.FlightLand, Icons.Filled.FlightLand),
    Tickets("Tickets", Icons.Outlined.ConfirmationNumber, Icons.Filled.ConfirmationNumber),
}

@Serializable data object HomeRoute
@Serializable data object ScanRoute
@Serializable data object PeopleRoute
@Serializable data object TravelRoute
@Serializable data object TicketsRoute
@Serializable data class ParticipantRoute(val eventId: String, val participantEventId: String)
@Serializable data class TicketRoute(val ticketId: String)
@Serializable data object SettingsRoute
@Serializable data class BlastsRoute(val eventId: String)
@Serializable data class KioskRoute(val eventId: String, val scanContextId: String?)

/** Navigation actions available to every screen. */
interface AppNavigator {
    fun switchTab(tab: Tab)
    fun openParticipant(eventId: String, participantEventId: String)
    fun openTicket(ticketId: String)
    fun openSettings()
    fun openBlasts(eventId: String)
    fun openKiosk(eventId: String, scanContextId: String?)
    fun openEventPicker()
    fun back()
}

object NoopNavigator : AppNavigator {
    override fun switchTab(tab: Tab) {}
    override fun openParticipant(eventId: String, participantEventId: String) {}
    override fun openTicket(ticketId: String) {}
    override fun openSettings() {}
    override fun openBlasts(eventId: String) {}
    override fun openKiosk(eventId: String, scanContextId: String?) {}
    override fun openEventPicker() {}
    override fun back() {}
}
