package chimahon.translate

import kotlinx.serialization.Serializable

/**
 * A chunk-by-chunk analysis of one sentence, returned by an LLM that was asked
 * for JSON matching [BREAKDOWN_SCHEMA] rather than for a prose layout.
 *
 * Measured over eight sentences, asking gpt-5.6-luna for this shape instead of
 * describing the layout in the prompt took format compliance from 5/8 to 8/8
 * while cutting output tokens by 42% — the model stops padding with prose it
 * was never asked for.
 */
@Serializable
data class SentenceBreakdown(
    val translation: String = "",
    val chunks: List<BreakdownChunk> = emptyList(),
    val note: String? = null,
) {
    val isUsable: Boolean
        get() = translation.isNotBlank() || chunks.isNotEmpty()

    /**
     * Flattens back to the prose layout, so the result can still be shown by
     * anything that only understands text.
     */
    fun toPlainText(): String = buildString {
        if (translation.isNotBlank()) {
            append(translation)
        }
        chunks.forEach { chunk ->
            if (isNotEmpty()) append("\n\n")
            append(chunk.chunk).append('\n')
            append(chunk.meaning).append('\n')
            append(chunk.grammar)
        }
        note?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append("\n\n")
            append(it)
        }
    }
}

@Serializable
data class BreakdownChunk(
    val chunk: String = "",
    val meaning: String = "",
    val grammar: String = "",
)

/**
 * The `json_schema` body for OpenAI Structured Outputs. `strict` mode requires
 * every property to be listed in `required` and `additionalProperties` to be
 * false, so an optional field is expressed as a nullable type instead.
 */
internal const val BREAKDOWN_SCHEMA = """
{
  "name": "sentence_breakdown",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {
      "translation": {
        "type": "string",
        "description": "One natural rendering of the whole sentence in the explanation language."
      },
      "chunks": {
        "type": "array",
        "description": "Meaningful chunks in reading order, together covering the whole sentence.",
        "items": {
          "type": "object",
          "properties": {
            "chunk": {
              "type": "string",
              "description": "The chunk in the studied language, exactly as it appears in the sentence."
            },
            "meaning": {
              "type": "string",
              "description": "Its meaning in the explanation language, reworded rather than a word-for-word gloss."
            },
            "grammar": {
              "type": "string",
              "description": "The role of each particle, the dictionary form of any verb or adjective, and every transformation applied to reach the surface form."
            }
          },
          "required": ["chunk", "meaning", "grammar"],
          "additionalProperties": false
        }
      },
      "note": {
        "type": ["string", "null"],
        "description": "What a learner is most likely to get wrong here, or null when there is nothing worth flagging."
      }
    },
    "required": ["translation", "chunks", "note"],
    "additionalProperties": false
  }
}
"""
