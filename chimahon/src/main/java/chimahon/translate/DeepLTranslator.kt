package chimahon.translate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepL API v2. Free keys end in `:fx` and live on a different host than Pro
 * keys, so the endpoint is derived from the key instead of asking the user.
 */
object DeepLTranslator : Translator {

    override val id = TranslationProviders.DEEPL

    private const val FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"
    private const val PRO_ENDPOINT = "https://api.deepl.com/v2/translate"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Serializable
    private data class Payload(
        val text: List<String>,
        @SerialName("target_lang") val targetLang: String,
        @SerialName("source_lang") val sourceLang: String? = null,
    )

    @Serializable
    private data class Response(val translations: List<Translation> = emptyList())

    @Serializable
    private data class Translation(
        val text: String = "",
        @SerialName("detected_source_language") val detectedSourceLanguage: String? = null,
    )

    fun endpointFor(apiKey: String): String =
        if (apiKey.trim().endsWith(":fx")) FREE_ENDPOINT else PRO_ENDPOINT

    override fun buildRequest(text: String, config: TranslationConfig): Request {
        val key = config.apiKey.trim()
        if (key.isEmpty()) throw TranslationException("DeepL: no API key configured")

        val payload = Payload(
            text = listOf(text),
            targetLang = config.targetLanguage.uppercase(),
            sourceLang = TranslationLanguages.deepLSourceCode(config.sourceLanguage),
        )

        return Request.Builder()
            .url(endpointFor(key))
            .header("Authorization", "DeepL-Auth-Key $key")
            .header("Content-Type", "application/json")
            .post(translationJson.encodeToString(payload).toRequestBody(jsonMediaType))
            .build()
    }

    override fun parseResponse(body: String, config: TranslationConfig): TranslationResult {
        val parsed = try {
            translationJson.decodeFromString<Response>(body)
        } catch (e: Exception) {
            throw TranslationException("DeepL: unreadable response${body.summarize()}", e)
        }
        val first = parsed.translations.firstOrNull()
            ?: throw TranslationException("DeepL: empty response")
        if (first.text.isBlank()) throw TranslationException("DeepL: empty translation")
        return TranslationResult(
            text = first.text,
            provider = id,
            detectedSourceLanguage = first.detectedSourceLanguage,
        )
    }

    override fun describeHttpError(code: Int, body: String): String = when (code) {
        403 -> "DeepL: invalid API key"
        413 -> "DeepL: sentence too long"
        429 -> "DeepL: too many requests, try again in a moment"
        456 -> "DeepL: character quota exhausted for this billing period"
        in 500..599 -> "DeepL: service error (HTTP $code)"
        else -> "DeepL: HTTP $code${body.summarize()}"
    }
}
