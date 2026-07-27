package app.berilo.reader.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.berilo.reader.annotations.ChapterFlags
import app.berilo.reader.annotations.ChapterHighlights
import app.berilo.reader.annotations.Highlight
import app.berilo.reader.annotations.NotebookScreen
import app.berilo.reader.annotations.NotebookUiState
import app.berilo.reader.annotations.TranslationFlag
import app.berilo.reader.annotations.TranslationProvenance
import app.berilo.reader.store.db.HighlightColor
import app.berilo.reader.ui.theme.BeriloTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2.10: renders [NotebookScreen] with a fixed set of highlights spanning all four
 * [HighlightColor] values and both annotated/plain highlights, so the color-coded
 * left-border and note-vs-no-note rows are both visible in the screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotebookScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun highlight(id: String, color: HighlightColor, text: String, note: String?) =
        Highlight(
            id = id,
            bookId = "book-1",
            color = color,
            selectedText = text,
            note = note,
            locatorJson = "{}",
            chapterTitle = "Chapter 1",
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun flag(
        id: String,
        text: String,
        comment: String?,
        provenance: TranslationProvenance?,
    ) =
        TranslationFlag(
            id = id,
            bookId = "book-1",
            selectedText = text,
            comment = comment,
            locatorJson = "{}",
            chapterTitle = "Chapter 1",
            provenance = provenance,
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun sampleUiState() =
        NotebookUiState(
            bookTitle = "Sandworm",
            chapters =
                listOf(
                    ChapterHighlights(
                        chapterTitle = "Chapter 1 — Iron Curtain",
                        highlights =
                            listOf(
                                highlight(
                                    "h1",
                                    HighlightColor.AMBER,
                                    "a longing for a home that may not exist",
                                    note = "Ties to the book's central theme of displacement.",
                                ),
                                highlight("h2", HighlightColor.SAGE, "the wires hummed with intent", note = null),
                            ),
                    ),
                    ChapterHighlights(
                        chapterTitle = "Chapter 2 — Sandworm",
                        highlights =
                            listOf(
                                highlight("h3", HighlightColor.SKY, "an outage with no clear author", note = null),
                                highlight(
                                    "h4",
                                    HighlightColor.ROSE,
                                    "attribution is the hardest problem in this field",
                                    note = "Worth quoting in the review.",
                                ),
                            ),
                    ),
                ),
            // B9: both flag variants, so the review sees the badge, the error-tinted border,
            // a comment and both the matched and unmatched provenance lines in one frame.
            flagChapters =
                listOf(
                    ChapterFlags(
                        chapterTitle = "Chapter 1 — Iron Curtain",
                        flags =
                            listOf(
                                flag(
                                    "f1",
                                    "Geografija je usoda, ki je nihče ne izbere.",
                                    comment = "Raje »obala« kot »breg«.",
                                    provenance =
                                        TranslationProvenance(
                                            bookHash = "bookhash1",
                                            segmentHash = "seghash1",
                                            model = "gpt-5-mini",
                                            lang = "sl",
                                            promptVersion = "revise_v1",
                                            glossaryHash = "glosshash1",
                                        ),
                                ),
                                flag("f2", "Ta stavek ni smiseln.", comment = null, provenance = null),
                            ),
                    ),
                ),
        )

    private fun render(width: String, useDarkTheme: Boolean, theme: String) {
        ScreenshotOutput.assumeRecording()
        composeRule.setContent {
            BeriloTheme(useDarkTheme = useDarkTheme) {
                Surface {
                    NotebookScreen(
                        uiState = sampleUiState(),
                        onBack = {},
                        onJumpTo = {},
                        onEditNote = { _, _ -> },
                        onChangeColor = { _, _ -> },
                        onDelete = {},
                        onExport = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val file = ScreenshotOutput.file("notebook", width, theme)
        composeRule.onRoot().captureRoboImage(file)
        ScreenshotOutput.assertCaptured(file)
    }

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone light`() = render("phone", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.PHONE)
    @Test
    fun `phone dark`() = render("phone", useDarkTheme = true, theme = "dark")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox light`() = render("boox", useDarkTheme = false, theme = "light")

    @Config(sdk = [35], qualifiers = ScreenshotQualifiers.BOOX_TAB_ULTRA)
    @Test
    fun `boox dark`() = render("boox", useDarkTheme = true, theme = "dark")
}
