package chimahon.translate

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Disk backing for [TranslationService]'s cache.
 *
 * The in-memory cache alone already spares the API when the reader taps a
 * second word in a sentence it has seen — entries are keyed by the sentence,
 * not the word — but it dies with the process. Persisting it means a sentence
 * stays free across app restarts too.
 *
 * Entries are written eldest-first so the LRU order survives a reload.
 */
internal object TranslationCacheStore {

    /** Bumped when the entry shape changes; an older file is discarded. */
    private const val VERSION = 1

    @Serializable
    private data class CacheFile(
        val version: Int = VERSION,
        val entries: List<CacheEntry> = emptyList(),
    )

    @Serializable
    private data class CacheEntry(val key: String, val result: TranslationResult)

    /**
     * Returns the stored entries in eldest-first order, or an empty list when
     * the file is missing, unreadable or written by an older version. A broken
     * cache is never worth an error — it just means paying for one more call.
     */
    fun load(file: File): List<Pair<String, TranslationResult>> {
        if (!file.isFile) return emptyList()
        return try {
            val parsed = translationJson.decodeFromString<CacheFile>(file.readText())
            if (parsed.version != VERSION) return emptyList()
            parsed.entries.map { it.key to it.result }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Writes via a temporary file so a crash mid-write cannot corrupt the cache. */
    fun save(file: File, entries: List<Pair<String, TranslationResult>>) {
        try {
            file.parentFile?.mkdirs()
            val payload = CacheFile(entries = entries.map { CacheEntry(it.first, it.second) })
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(translationJson.encodeToString(payload))
            if (!tmp.renameTo(file)) {
                // renameTo will not clobber an existing file on some filesystems.
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // A cache that cannot be written is not worth failing a translation over.
        }
    }
}
