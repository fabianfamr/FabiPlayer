package com.fabian.player.data.update

import android.os.Build
import com.fabian.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Looks up the newest published build of each channel from GitHub Releases. */
@Singleton
class AppReleaseSource @Inject constructor(
    baseClient: OkHttpClient,
) {
    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "FabiPlayer/${BuildConfig.VERSION_NAME} ($PROJECT_URL)")
                    .build()
            )
        }
        .build()

    /** Newest release of [channel], or null when the channel has nothing installable for this device. */
    suspend fun latest(channel: UpdateChannel): AppRelease? = withContext(Dispatchers.IO) {
        val releases = JSONArray(getBody(GITHUB_RELEASES_API))
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        when (channel) {
            UpdateChannel.STABLE -> parseLatestStable(releases, supportedAbis)
            UpdateChannel.ALPHA -> parseLatestAlpha(releases, supportedAbis)
        }
    }

    private fun getBody(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body.string()
        }
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/fabianfamr/FabiPlayer"

        /** Release applicationId; debug builds add a suffix but should still see real releases. */
        internal const val PUBLISHED_PACKAGE = "com.fabian.player"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/fabianfamr/FabiPlayer/releases?per_page=30"

        /**
         * Newest stable release (not draft and not prerelease) with an APK for one of [supportedAbis].
         */
        internal fun parseLatestStable(releases: JSONArray, supportedAbis: List<String>): AppRelease? {
            return (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .filter { !it.optBoolean("prerelease") && !it.optBoolean("draft") }
                .mapNotNull { release ->
                    val tag = release.optString("tag_name")
                    val asset = pickAsset(release.optJSONArray("assets"), supportedAbis) ?: return@mapNotNull null
                    release to AppRelease(
                        channel = UpdateChannel.STABLE,
                        versionName = tag.removePrefix("v"),
                        versionCode = null,
                        apkUrl = asset.optString("browser_download_url"),
                        apkSizeBytes = asset.optLong("size", -1L).takeIf { it > 0 },
                        pageUrl = release.optString("html_url").ifBlank { "$PROJECT_URL/releases/tag/$tag" },
                    )
                }
                .firstOrNull()?.second
        }

        /**
         * Newest alpha prerelease with an APK for one of [supportedAbis] (in device preference
         * order). Alpha workflow assets are named `FabiPlayer-<version>-<abi>.apk`.
         */
        internal fun parseLatestAlpha(releases: JSONArray, supportedAbis: List<String>): AppRelease? {
            return (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .filter { it.optBoolean("prerelease") && !it.optBoolean("draft") }
                .mapNotNull { release ->
                    val tag = release.optString("tag_name")
                    val version = AlphaVersion.parse(tag) ?: return@mapNotNull null
                    val asset = pickAsset(release.optJSONArray("assets"), supportedAbis) ?: return@mapNotNull null
                    version to AppRelease(
                        channel = UpdateChannel.ALPHA,
                        versionName = tag.removePrefix("v"),
                        versionCode = null,
                        apkUrl = asset.optString("browser_download_url"),
                        apkSizeBytes = asset.optLong("size", -1L).takeIf { it > 0 },
                        pageUrl = release.optString("html_url").ifBlank { "$PROJECT_URL/releases/tag/$tag" },
                    )
                }
                .maxByOrNull { (version, _) -> version }
                ?.second
        }

        private val ABI_SUFFIXES = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "armeabi", "mips", "mips64")

        internal fun pickAsset(assets: JSONArray?, supportedAbis: List<String>): JSONObject? {
            if (assets == null) return null
            val apks = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .filter {
                    val name = it.optString("name")
                    name.endsWith(".apk") &&
                        !name.endsWith("-debug.apk") &&
                        it.optString("browser_download_url").isNotBlank()
                }
            return supportedAbis.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { it.optString("name").endsWith("-$abi.apk") }
            } ?: apks.firstOrNull { asset ->
                val name = asset.optString("name")
                ABI_SUFFIXES.none { abi -> name.endsWith("-$abi.apk") }
            }
        }
    }
}
