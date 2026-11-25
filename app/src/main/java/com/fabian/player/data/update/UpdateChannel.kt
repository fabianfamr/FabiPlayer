package com.fabian.player.data.update

/**
 * Where builds of a channel are published. Both stable and alpha builds are published on GitHub Releases.
 */
enum class UpdateChannel(val storageKey: String) {
    STABLE("stable"),
    ALPHA("alpha");

    companion object {
        fun fromStorageKey(key: String?): UpdateChannel? = entries.firstOrNull { it.storageKey == key }

        /**
         * The channel the running build was distributed through. Alphas carry a pre-release suffix (-alpha.N).
         */
        fun ofVersionName(versionName: String): UpdateChannel =
            if ('-' in versionName) ALPHA else STABLE
    }
}

/** `0.3.0-alpha.16` as published by the alpha-release workflow. */
data class AlphaVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
) : Comparable<AlphaVersion> {

    override fun compareTo(other: AlphaVersion): Int = compareValuesBy(
        this, other, AlphaVersion::major, AlphaVersion::minor, AlphaVersion::patch, AlphaVersion::build
    )

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)-alpha\.(\d+)$""")

        fun parse(value: String): AlphaVersion? {
            val groups = PATTERN.matchEntire(value.trim())?.groupValues ?: return null
            return AlphaVersion(
                major = groups[1].toIntOrNull() ?: return null,
                minor = groups[2].toIntOrNull() ?: return null,
                patch = groups[3].toIntOrNull() ?: return null,
                build = groups[4].toIntOrNull() ?: return null,
            )
        }
    }
}

/** A published build that can be offered to the user. */
data class AppRelease(
    val channel: UpdateChannel,
    val versionName: String,
    val versionCode: Long?,
    val apkUrl: String,
    val apkSizeBytes: Long?,
    val pageUrl: String,
)

internal fun isVersionNewer(candidate: String, installed: String): Boolean {
    val candParts = candidate.removePrefix("v").split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    val instParts = installed.removePrefix("v").split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    val maxLen = maxOf(candParts.size, instParts.size)
    for (i in 0 until maxLen) {
        val c = candParts.getOrElse(i) { 0 }
        val inst = instParts.getOrElse(i) { 0 }
        if (c != inst) return c > inst
    }
    return false
}

/** Whether [release] is newer than the running build, assuming both are on the same channel. */
internal fun AppRelease.isNewerThan(installedVersionName: String, installedVersionCode: Long): Boolean =
    when (channel) {
        UpdateChannel.STABLE -> {
            if (versionCode != null && versionCode > 0) {
                versionCode > installedVersionCode
            } else {
                isVersionNewer(versionName, installedVersionName)
            }
        }
        UpdateChannel.ALPHA -> {
            val candidate = AlphaVersion.parse(versionName) ?: return false
            // PR and other CI builds share the alpha signing key but have no alpha number;
            // any published alpha is the way back onto the channel.
            val installed = AlphaVersion.parse(installedVersionName) ?: return true
            candidate > installed
        }
    }
