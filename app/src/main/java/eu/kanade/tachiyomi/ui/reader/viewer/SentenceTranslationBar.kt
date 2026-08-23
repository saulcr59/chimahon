package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chimahon.translate.TranslationException
import chimahon.translate.TranslationProviders
import chimahon.translate.TranslationService
import chimahon.translate.TranslationSlot
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.CancellationException
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private sealed interface TranslationUiState {
    data object Idle : TranslationUiState
    data object Loading : TranslationUiState
    data class Done(val text: String) : TranslationUiState
    data class Failed(val message: String) : TranslationUiState
}

/** Mutable state of one of the two buttons. */
@Stable
private class TranslationSlotState {
    var status by mutableStateOf<TranslationUiState>(TranslationUiState.Idle)

    /** Bumped by a tap; the effect keyed on it is what issues the request. */
    var attempt by mutableIntStateOf(0)

    val isLoading get() = status is TranslationUiState.Loading
    val isOpen get() = status is TranslationUiState.Done
}

/**
 * Drives one button: resets when the sentence or its settings change, and runs
 * the request from a [LaunchedEffect] so switching sentences cancels any call
 * still in flight.
 */
@Composable
private fun rememberTranslationSlot(
    service: TranslationService,
    slot: TranslationSlot,
    sentence: String,
    sourceLanguage: String,
    provider: String,
    targetLanguage: String,
    prompt: String,
    systemPrompt: String,
    visible: Boolean,
    autoTranslate: Boolean,
): TranslationSlotState {
    val state = remember(sentence, provider, targetLanguage, prompt, systemPrompt) {
        TranslationSlotState()
    }

    LaunchedEffect(
        sentence,
        provider,
        targetLanguage,
        prompt,
        systemPrompt,
        state.attempt,
        autoTranslate,
        visible,
    ) {
        if (!visible) return@LaunchedEffect
        if (state.attempt == 0 && !autoTranslate) return@LaunchedEffect
        state.status = TranslationUiState.Loading
        state.status = try {
            TranslationUiState.Done(service.translate(sentence, sourceLanguage, slot).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            TranslationUiState.Failed(e.message ?: "Translation failed")
        } catch (e: Exception) {
            TranslationUiState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    return state
}

/**
 * Translation row shown inside the dictionary popup, above the entry WebView.
 * Because every reader (manga, novel EPUB, subtitles, video OCR, screen lookup)
 * funnels through [OcrLookupPopup], adding it here covers all of them.
 *
 * Offers up to two buttons: the main one for a plain translation, and an
 * optional second one — typically an LLM with a custom prompt — for a grammar
 * breakdown of the same sentence. Both results can stay open at once.
 *
 * Renders nothing when the feature is disabled or there is no sentence.
 */
@Composable
internal fun SentenceTranslationBar(
    sentence: String,
    sourceLanguage: String,
    /**
     * The popup keeps a warm offscreen shell when hidden; translating from it
     * would burn API calls on sentences the user never sees.
     */
    visible: Boolean,
    eInkMode: Boolean,
    isDark: Boolean,
    backgroundColor: Color,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val preferences = remember { Injekt.get<DictionaryPreferences>() }
    val enabled by preferences.translationEnabled().collectAsState()
    val provider by preferences.translationProvider().collectAsState()
    val prompt by preferences.translationPrompt().collectAsState()
    val systemPrompt by preferences.translationSystemPrompt().collectAsState()
    val targetLanguage by preferences.translationTargetLanguage().collectAsState()
    val autoTranslate by preferences.translationAutoTranslate().collectAsState()
    val secondaryProvider by preferences.translationSecondaryProvider().collectAsState()
    val secondaryPrompt by preferences.translationSecondaryPrompt().collectAsState()
    val secondarySystemPrompt by preferences.translationSecondarySystemPrompt().collectAsState()
    val secondaryLabel by preferences.translationSecondaryLabel().collectAsState()

    if (!enabled || sentence.isBlank()) return

    val service = remember { Injekt.get<TranslationService>() }

    val primary = rememberTranslationSlot(
        service = service,
        slot = TranslationSlot.PRIMARY,
        sentence = sentence,
        sourceLanguage = sourceLanguage,
        provider = provider,
        targetLanguage = targetLanguage,
        prompt = prompt,
        systemPrompt = systemPrompt,
        visible = visible,
        autoTranslate = autoTranslate,
    )
    // Auto-translate deliberately covers only the main button: firing an LLM
    // grammar breakdown on every popup would burn tokens unasked.
    val secondary = rememberTranslationSlot(
        service = service,
        slot = TranslationSlot.SECONDARY,
        sentence = sentence,
        sourceLanguage = sourceLanguage,
        provider = secondaryProvider,
        targetLanguage = targetLanguage,
        prompt = secondaryPrompt,
        systemPrompt = secondarySystemPrompt,
        visible = visible,
        autoTranslate = false,
    )

    val chromeText = if (eInkMode) {
        if (isDark) Color.White else Color.Black
    } else {
        colorScheme.onSurface
    }
    val chromeBackground = if (eInkMode) {
        if (isDark) Color.Black else Color.White
    } else {
        backgroundColor
    }
    val chromeBorder = if (eInkMode) chromeText else colorScheme.outlineVariant
    val accent = if (eInkMode) chromeText else colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = chromeBorder,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            },
        color = chromeBackground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SlotButton(
                    state = primary,
                    idleLabel = "Translate",
                    icon = Icons.Outlined.Translate,
                    eInkMode = eInkMode,
                    chromeText = chromeText,
                    chromeBackground = chromeBackground,
                    chromeBorder = chromeBorder,
                    accent = accent,
                )
                if (secondaryProvider.isNotBlank()) {
                    SlotButton(
                        state = secondary,
                        idleLabel = secondaryLabel.ifBlank { "Breakdown" },
                        icon = Icons.Outlined.AutoAwesome,
                        eInkMode = eInkMode,
                        chromeText = chromeText,
                        chromeBackground = chromeBackground,
                        chromeBorder = chromeBorder,
                        accent = accent,
                    )
                }
                Text(
                    text = targetLanguage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = chromeText.copy(alpha = 0.6f),
                )
            }

            SlotResult(
                state = primary,
                providerLabel = TranslationProviders.displayName(provider),
                chromeText = chromeText,
                errorColor = if (eInkMode) chromeText else colorScheme.error,
            )
            if (secondaryProvider.isNotBlank()) {
                SlotResult(
                    state = secondary,
                    providerLabel = TranslationProviders.displayName(secondaryProvider),
                    chromeText = chromeText,
                    errorColor = if (eInkMode) chromeText else colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SlotButton(
    state: TranslationSlotState,
    idleLabel: String,
    icon: ImageVector,
    eInkMode: Boolean,
    chromeText: Color,
    chromeBackground: Color,
    chromeBorder: Color,
    accent: Color,
) {
    val open = state.isOpen
    Surface(
        modifier = Modifier.clickable(enabled = !state.isLoading) {
            // Tapping an open result collapses it; anything else runs the request.
            if (open) state.status = TranslationUiState.Idle else state.attempt++
        },
        shape = RoundedCornerShape(if (eInkMode) 0.dp else 20.dp),
        color = if (open) accent else Color.Transparent,
        contentColor = if (open) chromeBackground else chromeText,
        border = BorderStroke(1.dp, if (open) accent else chromeBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = chromeText,
                )
            } else {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = if (open) "Hide" else idleLabel,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SlotResult(
    state: TranslationSlotState,
    providerLabel: String,
    chromeText: Color,
    errorColor: Color,
) {
    when (val status = state.status) {
        is TranslationUiState.Done -> {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    text = providerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = chromeText.copy(alpha = 0.6f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A chunk-by-chunk breakdown is far taller than a
                        // translation, so this scrolls rather than pushing the
                        // dictionary entries off the popup.
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = status.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = chromeText,
                    )
                }
            }
        }
        is TranslationUiState.Failed -> {
            Text(
                text = status.message,
                style = MaterialTheme.typography.labelSmall,
                color = errorColor,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        else -> Unit
    }
}
