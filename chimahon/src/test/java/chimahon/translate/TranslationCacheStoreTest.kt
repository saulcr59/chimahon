package chimahon.translate

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TranslationCacheStoreTest {

    @TempDir
    lateinit var dir: File

    private fun entry(key: String, text: String) = key to TranslationResult(
        text = text,
        provider = TranslationProviders.OPENAI,
    )

    @Test
    fun `entries survive a round trip in the same order`() {
        val file = File(dir, "cache.json")
        val entries = listOf(entry("a", "uno"), entry("b", "dos"), entry("c", "tres"))

        TranslationCacheStore.save(file, entries)
        val loaded = TranslationCacheStore.load(file)

        // Order matters: it is the LRU order, eldest first.
        Assertions.assertEquals(listOf("a", "b", "c"), loaded.map { it.first })
        Assertions.assertEquals("dos", loaded[1].second.text)
    }

    @Test
    fun `a structured breakdown survives the round trip`() {
        val file = File(dir, "cache.json")
        val breakdown = SentenceBreakdown(
            translation = "Hoy hace buen tiempo.",
            chunks = listOf(BreakdownChunk("今日は", "hoy", "は marca el tema.")),
            note = "El tema no es el sujeto.",
        )
        TranslationCacheStore.save(
            file,
            listOf("k" to TranslationResult("plano", TranslationProviders.OPENAI, breakdown = breakdown)),
        )

        val loaded = TranslationCacheStore.load(file).single().second
        Assertions.assertEquals(breakdown, loaded.breakdown)
        Assertions.assertEquals("今日は", loaded.breakdown!!.chunks.single().chunk)
    }

    @Test
    fun `a missing file is simply empty`() {
        Assertions.assertTrue(TranslationCacheStore.load(File(dir, "nope.json")).isEmpty())
    }

    @Test
    fun `a corrupt file is discarded rather than thrown`() {
        val file = File(dir, "cache.json")
        file.writeText("{ this is not json")
        Assertions.assertTrue(TranslationCacheStore.load(file).isEmpty())
    }

    @Test
    fun `a file from another version is discarded`() {
        val file = File(dir, "cache.json")
        file.writeText("""{"version":999,"entries":[{"key":"a","result":{"text":"x","provider":"openai"}}]}""")
        Assertions.assertTrue(TranslationCacheStore.load(file).isEmpty())
    }

    @Test
    fun `saving twice replaces rather than appends`() {
        val file = File(dir, "cache.json")
        TranslationCacheStore.save(file, listOf(entry("a", "uno"), entry("b", "dos")))
        TranslationCacheStore.save(file, listOf(entry("c", "tres")))

        val loaded = TranslationCacheStore.load(file)
        Assertions.assertEquals(listOf("c"), loaded.map { it.first })
        Assertions.assertFalse(File(dir, "cache.json.tmp").exists())
    }
}
