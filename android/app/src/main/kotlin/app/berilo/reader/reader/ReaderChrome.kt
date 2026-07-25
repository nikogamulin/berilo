package app.berilo.reader.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import app.berilo.reader.annotations.AnnotationEditorActions
import app.berilo.reader.annotations.AnnotationEditorHost
import app.berilo.reader.annotations.AnnotationEditorUiState
import app.berilo.reader.dictionary.DictionarySheet
import app.berilo.reader.dictionary.DictionaryUiState
import app.berilo.reader.interpretation.InterpretationSheet
import app.berilo.reader.interpretation.InterpretationUiState
import app.berilo.reader.store.db.HighlightColor

// S2.11: the chapter title keeps at least this much width before ellipsising, so it can
// never be squeezed out of existence by the action row at narrow widths.
private val ChapterTitleMinWidth = 72.dp

/**
 * The reader's chrome overlay: a top bar (current chapter + entry points to
 * chapters and text settings), a bottom progress indicator, and the settings /
 * TOC panels. Rendered in a ComposeView on top of the Readium navigator's
 * rendering surface — it holds no reading surface of its own.
 *
 * Deference (design_guidelines.md §1): the chrome is absent while reading; the
 * host makes this overlay visible only when [ReaderChromeState.chromeVisible] is
 * true (a Readium tap toggles it). When shown, a tap on the scrim (anywhere not
 * on a control) dismisses it again.
 */
@Composable
fun ReaderChromeOverlay(
    state: ReaderChromeState,
    actions: ReaderChromeActions,
    modifier: Modifier = Modifier,
) {
    // No ripple/indication: an e-ink page must not flash on tap.
    val tapSource = remember { MutableInteractionSource() }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    onClick = actions.onToggleChrome,
                    indication = null,
                    interactionSource = tapSource,
                ),
        )

        if (state.chromeVisible) {
            ReaderTopBar(
                chapterTitle = state.chapterTitle,
                onChaptersClick = actions.onOpenChapters,
                onDefineClick = actions.onDefineSelection,
                onInterpretClick = actions.onInterpretSelection,
                onHighlightClick = actions.onHighlightSelection,
                onNoteClick = actions.onNoteSelection,
                onNotebookClick = actions.onOpenNotebook,
                onSettingsClick = actions.onOpenSettings,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ReaderBottomBar(
                progressFraction = state.progressFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        when (state.panel) {
            ReaderPanel.NONE -> Unit
            ReaderPanel.SETTINGS ->
                ReaderSettingsPanel(
                    preferences = state.preferences,
                    actions = actions,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            ReaderPanel.CHAPTERS ->
                ReaderTocPanel(
                    chapters = state.chapters,
                    onChapterClick = actions.onChapterSelected,
                    onDone = actions.onClosePanel,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
        }

        // Hosts the LLM dictionary (S2.4): a "Define" tap in the top bar captures the
        // navigator's current text selection and drives this state via DictionaryViewModel.
        DictionarySheet(uiState = state.dictionaryState, onDismiss = actions.onDismissDictionary)

        // Hosts paragraph interpretation (S2.5): an "Interpret" tap in the top bar sends the
        // whole current selection (or the visible locator's text as a fallback) and drives
        // this state via InterpretationViewModel.
        InterpretationSheet(uiState = state.interpretationState, onDismiss = actions.onDismissInterpretation)

        // Hosts highlight/note creation (S2.6): "Highlight"/"Note" taps in the top bar capture
        // the navigator's current selection and drive this state via HighlightViewModel.
        AnnotationEditorHost(
            state = state.annotationEditorState,
            actions =
                AnnotationEditorActions(
                    onColorSelected = actions.onAnnotationColorSelected,
                    onNoteColorChanged = actions.onAnnotationNoteColorChanged,
                    onNoteTextChanged = actions.onAnnotationNoteTextChanged,
                    onConfirmNote = actions.onConfirmAnnotationNote,
                    onDismiss = actions.onDismissAnnotationEditor,
                ),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ReaderTopBar(
    chapterTitle: String,
    onChaptersClick: () -> Unit,
    onDefineClick: () -> Unit,
    onInterpretClick: () -> Unit,
    onHighlightClick: () -> Unit,
    onNoteClick: () -> Unit,
    onNotebookClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onChaptersClick) {
                Text(stringResource(R.string.reader_chapters))
            }
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = ChapterTitleMinWidth)
                    .padding(horizontal = 8.dp),
            )
            // Horizontally scrollable so the growing action set (S2.6 adds Highlight/Note/
            // Notebook to Define/Interpret/Settings) never clips the chapter title above.
            //
            // S2.11: the action row MUST carry a weight. Without one it is measured at its
            // unconstrained preferred width before the weighted title gets any space, so at
            // phone width (411 dp) the six buttons consumed the row and the title collapsed
            // to zero width — it vanished outright rather than ellipsising, despite
            // TextOverflow.Ellipsis. `fill = false` lets the row take less than its share
            // when the buttons already fit (unchanged at Boox's 990 dp).
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDefineClick) {
                    Text(stringResource(R.string.reader_define))
                }
                TextButton(onClick = onInterpretClick) {
                    Text(stringResource(R.string.reader_interpret))
                }
                TextButton(onClick = onHighlightClick) {
                    Text(stringResource(R.string.reader_highlight))
                }
                TextButton(onClick = onNoteClick) {
                    Text(stringResource(R.string.reader_note))
                }
                TextButton(onClick = onNotebookClick) {
                    Text(stringResource(R.string.reader_notebook))
                }
                TextButton(onClick = onSettingsClick) {
                    Text(stringResource(R.string.reader_settings))
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    progressFraction: Float,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(
                    R.string.reader_progress_percent,
                    (progressFraction * 100).toInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ReaderSettingsPanel(
    preferences: ReaderPreferences,
    actions: ReaderChromeActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            StepperRow(
                label = stringResource(R.string.reader_font_size),
                value = "${preferences.fontSizePercent}%",
                onDecrease = actions.onFontSmaller,
                onIncrease = actions.onFontLarger,
                decreaseCd = stringResource(R.string.reader_font_smaller),
                increaseCd = stringResource(R.string.reader_font_larger),
            )
            StepperRow(
                label = stringResource(R.string.reader_margins),
                value = "${preferences.pageMarginsPercent}%",
                onDecrease = actions.onMarginsNarrower,
                onIncrease = actions.onMarginsWider,
                decreaseCd = stringResource(R.string.reader_margins_narrower),
                increaseCd = stringResource(R.string.reader_margins_wider),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ToggleRow(
                label = stringResource(R.string.reader_eink_mode),
                checked = preferences.einkMode,
                onCheckedChange = actions.onEinkModeChange,
            )
            ToggleRow(
                label = stringResource(R.string.reader_dark_theme),
                checked = preferences.darkTheme,
                onCheckedChange = actions.onDarkThemeChange,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = actions.onClosePanel) {
                    Text(stringResource(R.string.reader_done))
                }
            }
        }
    }
}

@Composable
private fun ReaderTocPanel(
    chapters: List<TocChapter>,
    onChapterClick: (TocChapter) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(chapters) { chapter ->
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(chapter) }
                            .padding(
                                start = (16 + chapter.depth * 16).dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.reader_done))
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseCd: String,
    increaseCd: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDecrease) { Text("−") }
        Text(
            text = value,
            modifier = Modifier
                .width(64.dp)
                .padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onIncrease) { Text("+") }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Which secondary panel, if any, is open over the reader. */
enum class ReaderPanel { NONE, SETTINGS, CHAPTERS }

/** Immutable snapshot the [ReaderScaffold] renders. */
data class ReaderChromeState(
    val chromeVisible: Boolean,
    val panel: ReaderPanel,
    val chapterTitle: String,
    val progressFraction: Float,
    val preferences: ReaderPreferences,
    val chapters: List<TocChapter>,
    val dictionaryState: DictionaryUiState = DictionaryUiState.Idle,
    val interpretationState: InterpretationUiState = InterpretationUiState.Idle,
    val annotationEditorState: AnnotationEditorUiState = AnnotationEditorUiState.Idle,
)

/** Callbacks the [ReaderScaffold] invokes; wired to the [ReaderViewModel]. */
data class ReaderChromeActions(
    val onToggleChrome: () -> Unit,
    val onOpenChapters: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onClosePanel: () -> Unit,
    val onChapterSelected: (TocChapter) -> Unit,
    val onFontSmaller: () -> Unit,
    val onFontLarger: () -> Unit,
    val onMarginsNarrower: () -> Unit,
    val onMarginsWider: () -> Unit,
    val onEinkModeChange: (Boolean) -> Unit,
    val onDarkThemeChange: (Boolean) -> Unit,
    val onDefineSelection: () -> Unit = {},
    val onDismissDictionary: () -> Unit = {},
    val onInterpretSelection: () -> Unit = {},
    val onDismissInterpretation: () -> Unit = {},
    val onHighlightSelection: () -> Unit = {},
    val onNoteSelection: () -> Unit = {},
    val onOpenNotebook: () -> Unit = {},
    val onAnnotationColorSelected: (HighlightColor) -> Unit = {},
    val onAnnotationNoteColorChanged: (HighlightColor) -> Unit = {},
    val onAnnotationNoteTextChanged: (String) -> Unit = {},
    val onConfirmAnnotationNote: () -> Unit = {},
    val onDismissAnnotationEditor: () -> Unit = {},
)
