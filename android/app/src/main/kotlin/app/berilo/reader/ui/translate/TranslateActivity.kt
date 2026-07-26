package app.berilo.reader.ui.translate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.BeriloApplication
import app.berilo.reader.ui.theme.BeriloTheme

private const val EPUB_MEDIA_TYPE = "application/epub+zip"
private const val FALLBACK_FILE_NAME = "book.epub"

/**
 * Hosts the on-device translation flow (B7), reached from the library's top bar.
 *
 * A separate activity rather than a sheet over the library: the flow outlives the picker, has
 * four distinct steps, and its middle step is a spending decision that deserves the whole
 * screen rather than a surface the user can dismiss by tapping beside it.
 */
class TranslateActivity : ComponentActivity() {

    private val viewModel: TranslateViewModel by viewModels {
        val container = (application as BeriloApplication).container
        TranslateViewModel.Factory(
            sourceImporter = container.sourceBookImporter,
            planner = container.translationPlanner,
            settingsRepository = container.settingsRepository,
            runner = container.translationRunner,
        )
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
                TranslateScreen(
                    uiState = uiState,
                    onPickSource = { pickEpub.launch(arrayOf(EPUB_MEDIA_TYPE, "*/*")) },
                    onTierSelected = viewModel::onTierSelected,
                    onConfirm = viewModel::confirmAndTranslate,
                    onCancelRun = viewModel::cancel,
                    onRetry = viewModel::retry,
                    onStartOver = viewModel::reset,
                    onBack = { finish() },
                )
            }
        }
    }

    private fun handlePickedEpub(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: FALLBACK_FILE_NAME
        // A factory, never an open stream: onSourcePicked returns as soon as it launches its
        // coroutine, so closing the stream here would close it before a byte is read. The
        // application resolver keeps the lambda from capturing this Activity for the copy's
        // lifetime. Same rule as MainActivity's library import.
        val resolver = applicationContext.contentResolver
        viewModel.onSourcePicked({ resolver.openInputStream(uri) }, displayName)
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, TranslateActivity::class.java)
    }
}
