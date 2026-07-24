package app.berilo.reader.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.BeriloApplication
import app.berilo.reader.R
import app.berilo.reader.ui.theme.BeriloTheme

private const val EXTRA_BOOK_ID = "app.berilo.reader.extra.BOOK_ID"

/**
 * Stub reader screen (S2.1): proves the library → reader handoff by loading
 * and showing the book title. Paginated Readium rendering, e-ink mode and
 * chapter navigation land in S2.2.
 */
class ReaderActivity : ComponentActivity() {

    private val bookId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "ReaderActivity requires $EXTRA_BOOK_ID" }
    }

    private val viewModel: ReaderViewModel by viewModels {
        val repository = (application as BeriloApplication).container.bookRepository
        ReaderViewModel.Factory(repository, bookId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeriloTheme {
                val book by viewModel.book.collectAsStateWithLifecycle()
                Scaffold { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(
                            text = book?.title ?: stringResource(R.string.reader_loading),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun newIntent(context: Context, bookId: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
