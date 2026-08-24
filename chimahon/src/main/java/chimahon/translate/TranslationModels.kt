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
 * Which of the popup's two translation buttons a request belongs to.
 *
 * The pair exists so a clean machine translation and a wordier LLM answer can
 * sit side by side: DeepL on [PRIMARY] for the sentence, an LLM on [SECONDARY]
 * with a custom prompt for a grammar breakdown. Both slots share the per-provider
 * API keys; only the provider and the prompt differ.
 */
enum class TranslationSlot { PRIMARY, SECONDARY }

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
     * Verbatim system-role instructions for LLM providers. Blank lets the
     * translator pick its own. Placeholders are expanded like [prompt], minus
     * `{text}` — the sentence belongs in the user message.
     */
    val systemPrompt: String = "",
    /**
     * Base URL override for the OpenAI-compatible provider, so a local server
     * (llama.cpp, LM Studio, Ollama, vLLM …) can be used instead of api.openai.com.
     * Blank means the official endpoint.
     */
    val openAiBaseUrl: String = "",
    /**
     * Whether to pin `temperature`. Reasoning-tier models reject anything but
     * their default and answer 400, so [TranslationService] clears this and
     * retries once rather than making the user know which models those are.
     */
    val sendTemperature: Boolean = true,
    /**
     * Ask the backend for JSON matching [BREAKDOWN_SCHEMA] instead of describing
     * the layout in the prompt. OpenAI-only; other providers ignore it.
     */
    val structured: Boolean = false,
) {
    val hasCredentials: Boolean
        get() = apiKey.isNotBlank() || (provider == TranslationProviders.OPENAI && openAiBaseUrl.isNotBlank())

    /**
     * A prompt the user wrote themselves asks for something other than a bare
     * translation, so the LLM backends must not bolt a "translation only"
     * system prompt on top of it.
     */
    val hasCustomPrompt: Boolean
        get() = prompt.isNotBlank()

    companion object {
        const val DEFAULT_PROMPT =
            "Translate the following {source} sentence into {target}.\n" +
                "Reply with the translation only — no explanations, no notes, no romanization, " +
                "no quotes around it.\n\n{text}"

        /** Both buttons send the bare sentence; the instructions live in the system prompt. */
        const val DEFAULT_SENTENCE_ONLY_PROMPT = "{text}"

        /**
         * Default system prompt for the main button. Modelled on Migaku's
         * sentence-translation prompt; its `[TARGET_LANG]` means the language to
         * translate *into*, so it maps onto `{target}`.
         */
        const val DEFAULT_TRANSLATE_SYSTEM_PROMPT =
            "You are a language translation API.\n" +
                "RESPOND ONLY with the translated text.\n" +
                "MAINTAIN the EXACT punctuation of the original text.\n" +
                "DO NOT RESPOND with enclosing quotations unless the original text has them.\n" +
                "Sentence MUST BE translated into this SPECIFIC language: {target}."

        /**
         * System prompt for structured mode. Much shorter than its prose
         * counterpart because the schema already carries the layout — all that
         * is left is the linguistic instruction.
         */
        const val DEFAULT_STRUCTURED_SYSTEM_PROMPT =
            "You are a grammar-explanation API inside a reading app for language learners.\n" +
                "The user sends one {source} sentence. Explain it for a learner who reads {target}.\n" +
                "Write every explanation in {target}. {source} belongs only in the chunk field " +
                "and when naming a dictionary form.\n" +
                "Do not output romaji, furigana, readings or pronunciations of any kind."

        /**
         * Default system prompt for the second button: a chunk-by-chunk
         * breakdown built for study rather than for a cramped tooltip.
         *
         * Descends from Migaku's sentence-breakdown prompt but drops its
         * "output no text in the studied language" rule, which contradicted its
         * own "chunk must be in its original language" rule. Measured against
         * gpt-5.6-*, that contradiction was not harmless: one model resolved it
         * by dropping the Japanese chunks entirely and another stopped naming
         * dictionary forms. Japanese is now explicitly allowed exactly where a
         * learner needs it — the chunk itself and dictionary forms.
         */
        const val DEFAULT_BREAKDOWN_SYSTEM_PROMPT =
            "You are a grammar-explanation API inside a reading app for language learners.\n" +
                "The user sends one {source} sentence. Explain it for a learner who reads {target}.\n" +
                "\n" +
                "Output EXACTLY these sections, in this order, and nothing else:\n" +
                "\n" +
                "TRANSLATION\n" +
                "One natural {target} rendering of the whole sentence.\n" +
                "\n" +
                "BREAKDOWN\n" +
                "One group per meaningful chunk, in reading order, together covering the whole " +
                "sentence.\n" +
                "Each group is exactly three lines:\n" +
                "#1 the chunk, in {source}, exactly as it appears in the sentence\n" +
                "#2 its meaning in {target}, reworded rather than a word-for-word gloss\n" +
                "#3 the grammar in {target}: the role of each particle, the dictionary form of " +
                "any verb or adjective, and every transformation applied to reach the surface form\n" +
                "ALWAYS restart the count at #1 for each group. NEVER count past #3.\n" +
                "\n" +
                "NOTE\n" +
                "One or two sentences in {target} on what a learner is most likely to get wrong " +
                "here: register, nuance, an idiom, or a structure with no clean {target} " +
                "equivalent. Omit this section entirely if there is nothing worth flagging.\n" +
                "\n" +
                "Rules:\n" +
                "{source} appears ONLY in the #1 field and when naming a dictionary form inside #3.\n" +
                "DO NOT output romaji, furigana, readings, or pronunciations of any kind.\n" +
                "DO NOT output bullet points, dashes, or headings other than the three above.\n" +
                "DO NOT repeat the original sentence outside the #1 fields.\n" +
                "DO NOT output quotation marks in the #1 or #2 fields."
    }
}

/** A finished translation, ready to be shown under the sentence. */
data class TranslationResult(
    val text: String,
    val provider: String,
    /** Source language reported by the backend, when it reports one. */
    val detectedSourceLanguage: String? = null,
    /**
     * Set only when the backend answered in [BREAKDOWN_SCHEMA] form, letting the
     * popup lay the sections out itself. [text] always holds a readable
     * flattening of the same content.
     */
    val breakdown: SentenceBreakdown? = null,
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
