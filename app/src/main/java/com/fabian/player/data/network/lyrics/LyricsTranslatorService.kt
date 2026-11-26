package com.fabian.player.data.network.lyrics

import android.content.Context
import com.fabian.player.data.model.Lyrics
import com.fabian.player.utils.MultiLangRomanizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Result of translating a song's lyrics and generating phonetic pronunciation. */
data class LyricsTranslationOutcome(
    val lyrics: Lyrics,
    val usedGemini: Boolean,
    val fromCache: Boolean = false,
    val fallbackReason: String? = null
)

internal data class TranslatedLyricLine(
    val index: Int,
    val romanization: String?,
    val translation: String?
)

/**
 * Translates synced and plain song lyrics into the user's target language and generates
 * phonetic pronunciation (e.g. Romaji for Japanese, Revised Romanization for Korean) using the
 * Gemini API with the model and API key configured in AI settings, with automatic fallback to a
 * free translation endpoint plus local [MultiLangRomanizer].
 */
@Singleton
class LyricsTranslatorService @Inject constructor(
    @ApplicationContext private val context: Context,
    baseOkHttpClient: OkHttpClient,
    private val json: Json
) {
    private val httpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

    suspend fun translateLyrics(
        songId: String,
        title: String,
        artist: String,
        lyrics: Lyrics,
        targetLanguageCode: String,
        geminiApiKey: String,
        geminiModel: String = DEFAULT_GEMINI_MODEL
    ): Result<LyricsTranslationOutcome> = withContext(Dispatchers.IO) {
        val linesToTranslate = extractTranslatableLines(lyrics)
        if (linesToTranslate.isEmpty()) {
            return@withContext Result.success(
                LyricsTranslationOutcome(lyrics = lyrics, usedGemini = false, fromCache = true)
            )
        }

        val normalizedLang = targetLanguageCode.trim().ifBlank { "es" }
        val trimmedKey = geminiApiKey.trim()
        val resolvedModel = sanitizeModelId(geminiModel)
        val cacheFile = cacheFileFor(
            songId = songId,
            targetLang = normalizedLang,
            fingerprint = fingerprintLyrics(
                lyrics = lyrics,
                useGemini = trimmedKey.isNotEmpty(),
                geminiModel = resolvedModel
            )
        )
        readCachedLyrics(cacheFile)?.let { cached ->
            return@withContext Result.success(
                LyricsTranslationOutcome(
                    lyrics = cached,
                    usedGemini = trimmedKey.isNotEmpty(),
                    fromCache = true
                )
            )
        }

        val entireLyricsHasKana = linesToTranslate.any { (_, text) ->
            MultiLangRomanizer.isJapanese(text, entireLyricsHasKana = false)
        }

        if (trimmedKey.isNotEmpty()) {
            try {
                val translatedLines = translateAllChunksWithGemini(
                    title = title,
                    artist = artist,
                    lines = linesToTranslate,
                    targetLanguageCode = normalizedLang,
                    model = resolvedModel,
                    apiKey = trimmedKey
                )
                val updatedLyrics = applyTranslationsToLyrics(
                    lyrics = lyrics,
                    translated = translatedLines,
                    entireLyricsHasKana = entireLyricsHasKana,
                    targetLanguageCode = normalizedLang
                )
                writeCachedLyrics(cacheFile, updatedLyrics)
                return@withContext Result.success(
                    LyricsTranslationOutcome(
                        lyrics = updatedLyrics,
                        usedGemini = true,
                        fromCache = false
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Gemini translation failed; falling back to free translator")
                val fallbackReason = e.message?.take(80) ?: "API error"
                return@withContext runCatching {
                    val fallbackLines = translateAllChunksFree(
                        lines = linesToTranslate,
                        targetLanguageCode = normalizedLang,
                        entireLyricsHasKana = entireLyricsHasKana
                    )
                    val updatedLyrics = applyTranslationsToLyrics(
                        lyrics = lyrics,
                        translated = fallbackLines,
                        entireLyricsHasKana = entireLyricsHasKana,
                        targetLanguageCode = normalizedLang
                    )
                    val freeCache = cacheFileFor(
                        songId = songId,
                        targetLang = normalizedLang,
                        fingerprint = fingerprintLyrics(lyrics, useGemini = false)
                    )
                    writeCachedLyrics(freeCache, updatedLyrics)
                    LyricsTranslationOutcome(
                        lyrics = updatedLyrics,
                        usedGemini = false,
                        fromCache = false,
                        fallbackReason = fallbackReason
                    )
                }
            }
        }

        runCatching {
            val fallbackLines = translateAllChunksFree(
                lines = linesToTranslate,
                targetLanguageCode = normalizedLang,
                entireLyricsHasKana = entireLyricsHasKana
            )
            val updatedLyrics = applyTranslationsToLyrics(
                lyrics = lyrics,
                translated = fallbackLines,
                entireLyricsHasKana = entireLyricsHasKana,
                targetLanguageCode = normalizedLang
            )
            writeCachedLyrics(cacheFile, updatedLyrics)
            LyricsTranslationOutcome(
                lyrics = updatedLyrics,
                usedGemini = false,
                fromCache = false
            )
        }
    }

    fun clearCacheForSong(songId: String) {
        val prefix = "${safeFilePart(songId)}_"
        cacheDir.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun translateAllChunksWithGemini(
        title: String,
        artist: String,
        lines: List<Pair<Int, String>>,
        targetLanguageCode: String,
        model: String,
        apiKey: String
    ): Map<Int, TranslatedLyricLine> {
        val endpoint = geminiEndpointForModel(model)
        val results = mutableMapOf<Int, TranslatedLyricLine>()
        lines.chunked(GEMINI_CHUNK_SIZE).forEach { chunk ->
            val requestJson = buildGeminiRequestBody(
                title = title,
                artist = artist,
                lines = chunk,
                targetLanguageCode = targetLanguageCode
            )
            val request = Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val apiMessage = extractGeminiErrorMessage(body)
                    throw IOException("HTTP ${response.code}${apiMessage?.let { ": $it" } ?: ""}")
                }
                val parsed = parseGeminiResponse(body)
                parsed.forEach { entry -> results[entry.index] = entry }
            }
        }
        return results
    }

    private fun translateAllChunksFree(
        lines: List<Pair<Int, String>>,
        targetLanguageCode: String,
        entireLyricsHasKana: Boolean
    ): Map<Int, TranslatedLyricLine> {
        val results = mutableMapOf<Int, TranslatedLyricLine>()
        lines.chunked(FREE_CHUNK_SIZE).forEach { chunk ->
            val sanitizedTexts = chunk.map { (_, text) ->
                text.replace('\n', ' ').replace('\r', ' ').trim()
            }
            val joined = sanitizedTexts.joinToString("\n")
            val translatedJoined = callFreeGoogleTranslate(joined, targetLanguageCode)
            val splitTranslated = translatedJoined.split('\n').map { it.trim() }

            if (splitTranslated.size == chunk.size) {
                chunk.forEachIndexed { pos, (index, originalText) ->
                    val romanization = if (MultiLangRomanizer.isScriptThatNeedsRomanization(originalText)) {
                        MultiLangRomanizer.romanizeLine(originalText, entireLyricsHasKana)?.let {
                            MultiLangRomanizer.adaptRomanizationForTargetLanguage(it, targetLanguageCode)
                        }
                    } else null
                    results[index] = TranslatedLyricLine(
                        index = index,
                        romanization = romanization,
                        translation = splitTranslated[pos].takeIf { it.isNotBlank() }
                    )
                }
            } else {
                // Fallback per line when the translator collapses or inserts line breaks.
                chunk.forEach { (index, originalText) ->
                    val single = callFreeGoogleTranslate(
                        originalText.replace('\n', ' ').trim(),
                        targetLanguageCode
                    ).trim()
                    val romanization = if (MultiLangRomanizer.isScriptThatNeedsRomanization(originalText)) {
                        MultiLangRomanizer.romanizeLine(originalText, entireLyricsHasKana)?.let {
                            MultiLangRomanizer.adaptRomanizationForTargetLanguage(it, targetLanguageCode)
                        }
                    } else null
                    results[index] = TranslatedLyricLine(
                        index = index,
                        romanization = romanization,
                        translation = single.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
        return results
    }

    private fun callFreeGoogleTranslate(text: String, targetLanguageCode: String): String {
        val url = "https://translate.googleapis.com/translate_a/single"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", targetLanguageCode)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()

        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("Translator HTTP ${response.code}")
            }
            return parseGoogleTranslateArrayResponse(body)
        }
    }

    private fun cacheFileFor(songId: String, targetLang: String, fingerprint: String): File =
        File(cacheDir, "${safeFilePart(songId)}_${safeFilePart(targetLang)}_$fingerprint.json")

    private fun readCachedLyrics(file: File): Lyrics? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<Lyrics>(file.readText())
        }.getOrNull()
    }

    private fun writeCachedLyrics(file: File, lyrics: Lyrics) {
        runCatching {
            file.writeText(json.encodeToString(lyrics))
        }.onFailure { Timber.tag(TAG).w(it, "Failed to persist translated lyrics cache") }
    }

    companion object {
        private const val TAG = "LyricsTranslator"
        private const val CACHE_DIR_NAME = "lyrics_translations"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        private val VALID_MODEL_REGEX = Regex("^[A-Za-z0-9._-]{1,80}$")
        private const val GEMINI_CHUNK_SIZE = 75
        private const val FREE_CHUNK_SIZE = 25
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun sanitizeModelId(model: String?): String {
            val trimmed = model?.trim().orEmpty()
            return if (VALID_MODEL_REGEX.matches(trimmed)) trimmed else DEFAULT_GEMINI_MODEL
        }

        internal fun geminiEndpointForModel(model: String?): String {
            val safeModel = sanitizeModelId(model)
            return "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent"
        }

        internal fun extractTranslatableLines(lyrics: Lyrics): List<Pair<Int, String>> {
            val synced = lyrics.synced
            if (!synced.isNullOrEmpty()) {
                return synced.mapIndexedNotNull { index, line ->
                    line.line.trim().takeIf { it.isNotEmpty() }?.let { index to it }
                }
            }
            val plain = lyrics.plain
            if (!plain.isNullOrEmpty()) {
                return plain.mapIndexedNotNull { index, line ->
                    val primary = line.lineSequence().firstOrNull()?.trim().orEmpty()
                    primary.takeIf { it.isNotEmpty() }?.let { index to it }
                }
            }
            return emptyList()
        }

        internal fun buildGeminiRequestBody(
            title: String,
            artist: String,
            lines: List<Pair<Int, String>>,
            targetLanguageCode: String
        ): String {
            val languageName = Locale.forLanguageTag(targetLanguageCode)
                .getDisplayLanguage(Locale.ENGLISH)
                .takeIf { it.isNotBlank() && it != targetLanguageCode }
                ?: targetLanguageCode

            val payloadLines = JSONArray().apply {
                lines.forEach { (index, text) ->
                    put(JSONObject().apply {
                        put("index", index)
                        put("text", text)
                    })
                }
            }

            val prompt = buildString {
                appendLine("You are an expert song lyric translator and phonetic transcriber.")
                appendLine("Song: \"$title\" by \"$artist\".")
                appendLine("Target language: $languageName (ISO code: $targetLanguageCode).")
                appendLine("For each object in the input JSON array:")
                appendLine("1. Preserve the exact \"index\" integer.")
                appendLine("2. Set \"translation\" to a natural, expressive translation of \"text\" into $languageName.")
                appendLine("3. Set \"romanization\" to an intuitive phonetic pronunciation written so a $languageName speaker knows how \"text\" sounds when sung (adapted to $languageName phonetics — e.g. for target Spanish: Russian \"Привет\" -> \"priviet\", Japanese \"東京\" -> \"tōkyō\", English \"I love you\" -> \"ai lov yu\"). Only set \"romanization\" to \"\" if \"text\" is already written in $languageName.")
                appendLine()
                appendLine("Input lines:")
                append(payloadLines.toString())
            }

            val responseSchema = JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("lines", JSONObject().apply {
                        put("type", "ARRAY")
                        put("items", JSONObject().apply {
                            put("type", "OBJECT")
                            put("properties", JSONObject().apply {
                                put("index", JSONObject().apply { put("type", "INTEGER") })
                                put("romanization", JSONObject().apply { put("type", "STRING") })
                                put("translation", JSONObject().apply { put("type", "STRING") })
                            })
                            put("required", JSONArray().apply {
                                put("index")
                                put("translation")
                            })
                        })
                    })
                })
                put("required", JSONArray().apply { put("lines") })
            }

            return JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", responseSchema)
                })
            }.toString()
        }

        internal fun parseGeminiResponse(responseBody: String): List<TranslatedLyricLine> {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: throw IOException("No candidates in Gemini response")
            val firstCandidate = candidates.optJSONObject(0) ?: throw IOException("Empty Gemini candidates")
            val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("No content parts in Gemini response")
            val text = (0 until parts.length())
                .mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                .joinToString("")
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (text.isEmpty()) throw IOException("Empty JSON payload in Gemini response")

            val parsedJson = JSONObject(text)
            val linesArray = parsedJson.optJSONArray("lines") ?: throw IOException("Missing 'lines' array in Gemini JSON")
            return (0 until linesArray.length()).mapNotNull { idx ->
                val item = linesArray.optJSONObject(idx) ?: return@mapNotNull null
                if (!item.has("index")) return@mapNotNull null
                TranslatedLyricLine(
                    index = item.getInt("index"),
                    romanization = item.optString("romanization").trim().takeIf { it.isNotEmpty() },
                    translation = item.optString("translation").trim().takeIf { it.isNotEmpty() }
                )
            }
        }

        internal fun parseGoogleTranslateArrayResponse(responseBody: String): String {
            val root = JSONArray(responseBody)
            val segments = root.optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                val piece = segment.optString(0)
                if (piece.isNotEmpty()) sb.append(piece)
            }
            return sb.toString()
        }

        internal fun applyTranslationsToLyrics(
            lyrics: Lyrics,
            translated: Map<Int, TranslatedLyricLine>,
            entireLyricsHasKana: Boolean,
            targetLanguageCode: String = "en"
        ): Lyrics {
            val synced = lyrics.synced
            if (!synced.isNullOrEmpty()) {
                val updatedSynced = synced.mapIndexed { index, line ->
                    val entry = translated[index]
                    val fallbackRomanization = if (
                        entry?.romanization.isNullOrBlank() &&
                        line.romanization.isNullOrBlank() &&
                        MultiLangRomanizer.isScriptThatNeedsRomanization(line.line)
                    ) {
                        MultiLangRomanizer.romanizeLine(line.line, entireLyricsHasKana)?.let {
                            MultiLangRomanizer.adaptRomanizationForTargetLanguage(it, targetLanguageCode)
                        }
                    } else null

                    line.copy(
                        romanization = entry?.romanization ?: line.romanization ?: fallbackRomanization,
                        translation = entry?.translation ?: line.translation
                    )
                }
                return lyrics.copy(synced = updatedSynced)
            }

            val plain = lyrics.plain
            if (!plain.isNullOrEmpty()) {
                val translationsList = plain.mapIndexed { index, raw ->
                    val primary = raw.lineSequence().firstOrNull()?.trim().orEmpty()
                    if (primary.isEmpty()) null else translated[index]?.translation
                }
                val romanizationsList = plain.mapIndexed { index, raw ->
                    val primary = raw.lineSequence().firstOrNull()?.trim().orEmpty()
                    if (primary.isEmpty()) {
                        null
                    } else {
                        translated[index]?.romanization
                            ?: if (MultiLangRomanizer.isScriptThatNeedsRomanization(primary)) {
                                MultiLangRomanizer.romanizeLine(primary, entireLyricsHasKana)?.let {
                                    MultiLangRomanizer.adaptRomanizationForTargetLanguage(it, targetLanguageCode)
                                }
                            } else null
                    }
                }
                return lyrics.copy(
                    plainTranslations = translationsList,
                    plainRomanizations = romanizationsList
                )
            }

            return lyrics
        }

        private fun extractGeminiErrorMessage(body: String): String? = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()

        private fun fingerprintLyrics(
            lyrics: Lyrics,
            useGemini: Boolean,
            geminiModel: String = DEFAULT_GEMINI_MODEL
        ): String {
            val raw = buildString {
                if (useGemini) {
                    append("g3:").append(sanitizeModelId(geminiModel)).append(':')
                } else {
                    append("f3:")
                }
                lyrics.synced?.forEach { append(it.time).append(':').append(it.line).append('\n') }
                lyrics.plain?.forEach { append(it).append('\n') }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        private fun safeFilePart(value: String): String =
            value.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }
}
