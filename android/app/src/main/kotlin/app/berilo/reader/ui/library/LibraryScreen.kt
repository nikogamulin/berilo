package app.berilo.reader.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import app.berilo.reader.store.repository.Book
import coil3.compose.AsyncImage

// Shared card geometry so the cover's corners and the card's corners always
// match ("consistent cover corner treatment" per docs/design_guidelines.md).
private val CardCornerRadius = 12.dp
private val CardShape = RoundedCornerShape(CardCornerRadius)
private val CardBorderWidth = 1.dp
private const val CardBorderAlpha = 0.35f
private val CardTextPaddingHorizontal = 12.dp
private val CardTextPaddingVertical = 10.dp

// Grid spacing tuned to read comfortably from phone width up to a Boox
// tablet: LazyVerticalGrid's Adaptive columns already recompute column count
// from available width, so a slightly larger minimum card width plus more
// generous gutters (design_guidelines.md "generous margins") is what actually
// changes across widths rather than a fixed column count.
private val GridMinCardWidth = 140.dp
private val GridHorizontalPadding = 24.dp
private val GridVerticalPadding = 20.dp
private val GridHorizontalSpacing = 16.dp
private val GridVerticalSpacing = 20.dp

private val EmptyIconContainerSize = 64.dp
private val EmptyIconSize = 32.dp
private const val EmptyIconBackgroundAlpha = 0.08f

private val LoadingIndicatorWidth = 120.dp

/** The library screen's three mutually exclusive content states. */
internal enum class LibraryContentState { LOADING, EMPTY, BOOKS }

/**
 * Resolves which of the three states [LibraryScreen] renders. Pulled out of
 * the composable so the decision — in particular that a loading library is
 * never mistaken for an empty one — is unit-testable without Compose.
 */
internal fun libraryContentState(uiState: LibraryUiState): LibraryContentState =
    when {
        uiState.isLoading -> LibraryContentState.LOADING
        uiState.isEmpty -> LibraryContentState.EMPTY
        else -> LibraryContentState.BOOKS
    }

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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    // B7: the entry point to on-device translation. Defaulted so the existing library tests,
    // which are about the grid and its states, keep constructing this screen unchanged.
    onOpenTranslate: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onOpenTranslate) {
                        Icon(
                            painter = painterResource(R.drawable.ic_translate),
                            contentDescription = stringResource(R.string.translate_library_cd),
                        )
                    }
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
        // Import outcomes are one-shot events (design_guidelines.md "instant, quiet feedback"):
        // a Snackbar, never a toast covering text or a blocking dialog.
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (libraryContentState(uiState)) {
                LibraryContentState.LOADING -> LibraryLoadingState(modifier = Modifier.align(Alignment.Center))
                LibraryContentState.EMPTY -> LibraryEmptyState(modifier = Modifier.align(Alignment.Center))
                LibraryContentState.BOOKS ->
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
        columns = GridCells.Adaptive(minSize = GridMinCardWidth),
        contentPadding = PaddingValues(horizontal = GridHorizontalPadding, vertical = GridVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(GridHorizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(GridVerticalSpacing),
    ) {
        items(books, key = { it.id }) { book ->
            BookCard(book = book, onClick = { onOpenBook(book) }, onLongClick = { onRequestDelete(book) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    val fallbackCover = fallbackCoverFor(book.title)

    // A restrained bordered surface, not a shadowed Material card: elevation
    // shadows don't render on e-ink and would read as a second, uncontrolled
    // "color". Surface clips ALL its content (cover + text) to CardShape, so
    // the cover's corners and the card's corners are always the same radius.
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(CardBorderWidth, MaterialTheme.colorScheme.outline.copy(alpha = CardBorderAlpha)),
        tonalElevation = 0.dp,
    ) {
        Column {
            Box {
                AsyncImage(
                    model = book.coverPath ?: fallbackCover,
                    error = painterResource(fallbackCover),
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
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = CardTextPaddingHorizontal,
                        vertical = CardTextPaddingVertical,
                    ),
            ) {
                // Title leads in UI sans; author is subordinate — smaller,
                // one weight down, and the pinned onSurfaceVariant role
                // (never an ad-hoc alpha) so both themes stay WCAG AA.
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                if (book.authors.isNotBlank()) {
                    Text(
                        text = book.authors,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Curated, text-free covers for the translated books bundled with the project.
 * Imported EPUB artwork still wins; these are only used when cover extraction
 * produces no usable file.
 */
@DrawableRes
internal fun fallbackCoverFor(title: String): Int {
    val normalized = title.lowercase()
    return when {
        "active measures" in normalized -> R.drawable.cover_active_measures
        "sandworm" in normalized -> R.drawable.cover_sandworm
        "new rules of war" in normalized -> R.drawable.cover_new_rules_of_war
        "revenge of geography" in normalized -> R.drawable.cover_revenge_of_geography
        "this is how they tell me the world ends" in normalized -> R.drawable.cover_world_ends
        else -> R.drawable.ic_book
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
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(EmptyIconContainerSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = EmptyIconBackgroundAlpha)),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_book),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(EmptyIconSize),
            )
        }
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        // One sentence teaches import (docs/design_guidelines.md "Library"
        // component note); the pinned onSurfaceVariant role keeps this
        // subordinate text AA-compliant in both themes instead of an ad-hoc alpha.
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * A subtle inline indicator while the library loads from Room — never a
 * spinner over content (docs/design_guidelines.md principle 5): a thin
 * LinearProgressIndicator, not a CircularProgressIndicator, and shown only
 * before any book grid exists to overlay.
 */
@Composable
private fun LibraryLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            modifier = Modifier.width(LoadingIndicatorWidth),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = stringResource(R.string.library_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
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
