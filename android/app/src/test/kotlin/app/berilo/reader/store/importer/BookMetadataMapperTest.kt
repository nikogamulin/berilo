package app.berilo.reader.store.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class BookMetadataMapperTest {

    @Test
    fun `mapTitle falls back to the filename when the EPUB has no title`() {
        assertEquals("My Book", BookMetadataMapper.mapTitle(null, "My Book"))
        assertEquals("My Book", BookMetadataMapper.mapTitle("   ", "My Book"))
    }

    @Test
    fun `mapTitle prefers the EPUB metadata title when present`() {
        assertEquals("Real Title", BookMetadataMapper.mapTitle(" Real Title ", "fallback"))
    }

    @Test
    fun `mapAuthors trims whitespace and drops blank entries`() {
        assertEquals(
            listOf("Ann Leckie", "N K Jemisin"),
            BookMetadataMapper.mapAuthors(listOf(" Ann Leckie ", "", "  ", "N K Jemisin")),
        )
    }

    @Test
    fun `mapAuthors falls back to Unknown when the EPUB lists no authors`() {
        assertEquals(listOf("Unknown"), BookMetadataMapper.mapAuthors(emptyList()))
        assertEquals(listOf("Unknown"), BookMetadataMapper.mapAuthors(listOf("   ")))
    }
}
