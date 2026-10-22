package lian.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lian.app.ui.theme.ThemeMode

/**
 * Non-sensitive settings only (Phase 2.4): switches, counters and timestamps. Never messages, links
 * or app names. Backup is disabled for the whole app.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lian.settings", Context.MODE_PRIVATE)

    private val _hideScreens = MutableStateFlow(sp.getBoolean(KEY_SECURE, !BuildConfig.DEBUG))
    /** FLAG_SECURE on every screen. Default on in release builds (debug builds default off so screenshots work). */
    val hideScreens: StateFlow<Boolean> = _hideScreens.asStateFlow()

    private val _watchEnabled = MutableStateFlow(sp.getBoolean(KEY_WATCH, false))
    val watchEnabled: StateFlow<Boolean> = _watchEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.entries.getOrElse(sp.getInt(KEY_THEME, 0)) { ThemeMode.SYSTEM })
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _wallpaperColors = MutableStateFlow(sp.getBoolean(KEY_WALLPAPER, false))
    val wallpaperColors: StateFlow<Boolean> = _wallpaperColors.asStateFlow()

    private val _onboarded = MutableStateFlow(sp.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    /** Message Shield counters for today: (checked, warnings). Numbers only. */
    private val _shieldCounts = MutableStateFlow(readCounts())
    val shieldCounts: StateFlow<Pair<Int, Int>> = _shieldCounts.asStateFlow()

    var auditDisclosureAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLOSURE, false)
        set(value) = sp.edit { putBoolean(KEY_DISCLOSURE, value) }

    var lastWatchRun: Long
        get() = sp.getLong(KEY_WATCH_RUN, 0)
        set(value) = sp.edit { putLong(KEY_WATCH_RUN, value) }

    fun setHideScreens(value: Boolean) {
        sp.edit { putBoolean(KEY_SECURE, value) }
        _hideScreens.value = value
    }

    fun setWatchEnabled(value: Boolean) {
        sp.edit { putBoolean(KEY_WATCH, value) }
        _watchEnabled.value = value
    }

    fun setThemeMode(value: ThemeMode) {
        sp.edit { putInt(KEY_THEME, value.ordinal) }
        _themeMode.value = value
    }

    fun setWallpaperColors(value: Boolean) {
        sp.edit { putBoolean(KEY_WALLPAPER, value) }
        _wallpaperColors.value = value
    }

    fun setOnboarded() {
        sp.edit { putBoolean(KEY_ONBOARDED, true) }
        _onboarded.value = true
    }

    @Synchronized
    fun countShield(warning: Boolean, today: Long) {
        val (checked, warnings) = if (sp.getLong(KEY_SHIELD_DAY, -1) == today) readCounts() else 0 to 0
        val next = (checked + 1) to (warnings + if (warning) 1 else 0)
        sp.edit { putLong(KEY_SHIELD_DAY, today); putInt(KEY_SHIELD_CHECKED, next.first); putInt(KEY_SHIELD_WARNINGS, next.second) }
        _shieldCounts.value = next
    }

    /** Counters reset when the day changes. */
    fun refreshShieldCounts(today: Long) {
        _shieldCounts.value = if (sp.getLong(KEY_SHIELD_DAY, -1) == today) readCounts() else 0 to 0
    }

    private fun readCounts() = sp.getInt(KEY_SHIELD_CHECKED, 0) to sp.getInt(KEY_SHIELD_WARNINGS, 0)

    fun clear() {
        sp.edit { clear() }
        _hideScreens.value = !BuildConfig.DEBUG
        _watchEnabled.value = false
        _themeMode.value = ThemeMode.SYSTEM
        _wallpaperColors.value = false
        _shieldCounts.value = 0 to 0
        // Onboarding stays done: deleting data shouldn't replay the tour.
        sp.edit { putBoolean(KEY_ONBOARDED, _onboarded.value) }
    }

    private companion object {
        const val KEY_SECURE = "hide_screens"
        const val KEY_WATCH = "watch_enabled"
        const val KEY_DISCLOSURE = "audit_disclosure_accepted"
        const val KEY_WATCH_RUN = "watch_last_run"
        const val KEY_THEME = "theme_mode"
        const val KEY_WALLPAPER = "wallpaper_colors"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_SHIELD_DAY = "shield_day"
        const val KEY_SHIELD_CHECKED = "shield_checked"
        const val KEY_SHIELD_WARNINGS = "shield_warnings"
    }
}
