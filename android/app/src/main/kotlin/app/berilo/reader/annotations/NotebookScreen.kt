package app.berilo.reader.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import app.berilo.reader.store.db.HighlightColor

/**
 * Per-book notebook screen (S2.6): highlights/notes grouped by chapter, per
 * `docs/design_guidelines.md`'s "chronological within book, color-coded left border" notebook
 * component note. Tapping an entry jumps back into the book; the toolbar's share icon exports
 * the whole notebook to Markdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(
    uiState: NotebookUiState,
    onBack: () -> Unit,
    onJumpTo: (Highlight) -> Unit,
    onEditNote: (Highlight, String) -> Unit,
    onChangeColor: (Highlight, HighlightColor) -> Unit,
    onDelete: (Highlight) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(uiState.bookTitle) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.reader_done)) }
                },
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.notebook_export_cd))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.chapters.isEmpty()) {
                Text(
                    text = stringResource(R.string.notebook_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                NotebookList(
                    chapters = uiState.chapters,
                    onJumpTo = onJumpTo,
                    onEditNote = onEditNote,
                    onChangeColor = onChangeColor,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun NotebookList(
    chapters: List<ChapterHighlights>,
    onJumpTo: (Highlight) -> Unit,
    onEditNote: (Highlight, String) -> Unit,
    onChangeColor: (Highlight, HighlightColor) -> Unit,
    onDelete: (Highlight) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        chapters.forEach { chapter ->
            item(key = "header-${chapter.chapterTitle}") {
                Text(
                    text = chapter.chapterTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(chapter.highlights, key = { it.id }) { highlight ->
                HighlightRow(
                    highlight = highlight,
                    onJumpTo = { onJumpTo(highlight) },
                    onEditNote = { text -> onEditNote(highlight, text) },
                    onChangeColor = { color -> onChangeColor(highlight, color) },
                    onDelete = { onDelete(highlight) },
                )
            }
        }
    }
}

@Composable
private fun HighlightRow(
    highlight: Highlight,
    onJumpTo: () -> Unit,
    onEditNote: (String) -> Unit,
    onChangeColor: (HighlightColor) -> Unit,
    onDelete: () -> Unit,
) {
    var editingNote by remember(highlight.id) { mutableStateOf(false) }
    var pendingDelete by remember(highlight.id) { mutableStateOf(false) }
    var pickingColor by remember(highlight.id) { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onJumpTo)) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxWidth()
                .background(highlight.color.toComposeColor()),
        )
        Column(modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                text = highlight.selectedText,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
            )
            highlight.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editingNote = true }) {
                    RowActionIcon(R.drawable.ic_note)
                    Text(stringResource(R.string.notebook_edit_note))
                }
                TextButton(onClick = { pickingColor = true }) {
                    RowActionIcon(R.drawable.ic_highlight)
                    Text(stringResource(R.string.notebook_change_color))
                }
                // Destructive action — tinted with the error role (matches
                // SettingsScreen's key-test-failure treatment) so it reads as
                // distinct from the two reversible actions beside it.
                TextButton(onClick = { pendingDelete = true }) {
                    RowActionIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.notebook_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (editingNote) {
        NoteEditDialog(
            initialText = highlight.note.orEmpty(),
            onConfirm = { text ->
                onEditNote(text)
                editingNote = false
            },
            onDismiss = { editingNote = false },
        )
    }
    if (pickingColor) {
        ColorPickDialog(
            current = highlight.color,
            onPick = { color ->
                onChangeColor(color)
                pickingColor = false
            },
            onDismiss = { pickingColor = false },
        )
    }
    if (pendingDelete) {
        DeleteHighlightDialog(
            onConfirm = {
                onDelete()
                pendingDelete = false
            },
            onDismiss = { pendingDelete = false },
        )
    }
}

/** Small leading glyph for a [TextButton]'s action row — decorative (the button's own text
 * already labels the action), sized down from the 24dp default so it doesn't outweigh the
 * label text, with a fixed gap before the label. */
@Composable
private fun RowActionIcon(@DrawableRes iconRes: Int, tint: Color = LocalContentColor.current) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp).padding(end = 4.dp),
    )
}

@Composable
private fun NoteEditDialog(initialText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook_edit_note)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.annotation_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.annotation_cancel)) }
        },
    )
}

@Composable
private fun ColorPickDialog(current: HighlightColor, onPick: (HighlightColor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook_change_color)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HIGHLIGHT_COLOR_ORDER.forEach { color ->
                    ColorSwatch(color = color, selected = color == current, onClick = { onPick(color) })
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.annotation_cancel)) }
        },
    )
}

@Composable
private fun DeleteHighlightDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook_delete_confirm_title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.notebook_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.annotation_cancel)) }
        },
    )
}
