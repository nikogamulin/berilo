package app.berilo.reader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.reader.ReaderPreferences.Companion.FONT_SIZE_STEP_PERCENT
import app.berilo.reader.reader.ReaderPreferences.Companion.MARGINS_STEP_PERCENT
import app.berilo.reader.store.repository.Book
import app.berilo.reader.store.repository.BookRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the reader screen. Deliberately free of Readium navigator
 * types so its logic — preferences, chrome visibility, chapter list, and the
 * debounced position-persistence path — is unit-testable on the host JVM.
 *
 * The navigator host (the Activity) does the Readium-facing work: it encodes the
 * live [org.readium.r2.shared.publication.Locator] to JSON via [LocatorCodec]
 * and feeds the string to [onPositionChanged], maps `publication.tableOfContents`
 * through [TocMapper] into [setTableOfContents], and decodes [initialLocatorJson]
 * to restore the exact opening position.
 *
 * @param clock Time source for open/position timestamps, injectable for tests.
 * @param ioDispatcher Dispatcher persistence runs on (findings.md pattern).
 * @param debounceMillis Quiet period before a coalesced position write.
 */
class ReaderViewModel(
    private val repository: BookRepository,
    private val bookId: String,
    private val settingsStore: ReaderSettingsStore,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    debounceMillis: Long = POSITION_SAVE_DEBOUNCE_MS,
) : ViewModel() {

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _preferences = MutableStateFlow(settingsStore.load())
    val preferences: StateFlow<ReaderPreferences> = _preferences.asStateFlow()

    private val _chromeVisible = MutableStateFlow(false)
    val chromeVisible: StateFlow<Boolean> = _chromeVisible.asStateFlow()

    private val _tableOfContents = MutableStateFlow<List<TocChapter>>(emptyList())
    val tableOfContents: StateFlow<List<TocChapter>> = _tableOfContents.asStateFlow()

    private val _initialLocatorJson = MutableStateFlow<String?>(null)

    /** Readium Locator JSON to open at, or null to start from the beginning. */
    val initialLocatorJson: StateFlow<String?> = _initialLocatorJson.asStateFlow()

    private val persister =
        LocatorPersister(viewModelScope, debounceMillis, ioDispatcher) { json ->
            repository.saveProgression(bookId, json, clock())
        }

    init {
        viewModelScope.launch {
            _initialLocatorJson.value = repository.getProgression(bookId)
            _book.value = repository.getBook(bookId)
            repository.markOpened(bookId, clock())
        }
    }

    /** Records a new reading position (Readium Locator JSON); write is debounced. */
    fun onPositionChanged(locatorJson: String) = persister.onChanged(locatorJson)

    /** Flushes the latest position to storage now; called from `onPause`. */
    fun persistPositionNow() = persister.flush()

    /** Replaces the chapter list shown in the TOC drawer. */
    fun setTableOfContents(chapters: List<TocChapter>) {
        _tableOfContents.value = chapters
    }

    /** Toggles the reading chrome (tap-center gesture). */
    fun toggleChrome() {
        _chromeVisible.value = !_chromeVisible.value
    }

    /** Hides the chrome (e.g. after picking a chapter). */
    fun hideChrome() {
        _chromeVisible.value = false
    }

    fun increaseFontSize() =
        updatePreferences(
            _preferences.value.withFontSizePercent(
                _preferences.value.fontSizePercent + FONT_SIZE_STEP_PERCENT,
            ),
        )

    fun decreaseFontSize() =
        updatePreferences(
            _preferences.value.withFontSizePercent(
                _preferences.value.fontSizePercent - FONT_SIZE_STEP_PERCENT,
            ),
        )

    fun increaseMargins() =
        updatePreferences(
            _preferences.value.withPageMarginsPercent(
                _preferences.value.pageMarginsPercent + MARGINS_STEP_PERCENT,
            ),
        )

    fun decreaseMargins() =
        updatePreferences(
            _preferences.value.withPageMarginsPercent(
                _preferences.value.pageMarginsPercent - MARGINS_STEP_PERCENT,
            ),
        )

    /** Turns e-ink mode (pure B/W, no animation, full-refresh turns) on or off. */
    fun setEinkMode(enabled: Boolean) =
        updatePreferences(_preferences.value.copy(einkMode = enabled))

    /** Sets the OLED dark theme (only effective when e-ink mode is off). */
    fun setDarkTheme(enabled: Boolean) =
        updatePreferences(_preferences.value.copy(darkTheme = enabled))

    private fun updatePreferences(updated: ReaderPreferences) {
        _preferences.value = updated
        settingsStore.save(updated)
    }

    class Factory(
        private val repository: BookRepository,
        private val bookId: String,
        private val settingsStore: ReaderSettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(repository, bookId, settingsStore) as T
    }

    companion object {
        /** Coalesce a burst of locator updates into one write after this quiet period. */
        const val POSITION_SAVE_DEBOUNCE_MS = 1_000L
    }
}
