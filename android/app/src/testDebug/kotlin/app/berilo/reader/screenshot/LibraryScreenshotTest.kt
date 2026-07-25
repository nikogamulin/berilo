package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.store.repository.Book
import app.berilo.reader.ui.library.LibraryScreen
import app.berilo.reader.ui.library.LibraryUiState
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Fixed epoch millis (2025-01-01T00:00:00Z) — never System.currentTimeMillis(), so reruns
// are byte-identical and diffable per the story's determinism requirement.
private const val FIXED_TIMESTAMP = 1_735_689_600_000L

/**
 * S2.10: renders [LibraryScreen] to PNG at both device widths and both themes. Covers with
 * `coverPath = null` fall back to the bundled title-matched drawables in
 * [app.berilo.reader.ui.library.fallbackCoverFor] — no network/Coil image load, so the
 * render is deterministic without any fake image server.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Seven books: enough to show several grid rows/columns at the Boox width, mixing the
    // bundled title-matched covers (see fallbackCoverFor) with the generic ic_book fallback,
    // and varying progress/authors so the strip and blank-authors branches both render.
    private fun sampleUiState() =
        LibraryUiState(
            isLoading = false,
            books =
                listOf(
                    book("book-1", "Sandworm", "Andy Greenberg", progressFraction = 0.42f),
                    book("book-2", "Active Measures", "Thomas Rid", progressFraction = null),
                    book("book-3", "The New Rules of War", "Sean McFate", progressFraction = 0.9f),
                    book("book-4", "The Revenge of Geography", "Robert D. Kaplan", progressFraction = 0.12f),
                    book(
                        "book-5",
                        "This Is How They Tell Me the World Ends",
                        "Nicole Perlroth",
                        progressFraction = null,
                    ),
                    book("book-6", "Zemljepis meje", authors = "", progressFraction = 0.05f),
                    book("book-7", "Nedokončana zgodba", "Neznani avtor", progressFraction = null),
                ),
        )

    private fun book(id: String, title: String, authors: String, progressFraction: Float?) =
        Book(
            id = id,
            title = title,
            authors = authors,
            filePath = "/$id.epub",
            coverPath = null,
            addedAt = FIXED_TIMESTAMP,
            lastOpenedAt = if (progressFraction != null) FIXED_TIMESTAMP else null,
            progressFraction = progressFraction,
        )

    private fun render(width: String, qualifiers: String, useDarkTheme: Boolean, theme: String) {
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    LibraryScreen(
                        uiState = sampleUiState(),
                        onAddBook = {},
                        onOpenBook = {},
                        onDeleteBook = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot()
            .captureRoboImage(ScreenshotOutput.file("library", width, theme))
    }

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light`() = render("phone", ScreenshotQualifiers.PHONE, useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark`() = render("phone", ScreenshotQualifiers.PHONE, useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light`() = render("boox", ScreenshotQualifiers.BOOX_TAB_ULTRA, useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark`() = render("boox", ScreenshotQualifiers.BOOX_TAB_ULTRA, useDarkTheme = true, theme = "dark")
}
