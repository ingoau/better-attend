package au.ingo.betterattend.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val sounds: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val selectedEventId: String? = null,
    /** eventId -> scan context id, stored as "event:context" pairs joined by ',' */
    val selectedContexts: Map<String, String> = emptyMap(),
    val participantView: Boolean = false,
    /** Notify when people sign up for the selected event. Off until the organizer turns it on. */
    val signupNotifications: Boolean = false,
    /** When they last turned it on (ISO-8601): rosters synced before then don't count as a baseline. */
    val signupNotificationsSince: String? = null,
    /** Notify when people withdraw from the selected event. Off until the organizer turns it on. */
    val withdrawalNotifications: Boolean = false,
    val withdrawalNotificationsSince: String? = null,
    /** Remind before each arrival that's awaiting pickup at the selected event. Off until turned on. */
    val arrivalNotifications: Boolean = false,
) {
    /** Any alert that needs the roster or travel kept fresh in the background. */
    val anyEventAlerts: Boolean get() = signupNotifications || withdrawalNotifications || arrivalNotifications
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val sounds = booleanPreferencesKey("sounds")
        val haptics = booleanPreferencesKey("haptics")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val selectedEvent = stringPreferencesKey("selected_event")
        val contexts = stringPreferencesKey("selected_contexts")
        val participantView = booleanPreferencesKey("participant_view")
        val signupNotifications = booleanPreferencesKey("signup_notifications")
        val signupNotificationsSince = stringPreferencesKey("signup_notifications_since")
        val withdrawalNotifications = booleanPreferencesKey("withdrawal_notifications")
        val withdrawalNotificationsSince = stringPreferencesKey("withdrawal_notifications_since")
        val arrivalNotifications = booleanPreferencesKey("arrival_notifications")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColor = p[Keys.dynamic] ?: false,
            sounds = p[Keys.sounds] ?: true,
            haptics = p[Keys.haptics] ?: true,
            keepScreenOn = p[Keys.keepScreenOn] ?: true,
            selectedEventId = p[Keys.selectedEvent],
            selectedContexts = p[Keys.contexts].orEmpty().split(',').mapNotNull {
                val parts = it.split(':'); if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap(),
            participantView = p[Keys.participantView] ?: false,
            signupNotifications = p[Keys.signupNotifications] ?: false,
            signupNotificationsSince = p[Keys.signupNotificationsSince],
            withdrawalNotifications = p[Keys.withdrawalNotifications] ?: false,
            withdrawalNotificationsSince = p[Keys.withdrawalNotificationsSince],
            arrivalNotifications = p[Keys.arrivalNotifications] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamic] = on }
    suspend fun setSounds(on: Boolean) = context.dataStore.edit { it[Keys.sounds] = on }
    suspend fun setHaptics(on: Boolean) = context.dataStore.edit { it[Keys.haptics] = on }
    suspend fun setKeepScreenOn(on: Boolean) = context.dataStore.edit { it[Keys.keepScreenOn] = on }
    suspend fun setParticipantView(on: Boolean) = context.dataStore.edit { it[Keys.participantView] = on }
    suspend fun setSignupNotifications(on: Boolean) = context.dataStore.edit {
        it[Keys.signupNotifications] = on
        if (on) it[Keys.signupNotificationsSince] = Time.nowIso() else it.remove(Keys.signupNotificationsSince)
    }
    suspend fun setWithdrawalNotifications(on: Boolean) = context.dataStore.edit {
        it[Keys.withdrawalNotifications] = on
        if (on) it[Keys.withdrawalNotificationsSince] = Time.nowIso() else it.remove(Keys.withdrawalNotificationsSince)
    }
    suspend fun setArrivalNotifications(on: Boolean) = context.dataStore.edit { it[Keys.arrivalNotifications] = on }
    suspend fun setSelectedEvent(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.selectedEvent) else it[Keys.selectedEvent] = id
    }
    suspend fun setSelectedContext(eventId: String, contextId: String) = context.dataStore.edit { p ->
        val map = p[Keys.contexts].orEmpty().split(',').mapNotNull {
            val parts = it.split(':'); if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap().toMutableMap()
        map[eventId] = contextId
        p[Keys.contexts] = map.entries.joinToString(",") { "${it.key}:${it.value}" }
    }
    suspend fun clearAccountData() = context.dataStore.edit {
        it.remove(Keys.selectedEvent); it.remove(Keys.contexts); it.remove(Keys.participantView)
        // The next account opts in for itself.
        it.remove(Keys.signupNotifications); it.remove(Keys.signupNotificationsSince)
        it.remove(Keys.withdrawalNotifications); it.remove(Keys.withdrawalNotificationsSince)
        it.remove(Keys.arrivalNotifications)
    }
}
