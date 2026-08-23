package chimahon.translate

import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * One translation backend.
 *
 * Building the request and parsing the response are kept separate from the
 * network call so both halves can be unit-tested without a server.
 */
interface Translator {

    val id: String

    /** Model used when the config leaves it blank. Empty for non-LLM backends. */
    val defaultModel: String get() = ""

    /** Throws [TranslationException] when the config cannot produce a valid request. */
    fun buildRequest(text: String, config: TranslationConfig): Request

    /** Throws [TranslationException] when the payload carries no usable translation. */
    fun parseResponse(body: String, config: TranslationConfig): TranslationResult

    /**
     * Turns a non-2xx response into a message worth showing in the popup.
     * Backends override this to name their own well-known status codes.
     */
    fun describeHttpError(code: Int, body: String): String = when (code) {
        401, 403 -> "${TranslationProviders.displayName(id)}: invalid API key (HTTP $code)"
        429 -> "${TranslationProviders.displayName(id)}: rate limited, try again in a moment"
        in 500..599 -> "${TranslationProviders.displayName(id)}: service error (HTTP $code)"
        else -> "${TranslationProviders.displayName(id)}: HTTP $code${body.summarize()}"
    }

    companion object {
        fun forProvider(provider: String): Translator = when (provider) {
            TranslationProviders.DEEPL -> DeepLTranslator
            TranslationProviders.GEMINI -> GeminiTranslator
            TranslationProviders.OPENAI -> OpenAiTranslator
            else -> throw TranslationException("Unknown translation provider: $provider")
        }
    }
}

internal val translationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Trims an error body down to something that fits in a popup toast. */
internal fun String.summarize(limit: Int = 160): String {
    val flat = trim().replace(Regex("\\s+"), " ")
    if (flat.isEmpty()) return ""
    return " — " + if (flat.length <= limit) flat else flat.take(limit) + "…"
}

/**
 * Fills the `{source}`, `{target}` and `{text}` placeholders of an LLM prompt.
 * A template without `{text}` still works: the sentence is appended instead of
 * being silently dropped.
 */
internal fun renderPrompt(template: String, text: String, config: TranslationConfig): String {
    val source = TranslationLanguages.sourceName(config.sourceLanguage) ?: "detected-language"
    val target = TranslationLanguages.targetName(config.targetLanguage)
    val filled = template
        .replace("{source}", source)
        .replace("{target}", target)
    return if (template.contains("{text}")) {
        filled.replace("{text}", text)
    } else {
        filled.trimEnd() + "\n\n" + text
    }
}

/**
 * Expands `{source}` / `{target}` in system-role instructions. Unlike
 * [renderPrompt] it never appends the sentence: the sentence belongs in the
 * user message, and a `{text}` placeholder here would smuggle it into a role
 * that is supposed to hold instructions only.
 */
internal fun renderSystemPrompt(template: String, config: TranslationConfig): String {
    val source = TranslationLanguages.sourceName(config.sourceLanguage) ?: "the source language"
    return template
        .replace("{source}", source)
        .replace("{target}", TranslationLanguages.targetName(config.targetLanguage))
        .replace("{text}", "")
        .trim()
}

/** Strips the wrapping quotes/code fences LLMs sometimes add around a translation. */
internal fun cleanLlmOutput(raw: String): String {
    var out = raw.trim()
    if (out.startsWith("```")) {
        out = out.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
    }
    if (out.length >= 2 && out.first() == '"' && out.last() == '"' && out.count { it == '"' } == 2) {
        out = out.substring(1, out.length - 1).trim()
    }
    return out
}
