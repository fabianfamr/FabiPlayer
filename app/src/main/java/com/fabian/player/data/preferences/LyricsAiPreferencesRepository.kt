package com.fabian.player.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores AI lyrics translation settings and the optional user-supplied Gemini API key.
 * The API key is persisted in [EncryptedSharedPreferences] so it never enters plain DataStore
 * backups.
 */
@Singleton
class LyricsAiPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val AUTO_TRANSLATE_LYRICS = booleanPreferencesKey("auto_translate_lyrics_v1")
        val TARGET_LANGUAGE = stringPreferencesKey("lyrics_translation_target_lang_v1")
        val SHOW_TRANSLATION = booleanPreferencesKey("show_lyrics_translation")
        val SHOW_ROMANIZATION = booleanPreferencesKey("show_lyrics_romanization")
    }

    private val encryptedPrefs: SharedPreferences by lazy { createApiKeyPrefs() }
    private val _geminiApiKeyFlow = MutableStateFlow(readStoredApiKey())
    val geminiApiKeyFlow: StateFlow<String> = _geminiApiKeyFlow.asStateFlow()

    val autoTranslateLyricsFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_TRANSLATE_LYRICS] ?: false
    }

    val targetLanguageFlow: Flow<String> = dataStore.data.map { prefs ->
        sanitizeLanguageCode(prefs[Keys.TARGET_LANGUAGE])
    }

    val showLyricsTranslationFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_TRANSLATION] ?: true
    }

    val showLyricsRomanizationFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SHOW_ROMANIZATION] ?: true
    }

    suspend fun getAutoTranslateLyrics(): Boolean = autoTranslateLyricsFlow.first()

    suspend fun setAutoTranslateLyrics(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_TRANSLATE_LYRICS] = enabled
        }
    }

    suspend fun getTargetLanguage(): String = targetLanguageFlow.first()

    suspend fun setTargetLanguage(languageCode: String) {
        dataStore.edit { prefs ->
            prefs[Keys.TARGET_LANGUAGE] = sanitizeLanguageCode(languageCode)
        }
    }

    suspend fun setShowLyricsTranslation(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_TRANSLATION] = enabled
        }
    }

    suspend fun setShowLyricsRomanization(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_ROMANIZATION] = enabled
        }
    }

    suspend fun ensureTranslationAndPronunciationVisible() {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_TRANSLATION] = true
            prefs[Keys.SHOW_ROMANIZATION] = true
        }
    }

    fun getGeminiApiKey(): String = _geminiApiKeyFlow.value

    fun setGeminiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        encryptedPrefs.edit().apply {
            if (trimmed.isEmpty()) remove(KEY_GEMINI_API_KEY) else putString(KEY_GEMINI_API_KEY, trimmed)
        }.apply()
        _geminiApiKeyFlow.value = trimmed
    }

    fun resolveTargetLanguageCode(storedCode: String = _targetCodeSnapshot()): String {
        val sanitized = sanitizeLanguageCode(storedCode)
        if (sanitized != LANG_AUTO) return sanitized
        return Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "es"
    }

    private fun _targetCodeSnapshot(): String = LANG_AUTO

    private fun readStoredApiKey(): String =
        runCatching { encryptedPrefs.getString(KEY_GEMINI_API_KEY, "").orEmpty() }
            .getOrDefault("")

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun createApiKeyPrefs(): SharedPreferences = try {
        createEncryptedPrefs()
    } catch (e: Exception) {
        Timber.e(e, "$TAG: EncryptedSharedPreferences unreadable, deleting and recreating")
        context.deleteSharedPreferences(PREFS_NAME)
        try {
            createEncryptedPrefs()
        } catch (e2: Exception) {
            Timber.e(e2, "$TAG: Encrypted prefs unavailable, falling back to plain")
            context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "LyricsAiPrefs"
        private const val PREFS_NAME = "lyrics_ai_secrets"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val LANG_AUTO = "auto"

        val SUPPORTED_LANGUAGE_CODES = listOf(
            LANG_AUTO, "es", "en", "pt", "fr", "de", "it", "ru", "ja", "ko", "zh"
        )

        fun sanitizeLanguageCode(code: String?): String =
            if (code != null && code in SUPPORTED_LANGUAGE_CODES) code else LANG_AUTO

        fun maskApiKey(apiKey: String): String {
            val trimmed = apiKey.trim()
            if (trimmed.length <= 8) return "••••"
            return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
        }
    }
}
