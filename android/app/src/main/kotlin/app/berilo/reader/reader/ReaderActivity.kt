package app.berilo.reader.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.berilo.reader.BeriloApplication
import app.berilo.reader.R
import app.berilo.reader.dictionary.DictionaryViewModel
import app.berilo.reader.dictionary.SelectionContext
import app.berilo.reader.dictionary.buildSelectionContext
import app.berilo.reader.interpretation.InterpretationViewModel
import app.berilo.reader.interpretation.resolveInterpretationPassage
import app.berilo.reader.ui.theme.BeriloTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private const val EXTRA_BOOK_ID = "app.berilo.reader.extra.BOOK_ID"
private const val NAVIGATOR_TAG = "reader.navigator"

/**
 * Reader screen. Hosts Readium's [EpubNavigatorFragment] (paginated rendering)
 * in `reader_container`, with the [ReaderChromeOverlay] Compose chrome on top.
 *
 * Wiring (device territory; the non-UI logic lives in the reader package and is
 * unit-tested separately): the publication is opened via the Streamer from the
 * app-private file path; the navigator opens at the [LocatorCodec]-decoded
 * position with [ReaderPreferences.toEpubPreferences] (e-ink defaults); its
 * `currentLocator` is encoded back and debounce-persisted through the ViewModel;
 * the TOC is mapped by [TocMapper]; a tap toggles the chrome via a Readium
 * [InputListener]; chapter taps call `navigator.go(link)`; [PerfLog] brackets
 * programmatic turns.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : FragmentActivity() {

    private val bookId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "ReaderActivity requires $EXTRA_BOOK_ID" }
    }

    private val viewModel: ReaderViewModel by viewModels {
        val repository = (application as BeriloApplication).container.bookRepository
        val settingsStore = SharedPrefsReaderSettingsStore(applicationContext)
        ReaderViewModel.Factory(repository, bookId, settingsStore)
    }

    private val dictionaryViewModel: DictionaryViewModel by viewModels {
        val container = (application as BeriloApplication).container
        DictionaryViewModel.Factory(container.settingsRepository, container.dictionaryRepository)
    }

    private val interpretationViewModel: InterpretationViewModel by viewModels {
        val container = (application as BeriloApplication).container
        InterpretationViewModel.Factory(container.settingsRepository, container.interpretationRepository)
    }

    private var navigator: EpubNavigatorFragment? = null
    private var openedPublication: Publication? = null
    private var pageTurnMark: Long? = null
    private val tocLinksByHref = mutableMapOf<String, Link>()

    private val chapterTitle = MutableStateFlow<String?>(null)
    private val progressFraction = MutableStateFlow(0f)
    private val openFailed = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // A dummy factory lets the FragmentManager re-instantiate a restored
        // navigator without the real Publication being ready yet; the restored
        // instance is discarded below and re-opened cleanly.
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reader_activity)

        val chrome = findViewById<ComposeView>(R.id.reader_chrome)
        chrome.setContent { ReaderChrome() }

        // The overlay is only hittable while chrome is shown, so the navigator's
        // WebView receives touches during reading.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chromeVisible.collect { visible -> chrome.isVisible = visible }
            }
        }

        supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)?.let { restored ->
            supportFragmentManager.beginTransaction().remove(restored).commitNowAllowingStateLoss()
        }
        lifecycleScope.launch { openBookAndAttachNavigator() }
    }

    override fun onPause() {
        super.onPause()
        // Never lose the reading position when leaving the foreground.
        viewModel.persistPositionNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the publication's archive/file handles (leak across recreations otherwise).
        openedPublication?.close()
        openedPublication = null
    }

    private suspend fun openBookAndAttachNavigator() {
        val filePath = viewModel.book.value?.filePath ?: awaitBookFilePath()
        val publication = openPublication(filePath)
        if (publication == null) {
            openFailed.value = true
            return
        }
        openedPublication = publication

        tocLinksByHref.clear()
        indexToc(publication.tableOfContents, tocLinksByHref)
        viewModel.setTableOfContents(TocMapper.flatten(publication.tableOfContents))

        val initialLocator = LocatorCodec.decode(viewModel.initialLocatorJson.value)
        val preferences = viewModel.preferences.value.toEpubPreferences()
        val navigatorFactory = EpubNavigatorFactory(publication)

        supportFragmentManager.fragmentFactory =
            navigatorFactory.createFragmentFactory(initialLocator, null, preferences)
        supportFragmentManager.beginTransaction()
            .replace(R.id.reader_container, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            .commitNow()

        val fragment = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
        navigator = fragment
        fragment?.let(::observeNavigator)
    }

    private fun observeNavigator(fragment: EpubNavigatorFragment) {
        fragment.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    viewModel.toggleChrome()
                    return true
                }

                override fun onDrag(event: DragEvent): Boolean = false

                override fun onKey(event: KeyEvent): Boolean = false
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fragment.currentLocator.collect { locator ->
                    pageTurnMark?.let {
                        PerfLog.pageTurnRendered(it)
                        pageTurnMark = null
                    }
                    chapterTitle.value = locator.title
                    progressFraction.value = (locator.locations.totalProgression ?: 0.0).toFloat()
                    viewModel.onPositionChanged(LocatorCodec.encode(locator))
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.preferences.collect { fragment.submitPreferences(it.toEpubPreferences()) }
            }
        }
    }

    /**
     * "Define" tap handler: captures the navigator's current text selection (device/WebView
     * territory — the pure sentence-reconstruction logic in [buildSelectionContext] is unit
     * tested separately) and starts a lookup, or tells the user to select a word first.
     */
    private fun onDefineSelectionClicked() {
        lifecycleScope.launch {
            val selection = captureSelection()
            if (selection == null) {
                Toast.makeText(this@ReaderActivity, R.string.reader_no_selection, Toast.LENGTH_SHORT).show()
            } else {
                dictionaryViewModel.lookup(selection)
            }
        }
    }

    /** Reads the current selection off the Readium navigator, if any. */
    private suspend fun captureSelection(): SelectionContext? {
        val text = navigator?.currentSelection()?.locator?.text ?: return null
        return buildSelectionContext(text.before.orEmpty(), text.highlight.orEmpty(), text.after.orEmpty())
    }

    /**
     * "Interpret" tap handler: unlike [onDefineSelectionClicked], sends the WHOLE current
     * selection (a passage/paragraph, not a single word) — no sentence reconstruction. Falls
     * back to the currently visible locator's `text.highlight` when nothing is selected, so a
     * long-press-free tap still interprets the page in view; if neither has text, tells the
     * user to select something.
     */
    private fun onInterpretSelectionClicked() {
        lifecycleScope.launch {
            val selectionHighlight = navigator?.currentSelection()?.locator?.text?.highlight
            val visibleHighlight = navigator?.currentLocator?.value?.text?.highlight
            val passage = resolveInterpretationPassage(selectionHighlight, visibleHighlight)
            val bookTitle = viewModel.book.value?.title.orEmpty()
            if (passage == null) {
                Toast.makeText(this@ReaderActivity, R.string.interpretation_no_selection, Toast.LENGTH_SHORT).show()
            } else {
                interpretationViewModel.interpret(passage, bookTitle)
            }
        }
    }

    private fun onChapterSelected(chapter: TocChapter) {
        val fragment = navigator ?: return
        tocLinksByHref[chapter.href]?.let { link ->
            pageTurnMark = PerfLog.pageTurnRequested()
            fragment.go(link, !viewModel.preferences.value.einkMode)
        }
        viewModel.hideChrome()
    }

    /** Opens the EPUB via the Streamer; returns null on any failure (loud error state). */
    private suspend fun openPublication(filePath: String): Publication? {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val opener =
            PublicationOpener(
                DefaultPublicationParser(
                    this,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                ),
            )
        val asset = assetRetriever.retrieve(File(filePath)).getOrElse { return null }
        return opener.open(asset, allowUserInteraction = false).getOrElse { return null }
    }

    private suspend fun awaitBookFilePath(): String =
        viewModel.book.first { it != null }!!.filePath

    private fun indexToc(links: List<Link>, out: MutableMap<String, Link>) {
        for (link in links) {
            out[link.href.toString()] = link
            if (link.children.isNotEmpty()) indexToc(link.children, out)
        }
    }

    @Composable
    private fun ReaderChrome() {
        val preferences by viewModel.preferences.collectAsStateWithLifecycle()
        val chromeVisible by viewModel.chromeVisible.collectAsStateWithLifecycle()
        val chapters by viewModel.tableOfContents.collectAsStateWithLifecycle()
        val title by chapterTitle.collectAsStateWithLifecycle()
        val progress by progressFraction.collectAsStateWithLifecycle()
        val failed by openFailed.collectAsStateWithLifecycle()
        val dictionaryState by dictionaryViewModel.uiState.collectAsStateWithLifecycle()
        val interpretationState by interpretationViewModel.uiState.collectAsStateWithLifecycle()

        BeriloTheme(useDarkTheme = !preferences.einkMode && preferences.darkTheme) {
            if (failed) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.reader_open_failed),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@BeriloTheme
            }

            var panel by remember { mutableStateOf(ReaderPanel.NONE) }
            if (!chromeVisible) panel = ReaderPanel.NONE

            val state = ReaderChromeState(
                chromeVisible = chromeVisible,
                panel = panel,
                chapterTitle = title
                    ?: (viewModel.book.value?.title ?: stringResource(R.string.reader_loading)),
                progressFraction = progress,
                preferences = preferences,
                chapters = chapters,
                dictionaryState = dictionaryState,
                interpretationState = interpretationState,
            )
            val actions = ReaderChromeActions(
                onToggleChrome = { viewModel.toggleChrome() },
                onOpenChapters = { panel = ReaderPanel.CHAPTERS },
                onOpenSettings = { panel = ReaderPanel.SETTINGS },
                onClosePanel = { panel = ReaderPanel.NONE },
                onChapterSelected = { chapter ->
                    panel = ReaderPanel.NONE
                    onChapterSelected(chapter)
                },
                onFontSmaller = viewModel::decreaseFontSize,
                onFontLarger = viewModel::increaseFontSize,
                onMarginsNarrower = viewModel::decreaseMargins,
                onMarginsWider = viewModel::increaseMargins,
                onEinkModeChange = viewModel::setEinkMode,
                onDarkThemeChange = viewModel::setDarkTheme,
                onDefineSelection = ::onDefineSelectionClicked,
                onDismissDictionary = dictionaryViewModel::dismiss,
                onInterpretSelection = ::onInterpretSelectionClicked,
                onDismissInterpretation = interpretationViewModel::dismiss,
            )
            ReaderChromeOverlay(state = state, actions = actions)
        }
    }

    companion object {
        fun newIntent(context: Context, bookId: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
