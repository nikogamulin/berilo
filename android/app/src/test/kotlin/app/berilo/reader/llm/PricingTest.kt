package app.berilo.reader.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PricingTest {

    @Test
    fun `known model computes EUR from USD per-million pricing`() {
        // gpt-5-mini: 1000 input * 0.25/1e6 + 500 output * 2.00/1e6 = 0.00125 USD -> * 0.92
        assertEquals(0.00115, costEur("gpt-5-mini", 1000, 500), 1e-9)
    }

    @Test
    fun `zero tokens costs zero`() {
        assertEquals(0.0, costEur("claude-haiku-4-5", 0, 0), 1e-12)
    }

    @Test
    fun `unknown model raises a PARSE LlmError rather than a silent zero cost`() {
        val error =
            try {
                costEur("not-a-real-model", 10, 10)
                fail("expected LlmError")
                null
            } catch (e: LlmError) {
                e
            }
        assertEquals(LlmError.Kind.PARSE, error!!.kind)
        assertTrue(error.message.orEmpty().contains("not-a-real-model"))
    }

    @Test
    fun `isKnownModel is true for every priced model and false otherwise`() {
        assertTrue(isKnownModel("gpt-5-mini"))
        assertTrue(isKnownModel("claude-opus-4-1"))
        assertFalse(isKnownModel("gpt-4.1"))
    }

    @Test
    fun `requireKnownModel is a no-op for a priced model`() {
        requireKnownModel("gpt-5-mini")
    }

    @Test
    fun `requireKnownModel raises PROVIDER listing known models for an unpriced model`() {
        val error =
            try {
                requireKnownModel("gpt-4.1")
                fail("expected LlmError")
                null
            } catch (e: LlmError) {
                e
            }
        assertEquals(LlmError.Kind.PROVIDER, error!!.kind)
        assertTrue(error.message.orEmpty().contains("gpt-4.1"))
        assertTrue(error.message.orEmpty().contains("gpt-5-mini"))
    }
}
