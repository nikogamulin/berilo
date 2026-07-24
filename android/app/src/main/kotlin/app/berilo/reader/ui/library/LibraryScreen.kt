package app.berilo.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import app.berilo.reader.store.repository.Book
import coil3.compose.AsyncImage

/**
 * Library grid: covers + title, thin progress strip when a book has a saved
 * position, empty-state teaching import, FAB to add a book. Tap opens the
 * reader; long-press offers delete (destructive, so it is the one place a
 * confirm dialog belongs per docs/design_guidelines.md anti-patterns).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onAddBook: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.library_settings_cd))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddBook) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.library_add_book_cd))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isEmpty -> LibraryEmptyState(modifier = Modifier.align(Alignment.Center))
                else ->
                    LibraryGrid(
                        books = uiState.books,
                        onOpenBook = onOpenBook,
                        onRequestDelete = { pendingDelete = it },
                    )
            }
        }
    }

    pendingDelete?.let { book ->
        DeleteBookDialog(
            book = book,
            onConfirm = {
                onDeleteBook(book)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun LibraryGrid(
    books: List<Book>,
    onOpenBook: (Book) -> Unit,
    onRequestDelete: (Book) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(books, key = { it.id }) { book ->
            BookCard(book = book, onClick = { onOpenBook(book) }, onLongClick = { onRequestDelete(book) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            AsyncImage(
                model = book.coverPath,
                contentDescription = stringResource(R.string.library_book_cover_cd, book.title),
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
            )
            book.progressFraction?.let { fraction ->
                ProgressStrip(fraction = fraction, modifier = Modifier.align(Alignment.BottomStart))
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Thin bottom bar on the cover, per docs/design_guidelines.md — "no percentages shouting". */
@Composable
private fun ProgressStrip(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun LibraryEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_book),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(text = stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DeleteBookDialog(book: Book, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_book_title)) },
        text = { Text(stringResource(R.string.delete_book_body, book.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete_book_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_book_cancel)) }
        },
    )
}
