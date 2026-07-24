package app.berilo.reader.annotations

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.BeriloApplication
import app.berilo.reader.reader.ReaderActivity
import app.berilo.reader.ui.theme.BeriloTheme

private const val EXTRA_BOOK_ID = "app.berilo.reader.annotations.extra.BOOK_ID"
private const val EXTRA_BOOK_TITLE = "app.berilo.reader.annotations.extra.BOOK_TITLE"

/**
 * Per-book notebook screen host (S2.6): lists highlights/notes grouped by chapter, and offers
 * edit/recolor/delete plus a Markdown export via the share sheet. Reachable from the reader
 * chrome's "Notebook" action; tapping an entry jumps back into [ReaderActivity] at that
 * highlight's locator.
 */
class NotebookActivity : ComponentActivity() {

    private val bookId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "NotebookActivity requires $EXTRA_BOOK_ID" }
    }
    private val bookTitle: String by lazy { intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty() }

    private val viewModel: NotebookViewModel by viewModels {
        val container = (application as BeriloApplication).container
        NotebookViewModel.Factory(bookId, bookTitle, container.annotationsRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeriloTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                NotebookScreen(
                    uiState = uiState,
                    onBack = { finish() },
                    onJumpTo = ::jumpToHighlight,
                    onEditNote = { highlight, text -> viewModel.updateNote(highlight.id, text) },
                    onChangeColor = { highlight, color -> viewModel.updateColor(highlight.id, color) },
                    onDelete = { highlight -> viewModel.delete(highlight.id) },
                    onExport = ::exportMarkdown,
                )
            }
        }
    }

    private fun jumpToHighlight(highlight: Highlight) {
        startActivity(ReaderActivity.newIntent(this, bookId, targetLocatorJson = highlight.locatorJson))
        finish()
    }

    private fun exportMarkdown() {
        val markdown = viewModel.exportMarkdown()
        startActivity(MarkdownShareExporter.buildShareIntent(this, bookTitle, markdown))
    }

    companion object {
        fun newIntent(context: Context, bookId: String, bookTitle: String): Intent =
            Intent(context, NotebookActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_BOOK_TITLE, bookTitle)
    }
}
