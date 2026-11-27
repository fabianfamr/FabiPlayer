package com.fabian.player.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val GEMINI_MODEL = stringPreferencesKey("lyrics_gemini_model_v1")
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

    val geminiModelFlow: Flow<String> = dataStore.data.map { prefs ->
        sanitizeGeminiModel(prefs[Keys.GEMINI_MODEL])
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

    suspend fun getGeminiModel(): String = geminiModelFlow.first()

    suspend fun setGeminiModel(model: String) {
        dataStore.edit { prefs ->
            prefs[Keys.GEMINI_MODEL] = sanitizeGeminiModel(model)
        }
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

    private fun createApiKeyPrefs(): SharedPreferences =
        SecurePreferencesFactory.open(context, PREFS_NAME, TAG)

    companion object {
        private const val TAG = "LyricsAiPrefs"
        private const val PREFS_NAME = "lyrics_ai_secrets"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val LANG_AUTO = "auto"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

        val SUPPORTED_LANGUAGE_CODES = listOf(
            LANG_AUTO, "es", "en", "pt", "fr", "de", "it", "ru", "ja", "ko", "zh"
        )

        val SUPPORTED_GEMINI_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-3-flash-preview"
        )

        private val VALID_GEMINI_MODEL_REGEX = Regex("^[A-Za-z0-9._-]{1,80}$")

        fun sanitizeLanguageCode(code: String?): String =
            if (code != null && code in SUPPORTED_LANGUAGE_CODES) code else LANG_AUTO

        fun sanitizeGeminiModel(model: String?): String {
            val trimmed = model?.trim().orEmpty()
            return if (VALID_GEMINI_MODEL_REGEX.matches(trimmed)) trimmed else DEFAULT_GEMINI_MODEL
        }

        fun maskApiKey(apiKey: String): String {
            val trimmed = apiKey.trim()
            if (trimmed.length <= 8) return "••••"
            return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
        }
    }
}
