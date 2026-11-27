package com.fabian.player.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.core.view.WindowCompat
import com.fabian.player.data.preferences.GlobalThemeTransitionMode
import com.fabian.player.data.preferences.InterfaceAppearance
import com.fabian.player.data.preferences.ThemePreset
import com.fabian.player.presentation.viewmodel.ColorSchemePair
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.delay

val LocalPixelPlayerDarkTheme = staticCompositionLocalOf { false }
val LocalPixelPlayerAmoledTheme = staticCompositionLocalOf { false }
val LocalShowScrollbar = staticCompositionLocalOf { true }
internal val LocalUseSystemFont = staticCompositionLocalOf { false }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Suppress("DEPRECATION")
@Composable
fun PixelPlayerStatusBarStyle(
    color: Color,
    useDarkIcons: Boolean = ColorUtils.calculateLuminance(color.toArgb()) > 0.55,
    navigationColor: Color? = null,
    useDarkNavigationIcons: Boolean = navigationColor
        ?.let { ColorUtils.calculateLuminance(it.toArgb()) > 0.55 }
        ?: useDarkIcons
) {
    val view = LocalView.current
    if (view.isInEditMode) return

    val updateNavigationBar = navigationColor != null
    DisposableEffect(view, useDarkIcons, updateNavigationBar, useDarkNavigationIcons) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }

            WindowCompat.getInsetsController(window, view).run {
                isAppearanceLightStatusBars = useDarkIcons

                if (updateNavigationBar) {
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                    isAppearanceLightNavigationBars = useDarkNavigationIcons
                }
            }
        }
        onDispose {}
    }
}

val DarkColorScheme = darkColorScheme(
    primary = PixelPlayerPurplePrimary,
    secondary = PixelPlayerPink,
    tertiary = PixelPlayerOrange,
    background = PixelPlayerPurpleDark,
    surface = PixelPlayerSurface,
    onPrimary = PixelPlayerWhite,
    onSecondary = PixelPlayerWhite,
    onTertiary = PixelPlayerWhite,
    onBackground = PixelPlayerWhite,
    onSurface = PixelPlayerLightPurple,
    error = Color(0xFFFF5252),
    onError = PixelPlayerWhite
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = PixelPlayerWhite,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = PixelPlayerPink,
    onSecondary = PixelPlayerWhite,
    secondaryContainer = PixelPlayerPink.copy(alpha = 0.15f),
    onSecondaryContainer = PixelPlayerPink.copy(alpha = 0.85f),
    tertiary = PixelPlayerOrange,
    onTertiary = PixelPlayerBlack,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.6f),
    surfaceTint = LightPrimary,
    error = Color(0xFFD32F2F),
    onError = PixelPlayerWhite
)

fun isAmoledThemePreset(preset: String): Boolean = preset == ThemePreset.AMOLED

/** Keep the source palette's accents and content colors, replacing only its surface hierarchy. */
fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF242424),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF242424),
    surfaceVariant = Color(0xFF242424),
)

/**
 * AMOLED always uses a dark palette, even when the app is set to light mode. The accents come
 * from [colorSchemePairOverride] (the app-wide now-playing palette) when the user enabled it,
 * otherwise from the wallpaper/dynamic dark scheme.
 */
internal fun resolveAmoledColorScheme(
    colorSchemePairOverride: ColorSchemePair?,
    wallpaperDarkScheme: () -> ColorScheme,
): ColorScheme = (colorSchemePairOverride?.dark ?: wallpaperDarkScheme()).withAmoledSurfaces()

fun getAmoledColorScheme(
    context: Context,
    colorSchemePairOverride: ColorSchemePair? = null,
): ColorScheme = resolveAmoledColorScheme(colorSchemePairOverride) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            dynamicDarkColorScheme(context)
        } catch (e: Exception) {
            DarkColorScheme
        }
    } else {
        DarkColorScheme
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PixelPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorSchemePairOverride: ColorSchemePair? = null,
    themePreset: String = ThemePreset.DEFAULT,
    interfaceAppearance: InterfaceAppearance = InterfaceAppearance(),
    themeTransitionDurationMs: Int = 0,
    themeTransitionMode: GlobalThemeTransitionMode = GlobalThemeTransitionMode.GRADUAL,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isAmoled = isAmoledThemePreset(themePreset)
    val targetColorScheme = remember(context, isAmoled, darkTheme, colorSchemePairOverride) {
        when {
            isAmoled -> getAmoledColorScheme(context, colorSchemePairOverride)
            colorSchemePairOverride == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                try {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } catch (e: Exception) {
                    if (darkTheme) DarkColorScheme else LightColorScheme
                }
            }
            colorSchemePairOverride != null -> {
                if (darkTheme) colorSchemePairOverride.dark else colorSchemePairOverride.light
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }
    val finalColorScheme = rememberSmoothColorScheme(
        target = targetColorScheme,
        durationMs = themeTransitionDurationMs,
        minStepIntervalMs = GLOBAL_THEME_MIN_STEP_INTERVAL_MS,
        transitionMode = themeTransitionMode
    )

    PixelPlayerStatusBarStyle(
        color = targetColorScheme.background,
        navigationColor = targetColorScheme.background
    )

    val systemDensity = LocalDensity.current
    val scalePercent = interfaceAppearance.scalePercent
    val interfaceDensity = remember(systemDensity, scalePercent) {
        if (scalePercent == InterfaceAppearance.DEFAULT_SCALE) {
            systemDensity
        } else {
            object : Density {
                override val density = systemDensity.density * scalePercent / 100f
                override val fontScale = systemDensity.fontScale
                override fun Dp.toSp(): TextUnit = with(systemDensity) { this@toSp.toSp() }
                override fun TextUnit.toDp(): Dp = with(systemDensity) { this@toDp.toDp() }
            }
        }
    }

    CompositionLocalProvider(
        LocalDensity provides interfaceDensity,
        LocalUseSystemFont provides interfaceAppearance.useSystemFont,
        LocalPixelPlayerDarkTheme provides (darkTheme || isAmoled),
        LocalPixelPlayerAmoledTheme provides isAmoled
    ) {
        MaterialTheme(
            colorScheme = finalColorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = if (interfaceAppearance.useSystemFont) SystemTypography else Typography,
            shapes = Shapes,
            content = content
        )
    }
}

private const val GLOBAL_THEME_MIN_STEP_INTERVAL_MS = 32L
private const val MAX_THEME_STEP_INTERVAL_MS = 120L
private const val AFTER_DELAY_FADE_MS = 350
private val GradualThemeEasing = CubicBezierEasing(0.45f, 0f, 0.85f, 1f)

/**
 * Smoothly interpolates a [ColorScheme] with a single frame-budgeted loop instead of 48
 * concurrent [animateColorAsState] instances.
 *
 * Because Material 3's `LocalColorScheme` is a `staticCompositionLocalOf`, every emitted
 * [ColorScheme] invalidates the consuming subtree. Pacing emissions between [minStepIntervalMs]
 * and [MAX_THEME_STEP_INTERVAL_MS] adapts to frame cost without freezing the transition during
 * heavy track-change frames.
 */
@Composable
internal fun rememberSmoothColorScheme(
    target: ColorScheme,
    durationMs: Int,
    minStepIntervalMs: Long = GLOBAL_THEME_MIN_STEP_INTERVAL_MS,
    transitionMode: GlobalThemeTransitionMode = GlobalThemeTransitionMode.GRADUAL,
    snapImmediately: Boolean = false
): ColorScheme {
    var displayedScheme by remember { mutableStateOf(target) }

    LaunchedEffect(target, durationMs, transitionMode, snapImmediately) {
        if (snapImmediately || durationMs <= 0) {
            displayedScheme = target
            return@LaunchedEffect
        }
        if (displayedScheme == target) return@LaunchedEffect

        val activeDurationMs: Int
        val activeEasing: Easing
        if (transitionMode == GlobalThemeTransitionMode.AFTER_DELAY) {
            delay(durationMs.toLong())
            activeDurationMs = AFTER_DELAY_FADE_MS
            activeEasing = FastOutSlowInEasing
        } else {
            activeDurationMs = durationMs
            activeEasing = GradualThemeEasing
        }

        val startScheme = displayedScheme
        val startNanos = withFrameNanos { it }
        val durationNanos = activeDurationMs.toLong() * 1_000_000L
        val minStepNanos = minStepIntervalMs.coerceAtLeast(16L) * 1_000_000L
        val maxStepNanos = MAX_THEME_STEP_INTERVAL_MS.coerceAtLeast(minStepIntervalMs) * 1_000_000L
        var lastEmitNanos = startNanos
        var lastFrameIntervalNanos = 0L
        var awaitingFrameCost = false

        while (isActive) {
            val frameNanos = withFrameNanos { it }
            if (awaitingFrameCost) {
                lastFrameIntervalNanos = (frameNanos - lastEmitNanos).coerceAtLeast(0L)
                awaitingFrameCost = false
            }

            val elapsedNanos = (frameNanos - startNanos).coerceAtLeast(0L)
            if (elapsedNanos >= durationNanos) {
                displayedScheme = target
                break
            }

            val adaptiveStepNanos = (lastFrameIntervalNanos * 2L).coerceAtMost(maxStepNanos)
            val requiredIntervalNanos = maxOf(minStepNanos, adaptiveStepNanos)
            if (frameNanos - lastEmitNanos >= requiredIntervalNanos) {
                val rawFraction = (elapsedNanos.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
                val easedFraction = activeEasing.transform(rawFraction)
                val nextScheme = lerpColorScheme(startScheme, target, easedFraction)
                lastEmitNanos = frameNanos
                if (nextScheme != displayedScheme) {
                    displayedScheme = nextScheme
                    awaitingFrameCost = true
                }
            }
        }
    }

    return if (snapImmediately || durationMs <= 0) target else displayedScheme
}

internal fun lerpColorScheme(from: ColorScheme, to: ColorScheme, t: Float): ColorScheme {
    if (t <= 0f) return from
    if (t >= 1f || from == to) return to
    return ColorScheme(
        primary = lerp(from.primary, to.primary, t),
        onPrimary = lerp(from.onPrimary, to.onPrimary, t),
        primaryContainer = lerp(from.primaryContainer, to.primaryContainer, t),
        onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, t),
        inversePrimary = lerp(from.inversePrimary, to.inversePrimary, t),
        secondary = lerp(from.secondary, to.secondary, t),
        onSecondary = lerp(from.onSecondary, to.onSecondary, t),
        secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, t),
        onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, t),
        tertiary = lerp(from.tertiary, to.tertiary, t),
        onTertiary = lerp(from.onTertiary, to.onTertiary, t),
        tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, t),
        onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, t),
        background = lerp(from.background, to.background, t),
        onBackground = lerp(from.onBackground, to.onBackground, t),
        surface = lerp(from.surface, to.surface, t),
        onSurface = lerp(from.onSurface, to.onSurface, t),
        surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, t),
        onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, t),
        surfaceTint = lerp(from.surfaceTint, to.surfaceTint, t),
        inverseSurface = lerp(from.inverseSurface, to.inverseSurface, t),
        inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, t),
        error = lerp(from.error, to.error, t),
        onError = lerp(from.onError, to.onError, t),
        errorContainer = lerp(from.errorContainer, to.errorContainer, t),
        onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, t),
        outline = lerp(from.outline, to.outline, t),
        outlineVariant = lerp(from.outlineVariant, to.outlineVariant, t),
        scrim = lerp(from.scrim, to.scrim, t),
        surfaceBright = lerp(from.surfaceBright, to.surfaceBright, t),
        surfaceDim = lerp(from.surfaceDim, to.surfaceDim, t),
        surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, t),
        surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
        surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, t),
        surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, t),
        surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, t),
        primaryFixed = lerp(from.primaryFixed, to.primaryFixed, t),
        primaryFixedDim = lerp(from.primaryFixedDim, to.primaryFixedDim, t),
        onPrimaryFixed = lerp(from.onPrimaryFixed, to.onPrimaryFixed, t),
        onPrimaryFixedVariant = lerp(from.onPrimaryFixedVariant, to.onPrimaryFixedVariant, t),
        secondaryFixed = lerp(from.secondaryFixed, to.secondaryFixed, t),
        secondaryFixedDim = lerp(from.secondaryFixedDim, to.secondaryFixedDim, t),
        onSecondaryFixed = lerp(from.onSecondaryFixed, to.onSecondaryFixed, t),
        onSecondaryFixedVariant = lerp(from.onSecondaryFixedVariant, to.onSecondaryFixedVariant, t),
        tertiaryFixed = lerp(from.tertiaryFixed, to.tertiaryFixed, t),
        tertiaryFixedDim = lerp(from.tertiaryFixedDim, to.tertiaryFixedDim, t),
        onTertiaryFixed = lerp(from.onTertiaryFixed, to.onTertiaryFixed, t),
        onTertiaryFixedVariant = lerp(from.onTertiaryFixedVariant, to.onTertiaryFixedVariant, t)
    )
}
