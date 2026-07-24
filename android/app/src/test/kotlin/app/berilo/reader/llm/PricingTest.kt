package app.berilo.reader.llm

import org.junit.Assert.assertEquals
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
}
