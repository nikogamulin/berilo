package app.berilo.reader.annotations

import app.berilo.reader.store.db.HighlightColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Same `Dispatchers.setMain`/`resetMain` pattern as `DictionaryViewModelTest`. `uiState` is
 * `stateIn(..., SharingStarted.WhileSubscribed(...))`: it only starts collecting the
 * repository Flow while subscribed, so every test that reads it starts a no-op background
 * collector first, then asserts on `.value` after `advanceUntilIdle()` — collecting into a
 * list and reading the list's history is unreliable here (a StateFlow only guarantees its
 * *latest* value reaches a collector, not every intermediate one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotebookViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(dao: FakeHighlightDao) =
        AnnotationsRepository(dao = dao, ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler))

    /** B9's flag repository over an in-memory DAO. Provenance is [NoProvenance] here: the
     * notebook's job is to show flags, matched or not, and `TranslationProvenanceResolverTest`
     * owns the matching itself. */
    private fun flagRepository(dao: FakeTranslationFlagDao, idPrefix: String = "flag") =
        TranslationFlagRepository(
            dao = dao,
            provenanceResolver = NoProvenance,
            ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
            clock = { 1_000L },
            idGenerator = { "$idPrefix-${dao.hashCode()}-${nextId++}" },
        )

    private var nextId = 0

    private fun viewModel(
        repo: AnnotationsRepository,
        flagRepo: TranslationFlagRepository = flagRepository(FakeTranslationFlagDao()),
        bookId: String = "book-1",
        bookTitle: String = "Testna knjiga",
    ) = NotebookViewModel(bookId, bookTitle, repo, flagRepo)

    @Test
    fun `uiState groups highlights by chapter in reading order`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            repo.create("book-1", HighlightColor.AMBER, "prva", "{}", "Poglavje ena")
            repo.create("book-1", HighlightColor.SAGE, "druga", "{}", "Poglavje dve")

            val viewModel = viewModel(repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val latest = viewModel.uiState.value
            assertEquals("Testna knjiga", latest.bookTitle)
            assertEquals(listOf("Poglavje ena", "Poglavje dve"), latest.chapters.map { it.chapterTitle })
        }

    @Test
    fun `uiState excludes highlights from other books`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            repo.create("book-1", HighlightColor.AMBER, "mine", "{}", "Poglavje ena")
            repo.create("book-2", HighlightColor.AMBER, "not mine", "{}", "Poglavje ena")

            val viewModel = viewModel(repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val allTexts = viewModel.uiState.value.chapters.flatMap { it.highlights }.map { it.selectedText }
            assertEquals(listOf("mine"), allTexts)
        }

    @Test
    fun `updateNote persists through the repository`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)
            val viewModel = viewModel(repo, bookTitle = "Knjiga")

            viewModel.updateNote(id, "nova opomba")
            advanceUntilIdle()

            assertEquals("nova opomba", dao.getById(id)?.note)
        }

    @Test
    fun `updateColor persists through the repository`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)
            val viewModel = viewModel(repo, bookTitle = "Knjiga")

            viewModel.updateColor(id, HighlightColor.ROSE)
            advanceUntilIdle()

            assertEquals(HighlightColor.ROSE, dao.getById(id)?.color)
        }

    @Test
    fun `delete removes the row through the repository`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            val id = repo.create("book-1", HighlightColor.AMBER, "text", "{}", null)
            val viewModel = viewModel(repo, bookTitle = "Knjiga")

            viewModel.delete(id)
            advanceUntilIdle()

            assertNull(dao.getById(id))
        }

    @Test
    fun `exportMarkdown renders the current snapshot via MarkdownExporter`() =
        runTest(testDispatcher) {
            val dao = FakeHighlightDao()
            val repo = repository(dao)
            repo.create("book-1", HighlightColor.AMBER, "izbrana vrstica", "{}", "Poglavje ena")
            val viewModel = viewModel(repo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val markdown = viewModel.exportMarkdown()

            assertEquals(
                "# Testna knjiga\n\n## Poglavje ena\n\n> izbrana vrstica\n",
                markdown,
            )
        }

    // --- B9: flagged translations ------------------------------------------------------

    @Test
    fun `uiState carries flagged translations grouped by chapter, alongside highlights`() =
        runTest(testDispatcher) {
            val repo = repository(FakeHighlightDao())
            repo.create("book-1", HighlightColor.AMBER, "izbrana vrstica", "{}", "Poglavje ena")
            val flagDao = FakeTranslationFlagDao()
            val flagRepo = flagRepository(flagDao)
            flagRepo.create("book-1", "slab prevod", "{}", "Poglavje ena", comment = "moj predlog")
            flagRepo.create("book-1", "še en slab prevod", "{}", "Poglavje dve")

            val viewModel = viewModel(repo, flagRepo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val latest = viewModel.uiState.value
            assertEquals(listOf("Poglavje ena"), latest.chapters.map { it.chapterTitle })
            assertEquals(listOf("Poglavje ena", "Poglavje dve"), latest.flagChapters.map { it.chapterTitle })
            assertEquals("moj predlog", latest.flagChapters[0].flags.single().comment)
            assertNull(latest.flagChapters[1].flags.single().comment)
        }

    @Test
    fun `uiState excludes flags from other books`() =
        runTest(testDispatcher) {
            val flagDao = FakeTranslationFlagDao()
            val flagRepo = flagRepository(flagDao)
            flagRepo.create("book-1", "moj", "{}", "Poglavje ena")
            flagRepo.create("book-2", "tuj", "{}", "Poglavje ena")

            val viewModel = viewModel(repository(FakeHighlightDao()), flagRepo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(
                listOf("moj"),
                viewModel.uiState.value.flagChapters.flatMap { it.flags }.map { it.selectedText },
            )
        }

    @Test
    fun `a book with only flags is not the empty state`() =
        runTest(testDispatcher) {
            val flagRepo = flagRepository(FakeTranslationFlagDao())
            flagRepo.create("book-1", "slab prevod", "{}", null)

            val viewModel = viewModel(repository(FakeHighlightDao()), flagRepo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            // Reading `chapters.isEmpty()` here would tell the screen to print "no highlights
            // or notes yet" over a list of real flags.
            assertFalse(viewModel.uiState.value.isEmpty)
            assertTrue(viewModel.uiState.value.chapters.isEmpty())
        }

    @Test
    fun `deleteFlag tombstones through the repository`() =
        runTest(testDispatcher) {
            val flagDao = FakeTranslationFlagDao()
            val flagRepo = flagRepository(flagDao)
            val id = flagRepo.create("book-1", "slab prevod", "{}", null)
            val viewModel = viewModel(repository(FakeHighlightDao()), flagRepo)

            viewModel.deleteFlag(id)
            advanceUntilIdle()

            assertNull(flagDao.getById(id))
            assertNotNull("a deleted flag keeps its row so a sync client can propagate it", flagDao.getAnyById(id))
        }

    @Test
    fun `exportMarkdown includes flags in their own section`() =
        runTest(testDispatcher) {
            val repo = repository(FakeHighlightDao())
            repo.create("book-1", HighlightColor.AMBER, "izbrana vrstica", "{}", "Poglavje ena")
            val flagRepo = flagRepository(FakeTranslationFlagDao())
            flagRepo.create("book-1", "slab prevod", "{}", "Poglavje ena", comment = "moj predlog")

            val viewModel = viewModel(repo, flagRepo)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(
                "# Testna knjiga\n\n## Poglavje ena\n\n> izbrana vrstica\n\n" +
                    "## Flagged translations\n\n### Poglavje ena\n\n" +
                    "> slab prevod\n\n**Flagged as a bad translation.**\n\nmoj predlog\n",
                viewModel.exportMarkdown(),
            )
        }
}
