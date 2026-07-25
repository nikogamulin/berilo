package app.berilo.reader.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import app.berilo.reader.store.db.HighlightColor

/** Callbacks the annotation editor (color picker / note editor) invokes. */
data class AnnotationEditorActions(
    val onColorSelected: (HighlightColor) -> Unit,
    val onNoteColorChanged: (HighlightColor) -> Unit,
    val onNoteTextChanged: (String) -> Unit,
    val onConfirmNote: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Hosts the reader's "Highlight"/"Note" creation UI for the current [AnnotationEditorUiState]:
 * a bare color row for a plain highlight, or a bottom sheet with a color row + text field for
 * a note. Not shown (no-op) when [state] is [AnnotationEditorUiState.Idle].
 */
@Composable
fun AnnotationEditorHost(state: AnnotationEditorUiState, actions: AnnotationEditorActions, modifier: Modifier = Modifier) {
    when (state) {
        AnnotationEditorUiState.Idle -> Unit
        is AnnotationEditorUiState.ColorPicker -> ColorPickerRow(actions = actions, modifier = modifier)
        is AnnotationEditorUiState.NoteEditor -> NoteEditorSheet(state = state, actions = actions, modifier = modifier)
    }
}

/** Inline swatch row for the plain-highlight path — no sheet, tapping a color commits
 * immediately (design_guidelines.md: "instant, quiet feedback"). */
@Composable
private fun ColorPickerRow(actions: AnnotationEditorActions, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            HIGHLIGHT_COLOR_ORDER.forEach { color ->
                ColorSwatch(color = color, onClick = { actions.onColorSelected(color) })
            }
            TextButton(onClick = actions.onDismiss) {
                Text(stringResource(R.string.annotation_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorSheet(state: AnnotationEditorUiState.NoteEditor, actions: AnnotationEditorActions, modifier: Modifier = Modifier) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            // Deliberately no icon+title header here (unlike DictionarySheet/InterpretationSheet):
            // the note text field right below already labels itself "Note"
            // (R.string.annotation_note_label), so a matching "Note" header would duplicate that
            // copy rather than aid recognition — and Cancel/Save already cover dismissal, so
            // there's no missing affordance to add an icon for either.
            Text(
                text = state.selectedText,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HIGHLIGHT_COLOR_ORDER.forEach { color ->
                    ColorSwatch(
                        color = color,
                        selected = color == state.color,
                        onClick = { actions.onNoteColorChanged(color) },
                    )
                }
            }
            OutlinedTextField(
                value = state.noteText,
                onValueChange = actions.onNoteTextChanged,
                label = { Text(stringResource(R.string.annotation_note_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.annotation_cancel)) }
                TextButton(onClick = actions.onConfirmNote) { Text(stringResource(R.string.annotation_save)) }
            }
        }
    }
}

/** Swatch diameter kept small (matches the muted-fill scale in the design guidelines); the
 * surrounding clickable [Box] is padded out to [MIN_TOUCH_TARGET_DP] so the tap area still
 * meets the WCAG AA 48dp minimum (design_guidelines.md Accessibility). */
private val MIN_TOUCH_TARGET_DP = 48.dp

@Composable
internal fun ColorSwatch(color: HighlightColor, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val ringColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    val swatchSize = if (selected) 36.dp else 32.dp
    val label = stringResource(color.labelRes())
    val description =
        if (selected) {
            stringResource(R.string.highlight_color_selected_cd, label)
        } else {
            stringResource(R.string.highlight_color_cd, label)
        }
    Box(
        // Previously an unlabelled clickable Box — TalkBack announced nothing at all for a
        // color swatch. `selectable` gives it a proper RadioButton role (the four colors are
        // a single-choice set) plus the selected state; `clearAndSetSemantics` replaces its
        // default (empty) content description with the color name.
        modifier = modifier
            .size(MIN_TOUCH_TARGET_DP)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(swatchSize).clip(CircleShape).background(color.toComposeColor()),
            color = androidx.compose.ui.graphics.Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, ringColor),
        ) {}
    }
}

/** Display name for a [HighlightColor], used as the swatch's accessible label. */
private fun HighlightColor.labelRes(): Int =
    when (this) {
        HighlightColor.AMBER -> R.string.highlight_color_amber
        HighlightColor.SAGE -> R.string.highlight_color_sage
        HighlightColor.SKY -> R.string.highlight_color_sky
        HighlightColor.ROSE -> R.string.highlight_color_rose
    }
