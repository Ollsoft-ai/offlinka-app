package cz.offlinka.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import cz.offlinka.ui.model.AppLanguage
import cz.offlinka.ui.model.ThemeMode
import cz.offlinka.ui.screens.TextZoom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsValues(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textZoom: Int = 100,
    val saveHistory: Boolean = true,
    /** "Vyzkoušejte hned" was closed (or used) once: never shown again. */
    val tryNowDismissed: Boolean = false,
)

/**
 * Small, synchronous preferences (read on start before the first frame). The UI language is stored by
 * AppCompat's per-app language API (system setting on Android 13+, AppLocalesMetadataHolderService before).
 */
class Settings(private val prefs: SharedPreferences, private val locales: LocaleStore = AppCompatLocaleStore) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    private val _values = MutableStateFlow(read())
    val values: StateFlow<SettingsValues> = _values.asStateFlow()

    private fun read() = SettingsValues(
        theme = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        textZoom = prefs.getInt(KEY_ZOOM, 100).coerceIn(TextZoom.MIN, TextZoom.MAX),
        saveHistory = prefs.getBoolean(KEY_HISTORY, true),
        tryNowDismissed = prefs.getBoolean(KEY_TRY_NOW, false),
    )

    fun dismissTryNow() {
        prefs.edit { putBoolean(KEY_TRY_NOW, true) }
        _values.value = _values.value.copy(tryNowDismissed = true)
    }

    /** True exactly once per install: Uložené gets the two emergency guides pre-filled. */
    fun takeFirstRunSeed(): Boolean {
        if (prefs.getBoolean(KEY_SEEDED, false)) return false
        prefs.edit { putBoolean(KEY_SEEDED, true) }
        return true
    }

    fun setTheme(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _values.value = _values.value.copy(theme = mode)
        applyTheme(mode)
    }

    fun setTextZoom(zoom: Int) {
        val z = zoom.coerceIn(TextZoom.MIN, TextZoom.MAX)
        prefs.edit { putInt(KEY_ZOOM, z) }
        _values.value = _values.value.copy(textZoom = z)
    }

    fun setSaveHistory(on: Boolean) {
        prefs.edit { putBoolean(KEY_HISTORY, on) }
        _values.value = _values.value.copy(saveHistory = on)
    }

    val language: AppLanguage get() = locales.get()

    fun setLanguage(language: AppLanguage) = locales.set(language)

    /** Applies the stored theme to AppCompat; call once in Application.onCreate. */
    fun applyTheme(mode: ThemeMode = _values.value.theme) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_ZOOM = "text_zoom"
        const val KEY_HISTORY = "save_history"
        const val KEY_TRY_NOW = "try_now_dismissed"
        const val KEY_SEEDED = "saved_seeded"
    }
}

interface LocaleStore {
    fun get(): AppLanguage
    fun set(language: AppLanguage)
}

object AppCompatLocaleStore : LocaleStore {
    override fun get(): AppLanguage {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-')
        return AppLanguage.entries.firstOrNull { it.tag == tag && it != AppLanguage.SYSTEM } ?: AppLanguage.SYSTEM
    }

    override fun set(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language.tag),
        )
    }
}
