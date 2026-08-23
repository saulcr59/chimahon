package chimahon.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates whole sentences for the dictionary popup.
 *
 * Results are cached per (provider, model, target language, sentence) so that
 * re-opening the popup on the same sentence — which happens constantly while
 * mining word by word — costs nothing.
 */
class TranslationService(
    private val preferences: TranslationPreferences,
    private val client: OkHttpClient = defaultClient(),
) {

    private val cache = object : LinkedHashMap<String, TranslationResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResult>) =
            size > CACHE_SIZE
    }
    private val cacheMutex = Mutex()

    /**
     * Reads the settings for one button into a config, tagged with the profile's
     * source language. API keys, models and the target language are shared by
     * both slots; the provider and the prompt are per-slot.
     */
    fun currentConfig(
        sourceLanguage: String = "",
        slot: TranslationSlot = TranslationSlot.PRIMARY,
    ): TranslationConfig {
        val provider = when (slot) {
            TranslationSlot.PRIMARY ->
                preferences.translationProvider().get().ifBlank { TranslationProviders.DEEPL }
            TranslationSlot.SECONDARY ->
                preferences.translationSecondaryProvider().get()
        }
        if (provider.isBlank()) throw TranslationException("No provider configured for this button")

        val prompt = when (slot) {
            TranslationSlot.PRIMARY -> preferences.translationPrompt().get()
            TranslationSlot.SECONDARY -> preferences.translationSecondaryPrompt().get()
                .ifBlank { TranslationConfig.DEFAULT_GRAMMAR_PROMPT }
        }

        return TranslationConfig(
            provider = provider,
            apiKey = when (provider) {
                TranslationProviders.GEMINI -> preferences.translationGeminiApiKey().get()
                TranslationProviders.OPENAI -> preferences.translationOpenAiApiKey().get()
                else -> preferences.translationDeepLApiKey().get()
            },
            targetLanguage = preferences.translationTargetLanguage().get().ifBlank { "EN-US" },
            sourceLanguage = sourceLanguage,
            model = when (provider) {
                TranslationProviders.GEMINI -> preferences.translationGeminiModel().get()
                TranslationProviders.OPENAI -> preferences.translationOpenAiModel().get()
                else -> ""
            },
            prompt = prompt,
            openAiBaseUrl = preferences.translationOpenAiBaseUrl().get(),
        )
    }

    /**
     * Translates [text], returning a cached result when one exists.
     * Throws [TranslationException] with a user-facing message on any failure.
     */
    suspend fun translate(
        text: String,
        sourceLanguage: String = "",
        slot: TranslationSlot = TranslationSlot.PRIMARY,
    ): TranslationResult {
        val sentence = text.trim()
        if (sentence.isEmpty()) throw TranslationException("Nothing to translate")

        val config = currentConfig(sourceLanguage, slot)
        if (!config.hasCredentials) {
            throw TranslationException(
                "${TranslationProviders.displayName(config.provider)}: no API key configured " +
                    "(Settings → Dictionary → Sentence translation)",
            )
        }

        val key = cacheKey(sentence, config)
        cacheMutex.withLock { cache[key] }?.let { return it }

        val result = request(sentence, config)
        cacheMutex.withLock { cache[key] = result }
        return result
    }

    suspend fun clearCache() = cacheMutex.withLock { cache.clear() }

    private suspend fun request(sentence: String, config: TranslationConfig): TranslationResult {
        val translator = Translator.forProvider(config.provider)
        val request = translator.buildRequest(sentence, config)

        val (code, body) = withContext(Dispatchers.IO) {
            try {
                client.newCall(request).await()
            } catch (e: TranslationException) {
                throw e
            } catch (e: IOException) {
                throw TranslationException(
                    "${TranslationProviders.displayName(config.provider)}: network error " +
                        "(${e.message ?: e.javaClass.simpleName})",
                    e,
                )
            }
        }

        if (code !in 200..299) throw TranslationException(translator.describeHttpError(code, body))
        return translator.parseResponse(body, config)
    }

    private fun cacheKey(sentence: String, config: TranslationConfig) = buildString {
        append(config.provider).append('|')
        append(config.model).append('|')
        append(config.targetLanguage).append('|')
        append(config.prompt.hashCode()).append('|')
        append(sentence)
    }

    companion object {
        private const val CACHE_SIZE = 128

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // LLM providers can take a while on a long sentence.
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

/** Suspending [Call.execute] that cancels the HTTP call when the coroutine is cancelled. */
private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val payload = try {
                    response.use { it.code to it.body.string() }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(payload)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }
        },
    )
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
        }
    }
}
