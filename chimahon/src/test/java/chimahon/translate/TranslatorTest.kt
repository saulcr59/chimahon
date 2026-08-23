package chimahon.translate

import okio.Buffer
import okhttp3.Request
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TranslatorTest {

    private fun Request.bodyAsString(): String {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    // ── DeepL ────────────────────────────────────────────────────────────────

    @Test
    fun `deepl free keys hit the free endpoint`() {
        Assertions.assertEquals(
            "https://api-free.deepl.com/v2/translate",
            DeepLTranslator.endpointFor("abcdef:fx"),
        )
        Assertions.assertEquals(
            "https://api.deepl.com/v2/translate",
            DeepLTranslator.endpointFor("abcdef"),
        )
    }

    @Test
    fun `deepl request carries auth header and language codes`() {
        val request = DeepLTranslator.buildRequest(
            "今日はいい天気ですね",
            TranslationConfig(
                provider = TranslationProviders.DEEPL,
                apiKey = "key:fx",
                targetLanguage = "es",
                sourceLanguage = "ja",
            ),
        )

        Assertions.assertEquals("DeepL-Auth-Key key:fx", request.header("Authorization"))
        val body = request.bodyAsString()
        Assertions.assertTrue(body.contains("\"target_lang\":\"ES\""), body)
        Assertions.assertTrue(body.contains("\"source_lang\":\"JA\""), body)
        Assertions.assertTrue(body.contains("今日は"), body)
    }

    @Test
    fun `deepl omits source_lang for unknown profile languages`() {
        val request = DeepLTranslator.buildRequest(
            "hola",
            TranslationConfig(apiKey = "key:fx", targetLanguage = "EN-US", sourceLanguage = "xx"),
        )
        Assertions.assertFalse(request.bodyAsString().contains("source_lang"))
    }

    @Test
    fun `deepl missing key fails before any request is built`() {
        val error = assertThrows<TranslationException> {
            DeepLTranslator.buildRequest("text", TranslationConfig(apiKey = "  "))
        }
        Assertions.assertTrue(error.message!!.contains("no API key"), error.message)
    }

    @Test
    fun `deepl parses translation and detected language`() {
        val result = DeepLTranslator.parseResponse(
            """{"translations":[{"detected_source_language":"JA","text":"Hoy hace buen tiempo"}]}""",
            TranslationConfig(),
        )
        Assertions.assertEquals("Hoy hace buen tiempo", result.text)
        Assertions.assertEquals("JA", result.detectedSourceLanguage)
        Assertions.assertEquals(TranslationProviders.DEEPL, result.provider)
    }

    @Test
    fun `deepl empty translations list is an error`() {
        assertThrows<TranslationException> {
            DeepLTranslator.parseResponse("""{"translations":[]}""", TranslationConfig())
        }
    }

    @Test
    fun `deepl names the quota error`() {
        Assertions.assertTrue(
            DeepLTranslator.describeHttpError(456, "").contains("quota"),
        )
    }

    // ── Gemini ───────────────────────────────────────────────────────────────

    @Test
    fun `gemini sends the key as a header and never in the url`() {
        val request = GeminiTranslator.buildRequest(
            "今日は",
            TranslationConfig(
                provider = TranslationProviders.GEMINI,
                apiKey = "secret",
                targetLanguage = "ES",
                sourceLanguage = "ja",
            ),
        )
        Assertions.assertEquals("secret", request.header("x-goog-api-key"))
        Assertions.assertFalse(request.url.toString().contains("secret"))
        Assertions.assertTrue(
            request.url.toString().endsWith("/models/gemini-2.5-flash:generateContent"),
            request.url.toString(),
        )
    }

    @Test
    fun `gemini prompt resolves source and target names`() {
        val request = GeminiTranslator.buildRequest(
            "今日は",
            TranslationConfig(
                provider = TranslationProviders.GEMINI,
                apiKey = "k",
                targetLanguage = "ES",
                sourceLanguage = "ja",
            ),
        )
        val body = request.bodyAsString()
        Assertions.assertTrue(body.contains("Japanese"), body)
        Assertions.assertTrue(body.contains("Spanish"), body)
        Assertions.assertFalse(body.contains("{text}"), body)
    }

    @Test
    fun `gemini honours a custom model`() {
        val request = GeminiTranslator.buildRequest(
            "text",
            TranslationConfig(apiKey = "k", model = "gemini-2.5-pro"),
        )
        Assertions.assertTrue(request.url.toString().contains("gemini-2.5-pro"))
    }

    @Test
    fun `gemini joins candidate parts`() {
        val result = GeminiTranslator.parseResponse(
            """{"candidates":[{"content":{"parts":[{"text":"Hoy "},{"text":"hace sol"}]}}]}""",
            TranslationConfig(),
        )
        Assertions.assertEquals("Hoy hace sol", result.text)
    }

    @Test
    fun `gemini reports a blocked prompt`() {
        val error = assertThrows<TranslationException> {
            GeminiTranslator.parseResponse(
                """{"promptFeedback":{"blockReason":"SAFETY"}}""",
                TranslationConfig(),
            )
        }
        Assertions.assertTrue(error.message!!.contains("SAFETY"), error.message)
    }

    @Test
    fun `gemini reports the finish reason on an empty answer`() {
        val error = assertThrows<TranslationException> {
            GeminiTranslator.parseResponse(
                """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""",
                TranslationConfig(),
            )
        }
        Assertions.assertTrue(error.message!!.contains("MAX_TOKENS"), error.message)
    }

    // ── OpenAI ───────────────────────────────────────────────────────────────

    @Test
    fun `openai normalizes base urls`() {
        Assertions.assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiTranslator.endpointFor(""),
        )
        Assertions.assertEquals(
            "http://localhost:8080/v1/chat/completions",
            OpenAiTranslator.endpointFor("http://localhost:8080"),
        )
        Assertions.assertEquals(
            "http://localhost:8080/v1/chat/completions",
            OpenAiTranslator.endpointFor("http://localhost:8080/v1/"),
        )
        Assertions.assertEquals(
            "http://localhost:8080/v1/chat/completions",
            OpenAiTranslator.endpointFor("http://localhost:8080/v1/chat/completions"),
        )
    }

    @Test
    fun `openai sends a bearer token when a key is set`() {
        val request = OpenAiTranslator.buildRequest(
            "text",
            TranslationConfig(provider = TranslationProviders.OPENAI, apiKey = "sk-test"),
        )
        Assertions.assertEquals("Bearer sk-test", request.header("Authorization"))
    }

    @Test
    fun `openai allows a keyless local server`() {
        val request = OpenAiTranslator.buildRequest(
            "text",
            TranslationConfig(
                provider = TranslationProviders.OPENAI,
                apiKey = "",
                openAiBaseUrl = "http://192.168.1.20:8080",
            ),
        )
        Assertions.assertNull(request.header("Authorization"))
        Assertions.assertEquals(
            "http://192.168.1.20:8080/v1/chat/completions",
            request.url.toString(),
        )
    }

    @Test
    fun `openai without key or base url fails`() {
        assertThrows<TranslationException> {
            OpenAiTranslator.buildRequest("text", TranslationConfig(provider = TranslationProviders.OPENAI))
        }
    }

    @Test
    fun `openai parses the first choice`() {
        val result = OpenAiTranslator.parseResponse(
            """{"choices":[{"message":{"role":"assistant","content":"Hoy hace buen tiempo"}}]}""",
            TranslationConfig(),
        )
        Assertions.assertEquals("Hoy hace buen tiempo", result.text)
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    @Test
    fun `llm output loses wrapping quotes and code fences`() {
        Assertions.assertEquals("Hoy hace sol", cleanLlmOutput("\"Hoy hace sol\""))
        Assertions.assertEquals("Hoy hace sol", cleanLlmOutput("```\nHoy hace sol\n```"))
        Assertions.assertEquals("Hoy hace sol", cleanLlmOutput("  Hoy hace sol  "))
        // A sentence that legitimately contains quotes is left alone.
        Assertions.assertEquals(
            "Dijo \"hola\" y se fue",
            cleanLlmOutput("Dijo \"hola\" y se fue"),
        )
    }

    @Test
    fun `prompt without a text placeholder still gets the sentence`() {
        val rendered = renderPrompt("Translate to {target}:", "今日は", TranslationConfig())
        Assertions.assertTrue(rendered.endsWith("今日は"), rendered)
    }

    @Test
    fun `unknown provider is rejected`() {
        assertThrows<TranslationException> { Translator.forProvider("babelfish") }
    }
}
