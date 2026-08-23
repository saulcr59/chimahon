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
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chimahon.translate.TranslationException
import chimahon.translate.TranslationProviders
import chimahon.translate.TranslationService
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

/**
 * "Translate sentence" row shown inside the dictionary popup, above the entry
 * WebView. Because every reader (manga, novel, subtitles, video OCR, screen
 * lookup) funnels through [OcrLookupPopup], adding it here covers all of them.
 *
 * Renders nothing when the feature is disabled in settings or when there is no
 * sentence to translate.
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
    val targetLanguage by preferences.translationTargetLanguage().collectAsState()
    val autoTranslate by preferences.translationAutoTranslate().collectAsState()

    if (!enabled || sentence.isBlank()) return

    val service = remember { Injekt.get<TranslationService>() }

    // A new sentence — or a settings change that invalidates the old answer —
    // resets the row back to its collapsed state.
    var state by remember(sentence, provider, targetLanguage) {
        mutableStateOf<TranslationUiState>(TranslationUiState.Idle)
    }
    // Bumped by the button; the effect below is what actually runs the request,
    // so switching sentences cancels any in-flight call for free.
    var attempt by remember(sentence, provider, targetLanguage) { mutableIntStateOf(0) }

    LaunchedEffect(sentence, provider, targetLanguage, attempt, autoTranslate, visible) {
        if (!visible) return@LaunchedEffect
        if (attempt == 0 && !autoTranslate) return@LaunchedEffect
        state = TranslationUiState.Loading
        state = try {
            TranslationUiState.Done(service.translate(sentence, sourceLanguage).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationException) {
            TranslationUiState.Failed(e.message ?: "Translation failed")
        } catch (e: Exception) {
            TranslationUiState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

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
    val buttonShape = RoundedCornerShape(if (eInkMode) 0.dp else 20.dp)

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
                val currentState = state
                Surface(
                    modifier = Modifier.clickable(enabled = currentState !is TranslationUiState.Loading) {
                        when (currentState) {
                            // Tapping the result collapses it again; anything else translates.
                            is TranslationUiState.Done -> state = TranslationUiState.Idle
                            else -> attempt++
                        }
                    },
                    shape = buttonShape,
                    color = if (currentState is TranslationUiState.Done) accent else Color.Transparent,
                    contentColor = if (currentState is TranslationUiState.Done) chromeBackground else chromeText,
                    border = BorderStroke(1.dp, if (currentState is TranslationUiState.Done) accent else chromeBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (currentState is TranslationUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = chromeText,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = when (currentState) {
                                is TranslationUiState.Loading -> "Translating…"
                                is TranslationUiState.Done -> "Hide"
                                else -> "Translate"
                            },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                Text(
                    text = TranslationProviders.displayName(provider) + " → " + targetLanguage,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = chromeText.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )
            }

            when (val currentState = state) {
                is TranslationUiState.Done -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                    ) {
                        Text(
                            text = currentState.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = chromeText,
                        )
                    }
                }
                is TranslationUiState.Failed -> {
                    Text(
                        text = currentState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (eInkMode) chromeText else colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                else -> Unit
            }
        }
    }
}
