package au.ingo.betterattend.screenshots

import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.preview.StaffSamples
import au.ingo.betterattend.ui.settings.SettingsActions
import au.ingo.betterattend.ui.settings.SettingsContent
import au.ingo.betterattend.ui.settings.SettingsUiState
import au.ingo.betterattend.ui.staff.AddStaffSheetContent
import au.ingo.betterattend.ui.staff.AddStaffState
import au.ingo.betterattend.ui.staff.MemberSheetContent
import au.ingo.betterattend.ui.staff.MemberSheetState
import au.ingo.betterattend.ui.staff.StaffContent
import au.ingo.betterattend.ui.staff.StaffUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class StaffScreenshots : ScreenshotTest() {
    private val state = StaffUiState(
        eventsLoaded = true, event = SampleData.event, me = SampleData.user,
        staff = StaffSamples.staff, roles = StaffSamples.roles,
    )
    private val tom = StaffSamples.staff.first { it.user.name == "Tom Nguyen" }
    private val orpheus = StaffSamples.staff.first { it.user.email == SampleData.user.email }

    @Test fun staffList() = snap("staff_list") { StaffContent(state) }

    @Test @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun staffListFull() = snap("staff_list_full") { StaffContent(state) }

    /** An ops staffer who opened a stale link: a calm empty state, never a raw 403. */
    @Test fun noAccess() = snap("staff_no_access") {
        StaffContent(state.copy(event = SampleData.event.copy(role = "ops"), staff = null))
    }

    @Test fun loadError() = snap("staff_error") {
        StaffContent(state.copy(staff = null, error = "You're offline. Check your connection."))
    }

    @Test fun addSheet() = snap("staff_add_sheet") {
        AddStaffSheetContent(AddStaffState(email = "new.volunteer@example.com", role = "ops"), StaffSamples.roles, {}, {}, {})
    }

    @Test fun addSheetError() = snap("staff_add_sheet_error") {
        AddStaffSheetContent(
            AddStaffState(email = "heidi@hackclub.com", role = "limited", error = "User is already on this event's staff"),
            StaffSamples.roles, {}, {}, {},
        )
    }

    @Test fun memberSheet() = snap("staff_member_sheet") {
        MemberSheetContent(tom, MemberSheetState(tom.id, "limited"), StaffSamples.roles, self = false, {}, {}, {}, {})
    }

    @Test fun memberSheetSelf() = snap("staff_member_sheet_self") {
        MemberSheetContent(orpheus, MemberSheetState(orpheus.id, "event_admin", error = "Couldn't reach Attend. Check your connection."),
            StaffSamples.roles, self = true, {}, {}, {}, {})
    }

    private val settings = SettingsUiState(user = SampleData.user, event = SampleData.event, settings = AppSettings(), versionName = "1.0.0")

    /** Event admins get Settings → Event staff… */
    @Test fun settingsWithStaff() = snap("settings_event_staff") { SettingsContent(settings, SettingsActions()) }

    /** …everyone else doesn't see it at all. */
    @Test fun settingsWithoutStaff() = snap("settings_no_event_staff") {
        SettingsContent(settings.copy(event = SampleData.event.copy(role = "safeguarding_lead")), SettingsActions())
    }
}
