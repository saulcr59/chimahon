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

    /** One of [TranslationProviders]. */
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
}
