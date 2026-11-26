package com.fabian.player.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.fabian.player.data.preferences.AppLanguage
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import java.util.Locale

object AppLocaleManager {
    private const val PREFERENCES_NAME = "app_locale_preferences"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"
    private const val PENDING_ENTRY_ANIMATION_WINDOW_NS = 3_000_000_000L

    /** The application context whose resources [keepApplicationLocale] already watches. */
    private var keptApplicationContext = WeakReference<Context>(null)

    @Volatile
    private var pendingEntryAnimationAtNs: Long = 0L

    fun markPendingEntryAnimation() {
        pendingEntryAnimationAtNs = System.nanoTime()
    }

    fun hasPendingEntryAnimation(): Boolean {
        val markedAt = pendingEntryAnimationAtNs
        if (markedAt == 0L) return false
        val elapsed = System.nanoTime() - markedAt
        if (elapsed in 0L..PENDING_ENTRY_ANIMATION_WINDOW_NS) return true
        pendingEntryAnimationAtNs = 0L
        return false
    }

    fun clearPendingEntryAnimation() {
        pendingEntryAnimationAtNs = 0L
    }

    fun currentLanguageTag(context: Context): String =
        AppLanguage.normalize(
            context
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE_TAG, AppLanguage.SYSTEM.tag)
                .orEmpty()
        )

    /**
     * Applies [languageTag] to preferences, application resources, and system per-app locales.
     * Returns `true` when Android 13+'s system `LocaleManager` received a new tag and may trigger
     * a configuration-driven Activity restart on its own.
     */
    fun applyLanguage(context: Context, languageTag: String): Boolean {
        val previousTag = currentLanguageTag(context)
        val normalized = AppLanguage.normalize(languageTag)
        if (normalized != previousTag) {
            markPendingEntryAnimation()
        }
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, normalized)
            .apply()

        // Recreated activities pick the language up in wrapContext, but the application context
        // was wrapped once at process start. Strings built from it (ViewModel progress text,
        // toasts, notifications) would otherwise stay in the old language until a restart.
        val applicationContext = context.applicationContext
        applyToApplicationResources(applicationContext, normalized)
        keepApplicationLocale(applicationContext)

        val locales = if (normalized.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(normalized)
        }

        val currentAppLocales = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val nextAppLocales = locales.toLanguageTags()
        if (currentAppLocales != nextAppLocales) {
            AppCompatDelegate.setApplicationLocales(locales)
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        }
        return false
    }

    /**
     * Recreates [activity] once after the language picker sheet has unmounted, avoiding a
     * second recreation when Android 13+ already restarts the activity via [LocaleManager].
     */
    suspend fun restartActivityAfterLocaleChange(
        activity: Activity?,
        localeDelegatedToSystem: Boolean
    ) {
        if (activity == null || activity.isDestroyed || activity.isFinishing) return
        if (!localeDelegatedToSystem || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            activity.recreate()
            return
        }
        delay(120L)
        if (!activity.isDestroyed && !activity.isFinishing) {
            activity.recreate()
        }
    }

    fun wrapContext(base: Context): Context {
        val languageTag = currentLanguageTag(base)
        if (languageTag.isBlank()) return base

        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)

        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
            setLocales(LocaleList(locale))
        }

        return base.createConfigurationContext(configuration)
    }

    private fun applyToApplicationResources(applicationContext: Context, languageTag: String) {
        val primaryLocale: Locale
        val locales: LocaleList
        if (languageTag.isBlank()) {
            locales = systemLocales(applicationContext)
            primaryLocale = locales[0] ?: return
        } else {
            primaryLocale = Locale.forLanguageTag(languageTag)
            locales = LocaleList(primaryLocale)
        }
        Locale.setDefault(primaryLocale)

        val resources = applicationContext.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(primaryLocale)
        }
        // The application context cannot be re-wrapped, so its resources are updated in place.
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun systemLocales(context: Context): LocaleList =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The device languages, ignoring a per-app locale the framework may still hold.
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }

    /**
     * Any later configuration change (rotation, dark theme, font size) re-applies the process
     * configuration to the application resources, which brings the start-up locale back, so the
     * chosen one is applied again afterwards. Registers once per application context.
     */
    @Synchronized
    private fun keepApplicationLocale(applicationContext: Context) {
        if (keptApplicationContext.get() === applicationContext) return
        keptApplicationContext = WeakReference(applicationContext)
        applicationContext.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                applyToApplicationResources(applicationContext, currentLanguageTag(applicationContext))
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }
}
