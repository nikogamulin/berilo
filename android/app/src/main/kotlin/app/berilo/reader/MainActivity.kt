package app.berilo.reader

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.store.repository.Book
import app.berilo.reader.reader.ReaderActivity
import app.berilo.reader.settings.SettingsActivity
import app.berilo.reader.ui.library.LibraryEvent
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
                val snackbarHostState = remember { SnackbarHostState() }

                val importedMessage = stringResource(R.string.library_import_success)
                val duplicateMessage = stringResource(R.string.library_import_duplicate)
                LaunchedEffect(Unit) {
                    // One-shot import outcomes surface as a Snackbar (design_guidelines.md
                    // "instant, quiet feedback" — never a toast covering text).
                    viewModel.events.collect { event ->
                        val message =
                            when (event) {
                                LibraryEvent.Imported -> importedMessage
                                LibraryEvent.AlreadyInLibrary -> duplicateMessage
                                is LibraryEvent.ImportFailed ->
                                    getString(R.string.library_import_failed, event.reason)
                            }
                        snackbarHostState.showSnackbar(message)
                    }
                }

                LibraryScreen(
                    uiState = uiState,
                    onAddBook = { pickEpub.launch(arrayOf(EPUB_MEDIA_TYPE, "*/*")) },
                    onOpenBook = ::openReader,
                    onDeleteBook = viewModel::deleteBook,
                    onOpenSettings = ::openSettings,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }

    private fun handlePickedEpub(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "book.epub"
        // Hand over a factory, never an open stream: importBook returns as soon as
        // it launches, so closing the stream here would close it before the import
        // coroutine reads a byte. The application resolver (not this Activity's)
        // keeps the lambda from capturing the Activity for the import's lifetime.
        val resolver = applicationContext.contentResolver
        viewModel.importBook({ resolver.openInputStream(uri) }, displayName)
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
