package app.berilo.reader.annotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.berilo.reader.R

/** Callbacks the flag editor invokes. */
data class FlagEditorActions(
    val onCommentChanged: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Hosts the reader's "Bad translation" sheet for the current [FlagEditorUiState] (B9). Not
 * shown (no-op) when [state] is [FlagEditorUiState.Idle].
 *
 * A `ModalBottomSheet` rather than the inline row the plain-highlight path uses: flagging always
 * offers the optional comment field, so unlike a colour tap there is no one-gesture variant to
 * keep inline. Confirming with the field untouched is the flag-only case, and the confirm label
 * says so — nothing here requires the user to type before their objection is recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlagEditorHost(state: FlagEditorUiState, actions: FlagEditorActions, modifier: Modifier = Modifier) {
    val composer = state as? FlagEditorUiState.Composer ?: return
    ModalBottomSheet(onDismissRequest = actions.onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.flag_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = composer.selectedText,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = composer.comment,
                onValueChange = actions.onCommentChanged,
                label = { Text(stringResource(R.string.flag_comment_label)) },
                supportingText = { Text(stringResource(R.string.flag_comment_optional)) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.annotation_cancel)) }
                TextButton(onClick = actions.onConfirm) { Text(stringResource(R.string.flag_confirm)) }
            }
        }
    }
}
