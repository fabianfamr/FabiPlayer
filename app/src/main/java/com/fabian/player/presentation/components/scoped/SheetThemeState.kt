package com.fabian.player.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.lerp
import com.fabian.player.data.model.Song
import com.fabian.player.data.preferences.ThemePreference
import com.fabian.player.presentation.viewmodel.ColorSchemePair
import com.fabian.player.ui.theme.rememberSmoothColorScheme
import com.fabian.player.ui.theme.withAmoledSurfaces

/**
 * Theme state for the player sheet.
 *
 * Expansion-dependent values ([miniAlpha], elevation) are **no longer** included here.
 * They are computed inline in the consuming composable's `graphicsLayer` / `derivedStateOf`,
 * reading directly from the [Animatable] expansion fraction during the draw phase.
 * This eliminates per-frame recomposition that the old [Transition]-based approach caused.
 */
internal data class SheetThemeState(
    val albumColorScheme: ColorScheme,
    val miniPlayerScheme: ColorScheme,
    val isPreparingPlayback: Boolean,
    val miniReadyAlpha: Float,
    val miniAppearScale: Float,
    val playerAreaBackground: Color
)

internal fun resolvePlayerSheetTargetScheme(
    isAlbumArtTheme: Boolean,
    hasAlbumArt: Boolean,
    currentSongActiveScheme: ColorScheme?,
    lastAlbumScheme: ColorScheme?,
    systemColorScheme: ColorScheme,
    isAmoledTheme: Boolean = false,
): ColorScheme {
    val base = when {
        !isAlbumArtTheme || !hasAlbumArt -> systemColorScheme
        currentSongActiveScheme != null -> currentSongActiveScheme
        lastAlbumScheme != null -> lastAlbumScheme
        else -> systemColorScheme
    }
    return if (isAmoledTheme) base.withAmoledSurfaces() else base
}

@Composable
internal fun rememberSheetThemeState(
    activePlayerSchemePair: ColorSchemePair?,
    isDarkTheme: Boolean,
    playerThemePreference: String,
    currentSong: Song?,
    themedAlbumArtUri: String?,
    preparingSongId: String?,
    systemColorScheme: ColorScheme,
    isAmoledTheme: Boolean = false
): SheetThemeState {
    val isAlbumArtTheme = playerThemePreference == ThemePreference.ALBUM_ART
    val hasAlbumArt = !currentSong?.albumArtUriString.isNullOrBlank()

    val activePlayerScheme = remember(activePlayerSchemePair, isDarkTheme) {
        activePlayerSchemePair?.let { if (isDarkTheme) it.dark else it.light }
    }
    val currentSongActiveScheme = remember(
        activePlayerScheme,
        currentSong?.albumArtUriString,
        themedAlbumArtUri
    ) {
        if (
            activePlayerScheme != null &&
            hasAlbumArt &&
            currentSong.albumArtUriString == themedAlbumArtUri
        ) {
            activePlayerScheme
        } else {
            null
        }
    }

    var lastAlbumScheme by remember { mutableStateOf<ColorScheme?>(null) }
    var lastAlbumSchemeSongId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSong?.id) {
        if (currentSong?.id != lastAlbumSchemeSongId) {
            lastAlbumSchemeSongId = currentSong?.id
        }
    }
    LaunchedEffect(currentSongActiveScheme, currentSong?.id) {
        val currentSongId = currentSong?.id
        if (currentSongId != null && currentSongActiveScheme != null) {
            lastAlbumScheme = currentSongActiveScheme
            lastAlbumSchemeSongId = currentSongId
        }
    }

    val isPreparingPlayback = remember(preparingSongId, currentSong?.id) {
        preparingSongId != null && preparingSongId == currentSong?.id
    }

    val lastAlbumSchemeSnapshot = lastAlbumScheme

    // Remembered so the AMOLED copy keeps its identity and does not restart the color animation.
    val rawAlbumColorScheme = remember(
        isAlbumArtTheme,
        hasAlbumArt,
        currentSongActiveScheme,
        lastAlbumSchemeSnapshot,
        systemColorScheme,
        isAmoledTheme
    ) {
        resolvePlayerSheetTargetScheme(
            isAlbumArtTheme = isAlbumArtTheme,
            hasAlbumArt = hasAlbumArt,
            currentSongActiveScheme = currentSongActiveScheme,
            lastAlbumScheme = lastAlbumSchemeSnapshot,
            systemColorScheme = systemColorScheme,
            isAmoledTheme = isAmoledTheme
        )
    }

    val isUsingSystemFallback = !isAlbumArtTheme || !hasAlbumArt
    var wasUsingSystemFallback by remember { mutableStateOf(isUsingSystemFallback) }
    val snapToAnimatedSystemScheme = isUsingSystemFallback && wasUsingSystemFallback
    LaunchedEffect(isUsingSystemFallback) {
        wasUsingSystemFallback = isUsingSystemFallback
    }

    val albumColorScheme = rememberSmoothColorScheme(
        target = rawAlbumColorScheme,
        durationMs = SHEET_THEME_TRANSITION_MS,
        minStepIntervalMs = SHEET_THEME_MIN_STEP_INTERVAL_MS,
        snapImmediately = snapToAnimatedSystemScheme
    )
    val miniPlayerScheme = albumColorScheme

    val miniAppearProgress = remember { Animatable(0f) }
    LaunchedEffect(currentSong?.id) {
        if (currentSong == null) {
            miniAppearProgress.snapTo(0f)
        } else if (miniAppearProgress.value < 1f) {
            miniAppearProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            )
        }
    }

    val miniReadyAlpha = miniAppearProgress.value
    val miniAppearScale = lerp(0.985f, 1f, miniAppearProgress.value)
    val playerAreaBackground = miniPlayerScheme.primaryContainer

    return SheetThemeState(
        albumColorScheme = albumColorScheme,
        miniPlayerScheme = miniPlayerScheme,
        isPreparingPlayback = isPreparingPlayback,
        miniReadyAlpha = miniReadyAlpha,
        miniAppearScale = miniAppearScale,
        playerAreaBackground = playerAreaBackground
    )
}

private const val SHEET_THEME_TRANSITION_MS = 450
private const val SHEET_THEME_MIN_STEP_INTERVAL_MS = 32L
