package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.store.repository.Book
import app.berilo.reader.ui.library.LibraryScreen
import app.berilo.reader.ui.library.LibraryUiState
import app.berilo.reader.ui.theme.BeriloTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Before
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
 * [app.berilo.reader.ui.library.fallbackCoverFor] — resolved through a local drawable
 * resource ID, so no network is involved, but [installSynchronousImageLoader] below explains
 * why that alone doesn't make the render deterministic: Coil's `AsyncImage` still fetches and
 * decodes asynchronously even for local resources.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Bounce fix: `BookCard`'s covers load through Coil's `AsyncImage`, which by default
     * dispatches fetch/decode onto `Dispatchers.IO` — a real background thread outside
     * Robolectric's main looper, so `composeRule.waitForIdle()` can return before that decode
     * (and the recomposition/draw it triggers) has landed. That race made `library_boox_dark`
     * flap between two states across reruns: a card's cover region either painted the theme's
     * PaperDark background (0xFF121212, i.e. RGB(18,18,18) — correct) or was still showing the
     * raw, undrawn canvas (pure black, RGB(0,0,0) — a frame captured mid-flight). It only
     * showed up on the Boox width (most covers in flight at once) and only on the very first
     * Coil decode in a given test JVM (subsequent tests hit Coil's warm memory cache and settle
     * fast enough to not race), which is why it looked like it was specific to boox/dark.
     *
     * The fix collapses Coil's pipeline onto the composing thread for these purely-local,
     * no-real-I/O decodes: `Dispatchers.Unconfined` doesn't hop threads for work that has no
     * real suspension point, so fetch+decode complete inline before `waitForIdle()` is ever
     * called — deterministic by construction (no dispatcher hop to race), not by polling or
     * sleeping around a race that's still there. Scoped to this test's JVM-wide Coil singleton
     * via `setUnsafe` (not `setSafe`, which errors if any earlier test already touched Coil).
     */
    @OptIn(DelicateCoilApi::class)
    @Before
    fun installSynchronousImageLoader() {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext())
                .coroutineContext(Dispatchers.Unconfined)
                .build(),
        )
    }

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
        ScreenshotOutput.assumeRecording()
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
        val file = ScreenshotOutput.file("library", width, theme)
        composeRule.onRoot().captureRoboImage(file)
        ScreenshotOutput.assertCaptured(file)
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
