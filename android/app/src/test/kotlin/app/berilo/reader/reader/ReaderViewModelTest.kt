package app.berilo.reader.reader

import app.berilo.reader.store.db.BookEntity
import app.berilo.reader.store.importer.FakeBookDao
import app.berilo.reader.store.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel state logic on the host JVM (no Readium runtime, no Robolectric):
 * position persistence is exercised with plain JSON strings, since the ViewModel
 * treats a Locator as an opaque encoded string (encoding is [LocatorCodec]'s job,
 * covered by its own Robolectric test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val bookId = "book-1"

    private class FakeSettingsStore(initial: ReaderPreferences = ReaderPreferences()) : ReaderSettingsStore {
        var saved: ReaderPreferences = initial
        override fun load(): ReaderPreferences = saved
        override fun save(preferences: ReaderPreferences) {
            saved = preferences
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedDao(progression: String? = null): FakeBookDao {
        val dao = FakeBookDao()
        dao.insert(
            BookEntity(
                id = bookId,
                title = "The Test Book",
                authors = "Author",
                filePath = "/tmp/$bookId.epub",
                coverPath = null,
                addedAt = 1L,
                lastOpenedAt = null,
                progressionJson = progression,
            ),
        )
        return dao
    }

    private fun viewModel(dao: FakeBookDao, store: ReaderSettingsStore): ReaderViewModel =
        ReaderViewModel(
            repository = BookRepository(dao, ioDispatcher = dispatcher),
            bookId = bookId,
            settingsStore = store,
            ioDispatcher = dispatcher,
            clock = { FIXED_NOW },
            debounceMillis = DEBOUNCE,
        )

    @Test
    fun `preferences default to e-ink mode on, loaded from the store`() =
        runTest(dispatcher) {
            val vm = viewModel(seedDao(), FakeSettingsStore())
            advanceUntilIdle()

            assertTrue("e-ink mode is the Boox default", vm.preferences.value.einkMode)
        }

    @Test
    fun `book and restored locator load on init`() =
        runTest(dispatcher) {
            val vm = viewModel(seedDao(progression = SEED_LOCATOR), FakeSettingsStore())
            advanceUntilIdle()

            assertEquals("The Test Book", vm.book.value?.title)
            assertEquals(SEED_LOCATOR, vm.initialLocatorJson.value)
        }

    @Test
    fun `font size steps up and down and persists to the store`() =
        runTest(dispatcher) {
            val store = FakeSettingsStore()
            val vm = viewModel(seedDao(), store)
            advanceUntilIdle()

            val start = vm.preferences.value.fontSizePercent
            vm.increaseFontSize()
            assertEquals(start + ReaderPreferences.FONT_SIZE_STEP_PERCENT, vm.preferences.value.fontSizePercent)
            assertEquals(vm.preferences.value, store.saved)

            vm.decreaseFontSize()
            assertEquals(start, vm.preferences.value.fontSizePercent)
        }

    @Test
    fun `font size will not step below the minimum`() =
        runTest(dispatcher) {
            val store = FakeSettingsStore(
                ReaderPreferences(fontSizePercent = ReaderPreferences.MIN_FONT_SIZE_PERCENT),
            )
            val vm = viewModel(seedDao(), store)
            advanceUntilIdle()

            vm.decreaseFontSize()

            assertEquals(ReaderPreferences.MIN_FONT_SIZE_PERCENT, vm.preferences.value.fontSizePercent)
        }

    @Test
    fun `toggling e-ink mode updates state and the store`() =
        runTest(dispatcher) {
            val store = FakeSettingsStore()
            val vm = viewModel(seedDao(), store)
            advanceUntilIdle()

            vm.setEinkMode(false)

            assertFalse(vm.preferences.value.einkMode)
            assertFalse(store.saved.einkMode)
        }

    @Test
    fun `chrome visibility toggles`() =
        runTest(dispatcher) {
            val vm = viewModel(seedDao(), FakeSettingsStore())
            advanceUntilIdle()

            assertFalse("chrome is hidden while reading by default", vm.chromeVisible.value)
            vm.toggleChrome()
            assertTrue(vm.chromeVisible.value)
            vm.hideChrome()
            assertFalse(vm.chromeVisible.value)
        }

    @Test
    fun `a debounced position change is persisted to the book row`() =
        runTest(dispatcher) {
            val dao = seedDao()
            val vm = viewModel(dao, FakeSettingsStore())
            advanceUntilIdle()

            vm.onPositionChanged(NEW_LOCATOR)
            advanceTimeBy(DEBOUNCE + 1)
            advanceUntilIdle()

            assertEquals(NEW_LOCATOR, dao.getById(bookId)?.progressionJson)
            assertEquals(FIXED_NOW, dao.getById(bookId)?.lastOpenedAt)
        }

    @Test
    fun `persistPositionNow flushes the latest position immediately`() =
        runTest(dispatcher) {
            val dao = seedDao()
            val vm = viewModel(dao, FakeSettingsStore())
            advanceUntilIdle()

            vm.onPositionChanged(NEW_LOCATOR)
            vm.persistPositionNow()
            advanceUntilIdle()

            assertEquals(NEW_LOCATOR, dao.getById(bookId)?.progressionJson)
        }

    @Test
    fun `table of contents can be set for the drawer`() =
        runTest(dispatcher) {
            val vm = viewModel(seedDao(), FakeSettingsStore())
            advanceUntilIdle()

            val chapters = listOf(TocChapter("Chapter 1", "/ch1.html", 0))
            vm.setTableOfContents(chapters)

            assertEquals(chapters, vm.tableOfContents.value)
        }

    private companion object {
        const val FIXED_NOW = 42L
        const val DEBOUNCE = 1_000L
        const val SEED_LOCATOR = """{"href":"/ch1.html","type":"application/xhtml+xml"}"""
        const val NEW_LOCATOR = """{"href":"/ch2.html","type":"application/xhtml+xml"}"""
    }
}
