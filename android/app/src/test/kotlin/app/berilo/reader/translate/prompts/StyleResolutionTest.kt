package app.berilo.reader.translate.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [resolveStyle] and [ensureSupports]: the `(tier, language)` resolution table (plan §3.3) and
 * the loud, actionable refusal a mismatched explicit style triggers (review finding 4).
 *
 * The five scenarios the story's Verify line names are asserted directly in
 * `resolution matches the story's five named scenarios`, and property-tested across every
 * `(context, tier, tag)` combination in `every resolvable style covers the language it was
 * resolved for` — the table can never hand back a style its own guard would refuse.
 */
class StyleResolutionTest {

    // --- Declarations --------------------------------------------------------------------

    @Test
    fun `Slovenian-bound styles declare sl and nothing else`() {
        for (name in listOf("sl_style_v1", "book_context_v1", "revise_v1")) {
            assertEquals(name, listOf("sl"), getStyle(name).targetLangs)
        }
        assertNull(getStyle("baseline_v1").targetLangs)
        assertNull(getStyle("revise_generic_v1").targetLangs)
    }

    @Test
    fun `revise_generic_v1 carries a second pass with no language contract`() {
        val generic = getStyle("revise_generic_v1")
        val revise = requireNotNull(generic.reviseSystem)
        assertEquals(generic.reviseStrictSystem, revise + STRICT_MARKER_CLAUSE)
        for (prompt in listOf(generic.batchSystem, generic.strictSystem, generic.singleSystem, revise)) {
            assertFalse(prompt, prompt.contains("Slovenian"))
            assertFalse(prompt, prompt.contains("šumniki"))
            assertFalse(prompt, prompt.contains("s strani"))
        }
        assertTrue(revise.contains("must NOT change the meaning"))
    }

    @Test
    fun `language-agnostic styles never refuse`() {
        for (name in listOf("baseline_v1", "revise_generic_v1")) {
            ensureSupports(getStyle(name), "de")
            ensureSupports(getStyle(name), "sl")
        }
    }

    // --- Loud, actionable refusal ----------------------------------------------------------

    @Test
    fun `style target mismatch is a loud actionable refusal naming style, languages, target and suggestion`() {
        val error =
            assertThrowsStyleLanguageError { ensureSupports(getStyle("revise_v1"), "de") }
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("revise_v1"))
        assertTrue(message, message.contains("sl")) // declared languages
        assertTrue(message, message.contains("'de'")) // requested target
        assertTrue(message, message.contains("revise_generic_v1")) // style that would have run
    }

    @Test
    fun `an explicit style is honoured but only when it fits`() {
        assertEquals("sl_style_v1", resolveStyle("sl", requested = getStyle("sl_style_v1")).name)
        assertEquals("baseline_v1", resolveStyle("de", requested = getStyle("baseline_v1")).name)
        assertThrowsStyleLanguageError { resolveStyle("de", requested = getStyle("revise_v1")) }
    }

    // --- Resolution table --------------------------------------------------------------------

    @Test
    fun `resolution table defaults differ by execution context`() {
        val workstation = resolveStyle("sl", context = ExecutionContext.WORKSTATION)
        val device = resolveStyle("sl", context = ExecutionContext.DEVICE)

        assertEquals("revise_v1", workstation.name)
        assertNotNull(workstation.reviseSystem) // two passes
        assertEquals("baseline_v1", device.name)
        assertNull(device.reviseSystem) // one pass
        assertSame(DEFAULT, workstation) // the workstation default is also the module DEFAULT
    }

    @Test
    fun `resolution table defaults differ by target language`() {
        assertEquals("revise_generic_v1", resolveStyle("de").name)
        assertEquals("revise_generic_v1", resolveStyle("fr").name)
        assertEquals("revise_v1", resolveStyle("sl-SI").name)
        for (tag in listOf("de", "fr", "sl", "Slovenian")) {
            ensureSupports(resolveStyle(tag), tag)
        }
    }

    @Test
    fun `resolution matches the story's five named scenarios`() {
        assertEquals(
            "DEVICE+sl -> baseline_v1",
            "baseline_v1",
            resolveStyle("sl", context = ExecutionContext.DEVICE).name,
        )
        assertEquals(
            "DEVICE+sl+QUALITY override -> revise_v1",
            "revise_v1",
            resolveStyle("sl", context = ExecutionContext.DEVICE, tier = StyleTier.QUALITY).name,
        )
        assertEquals(
            "DEVICE+de -> baseline_v1",
            "baseline_v1",
            resolveStyle("de", context = ExecutionContext.DEVICE).name,
        )
        assertEquals(
            "WORKSTATION+sl -> revise_v1",
            "revise_v1",
            resolveStyle("sl", context = ExecutionContext.WORKSTATION).name,
        )
        assertEquals(
            "WORKSTATION+de -> revise_generic_v1",
            "revise_generic_v1",
            resolveStyle("de", context = ExecutionContext.WORKSTATION).name,
        )
    }

    @Test
    fun `the device quality toggle resolves to the two-pass style without falsifying the context`() {
        // B7's "higher quality, ~2x cost" toggle is a tier override passed alongside
        // context = DEVICE — it must never lie about the context to reach revise_v1.
        val optIn = resolveStyle("sl", context = ExecutionContext.DEVICE, tier = StyleTier.QUALITY)
        assertEquals("revise_v1", optIn.name)
        assertEquals(StyleTier.ECONOMY, defaultTier(ExecutionContext.DEVICE))
        assertEquals(StyleTier.QUALITY, defaultTier(ExecutionContext.WORKSTATION))
    }

    @Test
    fun `every resolvable style covers the language it was resolved for`() {
        // The table can never hand back a style that its own guard would refuse.
        val tags = listOf("sl", "sl-SI", "de", "ja", "pt-BR", "xyz")
        for (context in ExecutionContext.entries) {
            for (tier in StyleTier.entries) {
                for (tag in tags) {
                    val style = resolveStyle(tag, context = context, tier = tier)
                    ensureSupports(style, tag) // must not throw
                }
            }
        }
    }

    // --- Lookup ---------------------------------------------------------------------------

    @Test
    fun `unknown style fails loudly`() {
        val error =
            try {
                getStyle("sl_style_v2")
                null
            } catch (e: IllegalArgumentException) {
                e
            }
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("unknown translation style"))
    }

    @Test
    fun `DEFAULT is revise_v1, the workstation default`() {
        assertSame(REVISE, DEFAULT)
        assertEquals("revise_v1", DEFAULT_STYLE_NAME)
    }

    private fun assertThrowsStyleLanguageError(block: () -> Unit): StyleLanguageError {
        try {
            block()
        } catch (e: StyleLanguageError) {
            return e
        }
        fail("expected StyleLanguageError")
        error("unreachable")
    }
}
