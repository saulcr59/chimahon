package chimahon.translate

import tachiyomi.core.common.preference.Preference

/**
 * Settings backing the sentence-translation button.
 * Implemented by the app's `DictionaryPreferences`, mirroring
 * [chimahon.audio.WordAudioPreferences].
 */
interface TranslationPreferences {
    /** Master switch — when off, the popup shows no translate button at all. */
    fun translationEnabled(): Preference<Boolean>

    /** Translate as soon as the popup opens, instead of waiting for a tap. */
    fun translationAutoTranslate(): Preference<Boolean>

    /** One of [TranslationProviders]. Backs the popup's main button. */
    fun translationProvider(): Preference<String>

    /** DeepL-style target language code, e.g. "ES". */
    fun translationTargetLanguage(): Preference<String>

    fun translationDeepLApiKey(): Preference<String>
    fun translationGeminiApiKey(): Preference<String>
    fun translationOpenAiApiKey(): Preference<String>

    fun translationGeminiModel(): Preference<String>
    fun translationOpenAiModel(): Preference<String>

    /** Blank = official OpenAI endpoint; set it to target a local/compatible server. */
    fun translationOpenAiBaseUrl(): Preference<String>

    /** Prompt template for the LLM providers. Blank = [TranslationConfig.DEFAULT_PROMPT]. */
    fun translationPrompt(): Preference<String>

    /** System-role instructions for the main button. Blank = the translator's own. */
    fun translationSystemPrompt(): Preference<String>

    // -------------------------------------------------------------------------
    // Second button — off by default, so the popup keeps a single button until
    // the user asks for two. Shares the API keys and target language above.
    // -------------------------------------------------------------------------

    /** One of [TranslationProviders], or blank to hide the second button. */
    fun translationSecondaryProvider(): Preference<String>

    /** User message for the second button. Blank = [TranslationConfig.DEFAULT_SECONDARY_PROMPT]. */
    fun translationSecondaryPrompt(): Preference<String>

    /**
     * System-role instructions for the second button.
     * Blank = [TranslationConfig.DEFAULT_BREAKDOWN_SYSTEM_PROMPT].
     */
    fun translationSecondarySystemPrompt(): Preference<String>

    /**
     * Ask OpenAI for a schema-shaped answer instead of a prose layout, and lay
     * the sections out in the popup. Ignored by the other providers.
     */
    fun translationSecondaryStructured(): Preference<Boolean>

    /** Caption on the second button. Blank = "Breakdown". */
    fun translationSecondaryLabel(): Preference<String>
}
