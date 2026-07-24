package app.berilo.reader

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.store.repository.Book
import app.berilo.reader.reader.ReaderActivity
import app.berilo.reader.settings.SettingsActivity
import app.berilo.reader.ui.library.LibraryScreen
import app.berilo.reader.ui.library.LibraryViewModel
import app.berilo.reader.ui.theme.BeriloTheme

private const val EPUB_MEDIA_TYPE = "application/epub+zip"

class MainActivity : ComponentActivity() {

    private val viewModel: LibraryViewModel by viewModels {
        val container = (application as BeriloApplication).container
        LibraryViewModel.Factory(container.bookRepository, container.bookImporter)
    }

    private val pickEpub =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(::handlePickedEpub)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeriloTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    // Events are transient (snackbar-worthy); S2.7 adds a Snackbar host.
                    // For S2.1 they are consumed silently so the sealed type has a call site.
                    viewModel.events.collect { /* no-op until S2.7 surfaces a Snackbar */ }
                }

                LibraryScreen(
                    uiState = uiState,
                    onAddBook = { pickEpub.launch(arrayOf(EPUB_MEDIA_TYPE, "*/*")) },
                    onOpenBook = ::openReader,
                    onDeleteBook = viewModel::deleteBook,
                    onOpenSettings = ::openSettings,
                )
            }
        }
    }

    private fun handlePickedEpub(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "book.epub"
        contentResolver.openInputStream(uri)?.use { input ->
            viewModel.importBook(input, displayName)
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

    private fun openReader(book: Book) {
        startActivity(ReaderActivity.newIntent(this, book.id))
    }

    private fun openSettings() {
        startActivity(SettingsActivity.newIntent(this))
    }
}
