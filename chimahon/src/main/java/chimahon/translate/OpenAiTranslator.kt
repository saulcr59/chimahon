package chimahon.translate

import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions, and by extension any server that speaks the same
 * shape — llama.cpp, LM Studio, Ollama, vLLM, OpenRouter — through
 * [TranslationConfig.openAiBaseUrl].
 */
object OpenAiTranslator : Translator {

    override val id = TranslationProviders.OPENAI

    override val defaultModel = "gpt-4o-mini"

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private const val TRANSLATE_ONLY_SYSTEM_PROMPT =
        "You are a translation engine embedded in a reading app. " +
            "Output only the translation of the user's sentence. Never add commentary."

    /**
     * A user-written prompt usually asks for something the translate-only rule
     * would forbid — a grammar breakdown, say — so it gets a neutral system
     * prompt instead of one that contradicts it.
     */
    private const val FREEFORM_SYSTEM_PROMPT =
        "You are a language-learning assistant embedded in a reading app. " +
            "Follow the user's instructions exactly and keep the answer compact."

    @Serializable
    private data class Payload(
        val model: String,
        val messages: List<Message>,
        val temperature: Float,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Response(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: Message? = null, val finish_reason: String? = null)

    /** Normalizes a user-typed base URL into `<host>/v1/chat/completions`. */
    fun endpointFor(baseUrl: String): String {
        val base = baseUrl.trim().ifEmpty { DEFAULT_BASE_URL }.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    override fun buildRequest(text: String, config: TranslationConfig): Request {
        val key = config.apiKey.trim()
        val baseUrl = config.openAiBaseUrl.trim()
        // A local OpenAI-compatible server usually needs no key; the hosted API always does.
        if (key.isEmpty() && baseUrl.isEmpty()) {
            throw TranslationException("OpenAI: no API key configured")
        }

        val model = config.model.trim().ifEmpty { defaultModel }
        val prompt = renderPrompt(
            config.prompt.trim().ifEmpty { TranslationConfig.DEFAULT_PROMPT },
            text,
            config,
        )
        val payload = Payload(
            model = model,
            messages = listOf(
                Message(
                    role = "system",
                    content = when {
                        config.systemPrompt.isNotBlank() -> renderSystemPrompt(config.systemPrompt, config)
                        config.hasCustomPrompt -> FREEFORM_SYSTEM_PROMPT
                        else -> TRANSLATE_ONLY_SYSTEM_PROMPT
                    },
                ),
                Message(role = "user", content = prompt),
            ),
            temperature = 0.2f,
        )

        val builder = Request.Builder()
            .url(endpointFor(baseUrl))
            .header("Content-Type", "application/json")
            .post(translationJson.encodeToString(payload).toRequestBody(jsonMediaType))
        if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
        return builder.build()
    }

    override fun parseResponse(body: String, config: TranslationConfig): TranslationResult {
        val parsed = try {
            translationJson.decodeFromString<Response>(body)
        } catch (e: Exception) {
            throw TranslationException("OpenAI: unreadable response${body.summarize()}", e)
        }
        val choice = parsed.choices.firstOrNull()
            ?: throw TranslationException("OpenAI: no choices returned")
        val text = cleanLlmOutput(choice.message?.content.orEmpty())
        if (text.isBlank()) throw TranslationException("OpenAI: empty translation")
        return TranslationResult(text = text, provider = id)
    }

    override fun describeHttpError(code: Int, body: String): String = when (code) {
        401 -> "OpenAI: invalid API key"
        404 -> "OpenAI: unknown model or endpoint${body.summarize()}"
        429 -> "OpenAI: rate limited or out of quota"
        in 500..599 -> "OpenAI: service error (HTTP $code)"
        else -> "OpenAI: HTTP $code${body.summarize()}"
    }
}
