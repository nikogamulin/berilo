package app.berilo.reader.ui.library

import app.berilo.reader.R
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCoverTest {
    @Test
    fun `translated example books receive curated fallback covers`() {
        assertEquals(R.drawable.cover_active_measures, fallbackCoverFor("[SL] Active Measures"))
        assertEquals(R.drawable.cover_sandworm, fallbackCoverFor("Sandworm"))
        assertEquals(R.drawable.cover_new_rules_of_war, fallbackCoverFor("The New Rules of War"))
        assertEquals(R.drawable.cover_revenge_of_geography, fallbackCoverFor("The Revenge of Geography"))
        assertEquals(
            R.drawable.cover_world_ends,
            fallbackCoverFor("This Is How They Tell Me the World Ends"),
        )
    }

    @Test
    fun `unknown books retain the generic fallback`() {
        assertEquals(R.drawable.ic_book, fallbackCoverFor("An Unrelated Book"))
    }
}
