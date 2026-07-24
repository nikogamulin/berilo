package app.berilo.reader.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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

@Composable
internal fun ColorSwatch(color: HighlightColor, onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier) {
    val ringColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier
            .size(if (selected) 36.dp else 32.dp)
            .clip(CircleShape)
            .background(color.toComposeColor())
            .clickable(onClick = onClick),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, ringColor),
    ) {}
}
