package com.fabian.player.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.LinearScale
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.fabian.player.R
import com.fabian.player.data.model.LyricsSourcePreference
import com.fabian.player.data.preferences.LyricsAiPreferencesRepository
import com.fabian.player.data.preferences.LyricsWordHighlightStyle
import com.fabian.player.data.preferences.MAX_LYRICS_FONT_SIZE_SP
import com.fabian.player.data.preferences.MIN_LYRICS_FONT_SIZE_SP
import com.fabian.player.presentation.components.CollapsibleCommonTopBar
import com.fabian.player.presentation.components.MiniPlayerHeight
import com.fabian.player.presentation.components.rememberSyncedSliderState
import com.fabian.player.presentation.settings.search.LocalSettingsHighlightTarget
import com.fabian.player.presentation.settings.search.settingHighlight
import com.fabian.player.presentation.viewmodel.PlayerViewModel
import com.fabian.player.presentation.viewmodel.SettingsViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_LYRICS_BLUR_STRENGTH = 0.1f
private const val MAX_LYRICS_BLUR_STRENGTH = 2f

@Composable
fun LyricsSettingsScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    highlightKey: String? = null
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    var fontSizeDraft by remember(uiState.lyricsFontSizeSp) { mutableFloatStateOf(uiState.lyricsFontSizeSp) }
    var blurStrengthDraft by remember(uiState.animatedLyricsBlurStrength) {
        mutableFloatStateOf(uiState.animatedLyricsBlurStrength)
    }
    var showClearLyricsDialog by remember { mutableStateOf(false) }
    var showGeminiKeyDialog by remember { mutableStateOf(false) }
    var showGeminiModelDialog by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 180.dp
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }

    LaunchedEffect(minTopBarHeightPx, maxTopBarHeightPx) {
        val clamped = topBarHeight.value.coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
        if (clamped != topBarHeight.value) topBarHeight.snapTo(clamped)
    }
    val collapseFraction = 1f - (
        (topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)
        ).coerceIn(0f, 1f)

    val nestedScrollConnection = remember(minTopBarHeightPx, maxTopBarHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val scrollingUp = available.y < 0f
                if (!scrollingUp && (
                        lazyListState.firstVisibleItemIndex > 0 ||
                            lazyListState.firstVisibleItemScrollOffset > 0
                        )
                ) return Offset.Zero

                val previous = topBarHeight.value
                val next = (previous + available.y).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = next - previous
                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(next) }
                }
                return if (scrollingUp && next == minTopBarHeightPx) Offset.Zero else Offset(0f, consumed)
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val canExpand = lazyListState.firstVisibleItemIndex == 0 &&
                lazyListState.firstVisibleItemScrollOffset == 0
            val expand = canExpand && topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2f
            topBarHeight.animateTo(
                targetValue = if (expand) maxTopBarHeightPx else minTopBarHeightPx,
                animationSpec = spring(stiffness = Spring.StiffnessMedium)
            )
        }
    }

    // The rows live in separate lazy items, so bring the target's item into composition
    // first; the row then scrolls itself clear of the bars. Skipped once the highlight was
    // consumed, so returning here or rotating does not scroll again.
    val highlightPending = LocalSettingsHighlightTarget.current != null
            LaunchedEffect(highlightKey) {
        if (highlightPending && !highlightKey.isNullOrBlank()) {
            delay(200)
            val targetIndex = when (highlightKey) {
                "item_lyrics_auto_translate",
                "item_lyrics_show_romanization",
                "item_lyrics_show_translation",
                "item_lyrics_translation_lang",
                "item_lyrics_gemini_key",
                "item_lyrics_gemini_model" -> 1
                "item_lyrics_source_priority", "item_lyrics_reset_imported" -> 2
                else -> 0
            }
            lazyListState.animateScrollToItem(targetIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
        val currentTopBarHeight = with(density) { topBarHeight.value.toDp() }
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = currentTopBarHeight + 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = MiniPlayerHeight +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            )
        ) {
            item(key = "lyrics_readability") {
                LyricsSettingsSubsection(title = stringResource(R.string.setcat_lyrics_readability_section)) {
                    LyricsSliderItem(
                        title = stringResource(R.string.setcat_lyrics_font_size_title),
                        subtitle = stringResource(R.string.setcat_lyrics_font_size_subtitle),
                        valueText = stringResource(R.string.setcat_lyrics_font_size_value, fontSizeDraft),
                        icon = Icons.Rounded.FormatSize,
                        value = fontSizeDraft,
                        valueRange = MIN_LYRICS_FONT_SIZE_SP..MAX_LYRICS_FONT_SIZE_SP,
                        steps = (MAX_LYRICS_FONT_SIZE_SP - MIN_LYRICS_FONT_SIZE_SP).toInt() - 1,
                        onValueChange = { fontSizeDraft = it },
                        onValueChangeFinished = { settingsViewModel.setLyricsFontSize(fontSizeDraft) },
                        modifier = Modifier.settingHighlight("item_lyrics_font_size", highlightKey)
                    )

                    ThemeSelectorItem(
                        label = stringResource(R.string.lyrics_highlight_style_title),
                        description = stringResource(R.string.lyrics_highlight_style_subtitle),
                        options = mapOf(
                            LyricsWordHighlightStyle.WIPE.storageValue to
                                stringResource(R.string.lyrics_highlight_style_wipe),
                            LyricsWordHighlightStyle.FADE.storageValue to
                                stringResource(R.string.lyrics_highlight_style_fade),
                            LyricsWordHighlightStyle.CLASSIC.storageValue to
                                stringResource(R.string.lyrics_highlight_style_classic)
                        ),
                        optionDescriptions = mapOf(
                            LyricsWordHighlightStyle.WIPE.storageValue to
                                stringResource(R.string.lyrics_highlight_style_wipe_desc),
                            LyricsWordHighlightStyle.FADE.storageValue to
                                stringResource(R.string.lyrics_highlight_style_fade_desc),
                            LyricsWordHighlightStyle.CLASSIC.storageValue to
                                stringResource(R.string.lyrics_highlight_style_classic_desc)
                        ),
                        selectedKey = uiState.lyricsWordHighlightStyle.storageValue,
                        onSelectionChanged = { key ->
                            settingsViewModel.setLyricsWordHighlightStyle(LyricsWordHighlightStyle.fromStorage(key))
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Gradient, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_word_highlight", highlightKey)
                    )

                    SwitchSettingItem(
                        title = stringResource(R.string.lyrics_duet_layout_title),
                        subtitle = stringResource(R.string.lyrics_duet_layout_subtitle),
                        checked = uiState.lyricsDuetLayoutEnabled,
                        onCheckedChange = settingsViewModel::setLyricsDuetLayoutEnabled,
                        leadingIcon = {
                            Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_duet_layout", highlightKey)
                    )

                    SwitchSettingItem(
                        title = stringResource(R.string.lyrics_background_vocals_title),
                        subtitle = stringResource(R.string.lyrics_background_vocals_subtitle),
                        checked = uiState.showLyricsBackgroundVocals,
                        onCheckedChange = settingsViewModel::setShowLyricsBackgroundVocals,
                        leadingIcon = {
                            Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_background_vocals", highlightKey)
                    )

                    val blurAvailable = uiState.useAnimatedLyrics
                    SwitchSettingItem(
                        title = stringResource(R.string.presentation_batch_f_exp_lyric_blur_title),
                        subtitle = if (blurAvailable) {
                            stringResource(R.string.presentation_batch_f_exp_lyric_blur_subtitle)
                        } else {
                            stringResource(R.string.setcat_lyrics_blur_requires_animated)
                        },
                        checked = uiState.animatedLyricsBlurEnabled,
                        onCheckedChange = settingsViewModel::setAnimatedLyricsBlurEnabled,
                        enabled = blurAvailable,
                        leadingIcon = {
                            Icon(Icons.Rounded.BlurOn, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_animated_blur", highlightKey)
                    )

                    AnimatedVisibility(
                        visible = blurAvailable && uiState.animatedLyricsBlurEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        LyricsSliderItem(
                            title = stringResource(R.string.presentation_batch_f_exp_blur_strength),
                            subtitle = stringResource(R.string.presentation_batch_f_exp_blur_strength_subtitle),
                            valueText = stringResource(
                                R.string.presentation_batch_f_exp_blur_strength_value,
                                blurStrengthDraft
                            ),
                            icon = Icons.Outlined.LinearScale,
                            value = blurStrengthDraft,
                            valueRange = MIN_LYRICS_BLUR_STRENGTH..MAX_LYRICS_BLUR_STRENGTH,
                            steps = 18,
                            onValueChange = { blurStrengthDraft = it },
                            onValueChangeFinished = {
                                settingsViewModel.setAnimatedLyricsBlurStrength(blurStrengthDraft)
                            }
                        )
                    }
                }
            }

            item(key = "lyrics_ai_translation") {
                LyricsSettingsSubsection(title = stringResource(R.string.setcat_lyrics_ai_section)) {
                    SwitchSettingItem(
                        title = stringResource(R.string.lyrics_ai_auto_translate_title),
                        subtitle = stringResource(R.string.lyrics_ai_auto_translate_subtitle),
                        checked = uiState.autoTranslateLyricsEnabled,
                        onCheckedChange = settingsViewModel::setAutoTranslateLyricsEnabled,
                        leadingIcon = {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_auto_translate", highlightKey)
                    )

                    val languageOptions = mapOf(
                        LyricsAiPreferencesRepository.LANG_AUTO to stringResource(R.string.lyrics_ai_lang_auto),
                        "es" to stringResource(R.string.lyrics_ai_lang_es),
                        "en" to stringResource(R.string.lyrics_ai_lang_en),
                        "pt" to stringResource(R.string.lyrics_ai_lang_pt),
                        "fr" to stringResource(R.string.lyrics_ai_lang_fr),
                        "de" to stringResource(R.string.lyrics_ai_lang_de),
                        "it" to stringResource(R.string.lyrics_ai_lang_it),
                        "ru" to stringResource(R.string.lyrics_ai_lang_ru),
                        "ja" to stringResource(R.string.lyrics_ai_lang_ja),
                        "ko" to stringResource(R.string.lyrics_ai_lang_ko),
                        "zh" to stringResource(R.string.lyrics_ai_lang_zh)
                    )
                    ThemeSelectorItem(
                        label = stringResource(R.string.lyrics_ai_target_language_title),
                        description = stringResource(R.string.lyrics_ai_target_language_subtitle),
                        options = languageOptions,
                        selectedKey = uiState.lyricsTranslationTargetLanguage,
                        onSelectionChanged = settingsViewModel::setLyricsTranslationTargetLanguage,
                        leadingIcon = {
                            Icon(Icons.Rounded.Translate, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_translation_lang", highlightKey)
                    )

                    SwitchSettingItem(
                        title = stringResource(R.string.lyrics_ai_show_romanization_title),
                        subtitle = stringResource(R.string.lyrics_ai_show_romanization_subtitle),
                        checked = uiState.showLyricsRomanization,
                        onCheckedChange = settingsViewModel::setShowLyricsRomanization,
                        leadingIcon = {
                            Icon(Icons.Rounded.Abc, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_show_romanization", highlightKey)
                    )

                    SwitchSettingItem(
                        title = stringResource(R.string.lyrics_ai_show_translation_title),
                        subtitle = stringResource(R.string.lyrics_ai_show_translation_subtitle),
                        checked = uiState.showLyricsTranslation,
                        onCheckedChange = settingsViewModel::setShowLyricsTranslation,
                        leadingIcon = {
                            Icon(Icons.Rounded.Subtitles, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_show_translation", highlightKey)
                    )

                    SettingsItem(
                        title = stringResource(R.string.lyrics_ai_gemini_key_title),
                        subtitle = if (uiState.hasGeminiApiKey) {
                            stringResource(
                                R.string.lyrics_ai_gemini_key_configured,
                                LyricsAiPreferencesRepository.maskApiKey(uiState.geminiApiKey)
                            )
                        } else {
                            stringResource(R.string.lyrics_ai_gemini_key_not_configured)
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_gemini_key", highlightKey),
                        onClick = { showGeminiKeyDialog = true }
                    )

                    SettingsItem(
                        title = stringResource(R.string.lyrics_ai_gemini_model_title),
                        subtitle = stringResource(
                            R.string.lyrics_ai_gemini_model_subtitle,
                            uiState.geminiModel
                        ),
                        leadingIcon = {
                            Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_gemini_model", highlightKey),
                        onClick = { showGeminiModelDialog = true }
                    )
                }
            }

            item(key = "lyrics_management") {
                LyricsSettingsSubsection(
                    title = stringResource(R.string.setcat_lyrics_management),
                    addBottomSpace = false
                ) {
                    ThemeSelectorItem(
                        label = stringResource(R.string.setcat_lyrics_source_priority_label),
                        description = stringResource(R.string.setcat_lyrics_source_priority_desc),
                        options = mapOf(
                            LyricsSourcePreference.EMBEDDED_FIRST.name to stringResource(R.string.setcat_lyrics_embedded_first),
                            LyricsSourcePreference.API_FIRST.name to stringResource(R.string.setcat_lyrics_online_first),
                            LyricsSourcePreference.LOCAL_FIRST.name to stringResource(R.string.setcat_lyrics_local_first)
                        ),
                        selectedKey = uiState.lyricsSourcePreference.name,
                        onSelectionChanged = { key ->
                            settingsViewModel.setLyricsSourcePreference(LyricsSourcePreference.fromName(key))
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.rounded_lyrics_24),
                                null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_source_priority", highlightKey)
                    )
                    SettingsItem(
                        title = stringResource(R.string.setcat_reset_imported_lyrics_title),
                        subtitle = stringResource(R.string.setcat_reset_imported_lyrics_subtitle),
                        leadingIcon = {
                            Icon(Icons.Outlined.ClearAll, null, tint = MaterialTheme.colorScheme.secondary)
                        },
                        modifier = Modifier.settingHighlight("item_lyrics_reset_imported", highlightKey),
                        onClick = { showClearLyricsDialog = true }
                    )
                }
            }
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.setcat_lyrics_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeight,
            onBackClick = { navController.popBackStack() }
        )
    }

    if (showClearLyricsDialog) {
        AlertDialog(
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text(stringResource(R.string.dialog_reset_lyrics_title)) },
            text = { Text(stringResource(R.string.dialog_cannot_undo)) },
            onDismissRequest = { showClearLyricsDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearLyricsDialog = false
                        playerViewModel.resetAllLyrics()
                    }
                ) {
                    Text(stringResource(R.string.confirm), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLyricsDialog = false }) {
                    Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showGeminiKeyDialog) {
        var apiKeyInput by remember(uiState.geminiApiKey) { mutableStateOf(uiState.geminiApiKey) }
        var showKeyText by remember { mutableStateOf(false) }

        AlertDialog(
            icon = { Icon(Icons.Rounded.AutoAwesome, null) },
            title = { Text(stringResource(R.string.lyrics_ai_gemini_key_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.lyrics_ai_gemini_key_dialog_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.lyrics_ai_gemini_key_hint)) },
                        visualTransformation = if (showKeyText) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showKeyText = !showKeyText }) {
                                Icon(
                                    imageVector = if (showKeyText) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            onDismissRequest = { showGeminiKeyDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.setGeminiApiKey(apiKeyInput)
                        showGeminiKeyDialog = false
                    }
                ) {
                    Text(stringResource(R.string.lyrics_ai_gemini_key_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (uiState.hasGeminiApiKey) {
                        TextButton(
                            onClick = {
                                settingsViewModel.setGeminiApiKey("")
                                showGeminiKeyDialog = false
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.lyrics_ai_gemini_key_clear),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(onClick = { showGeminiKeyDialog = false }) {
                        Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        )
    }

    if (showGeminiModelDialog) {
        var modelInput by remember(uiState.geminiModel) { mutableStateOf(uiState.geminiModel) }

        AlertDialog(
            icon = { Icon(Icons.Rounded.AutoAwesome, null) },
            title = { Text(stringResource(R.string.lyrics_ai_gemini_model_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.lyrics_ai_gemini_model_dialog_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LyricsAiPreferencesRepository.SUPPORTED_GEMINI_MODELS.forEach { preset ->
                            FilterChip(
                                selected = modelInput.trim() == preset,
                                onClick = { modelInput = preset },
                                label = { Text(preset, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.lyrics_ai_gemini_model_custom_label)) },
                        placeholder = { Text(stringResource(R.string.lyrics_ai_gemini_model_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            onDismissRequest = { showGeminiModelDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.setGeminiModel(modelInput)
                        showGeminiModelDialog = false
                    }
                ) {
                    Text(stringResource(R.string.lyrics_ai_gemini_key_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (uiState.geminiModel != LyricsAiPreferencesRepository.DEFAULT_GEMINI_MODEL) {
                        TextButton(
                            onClick = {
                                settingsViewModel.setGeminiModel(LyricsAiPreferencesRepository.DEFAULT_GEMINI_MODEL)
                                showGeminiModelDialog = false
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.lyrics_ai_gemini_model_reset),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(onClick = { showGeminiModelDialog = false }) {
                        Text(stringResource(R.string.cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        )
    }
}

@Composable
private fun LyricsSettingsSubsection(
    title: String,
    addBottomSpace: Boolean = true,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
    if (addBottomSpace) {
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun LyricsSliderItem(
    title: String,
    subtitle: String,
    valueText: String,
    icon: ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(end = 8.dp)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                text = valueText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Slider(
                state = rememberSyncedSliderState(
                    value = value,
                    steps = steps,
                    valueRange = valueRange
                ),
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,)
        }
    }
}
