package au.ingo.betterattend.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.ui.blasts.BlastsScreen
import au.ingo.betterattend.ui.components.EventPickerSheet
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.ProvideAppHaptics
import au.ingo.betterattend.ui.dashboard.DashboardScreen
import au.ingo.betterattend.ui.login.LoginScreen
import au.ingo.betterattend.ui.nav.*
import au.ingo.betterattend.ui.firstaid.FirstAidScreen
import au.ingo.betterattend.ui.people.ParticipantDetailScreen
import au.ingo.betterattend.ui.rollcall.RollCallScreen
import au.ingo.betterattend.ui.people.PeopleScreen
import au.ingo.betterattend.ui.scan.KioskScreen
import au.ingo.betterattend.ui.scan.ScanScreen
import au.ingo.betterattend.ui.settings.SettingsScreen
import au.ingo.betterattend.ui.staff.StaffScreen
import au.ingo.betterattend.ui.theme.AttendTheme
import au.ingo.betterattend.ui.tickets.TicketDetailScreen
import au.ingo.betterattend.ui.tickets.TicketsScreen
import au.ingo.betterattend.ui.travel.TravelScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer provided") }

/** Requests from outside Compose (widgets, shortcuts, notifications) to open a tab. */
object ExternalNavRequests {
    val tab = MutableStateFlow<Tab?>(null)
    /** A ticket (participant_event id) to open, e.g. from the "My ticket" widget. */
    val ticket = MutableStateFlow<String?>(null)
}

@Composable
fun AttendRoot(container: AppContainer, onSignIn: () -> Unit, onIssueToken: (deviceName: String) -> Unit = {}) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val auth by container.auth.state.collectAsStateWithLifecycle()
    val s = settings ?: return
    AttendTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
        ProvideAppHaptics(enabled = s.haptics) { androidx.compose.runtime.CompositionLocalProvider(LocalAppContainer provides container) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                AnimatedContent(
                    targetState = auth,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "auth",
                ) { state ->
                    when (state) {
                        AuthState.Loading -> LoadingState(message = "Signing you in…")
                        is AuthState.SignedOut -> LoginScreen(loading = false, error = state.message, onSignIn = onSignIn)
                        is AuthState.SignedIn -> SignedInApp(container, state.user, onIssueToken)
                    }
                }
            }
        }
    } }
}

@Composable
private fun SignedInApp(container: AppContainer, user: User, onIssueToken: (deviceName: String) -> Unit) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val events by container.events.events.collectAsStateWithLifecycle()
    val selected by container.events.selectedEvent.collectAsStateWithLifecycle()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    val isOrganizer = user.isOrganizer || user.globalAdmin || !events.isNullOrEmpty()
    val tabs = buildList {
        if (isOrganizer) {
            add(Tab.Home); add(Tab.Scan)
            if (selected?.canViewParticipants != false) add(Tab.People)
            if (selected?.travelEnabled == true) add(Tab.Travel)
        }
        if (user.isParticipant || !isOrganizer) add(Tab.Tickets)
    }

    LaunchedEffect(user.id) {
        launch { container.auth.refreshUser() }
        if (user.isOrganizer || user.globalAdmin) launch { container.events.refresh() }
        if (user.isParticipant) launch { container.tickets.refresh() }
    }

    // Tab changes requested from anywhere; MainTabs consumes them (after we pop back to it).
    val tabRequests = remember { MutableStateFlow<Tab?>(null) }

    val navigator = remember(nav) {
        object : AppNavigator {
            override fun switchTab(tab: Tab) {
                if (nav.currentDestination?.hasRoute(MainRoute::class) != true) nav.popBackStack(MainRoute, inclusive = false)
                tabRequests.value = tab
            }
            override fun openParticipant(eventId: String, participantEventId: String) = nav.navigate(ParticipantRoute(eventId, participantEventId)) { launchSingleTop = true }
            override fun openTicket(ticketId: String) = nav.navigate(TicketRoute(ticketId)) { launchSingleTop = true }
            override fun openSettings() = nav.navigate(SettingsRoute) { launchSingleTop = true }
            override fun openBlasts(eventId: String) = nav.navigate(BlastsRoute(eventId)) { launchSingleTop = true }
            override fun openKiosk(eventId: String, scanContextId: String?) = nav.navigate(KioskRoute(eventId, scanContextId)) { launchSingleTop = true }
            override fun openStaff(eventId: String) = nav.navigate(StaffRoute(eventId)) { launchSingleTop = true }
            override fun openEventPicker() { pickerOpen = true }
            override fun back() { nav.popBackStack() }
            override fun openRollCall(eventId: String) = nav.navigate(RollCallRoute(eventId)) { launchSingleTop = true }
            override fun openFirstAid(eventId: String) = nav.navigate(FirstAidRoute(eventId)) { launchSingleTop = true }
        }
    }

    val external by ExternalNavRequests.tab.collectAsStateWithLifecycle()
    LaunchedEffect(external, tabs) {
        val t = external ?: return@LaunchedEffect
        if (t in tabs) navigator.switchTab(t)
        ExternalNavRequests.tab.value = null
    }

    val externalTicket by ExternalNavRequests.ticket.collectAsStateWithLifecycle()
    LaunchedEffect(externalTicket) {
        val id = externalTicket ?: return@LaunchedEffect
        if (Tab.Tickets in tabs) navigator.switchTab(Tab.Tickets)
        nav.navigate(TicketRoute(id)) { launchSingleTop = true }
        ExternalNavRequests.ticket.value = null
    }

    NavHost(
        nav,
        startDestination = MainRoute,
        modifier = Modifier.fillMaxSize(),
        enterTransition = AppMotion.enter,
        exitTransition = AppMotion.exit,
        popEnterTransition = AppMotion.popEnter,
        popExitTransition = AppMotion.popExit,
        predictivePopEnterTransition = AppMotion.predictivePopEnter,
        predictivePopExitTransition = AppMotion.predictivePopExit,
    ) {
        composable<MainRoute> {
            UnderlayFrame { MainTabs(tabs, tabRequests) { tab ->
                when (tab) {
                    Tab.Home -> DashboardScreen(navigator)
                    Tab.Scan -> ScanScreen(navigator)
                    Tab.People -> PeopleScreen(navigator)
                    Tab.Travel -> TravelScreen(navigator)
                    Tab.Tickets -> TicketsScreen(navigator, showAccount = tabs.size == 1)
                }
            } }
        }
        composable<ParticipantRoute> { val r = it.toRoute<ParticipantRoute>(); CardFrame { ParticipantDetailScreen(r.eventId, r.participantEventId, navigator) } }
        composable<TicketRoute> { val id = it.toRoute<TicketRoute>().ticketId; CardFrame { TicketDetailScreen(id, navigator) } }
        composable<SettingsRoute> { CardFrame { SettingsScreen(navigator, onIssueToken) } }
        composable<BlastsRoute> { val id = it.toRoute<BlastsRoute>().eventId; CardFrame { BlastsScreen(id, navigator) } }
        composable<StaffRoute> { val id = it.toRoute<StaffRoute>().eventId; CardFrame { StaffScreen(id, navigator) } }
        composable<KioskRoute> { val r = it.toRoute<KioskRoute>(); CardFrame { KioskScreen(r.eventId, r.scanContextId, navigator) } }
        composable<RollCallRoute> { val id = it.toRoute<RollCallRoute>().eventId; CardFrame { RollCallScreen(id, navigator) } }
        composable<FirstAidRoute> { val id = it.toRoute<FirstAidRoute>().eventId; CardFrame { FirstAidScreen(id, navigator) } }
    }

    if (pickerOpen) {
        EventPickerSheet(
            events = events.orEmpty(),
            selectedId = selected?.id,
            onSelect = { e ->
                pickerOpen = false
                scope.launch { container.events.select(e.id) }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}
