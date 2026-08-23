package chimahon.translate

import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Google Gemini via the Generative Language API (`generateContent`). */
object GeminiTranslator : Translator {

    override val id = TranslationProviders.GEMINI

    override val defaultModel = "gemini-2.5-flash"

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Serializable
    private data class Payload(
        val contents: List<Content>,
        val generationConfig: GenerationConfig,
    )

    @Serializable
    private data class Content(val parts: List<Part>, val role: String = "user")

    @Serializable
    private data class Part(val text: String = "")

    @Serializable
    private data class GenerationConfig(val temperature: Float, val maxOutputTokens: Int)

    @Serializable
    private data class Response(
        val candidates: List<Candidate> = emptyList(),
        val promptFeedback: PromptFeedback? = null,
    )

    @Serializable
    private data class Candidate(
        val content: ResponseContent? = null,
        val finishReason: String? = null,
    )

    @Serializable
    private data class ResponseContent(val parts: List<Part> = emptyList())

    @Serializable
    private data class PromptFeedback(val blockReason: String? = null)

    override fun buildRequest(text: String, config: TranslationConfig): Request {
        val key = config.apiKey.trim()
        if (key.isEmpty()) throw TranslationException("Gemini: no API key configured")

        val model = config.model.trim().ifEmpty { defaultModel }
        val prompt = renderPrompt(
            config.prompt.trim().ifEmpty { TranslationConfig.DEFAULT_PROMPT },
            text,
            config,
        )
        val payload = Payload(
            contents = listOf(Content(parts = listOf(Part(prompt)))),
            generationConfig = GenerationConfig(temperature = 0.2f, maxOutputTokens = 1024),
        )

        return Request.Builder()
            .url("$BASE/$model:generateContent")
            // Header rather than ?key= so the secret stays out of URLs and logs.
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
            .post(translationJson.encodeToString(payload).toRequestBody(jsonMediaType))
            .build()
    }

    override fun parseResponse(body: String, config: TranslationConfig): TranslationResult {
        val parsed = try {
            translationJson.decodeFromString<Response>(body)
        } catch (e: Exception) {
            throw TranslationException("Gemini: unreadable response${body.summarize()}", e)
        }

        parsed.promptFeedback?.blockReason?.let {
            throw TranslationException("Gemini: request blocked ($it)")
        }

        val candidate = parsed.candidates.firstOrNull()
            ?: throw TranslationException("Gemini: no candidates returned")
        val text = candidate.content?.parts.orEmpty()
            .joinToString("") { it.text }
            .let(::cleanLlmOutput)

        if (text.isBlank()) {
            val reason = candidate.finishReason
            throw TranslationException(
                if (reason != null) "Gemini: empty translation (finishReason=$reason)"
                else "Gemini: empty translation",
            )
        }
        return TranslationResult(text = text, provider = id)
    }

    override fun describeHttpError(code: Int, body: String): String = when (code) {
        400 -> "Gemini: bad request${body.summarize()}"
        403 -> "Gemini: invalid API key or API not enabled"
        404 -> "Gemini: unknown model${body.summarize()}"
        429 -> "Gemini: quota exceeded, try again in a moment"
        in 500..599 -> "Gemini: service error (HTTP $code)"
        else -> "Gemini: HTTP $code${body.summarize()}"
    }
}
