package app.berilo.reader.translate.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cross-language gate for the prompt registry: every string this port carries must be
 * byte-identical to what `translator/berilo/prompts.py` produces, because the strings are what
 * the model sees and [TranslationStyle.version] is the identity the translation cache — and any
 * rubric score derived from a translation — traces back to.
 *
 * [PromptVectors] loads the committed vectors generated from the real Python module (see its
 * kdoc); this test diffs the Kotlin registry against them rather than against a second,
 * hand-typed copy, so a transcription slip is a red test, not a silent divergence.
 */
class PromptTextTest {

    private val vectors = PromptVectors.load()

    @Test
    fun `STRICT_MARKER_CLAUSE is byte-identical to Python's`() {
        assertEquals(vectors.strictMarkerClause, STRICT_MARKER_CLAUSE)
    }

    @Test
    fun `baseline_v1 reproduces the pre-registry prompts exactly, character for character`() {
        // Pinned verbatim, mirroring translator/tests/test_prompts.py's own literal pin — the
        // historical pre-registry text, not merely "whatever the committed vector says".
        val preRefactorBatch =
            "You are a professional literary translator. Translate the MEANING, not the " +
                "words: preserve register and tone, render idioms natively, and keep " +
                "terminology consistent with the glossary. Preserve any inline HTML tags " +
                "exactly (<em>, <strong>, <i>, <b>, <sub>, <sup>). Each source segment is " +
                "prefixed with a marker like [[1]]. Return EVERY segment, each prefixed with " +
                "its EXACT same marker, in the SAME order, and translate nothing else. Do " +
                "not merge, split, add, or drop segments. Output only the marked " +
                "translations."
        val preRefactorStrict =
            preRefactorBatch +
                " CRITICAL: the previous attempt did not return exactly one [[n]] marker " +
                "per source segment. Return EXACTLY the same markers you were given — no " +
                "more, no fewer — each on its own line followed by that segment's " +
                "translation."
        val preRefactorSingle =
            "You are a professional literary translator. Translate the MEANING, not the " +
                "words, preserving register, idioms, and any inline HTML tags exactly " +
                "(<em>, <strong>, <i>, <b>, <sub>, <sup>). Reply with ONLY the translation, " +
                "no markers, no commentary."

        assertEquals(preRefactorBatch, BASELINE.batchSystem)
        assertEquals(preRefactorStrict, BASELINE.strictSystem)
        assertEquals(preRefactorSingle, BASELINE.singleSystem)
        assertEquals("baseline_v1", BASELINE.version)
        assertNull(BASELINE.bookContextSystem)
        assertNull(BASELINE.reviseSystem)
        assertNull(BASELINE.reviseStrictSystem)
    }

    @Test
    fun `registry exposes the styles in Python's registration order`() {
        assertEquals(vectors.styles.map { it.name }, styleNames())
    }

    @Test
    fun `every style's prompt strings are byte-identical to Python's committed vectors`() {
        for (expected in vectors.styles) {
            val style = getStyle(expected.name)
            assertEquals("${expected.name} version", expected.version, style.version)
            assertEquals("${expected.name} batchSystem", expected.batchSystem, style.batchSystem)
            assertEquals(
                "${expected.name} strictSystem",
                expected.strictSystem,
                style.strictSystem,
            )
            assertEquals(
                "${expected.name} singleSystem",
                expected.singleSystem,
                style.singleSystem,
            )
            assertEquals(
                "${expected.name} bookContextSystem",
                expected.bookContextSystem,
                style.bookContextSystem,
            )
            assertEquals(
                "${expected.name} reviseSystem",
                expected.reviseSystem,
                style.reviseSystem,
            )
            assertEquals(
                "${expected.name} reviseStrictSystem",
                expected.reviseStrictSystem,
                style.reviseStrictSystem,
            )
            assertEquals(
                "${expected.name} targetLangs",
                expected.targetLangs,
                style.targetLangs,
            )
        }
    }

    @Test
    fun `every style's promptDigest matches Python's`() {
        for (expected in vectors.styles) {
            assertEquals(expected.name, expected.promptDigest, getStyle(expected.name).promptDigest)
        }
    }

    @Test
    fun `styles are immutable`() {
        // A data class with val properties cannot be mutated at all — a compile-time guarantee
        // stronger than Python's runtime FrozenInstanceError. Documented here, not tested by
        // attempted mutation, because there is no reflection-free way to attempt it in Kotlin.
        assertEquals(BASELINE, BASELINE.copy())
    }
}
