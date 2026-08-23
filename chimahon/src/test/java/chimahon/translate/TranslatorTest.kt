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

    @Test
    fun `openai drops the translate-only system prompt when the user wrote their own`() {
        val translateOnly = OpenAiTranslator
            .buildRequest("text", TranslationConfig(apiKey = "k"))
            .bodyAsString()
        Assertions.assertTrue(translateOnly.contains("Output only the translation"), translateOnly)

        // A grammar prompt must not be fought by a system prompt forbidding commentary.
        val custom = OpenAiTranslator
            .buildRequest("text", TranslationConfig(apiKey = "k", prompt = "Explain the grammar of {text}"))
            .bodyAsString()
        Assertions.assertFalse(custom.contains("Output only the translation"), custom)
        Assertions.assertTrue(custom.contains("Follow the user's instructions"), custom)
    }

    // ── Two-slot setup ───────────────────────────────────────────────────────

    @Test
    fun `a blank prompt is not a custom prompt`() {
        Assertions.assertFalse(TranslationConfig().hasCustomPrompt)
        Assertions.assertFalse(TranslationConfig(prompt = "   ").hasCustomPrompt)
        Assertions.assertTrue(TranslationConfig(prompt = "Explain").hasCustomPrompt)
    }

    @Test
    fun `breakdown system prompt names both languages`() {
        val rendered = renderSystemPrompt(
            TranslationConfig.DEFAULT_BREAKDOWN_SYSTEM_PROMPT,
            TranslationConfig(targetLanguage = "ES", sourceLanguage = "ja"),
        )
        // Explanations in Spanish, and no Japanese in the output fields.
        Assertions.assertTrue(rendered.contains("in language Spanish"), rendered)
        Assertions.assertTrue(rendered.contains("DO NOT OUTPUT any text in Japanese"), rendered)
    }

    @Test
    fun `translate system prompt targets the output language`() {
        val rendered = renderSystemPrompt(
            TranslationConfig.DEFAULT_TRANSLATE_SYSTEM_PROMPT,
            TranslationConfig(targetLanguage = "ES", sourceLanguage = "ja"),
        )
        Assertions.assertTrue(rendered.endsWith("SPECIFIC language: Spanish."), rendered)
    }

    @Test
    fun `system prompts never carry the sentence`() {
        // {text} belongs in the user message; leaving it in the system role
        // would send the sentence twice.
        val rendered = renderSystemPrompt("Do the thing with {text}", TranslationConfig())
        Assertions.assertFalse(rendered.contains("{text}"), rendered)
        Assertions.assertEquals("Do the thing with", rendered)
    }

    @Test
    fun `openai puts a configured system prompt in the system role`() {
        val body = OpenAiTranslator.buildRequest(
            "今日は",
            TranslationConfig(
                apiKey = "k",
                targetLanguage = "ES",
                systemPrompt = "Reply only in {target}.",
                prompt = TranslationConfig.DEFAULT_SENTENCE_ONLY_PROMPT,
            ),
        ).bodyAsString()
        Assertions.assertTrue(body.contains("Reply only in Spanish."), body)
        Assertions.assertFalse(body.contains("Output only the translation"), body)
    }

    @Test
    fun `gemini sends a configured system prompt as systemInstruction`() {
        val withSystem = GeminiTranslator.buildRequest(
            "今日は",
            TranslationConfig(apiKey = "k", targetLanguage = "ES", systemPrompt = "Reply only in {target}."),
        ).bodyAsString()
        Assertions.assertTrue(withSystem.contains("systemInstruction"), withSystem)
        Assertions.assertTrue(withSystem.contains("Reply only in Spanish."), withSystem)

        // Blank system prompt must not add an empty instruction block.
        val without = GeminiTranslator
            .buildRequest("今日は", TranslationConfig(apiKey = "k"))
            .bodyAsString()
        Assertions.assertFalse(without.contains("systemInstruction"), without)
    }

    @Test
    fun `the two slots can share a provider without colliding`() {
        // Same provider and model, different prompts — the requests must differ,
        // which is what keeps the service cache from serving one for the other.
        val translate = GeminiTranslator
            .buildRequest("今日は", TranslationConfig(apiKey = "k", prompt = ""))
            .bodyAsString()
        val grammar = GeminiTranslator
            .buildRequest(
                "今日は",
                TranslationConfig(
                    apiKey = "k",
                    prompt = TranslationConfig.DEFAULT_SENTENCE_ONLY_PROMPT,
                    systemPrompt = TranslationConfig.DEFAULT_BREAKDOWN_SYSTEM_PROMPT,
                ),
            )
            .bodyAsString()
        Assertions.assertNotEquals(translate, grammar)
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
