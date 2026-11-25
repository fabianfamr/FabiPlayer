package com.fabian.player.data.network.lyrics

import com.fabian.player.data.model.Lyrics
import com.fabian.player.data.model.SyncedLine
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

class LyricsTranslatorServiceTest {

    @Test
    fun `extractTranslatableLines skips blank lines while preserving indices`() {
        val lyrics = Lyrics(
            synced = listOf(
                SyncedLine(time = 1000, line = "夢ならばどれほどよかったでしょう"),
                SyncedLine(time = 2000, line = "   "),
                SyncedLine(time = 3000, line = "未だにあなたのことを夢にみる")
            )
        )

        val lines = LyricsTranslatorService.extractTranslatableLines(lyrics)

        assertThat(lines).containsExactly(
            0 to "夢ならばどれほどよかったでしょう",
            2 to "未だにあなたのことを夢にみる"
        ).inOrder()
    }

    @Test
    fun `buildGeminiRequestBody configures structured JSON schema without deprecated sampling params`() {
        val body = LyricsTranslatorService.buildGeminiRequestBody(
            title = "Lemon",
            artist = "Kenshi Yonezu",
            lines = listOf(0 to "夢ならばどれほどよかったでしょう"),
            targetLanguageCode = "es"
        )

        val json = JSONObject(body)
        val genConfig = json.getJSONObject("generationConfig")
        assertThat(genConfig.getString("responseMimeType")).isEqualTo("application/json")
        assertThat(genConfig.has("responseSchema")).isTrue()
        assertThat(genConfig.has("temperature")).isFalse()
        assertThat(genConfig.has("topP")).isFalse()
        assertThat(genConfig.has("topK")).isFalse()

        val promptText = json.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertThat(promptText).contains("Spanish")
        assertThat(promptText).contains("phonetic pronunciation")
    }

    @Test
    fun `parseGeminiResponse parses lines with romanization and translation`() {
        val responseJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"lines\":[{\"index\":0,\"romanization\":\"Yume naraba dorehodo yokatta deshou\",\"translation\":\"Si fuera un sueño, qué bueno habría sido\"}]}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = LyricsTranslatorService.parseGeminiResponse(responseJson)

        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].index).isEqualTo(0)
        assertThat(parsed[0].romanization).isEqualTo("Yume naraba dorehodo yokatta deshou")
        assertThat(parsed[0].translation).isEqualTo("Si fuera un sueño, qué bueno habría sido")
    }

    @Test
    fun `applyTranslationsToLyrics maps synced and plain translations and romanizations`() {
        val synced = Lyrics(
            synced = listOf(
                SyncedLine(time = 1000, line = "こんにちは"),
                SyncedLine(time = 2000, line = "")
            )
        )
        val translatedMap = mapOf(
            0 to TranslatedLyricLine(
                index = 0,
                romanization = "Konnichiwa",
                translation = "Hola"
            )
        )

        val updatedSynced = LyricsTranslatorService.applyTranslationsToLyrics(
            lyrics = synced,
            translated = translatedMap,
            entireLyricsHasKana = true
        )

        assertThat(updatedSynced.synced!![0].romanization).isEqualTo("Konnichiwa")
        assertThat(updatedSynced.synced!![0].translation).isEqualTo("Hola")
        assertThat(updatedSynced.synced!![1].translation).isNull()

        val plain = Lyrics(plain = listOf("こんにちは", "", "さようなら"))
        val updatedPlain = LyricsTranslatorService.applyTranslationsToLyrics(
            lyrics = plain,
            translated = mapOf(
                0 to TranslatedLyricLine(0, "Konnichiwa", "Hola"),
                2 to TranslatedLyricLine(2, "Sayounara", "Adiós")
            ),
            entireLyricsHasKana = true
        )

        assertThat(updatedPlain.plainRomanizations).containsExactly("Konnichiwa", null, "Sayounara").inOrder()
        assertThat(updatedPlain.plainTranslations).containsExactly("Hola", null, "Adiós").inOrder()
    }

    @Test
    fun `parseGoogleTranslateArrayResponse concatenates segments cleanly`() {
        val sample = """[[["Hola\n","Hello\n",null,null,10],["Mundo","World",null,null,10]],null,"en"]"""

        val text = LyricsTranslatorService.parseGoogleTranslateArrayResponse(sample)

        assertThat(text).isEqualTo("Hola\nMundo")
    }
}
