package app.berilo.reader.annotations

import android.app.Activity
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.berilo.reader.R
import app.berilo.reader.reader.ReaderChromeActions
import app.berilo.reader.reader.ReaderChromeOverlay
import app.berilo.reader.reader.ReaderChromeState
import app.berilo.reader.reader.ReaderPanel
import app.berilo.reader.reader.ReaderPreferences
import app.berilo.reader.reader.SelectionAction
import app.berilo.reader.reader.beriloNavigatorConfiguration
import app.berilo.reader.store.db.AppDatabase
import app.berilo.reader.store.db.TranslationEntity
import app.berilo.reader.store.db.TranslationFlagEntity
import app.berilo.reader.ui.theme.BeriloTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * **B9's whole point, walked once: text selection -> flag -> stored row -> review surface ->
 * Markdown export.**
 *
 * S2.6 shipped highlights, notes, the notebook and the decoration renderer with no reachable
 * path to any of them, and two months of green tests said nothing, because every test covered
 * one joint and none covered the chain (CLAUDE.md §9). This test is the chain. Everything in it
 * is production code except the WebView:
 *
 * - the menu comes from [beriloNavigatorConfiguration]'s real `SelectionActionModeCallback`,
 *   and the flag item is looked up **by its label**, never by an id the test computes — so an
 *   action dropped from the selection bar fails here and cannot be papered over;
 * - the sheet is composed inside the real [ReaderChromeOverlay], the same host `ReaderActivity`
 *   renders, so a sheet the chrome forgets to host fails here too;
 * - the taps are real Compose gestures on the real [FlagEditorHost];
 * - the row lands in a real Room database through the real repository, provenance resolved
 *   against B4's real translation cache;
 * - the review surface is the real [NotebookViewModel]/[NotebookScreen], and the export is the
 *   real [MarkdownExporter].
 *
 * The one seam is the selection itself: `EpubNavigatorFragment.currentSelection()` is a
 * suspending JS round trip into a WebView, so the captured `Locator`'s text and chapter title
 * are supplied here the way the navigator supplies them to `ReaderActivity.onSelectionAction`.
 * That dispatch is a `when` with no `else` over [SelectionAction], so a flag branch cannot go
 * missing from the activity without failing the compile.
 *
 * Rendered at the Boox qualifier: at phone height a control below the fold makes `performClick`
 * silently do nothing, with no error (`docs/findings.md`, B7).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = BOOX_QUALIFIER)
class FlagBadTranslationEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: AppDatabase
    private lateinit var flagRepository: TranslationFlagRepository
    private lateinit var flagViewModel: TranslationFlagViewModel
    private lateinit var notebookViewModel: NotebookViewModel
    private lateinit var menuHostActivity: Activity
    private lateinit var menu: Menu
    private var notebookSubscription: Job? = null
    private var nextFlagId = 0

    /**
     * Which surface the single `setContent` renders. `setContent` may only be called once per
     * compose rule, and the walk crosses from the reader to the notebook, so the crossing is a
     * state change rather than a second composition — which is also what it is in the app.
     */
    private val notebookState = mutableStateOf<NotebookUiState?>(null)

    /** Text that IS in the translation cache — the provenance-recoverable case. */
    private val cachedTranslation =
        "Geografija je usoda, ki je nihče ne izbere, in reke so starejše od vsake meje."

    /** Text that is NOT in the cache — the provenance-free case, which must still store. */
    private val uncachedTranslation = "Ta odstavek ni nikoli šel skozi predpomnilnik te naprave."

    @Before
    fun setUp() {
        // Unconfined everywhere: viewModelScope.launch and the repository's IO both run inline,
        // so a tap and the row it writes are one synchronous step. No virtual time, because the
        // Compose test clock owns scheduling here.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        runBlocking {
            database.translationCacheDao().insertTranslations(
                listOf(
                    TranslationEntity(
                        bookHash = "book-hash-1",
                        segmentHash = "segment-hash-1",
                        model = "gpt-5-mini",
                        lang = "sl",
                        promptVersion = "revise_v1",
                        glossaryHash = "glossary-hash-1",
                        text = cachedTranslation,
                        costEur = 0.0012,
                        createdAt = 1_000L,
                    ),
                ),
            )
        }

        flagRepository =
            TranslationFlagRepository(
                dao = database.translationFlagDao(),
                provenanceResolver = TranslationProvenanceResolver(database.translationCacheDao()),
                ioDispatcher = Dispatchers.Unconfined,
                clock = { 7_000L },
                idGenerator = { "flag-${nextFlagId++}" },
            )
        flagViewModel = TranslationFlagViewModel(BOOK_ID, flagRepository)
        notebookViewModel =
            NotebookViewModel(
                bookId = BOOK_ID,
                bookTitle = BOOK_TITLE,
                repository = AnnotationsRepository(database.highlightDao(), Dispatchers.Unconfined),
                flagRepository = flagRepository,
            )
        // WhileSubscribed: the notebook state is cold without a collector, and exportMarkdown()
        // reads `uiState.value`. Hold a subscription for the length of the walk.
        notebookSubscription =
            CoroutineScope(Dispatchers.Unconfined).launch { notebookViewModel.uiState.collect {} }

        menuHostActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        menu = PopupMenu(menuHostActivity, View(menuHostActivity)).menu
    }

    @After
    fun tearDown() {
        notebookSubscription?.cancel()
        database.close()
        Dispatchers.resetMain()
    }

    // --- the walk ----------------------------------------------------------------------

    @Test
    fun `a selection becomes a stored flag, a notebook entry and an exported line`() {
        composeRule.setContent { ReaderUnderTest() }

        // 1. GESTURE — long-press raises Berilo's bar, the reader taps "Bad translation".
        //    The item is found by label: nothing here knows or computes a menu id.
        tapSelectionAction(R.string.reader_flag, selectedText = cachedTranslation, chapterTitle = "Poglavje ena")

        // 2. The sheet is actually on screen. If ReaderChromeOverlay did not host it, or the
        //    view model never left Idle, every assertion below would fail on a missing node.
        composeRule.onNodeWithText(activityString(R.string.flag_title)).assertExists()
        composeRule.onNodeWithText(cachedTranslation).assertExists()

        // 3. The user types what it should have said, and confirms.
        composeRule.onNodeWithText(activityString(R.string.flag_comment_label))
            .performTextInput("Raje »obala« kot »breg«.")
        tapFlagConfirm()

        // 4. STORED — with provenance, because the passage matched a cached translation.
        val withComment = awaitStoredFlag("flag-0")
        assertEquals(BOOK_ID, withComment.bookId)
        assertEquals(cachedTranslation, withComment.selectedText)
        assertEquals("Raje »obala« kot »breg«.", withComment.comment)
        assertEquals("Poglavje ena", withComment.chapterTitle)
        assertEquals(LOCATOR_JSON, withComment.locatorJson)
        assertEquals("book-hash-1", withComment.cacheBookHash)
        assertEquals("segment-hash-1", withComment.cacheSegmentHash)
        assertEquals("gpt-5-mini", withComment.cacheModel)
        assertEquals("sl", withComment.cacheLang)
        assertEquals("revise_v1", withComment.cachePromptVersion)
        assertEquals("glossary-hash-1", withComment.cacheGlossaryHash)
        assertNull(withComment.deletedAt)

        // 5. The second variant: flagged as incorrect with no further input, on a passage the
        //    cache cannot identify. It must still store — a flag with less provenance beats no
        //    flag.
        tapSelectionAction(R.string.reader_flag, selectedText = uncachedTranslation, chapterTitle = "Poglavje dve")
        composeRule.onNodeWithText(uncachedTranslation).assertExists()
        tapFlagConfirm()

        val bare = awaitStoredFlag("flag-1")
        assertEquals(uncachedTranslation, bare.selectedText)
        assertNull("flag-only means no comment, not an empty one", bare.comment)
        assertNull("nothing in the cache matched, so there is no provenance to claim", bare.cacheBookHash)
        assertNull(bare.cacheSegmentHash)

        // 6. REVIEW SURFACE — both flags reach the notebook, chapter-grouped in reading order.
        val state = awaitNotebookState { it.flagChapters.sumOf { chapter -> chapter.flags.size } == 2 }
        assertEquals(listOf("Poglavje ena", "Poglavje dve"), state.flagChapters.map { it.chapterTitle })
        assertEquals(cachedTranslation, state.flagChapters[0].flags.single().selectedText)
        assertNotNull(state.flagChapters[0].flags.single().provenance)
        assertNull(state.flagChapters[1].flags.single().provenance)

        // 7. …and are rendered by the real notebook screen, under their own heading.
        notebookState.value = state
        composeRule.waitForIdle()
        composeRule.onNodeWithText(activityString(R.string.notebook_flags_heading)).assertExists()
        composeRule.onNodeWithText(cachedTranslation).assertExists()
        composeRule.onNodeWithText("Raje »obala« kot »breg«.").assertExists()
        composeRule.onNodeWithText(uncachedTranslation).assertExists()

        // 8. EXPORT — both flags ride along in the Markdown the share sheet hands out, marked
        //    as flags rather than as highlights.
        val markdown = notebookViewModel.exportMarkdown()
        assertTrue(markdown, markdown.contains("## Flagged translations"))
        assertTrue(markdown, markdown.contains("> $cachedTranslation"))
        assertTrue(markdown, markdown.contains("**Flagged as a bad translation.**"))
        assertTrue(markdown, markdown.contains("Raje »obala« kot »breg«."))
        assertTrue(markdown, markdown.contains("Source segment `segment-hash-1`"))
        assertTrue(markdown, markdown.contains("gpt-5-mini → sl"))
        assertTrue(markdown, markdown.contains("prompt `revise_v1`"))
        assertTrue(markdown, markdown.contains("> $uncachedTranslation"))
    }

    @Test
    fun `cancelling the sheet leaves nothing behind`() {
        composeRule.setContent { ReaderUnderTest() }

        tapSelectionAction(R.string.reader_flag, selectedText = cachedTranslation, chapterTitle = null)
        composeRule.onNodeWithText(activityString(R.string.annotation_cancel))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(emptyList<ChapterFlags>(), awaitNotebookState { true }.flagChapters)
    }

    // --- harness -----------------------------------------------------------------------

    /**
     * The reader as `ReaderActivity` assembles it — the real chrome overlay, its flag state read
     * off the real view model, its flag callbacks wired to the same view model methods — until
     * the walk reaches the notebook, which then replaces it exactly as `NotebookActivity` does.
     */
    @Composable
    private fun ReaderUnderTest() {
        val notebook = notebookState.value
        if (notebook != null) {
            BeriloTheme {
                NotebookScreen(
                    uiState = notebook,
                    onBack = {},
                    onJumpTo = {},
                    onEditNote = { _, _ -> },
                    onChangeColor = { _, _ -> },
                    onDelete = {},
                    onExport = {},
                    onJumpToFlag = {},
                    onDeleteFlag = {},
                )
            }
            return
        }
        val flagEditorState by flagViewModel.editorState.collectAsState()
        BeriloTheme {
            ReaderChromeOverlay(
                state =
                    ReaderChromeState(
                        chromeVisible = false,
                        panel = ReaderPanel.NONE,
                        chapterTitle = BOOK_TITLE,
                        progressFraction = 0.4f,
                        preferences = ReaderPreferences(),
                        chapters = emptyList(),
                        flagEditorState = flagEditorState,
                    ),
                actions =
                    ReaderChromeActions(
                        onToggleChrome = {},
                        onOpenChapters = {},
                        onOpenSettings = {},
                        onClosePanel = {},
                        onChapterSelected = {},
                        onFontSmaller = {},
                        onFontLarger = {},
                        onMarginsNarrower = {},
                        onMarginsWider = {},
                        onEinkModeChange = {},
                        onDarkThemeChange = {},
                        onFlagCommentChanged = flagViewModel::onCommentChanged,
                        onConfirmFlag = flagViewModel::confirmFlag,
                        onDismissFlagEditor = flagViewModel::dismissEditor,
                    ),
            )
        }
    }

    /**
     * Raises Berilo's selection bar through the real callback and taps the item labelled
     * [labelRes], dispatching it exactly as `ReaderActivity.onSelectionAction` does.
     *
     * The lookup is by label on purpose. `menu.findItem(SelectionAction.FLAG.menuItemId)` would
     * pass even if `onCreateActionMode` stopped adding the item, because the id is derived from
     * the enum rather than read off the bar.
     */
    private fun tapSelectionAction(labelRes: Int, selectedText: String, chapterTitle: String?) {
        menu.clear()
        val callback =
            requireNotNull(beriloNavigatorConfiguration { action, mode -> dispatch(action, mode, selectedText, chapterTitle) }
                .selectionActionModeCallback) {
                "Readium falls back to the platform popup when this is null — S2.12"
            }
        val mode = NoopActionMode()
        callback.onCreateActionMode(mode, menu)

        val wanted = activityString(labelRes)
        val item =
            (0 until menu.size()).map(menu::getItem).firstOrNull { it.title?.toString() == wanted }
                ?: throw AssertionError(
                    "\"$wanted\" is not on Berilo's selection bar. Present: " +
                        (0 until menu.size()).map { menu.getItem(it).title }.joinToString(),
                )
        assertTrue("the callback declined its own menu item", callback.onActionItemClicked(mode, item))
    }

    /** `ReaderActivity.onSelectionAction`'s FLAG branch, with the navigator's captured selection
     * supplied by the test (the one seam — see the class KDoc). */
    private fun dispatch(action: SelectionAction, mode: ActionMode, selectedText: String, chapterTitle: String?) {
        mode.finish()
        when (action) {
            SelectionAction.FLAG -> flagViewModel.beginFlag(selectedText, LOCATOR_JSON, chapterTitle)
            else -> throw AssertionError("this walk only drives FLAG, got $action")
        }
    }

    /** Confirms the sheet. `ModalBottomSheet`'s reveal animation makes a simulated touch
     * unreliable under Robolectric, so the semantics action is invoked directly — the same
     * approach `AnnotationEditorSheetTest`/`DictionarySheetTest` already use. */
    private fun tapFlagConfirm() {
        composeRule.onNodeWithText(activityString(R.string.flag_confirm))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /**
     * Waits for the tap's write to land, then for the sheet to close.
     *
     * Room's suspend DAOs hop to their own query executor, which no `Dispatchers.Unconfined`
     * and no Compose idling resource controls — reading the row straight after the tap races
     * the insert, and the race is won often enough to look like a broken write. Polling the
     * database is the honest wait: it ends when the row the user's gesture produced exists.
     */
    private fun awaitStoredFlag(id: String): TranslationFlagEntity =
        runBlocking {
            withTimeout(NOTEBOOK_TIMEOUT_MS) {
                var row = database.translationFlagDao().getById(id)
                while (row == null) {
                    delay(POLL_INTERVAL_MS)
                    row = database.translationFlagDao().getById(id)
                }
                // The sheet closes in the same coroutine, right after the write commits.
                while (flagViewModel.editorState.value !is FlagEditorUiState.Idle) delay(POLL_INTERVAL_MS)
                row
            }
        }.also { composeRule.waitForIdle() }

    private fun awaitNotebookState(predicate: (NotebookUiState) -> Boolean): NotebookUiState =
        runBlocking {
            withTimeout(NOTEBOOK_TIMEOUT_MS) {
                while (!predicate(notebookViewModel.uiState.value)) delay(POLL_INTERVAL_MS)
                notebookViewModel.uiState.value
            }
        }

    private fun activityString(resId: Int): String = composeRule.activity.getString(resId)

    /** [ActionMode] double: the callback hands the mode to the dispatcher rather than finishing
     * it (S2.12), so all this needs to do is exist and count nothing. */
    private class NoopActionMode : ActionMode() {
        private var modeTitle: CharSequence? = null
        private var modeSubtitle: CharSequence? = null

        override fun setTitle(title: CharSequence?) { modeTitle = title }
        override fun setTitle(resId: Int) = Unit
        override fun setSubtitle(subtitle: CharSequence?) { modeSubtitle = subtitle }
        override fun setSubtitle(resId: Int) = Unit
        override fun getTitle(): CharSequence? = modeTitle
        override fun getSubtitle(): CharSequence? = modeSubtitle
        override fun setCustomView(view: View?) = Unit
        override fun getCustomView(): View? = null
        override fun invalidate() = Unit
        override fun finish() = Unit
        override fun getMenu(): Menu = throw UnsupportedOperationException()
        override fun getMenuInflater(): MenuInflater = throw UnsupportedOperationException()
    }

    private companion object {
        const val BOOK_ID = "book-1"
        const val BOOK_TITLE = "Maščevanje geografije"
        const val LOCATOR_JSON = "{\"href\":\"chapter1.xhtml\",\"type\":\"application/xhtml+xml\"}"
        const val NOTEBOOK_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 5L
    }
}

/** Boox Tab Ultra, spelled out because `@Config` needs a compile-time constant and
 * `ScreenshotQualifiers` is in the screenshot package. */
private const val BOOX_QUALIFIER = "w990dp-h1319dp-xlarge-notlong-notround-any-227dpi-keyshidden-nonav"
