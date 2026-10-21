package io.github.pportilla.upmmail

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.pportilla.upmmail.data.DefaultAppContainer
import io.github.pportilla.upmmail.data.repository.AppContainer
import io.github.pportilla.upmmail.data.settings.AppLanguage
import io.github.pportilla.upmmail.data.settings.PrefsSettingsRepository
import java.util.Locale

/**
 * The application: creates the [container] and applies the language chosen in Settings
 * ([changeLanguage]), which [AppSettings.language][io.github.pportilla.upmmail.model.AppSettings.language]
 * mirrors on every Android version.
 *
 * Android 13+ keeps a per-app language itself (it also appears in the system settings) and
 * applies it to the whole process, default locale included. Older versions have no per-app
 * language, so the app applies it on its own: to the application's resources, which every text
 * made outside the screens comes from (notifications, quotes in replies), to the activity
 * ([wrapInLanguage]) and to the default locale, which date formatting follows.
 */
class UpmMailApp : Application() {
    lateinit var container: AppContainer
        private set

    /** The language the app applies itself (Android 12 and older); null follows the system. */
    @Volatile
    private var ownLanguage: OwnLanguage? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            applyOwnLanguage(PrefsSettingsRepository.storedLanguage(base))
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        mirrorSystemLanguage()
        container.syncController.schedulePeriodicSync()
    }

    override fun getResources(): Resources = ownLanguage?.resources ?: super.getResources()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android sets the default locale from the configuration again: keep the app's own, in
        // the region of the system's languages, which may have changed too.
        ownLanguage?.let { applyOwnLanguage(it.tag) }
        if (!::container.isInitialized) return
        // A new system language, or (Android 13+) a per-app language set in the system settings,
        // which need not change the app's first locale.
        (container as? DefaultAppContainer)?.onLocaleChanged()
        mirrorSystemLanguage()
    }

    /**
     * [base] with its resources in the language the app applies itself (Android 12 and older),
     * for activities; [base] itself when the system's language applies.
     */
    fun wrapInLanguage(base: Context): Context {
        val locale = ownLanguage?.locale ?: return base
        return base.createConfigurationContext(localeConfiguration(locale))
    }

    /**
     * Switches the app to [language] ([AppLanguage.SYSTEM] follows the system) and shows
     * [activity] in it. Call on the main thread.
     */
    fun changeLanguage(activity: Activity, language: String) {
        val tag = AppLanguage.normalize(language)
        container.settingsRepository.update { it.copy(language = tag) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android keeps it, updates the configuration and recreates the activities.
            setSystemLanguage(tag)
        } else if (tag != (ownLanguage?.tag ?: AppLanguage.SYSTEM)) {
            applyOwnLanguage(tag)
            (container as? DefaultAppContainer)?.onLocaleChanged()
            activity.recreate()
        }
    }

    /** Android 12 and older: the application's resources and the default locale in [language]. */
    private fun applyOwnLanguage(language: String) {
        val systemLocales = Resources.getSystem().configuration.locales
        val locale = AppLanguage.localeFor(language, systemLocales.toList())
        if (locale == null) {
            if (ownLanguage == null) return
            ownLanguage = null
            // As Android does: the system language the app's texts are in first.
            val locales = resources.configuration.locales
            if (!locales.isEmpty) LocaleList.setDefault(locales)
            return
        }
        if (locale != ownLanguage?.locale) {
            val resources = baseContext.createConfigurationContext(localeConfiguration(locale)).resources
            ownLanguage = OwnLanguage(AppLanguage.normalize(language), locale, resources)
        }
        LocaleList.setDefault(LocaleList(locale))
    }

    /**
     * Android 13+: copies the per-app language into the settings, as it can also be changed in
     * the system settings, without always changing the configuration (so the activity calls
     * this whenever it is shown). Call on the main thread.
     */
    fun mirrorSystemLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val language = try {
            systemLanguage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the app language", e)
            return
        }
        if (!PrefsSettingsRepository.isLanguageInSystem(this)) {
            PrefsSettingsRepository.markLanguageInSystem(this)
            // A language chosen before the device was updated from Android 12 or older, when the
            // app applied it itself: hand it to the system instead of losing it.
            val stored = container.settingsRepository.settings.value.language
            if (language == AppLanguage.SYSTEM && stored != AppLanguage.SYSTEM) {
                setSystemLanguage(stored)
                return
            }
        }
        container.settingsRepository.update { it.copy(language = language) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun systemLanguage(): String {
        val locales = getSystemService(LocaleManager::class.java)?.applicationLocales ?: return AppLanguage.SYSTEM
        return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.normalize(locales[0]?.toLanguageTag())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setSystemLanguage(language: String) {
        val locale = AppLanguage.localeFor(language, Resources.getSystem().configuration.locales.toList())
        try {
            getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not set the app language", e)
        }
    }

    /** A language the app applies itself: its tag, locale and resources. */
    private class OwnLanguage(val tag: String, val locale: Locale, val resources: Resources)

    private companion object {
        const val TAG = "UpmMailApp"

        /** A configuration change of the locale (and layout direction) only. */
        fun localeConfiguration(locale: Locale): Configuration = Configuration().apply { setLocale(locale) }

        fun LocaleList.toList(): List<Locale> = (0 until size()).map { get(it) }
    }
}
