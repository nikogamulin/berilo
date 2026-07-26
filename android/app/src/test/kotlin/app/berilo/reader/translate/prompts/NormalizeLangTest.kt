package app.berilo.reader.translate.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [normalizeLang] must match `berilo.prompts.normalize_lang` exactly — the app exposes target
 * language as a free-text field (`settings/LlmSettings.kt`), so any divergence is one keystroke
 * away from every user.
 */
class NormalizeLangTest {

    @Test
    fun `language tags reduce to their primary subtag`() {
        for (tag in listOf("sl", "SL", " sl ", "sl-SI", "sl_SI", "SL-si")) {
            assertEquals(tag, "sl", normalizeLang(tag))
        }
    }

    @Test
    fun `an empty tag normalizes to empty`() {
        assertEquals("", normalizeLang(""))
    }

    @Test
    fun `slv and Slovenian do not reduce to sl (no language-name lookup)`() {
        assertEquals("slv", normalizeLang("slv"))
        assertEquals("slovenian", normalizeLang("Slovenian"))
    }

    @Test
    fun `EN-GB and eng normalize before comparison and do not spuriously refuse`() {
        // Book.language is raw OPF soup across the corpus (en-US, en, eng, EN-GB — B1a). Neither
        // reduces to a declared code (only "sl" is declared), so both correctly resolve to the
        // language-agnostic style rather than raising a StyleLanguageError.
        assertEquals("en", normalizeLang("EN-GB"))
        assertEquals("eng", normalizeLang("eng"))
        ensureSupports(resolveStyle("EN-GB"), "EN-GB")
        ensureSupports(resolveStyle("eng"), "eng")
        assertTrue(getStyle("baseline_v1").supports("EN-GB"))
        assertTrue(getStyle("baseline_v1").supports("eng"))
    }

    @Test
    fun `a Slovenian-bound style does not claim tags that do not reduce to sl`() {
        for (tag in listOf("de", "de-DE", "fr", "slv", "Slovenian", "en")) {
            assertFalse(tag, getStyle("revise_v1").supports(tag))
        }
    }

    @Test
    fun `sl-bound style matches every case and separator variant`() {
        for (tag in listOf("sl", "SL", " sl ", "sl-SI", "sl_SI", "SL-si")) {
            assertTrue(tag, getStyle("revise_v1").supports(tag))
        }
    }
}
