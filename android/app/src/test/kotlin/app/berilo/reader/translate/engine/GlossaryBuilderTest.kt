package app.berilo.reader.translate.engine

import app.berilo.reader.translate.model.bookHash
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-book glossary pass (B5), ported from `translator/berilo/glossary.py`.
 *
 * The load-bearing assertion is [`the derived prompt version matches the CLI's byte for byte`]:
 * the version is *derived* from the extraction prompt and its sampling parameters, so a prompt
 * edit correctly misses the cache instead of silently returning old terms (CLAUDE.md §9).
 */
class GlossaryBuilderTest {

    /**
     * Pinned against the value the live Python module derives
     * (`berilo.glossary.GLOSSARY_PROMPT_VERSION`, and `cache.py`'s
     * `BASELINE_GLOSSARY_PROMPT_VERSION`). A single byte of drift in the ported system prompt or
     * template changes this — which is the whole point: the tablet and the workstation must key
     * the same glossary rows, or a book already paid for is re-extracted.
     */
    @Test
    fun `the derived prompt version matches the CLI's byte for byte`() {
        assertEquals("glossary_3512dce61808", GLOSSARY_PROMPT_VERSION)
        assertEquals("glossary_3512dce61808", glossaryPromptVersion())
    }

    /**
     * **Mutation-proof:** hard-coding the version string instead of deriving it turns this red —
     * changing a sampling parameter would then be a silent no-op on the next run.
     */
    @Test
    fun `changing any sampling parameter changes the derived version`() {
        assertNotEquals(GLOSSARY_PROMPT_VERSION, glossaryPromptVersion(sampleChapters = 7))
        assertNotEquals(GLOSSARY_PROMPT_VERSION, glossaryPromptVersion(maxSampleChars = 11_000))
        assertNotEquals(GLOSSARY_PROMPT_VERSION, glossaryPromptVersion(maxTerms = 79))
    }

    @Test
    fun `a cached glossary is served without any API call`() =
        runTest {
            val book = chapterOf(3)
            val cache = InMemoryTranslationCache()
            cache.storeGlossary(
                bookHash = bookHash(book),
                model = TEST_MODEL,
                lang = "sl",
                terms = mapOf("Kaplan" to "Kaplan"),
                call = null,
                promptVersion = GLOSSARY_PROMPT_VERSION,
            )
            val client = ScriptedLlmClient(emptyList())

            val glossary = buildGlossary(book, client, "sl", TEST_MODEL, cache)

            assertEquals(0, client.callCount)
            assertEquals(mapOf("Kaplan" to "Kaplan"), glossary.terms)
        }

    @Test
    fun `extraction makes exactly one call and caches the result`() =
        runTest {
            val book = chapterOf(3)
            val cache = InMemoryTranslationCache()
            val client =
                ScriptedLlmClient(
                    listOf(ScriptedReply.Text("""{"Kaplan": "Kaplan", "the West": "Zahod"}""")),
                )

            val glossary = buildGlossary(book, client, "sl", TEST_MODEL, cache)

            assertEquals(1, client.callCount)
            assertEquals(mapOf("Kaplan" to "Kaplan", "the West" to "Zahod"), glossary.terms)
            assertEquals(
                "the extraction call is accounted for",
                listOf(CALL_KIND_GLOSSARY),
                cache.calls.map(CallRecord::kind),
            )

            // A second build serves the cache — the pass runs at most once per book.
            val second = ScriptedLlmClient(emptyList())
            assertEquals(
                glossary.terms,
                buildGlossary(book, second, "sl", TEST_MODEL, cache).terms,
            )
            assertEquals(0, second.callCount)
        }

    @Test
    fun `the extraction prompt carries the target language and sampled source text`() =
        runTest {
            val book = chapterOf(3, prefix = "Distinctive")
            val client = ScriptedLlmClient(listOf(ScriptedReply.Text("{}")))

            buildGlossary(book, client, "sl", TEST_MODEL, null)

            val (prompt, system) = client.calls.single()
            assertTrue(prompt.startsWith("Target language: sl."))
            assertTrue(prompt.contains("Distinctive 1."))
            assertEquals(GLOSSARY_SYSTEM, system)
        }

    @Test
    fun `a book with no text makes no call at all`() =
        runTest {
            val book = bookOf(0 to "   ", 0 to "")
            val client = ScriptedLlmClient(emptyList())

            val glossary = buildGlossary(book, client, "sl", TEST_MODEL, null)

            assertEquals(0, client.callCount)
            assertTrue(glossary.isEmpty())
        }

    // ---------------------------------------------------------------------------------------
    // Reply parsing (mirrors `_parse_glossary_json`)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `stray prose around the JSON object is tolerated`() {
        val terms = parseGlossaryJson("Here you go:\n```json\n{\"A\": \"B\"}\n```\nHope that helps.")

        assertEquals(mapOf("A" to "B"), terms)
    }

    @Test
    fun `a reply with no JSON object yields an empty glossary rather than failing`() {
        assertEquals(emptyMap<String, String>(), parseGlossaryJson("I could not do that."))
    }

    @Test
    fun `unparseable JSON yields an empty glossary rather than failing`() {
        assertEquals(emptyMap<String, String>(), parseGlossaryJson("{not: valid, json at all"))
    }

    @Test
    fun `blank terms and blank renderings are dropped`() {
        val terms = parseGlossaryJson("""{"A": "B", "  ": "C", "D": "   ", "E": "F"}""")

        assertEquals(mapOf("A" to "B", "E" to "F"), terms)
    }

    @Test
    fun `non-string values are stringified, as Python's str() does`() {
        assertEquals(mapOf("Year" to "1984"), parseGlossaryJson("""{"Year": 1984}"""))
    }

    @Test
    fun `the term cap is enforced`() {
        val raw =
            (1..MAX_GLOSSARY_TERMS + 20).joinToString(", ", "{", "}") { """"t$it": "r$it"""" }

        assertEquals(MAX_GLOSSARY_TERMS, parseGlossaryJson(raw).size)
    }

    /**
     * The glossary identity hashes the *rendered prompt block*, which sorts by source term — so
     * two extractions of the same terms in different orders key identically. That defect was
     * shipped and fixed on the Python side (`docs/findings.md`, 2026-07-26); this asserts the
     * Kotlin port inherits the fix.
     */
    @Test
    fun `two extractions of the same terms in different orders key identically`() {
        val first =
            app.berilo.reader.translate.model.Glossary(
                linkedMapOf("Zahod" to "Zahod", "Kaplan" to "Kaplan"),
            )
        val second =
            app.berilo.reader.translate.model.Glossary(
                linkedMapOf("Kaplan" to "Kaplan", "Zahod" to "Zahod"),
            )

        assertEquals(
            app.berilo.reader.translate.model.glossaryIdentity(first),
            app.berilo.reader.translate.model.glossaryIdentity(second),
        )
    }
}
