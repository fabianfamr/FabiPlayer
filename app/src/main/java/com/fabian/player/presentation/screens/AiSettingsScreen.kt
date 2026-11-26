package com.fabian.player.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.fabian.player.R
import com.fabian.player.data.preferences.LyricsAiPreferencesRepository
import com.fabian.player.presentation.components.CollapsibleCommonTopBar
import com.fabian.player.presentation.components.MiniPlayerHeight
import com.fabian.player.presentation.settings.search.LocalSettingsHighlightTarget
import com.fabian.player.presentation.settings.search.settingHighlight
import com.fabian.player.presentation.viewmodel.SettingsViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    highlightKey: String? = null
) {
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
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

    val highlightPending = LocalSettingsHighlightTarget.current != null
    LaunchedEffect(highlightKey) {
        if (highlightPending && !highlightKey.isNullOrBlank()) {
            delay(200)
            val targetIndex = when (highlightKey) {
                "item_ai_gemini_key", "item_ai_gemini_model" -> 1
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
            item(key = "ai_overview") {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.ai_settings_overview),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            item(key = "ai_gemini_configuration") {
                Text(
                    text = stringResource(R.string.ai_settings_gemini_section),
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
                        modifier = Modifier
                            .settingHighlight("item_ai_gemini_key", highlightKey)
                            .testTag("ai_gemini_key_item"),
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
                        modifier = Modifier
                            .settingHighlight("item_ai_gemini_model", highlightKey)
                            .testTag("ai_gemini_model_item"),
                        onClick = { showGeminiModelDialog = true }
                    )
                }
            }
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.settings_category_ai_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeight,
            onBackClick = { navController.popBackStack() }
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
                            IconButton(
                                onClick = { showKeyText = !showKeyText },
                                modifier = Modifier.testTag("ai_gemini_key_visibility_toggle")
                            ) {
                                Icon(
                                    imageVector = if (showKeyText) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = stringResource(
                                        if (showKeyText) R.string.cd_hide_api_key else R.string.cd_show_api_key
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_gemini_key_input")
                    )
                }
            },
            onDismissRequest = { showGeminiKeyDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.setGeminiApiKey(apiKeyInput)
                        showGeminiKeyDialog = false
                    },
                    modifier = Modifier.testTag("ai_gemini_key_save_button")
                ) {
                    Text(
                        stringResource(R.string.lyrics_ai_gemini_key_save),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (uiState.hasGeminiApiKey) {
                        TextButton(
                            onClick = {
                                settingsViewModel.setGeminiApiKey("")
                                showGeminiKeyDialog = false
                            },
                            modifier = Modifier.testTag("ai_gemini_key_clear_button")
                        ) {
                            Text(
                                text = stringResource(R.string.lyrics_ai_gemini_key_clear),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(
                        onClick = { showGeminiKeyDialog = false },
                        modifier = Modifier.testTag("ai_gemini_key_cancel_button")
                    ) {
                        Text(
                            stringResource(R.string.cancel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                                label = { Text(preset, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.testTag("ai_gemini_model_chip_$preset")
                            )
                        }
                    }
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.lyrics_ai_gemini_model_custom_label)) },
                        placeholder = { Text(stringResource(R.string.lyrics_ai_gemini_model_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_gemini_model_input")
                    )
                }
            },
            onDismissRequest = { showGeminiModelDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.setGeminiModel(modelInput)
                        showGeminiModelDialog = false
                    },
                    modifier = Modifier.testTag("ai_gemini_model_save_button")
                ) {
                    Text(
                        stringResource(R.string.lyrics_ai_gemini_key_save),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (uiState.geminiModel != LyricsAiPreferencesRepository.DEFAULT_GEMINI_MODEL) {
                        TextButton(
                            onClick = {
                                settingsViewModel.setGeminiModel(LyricsAiPreferencesRepository.DEFAULT_GEMINI_MODEL)
                                showGeminiModelDialog = false
                            },
                            modifier = Modifier.testTag("ai_gemini_model_reset_button")
                        ) {
                            Text(
                                text = stringResource(R.string.lyrics_ai_gemini_model_reset),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(
                        onClick = { showGeminiModelDialog = false },
                        modifier = Modifier.testTag("ai_gemini_model_cancel_button")
                    ) {
                        Text(
                            stringResource(R.string.cancel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        )
    }
}
