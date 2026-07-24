package app.berilo.reader.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryDefinitionParserTest {

    @Test
    fun `parses all four labeled fields`() {
        val response =
            """
            DEFINITION: banka
            CONTEXT: finančna ustanova, kjer je junak dvignil denar
            BASE FORM: banka
            USAGE: pogosto v ednini
            """.trimIndent()

        val definition = parseDictionaryResponse("bank", response)

        assertEquals("bank", definition.word)
        assertEquals("banka", definition.definition)
        assertEquals("finančna ustanova, kjer je junak dvignil denar", definition.contextMeaning)
        assertEquals("banka", definition.baseForm)
        assertEquals("pogosto v ednini", definition.usageNote)
    }

    @Test
    fun `field matching is case-insensitive and tolerates extra whitespace`() {
        val response = "  definition:   banka  \ncontext:tiho ob reki"

        val definition = parseDictionaryResponse("bank", response)

        assertEquals("banka", definition.definition)
        assertEquals("tiho ob reki", definition.contextMeaning)
    }

    @Test
    fun `free text with no labeled fields becomes the whole definition, not dropped`() {
        val response = "Banka je institucija, ki hrani denar ljudi."

        val definition = parseDictionaryResponse("bank", response)

        assertEquals(response, definition.definition)
        assertEquals("", definition.contextMeaning)
        assertEquals("", definition.baseForm)
        assertEquals("", definition.usageNote)
    }

    @Test
    fun `missing fields default to empty rather than throwing`() {
        val response = "DEFINITION: banka"

        val definition = parseDictionaryResponse("bank", response)

        assertEquals("banka", definition.definition)
        assertEquals("", definition.contextMeaning)
        assertEquals("", definition.baseForm)
        assertEquals("", definition.usageNote)
    }
}
