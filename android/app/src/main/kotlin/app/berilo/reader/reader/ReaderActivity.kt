package app.berilo.reader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
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
import androidx.compose.ui.graphics.toArgb
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
import app.berilo.reader.annotations.Highlight
import app.berilo.reader.annotations.HighlightViewModel
import app.berilo.reader.annotations.NotebookActivity
import app.berilo.reader.annotations.TranslationFlagViewModel
import app.berilo.reader.annotations.toComposeColor
import app.berilo.reader.dictionary.DictionaryViewModel
import app.berilo.reader.dictionary.buildSelectionContext
import app.berilo.reader.interpretation.InterpretationViewModel
import app.berilo.reader.interpretation.resolveInterpretationPassage
import app.berilo.reader.ui.theme.BeriloTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private const val EXTRA_BOOK_ID = "app.berilo.reader.extra.BOOK_ID"
private const val EXTRA_TARGET_LOCATOR = "app.berilo.reader.extra.TARGET_LOCATOR"
private const val NAVIGATOR_TAG = "reader.navigator"

/** Decoration group name Readium keys the S2.6 highlight decorations under (its own
 * namespace — other decoration groups, if any land later, use a different name). */
private const val HIGHLIGHT_DECORATION_GROUP = "highlights"

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
 * programmatic turns; a text selection raises Berilo's own action bar through
 * [beriloNavigatorConfiguration] and dispatches to [onSelectionAction].
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : FragmentActivity() {

    private val bookId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "ReaderActivity requires $EXTRA_BOOK_ID" }
    }

    /** Locator JSON to jump to once the navigator is ready, set when opened from the
     * notebook's "tap to jump back" action ([NotebookActivity.newIntent]-style callers). */
    private val targetLocatorJson: String? by lazy { intent.getStringExtra(EXTRA_TARGET_LOCATOR) }
    private var targetLocatorApplied = false

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

    private val highlightViewModel: HighlightViewModel by viewModels {
        val container = (application as BeriloApplication).container
        HighlightViewModel.Factory(bookId, container.annotationsRepository)
    }

    private val flagViewModel: TranslationFlagViewModel by viewModels {
        val container = (application as BeriloApplication).container
        TranslationFlagViewModel.Factory(bookId, container.translationFlagRepository)
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

        // The overlay is only hittable while something is actually shown in it, so the
        // navigator's WebView receives touches (including long-press-to-select) during
        // reading. S2.12: "something" is no longer just the chrome bars — the annotation
        // editor and the two LLM sheets now open from the text selection, with the bars
        // hidden, and would otherwise render inside a GONE view. See [readerOverlayVisible].
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.chromeVisible,
                    dictionaryViewModel.uiState,
                    interpretationViewModel.uiState,
                    highlightViewModel.editorState,
                    flagViewModel.editorState,
                    ::readerOverlayVisible,
                ).collect { visible -> chrome.isVisible = visible }
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

        // S2.12: `configuration` is what makes text selection usable. Left at its default,
        // `selectionActionModeCallback` is null and R2BasicWebView falls straight through to
        // WebView.startActionMode — the platform bar (Copy / Share / Web search / every
        // installed PROCESS_TEXT app), with no way to reach Highlight or Note.
        supportFragmentManager.fragmentFactory =
            navigatorFactory.createFragmentFactory(
                initialLocator = initialLocator,
                initialPreferences = preferences,
                configuration = beriloNavigatorConfiguration(::onSelectionAction),
            )
        supportFragmentManager.beginTransaction()
            .replace(R.id.reader_container, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            .commitNow()

        val fragment = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as? EpubNavigatorFragment
        navigator = fragment
        fragment?.let(::observeNavigator)
        applyTargetLocatorIfPending(fragment)
    }

    /**
     * Jumps to [targetLocatorJson] once, the moment the navigator is attached (the notebook's
     * "tap an entry to jump back into the book" action, `docs/project_plan.md` S2.6). A no-op
     * if there is no pending target or it was already applied (e.g. a config-change
     * recreation replays [openBookAndAttachNavigator] with the same intent extras).
     */
    private fun applyTargetLocatorIfPending(fragment: EpubNavigatorFragment?) {
        if (targetLocatorApplied) return
        val locator = LocatorCodec.decode(targetLocatorJson) ?: return
        targetLocatorApplied = true
        fragment?.go(locator, !viewModel.preferences.value.einkMode)
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

        // S2.6: render every stored highlight as a decoration, re-applying the whole set on
        // every change to `highlightViewModel.highlights` (a fresh collect immediately
        // replays the StateFlow's current value, which covers "on locator load" too — there
        // is no separate initial-apply call).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                highlightViewModel.highlights.collect { highlights -> applyHighlightDecorations(fragment, highlights) }
            }
        }
    }

    /**
     * Renders [highlights] as Readium decorations on [fragment] (device/WebView territory —
     * kept thin per the story's scope note). A highlight whose stored locator no longer
     * decodes is silently skipped rather than crashing the whole render pass.
     */
    private suspend fun applyHighlightDecorations(fragment: EpubNavigatorFragment, highlights: List<Highlight>) {
        val decorable = fragment as? DecorableNavigator ?: return
        val decorations =
            highlights.mapNotNull { highlight ->
                val locator = LocatorCodec.decode(highlight.locatorJson) ?: return@mapNotNull null
                Decoration(
                    id = highlight.id,
                    locator = locator,
                    style = Decoration.Style.Highlight(tint = highlight.color.toComposeColor().toArgb()),
                )
            }
        decorable.applyDecorations(decorations, HIGHLIGHT_DECORATION_GROUP)
    }

    /**
     * Runs one [SelectionAction] picked from Berilo's selection popup (S2.12).
     *
     * Ordering is the whole point of this function. `ActionMode.finish()` clears the WebView
     * selection, and `currentSelection()` is a suspending round trip through the JS bridge —
     * so the selection is read *first*, the bar dismissed second, and the action run against
     * the captured [Locator]. Doing it the other way round reproduces exactly the S2.6 defect
     * this story fixes: a handler that always sees `null` and toasts instead of acting.
     */
    private fun onSelectionAction(action: SelectionAction, mode: ActionMode) {
        lifecycleScope.launch {
            val locator = navigator?.currentSelection()?.locator
            mode.finish()
            navigator?.clearSelection()
            when (action) {
                SelectionAction.HIGHLIGHT -> beginAnnotation(locator, withNote = false)
                SelectionAction.NOTE -> beginAnnotation(locator, withNote = true)
                SelectionAction.DEFINE -> define(locator)
                SelectionAction.INTERPRET -> interpret(locator?.text?.highlight)
                SelectionAction.FLAG -> beginFlag(locator)
                SelectionAction.COPY -> copyToClipboard(locator?.text?.highlight)
            }
        }
    }

    /**
     * Opens the color picker ([withNote] false) or the note editor ([withNote] true) for
     * [locator]. A highlight needs the whole anchor position, not just the selected text —
     * [applyHighlightDecorations] re-renders from it and the notebook jumps back to it.
     */
    private fun beginAnnotation(locator: Locator?, withNote: Boolean) {
        val target = highlightTarget(locator)
        if (target == null) {
            toastSelectionLost()
            return
        }
        if (withNote) {
            highlightViewModel.beginNote(target.text, target.locatorJson, target.chapterTitle)
        } else {
            highlightViewModel.beginHighlight(target.text, target.locatorJson, target.chapterTitle)
        }
    }

    /**
     * Opens the "Bad translation" sheet for [locator] (B9).
     *
     * Reuses [highlightTarget] deliberately: a flag needs exactly what a highlight needs — the
     * selected text as the user saw it, plus the anchor to jump back to — and the flagged text
     * doubles as the key the translation cache is matched on
     * ([app.berilo.reader.annotations.TranslationProvenanceResolver]).
     */
    private fun beginFlag(locator: Locator?) {
        val target = highlightTarget(locator)
        if (target == null) {
            toastSelectionLost()
            return
        }
        flagViewModel.beginFlag(target.text, target.locatorJson, target.chapterTitle)
    }

    /**
     * Looks up the selected word (the pure sentence-reconstruction logic in
     * [buildSelectionContext] is unit tested separately), or reports a lost selection.
     */
    private fun define(locator: Locator?) {
        val text = locator?.text
        val selection =
            text?.let { buildSelectionContext(it.before.orEmpty(), it.highlight.orEmpty(), it.after.orEmpty()) }
        if (selection == null) {
            toastSelectionLost()
        } else {
            dictionaryViewModel.lookup(selection)
        }
    }

    /**
     * Interprets [selectionHighlight] — unlike [define], the WHOLE passage, with no sentence
     * reconstruction. Falls back to the currently visible locator's `text.highlight` when it
     * is null, which is what the chrome's "Interpret" button relies on to interpret the page
     * in view with nothing selected.
     */
    private fun interpret(selectionHighlight: String?) {
        val visibleHighlight = navigator?.currentLocator?.value?.text?.highlight
        val passage = resolveInterpretationPassage(selectionHighlight, visibleHighlight)
        if (passage == null) {
            Toast.makeText(this, R.string.interpretation_no_selection, Toast.LENGTH_SHORT).show()
        } else {
            interpretationViewModel.interpret(passage, viewModel.book.value?.title.orEmpty())
        }
    }

    /** Puts the selected passage on the clipboard — the one platform action worth keeping
     * from the bar S2.12 replaced. */
    private fun copyToClipboard(text: String?) {
        if (text.isNullOrBlank()) {
            toastSelectionLost()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.reader_selection_clip_label), text))
        // Android 13 shows its own copy confirmation; a second toast would double it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.reader_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toastSelectionLost() {
        Toast.makeText(this, R.string.reader_no_selection, Toast.LENGTH_SHORT).show()
    }

    /** The annotation target [locator] anchors, or `null` when it carries no selected text. */
    private fun highlightTarget(locator: Locator?): HighlightTarget? {
        if (locator == null) return null
        val text = locator.text.highlight?.trim().orEmpty()
        if (text.isEmpty()) return null
        return HighlightTarget(text = text, locatorJson = LocatorCodec.encode(locator), chapterTitle = locator.title)
    }

    /** Opens the per-book notebook screen ([NotebookActivity]). */
    private fun onOpenNotebookClicked() {
        startActivity(NotebookActivity.newIntent(this, bookId, viewModel.book.value?.title.orEmpty()))
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
        val annotationEditorState by highlightViewModel.editorState.collectAsStateWithLifecycle()
        val flagEditorState by flagViewModel.editorState.collectAsStateWithLifecycle()

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
                annotationEditorState = annotationEditorState,
                flagEditorState = flagEditorState,
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
                onDismissDictionary = dictionaryViewModel::dismiss,
                // S2.12: the only selection action that survives in the chrome, because it is
                // the only one that works without a selection — it falls back to the visible
                // page. Define/Highlight/Note moved onto the selection popup, where the
                // selection they need is still alive.
                onInterpretVisiblePage = { interpret(selectionHighlight = null) },
                onDismissInterpretation = interpretationViewModel::dismiss,
                onOpenNotebook = ::onOpenNotebookClicked,
                onAnnotationColorSelected = highlightViewModel::confirmColor,
                onAnnotationNoteColorChanged = highlightViewModel::onNoteColorChanged,
                onAnnotationNoteTextChanged = highlightViewModel::onNoteTextChanged,
                onConfirmAnnotationNote = highlightViewModel::confirmNote,
                onDismissAnnotationEditor = highlightViewModel::dismissEditor,
                onFlagCommentChanged = flagViewModel::onCommentChanged,
                onConfirmFlag = flagViewModel::confirmFlag,
                onDismissFlagEditor = flagViewModel::dismissEditor,
            )
            ReaderChromeOverlay(state = state, actions = actions)
        }
    }

    /** Result of capturing the navigator's current selection for a highlight/note (S2.6):
     * the selected text, its full Locator JSON (anchor + jump-back target), and the
     * enclosing chapter's title, if any. */
    private data class HighlightTarget(val text: String, val locatorJson: String, val chapterTitle: String?)

    companion object {
        /**
         * @param targetLocatorJson Readium Locator JSON to jump to once the navigator is
         *   ready (the notebook's "tap to jump back" action), or null to open at the book's
         *   normally persisted position.
         */
        fun newIntent(context: Context, bookId: String, targetLocatorJson: String? = null): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_TARGET_LOCATOR, targetLocatorJson)
    }
}
