package app.berilo.reader.reader

import app.berilo.reader.annotations.AnnotationEditorUiState
import app.berilo.reader.annotations.FlagEditorUiState
import app.berilo.reader.dictionary.DictionaryUiState
import app.berilo.reader.interpretation.InterpretationUiState

/**
 * Whether the reader's chrome `ComposeView` must be visible (and therefore hittable).
 *
 * The view hosts more than the top/bottom bars: the annotation editor and the
 * dictionary/interpretation sheets live in it too. While every action that opened those
 * surfaces lived *in* the chrome, tracking [chromeVisible] alone was enough. S2.12 moved
 * them onto the text selection, where chrome is hidden by definition — so a sheet would
 * open inside a `GONE` view and the highlight would be silently written with no UI.
 *
 * B9's flag editor is the same shape and carries the same hazard: every surface opened from
 * the selection popup must be named here, or it opens invisibly and the user's gesture appears
 * to do nothing at all.
 */
fun readerOverlayVisible(
    chromeVisible: Boolean,
    dictionary: DictionaryUiState,
    interpretation: InterpretationUiState,
    editor: AnnotationEditorUiState,
    flagEditor: FlagEditorUiState,
): Boolean =
    chromeVisible ||
        dictionary !is DictionaryUiState.Idle ||
        interpretation !is InterpretationUiState.Idle ||
        editor !is AnnotationEditorUiState.Idle ||
        flagEditor !is FlagEditorUiState.Idle

/** What a tap on the reader's full-screen scrim means. */
enum class ScrimTap {
    /** Show or hide the chrome bars — the reading-time default. */
    TOGGLE_CHROME,

    /** Cancel an in-progress highlight instead. */
    DISMISS_ANNOTATION_EDITOR,
}

/**
 * Resolves a scrim tap. The colour picker is an inline row, not a `ModalBottomSheet`, so it
 * has no scrim of its own; without this case the only full-screen target behind it toggles
 * chrome *on*, stranding the picker over a top bar the user did not ask for. The note editor
 * is modal and brings its own dismissal, so it is left alone.
 */
fun scrimTapAction(chromeVisible: Boolean, editor: AnnotationEditorUiState): ScrimTap =
    if (!chromeVisible && editor is AnnotationEditorUiState.ColorPicker) {
        ScrimTap.DISMISS_ANNOTATION_EDITOR
    } else {
        ScrimTap.TOGGLE_CHROME
    }
