package chimahon.translate

/** Identifiers of the supported sentence-translation backends. */
object TranslationProviders {
    const val DEEPL = "deepl"
    const val GEMINI = "gemini"
    const val OPENAI = "openai"

    val ALL = listOf(DEEPL, GEMINI, OPENAI)

    fun displayName(provider: String): String = when (provider) {
        DEEPL -> "DeepL"
        GEMINI -> "Gemini"
        OPENAI -> "OpenAI"
        else -> provider
    }

    /** LLM backends take a model name and a free-form prompt; DeepL does not. */
    fun isLlm(provider: String): Boolean = provider == GEMINI || provider == OPENAI
}

/**
 * Everything a [Translator] needs for one request.
 *
 * [targetLanguage] is a DeepL-style code ("ES", "EN-US", …) for every provider;
 * the LLM translators turn it into a language name via [TranslationLanguages].
 */
data class TranslationConfig(
    val provider: String = TranslationProviders.DEEPL,
    val apiKey: String = "",
    val targetLanguage: String = "EN-US",
    /** Two-letter source hint ("ja", "ko", …) taken from the active profile. Blank = autodetect. */
    val sourceLanguage: String = "",
    /** Model name for LLM providers. Blank falls back to the translator's default. */
    val model: String = "",
    /** Prompt template for LLM providers. Blank falls back to [DEFAULT_PROMPT]. */
    val prompt: String = "",
    /**
     * Base URL override for the OpenAI-compatible provider, so a local server
     * (llama.cpp, LM Studio, Ollama, vLLM …) can be used instead of api.openai.com.
     * Blank means the official endpoint.
     */
    val openAiBaseUrl: String = "",
) {
    val hasCredentials: Boolean
        get() = apiKey.isNotBlank() || (provider == TranslationProviders.OPENAI && openAiBaseUrl.isNotBlank())

    companion object {
        const val DEFAULT_PROMPT =
            "Translate the following {source} sentence into {target}.\n" +
                "Reply with the translation only — no explanations, no notes, no romanization, " +
                "no quotes around it.\n\n{text}"
    }
}

/** A finished translation, ready to be shown under the sentence. */
data class TranslationResult(
    val text: String,
    val provider: String,
    /** Source language reported by the backend, when it reports one. */
    val detectedSourceLanguage: String? = null,
)

/** Any failure worth showing the user in the popup, with an already-readable message. */
class TranslationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Target languages offered in settings. Codes are DeepL's, because DeepL is the
 * strictest consumer of them; the LLM providers only need the display name.
 */
object TranslationLanguages {
    val TARGETS: List<Pair<String, String>> = listOf(
        "ES" to "Spanish",
        "EN-US" to "English (American)",
        "EN-GB" to "English (British)",
        "PT-BR" to "Portuguese (Brazilian)",
        "PT-PT" to "Portuguese",
        "FR" to "French",
        "DE" to "German",
        "IT" to "Italian",
        "NL" to "Dutch",
        "PL" to "Polish",
        "RU" to "Russian",
        "UK" to "Ukrainian",
        "TR" to "Turkish",
        "SV" to "Swedish",
        "DA" to "Danish",
        "FI" to "Finnish",
        "NB" to "Norwegian",
        "CS" to "Czech",
        "EL" to "Greek",
        "HU" to "Hungarian",
        "ID" to "Indonesian",
        "RO" to "Romanian",
        "JA" to "Japanese",
        "KO" to "Korean",
        "ZH" to "Chinese",
    )

    private val SOURCE_NAMES: Map<String, String> = mapOf(
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "ar" to "Arabic",
        "en" to "English",
        "de" to "German",
        "fr" to "French",
        "ru" to "Russian",
        "es" to "Spanish",
        "it" to "Italian",
    )

    fun targetName(code: String): String =
        TARGETS.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second ?: code

    /** English name of a profile language code, or null when it is blank/unknown. */
    fun sourceName(code: String): String? =
        SOURCE_NAMES[code.lowercase().substringBefore('-')]

    /**
     * DeepL only accepts base source codes (no regional variants) and rejects
     * unknown ones, so map through the known set and drop anything else.
     */
    fun deepLSourceCode(code: String): String? =
        SOURCE_NAMES[code.lowercase().substringBefore('-')]?.let { code.substringBefore('-').uppercase() }
}
