package app.berilo.reader.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.BeriloApplication
import app.berilo.reader.R
import app.berilo.reader.ui.theme.BeriloTheme

private const val EXTRA_BOOK_ID = "app.berilo.reader.extra.BOOK_ID"

/**
 * Reader screen. Renders the chrome (chapter bar, progress, text-settings and
 * TOC panels — [ReaderScaffold]) driven by the [ReaderViewModel], over the
 * paginated reading surface.
 *
 * The reading surface itself hosts Readium's `EpubNavigatorFragment`. That host
 * is intentionally NOT wired here yet: `androidx.fragment` is only a runtime
 * (implementation) dependency of `readium-navigator` and is not on this module's
 * compile classpath, so the fragment/`FragmentFactory` types cannot be
 * referenced until `androidx.fragment` (or `androidx.fragment:fragment-compose`)
 * is added to `app/build.gradle.kts` — a build-config change outside this
 * story's file footprint. Until then a labeled placeholder stands in for the
 * WebView surface; all non-UI reader logic (position persistence, e-ink
 * preference mapping, TOC mapping, debounced saves) is complete and unit-tested.
 */
class ReaderActivity : ComponentActivity() {

    private val bookId: String by lazy {
        requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)) { "ReaderActivity requires $EXTRA_BOOK_ID" }
    }

    private val viewModel: ReaderViewModel by viewModels {
        val repository = (application as BeriloApplication).container.bookRepository
        val settingsStore = SharedPrefsReaderSettingsStore(applicationContext)
        ReaderViewModel.Factory(repository, bookId, settingsStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            BeriloTheme(useDarkTheme = !preferences.einkMode && preferences.darkTheme) {
                val book by viewModel.book.collectAsStateWithLifecycle()
                val chromeVisible by viewModel.chromeVisible.collectAsStateWithLifecycle()
                val chapters by viewModel.tableOfContents.collectAsStateWithLifecycle()

                var panel by remember { mutableStateOf(ReaderPanel.NONE) }

                val state = ReaderChromeState(
                    chromeVisible = chromeVisible,
                    panel = panel,
                    chapterTitle = book?.title ?: stringResource(R.string.reader_loading),
                    progressFraction = 0f,
                    preferences = preferences,
                    chapters = chapters,
                )
                val actions = ReaderChromeActions(
                    onToggleChrome = {
                        panel = ReaderPanel.NONE
                        viewModel.toggleChrome()
                    },
                    onOpenChapters = { panel = ReaderPanel.CHAPTERS },
                    onOpenSettings = { panel = ReaderPanel.SETTINGS },
                    onClosePanel = { panel = ReaderPanel.NONE },
                    onChapterSelected = {
                        // Navigation lands with the EpubNavigatorFragment host.
                        panel = ReaderPanel.NONE
                        viewModel.hideChrome()
                    },
                    onFontSmaller = viewModel::decreaseFontSize,
                    onFontLarger = viewModel::increaseFontSize,
                    onMarginsNarrower = viewModel::decreaseMargins,
                    onMarginsWider = viewModel::increaseMargins,
                    onEinkModeChange = viewModel::setEinkMode,
                    onDarkThemeChange = viewModel::setDarkTheme,
                )

                ReaderScaffold(state = state, actions = actions) {
                    ReadingSurfacePlaceholder(title = book?.title)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Never lose the reading position when leaving the foreground.
        viewModel.persistPositionNow()
    }

    companion object {
        fun newIntent(context: Context, bookId: String): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}

/**
 * Placeholder standing in for the Readium `EpubNavigatorFragment` reading
 * surface until `androidx.fragment` is on the compile classpath (see
 * [ReaderActivity] KDoc).
 */
@androidx.compose.runtime.Composable
private fun ReadingSurfacePlaceholder(title: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = title ?: stringResource(R.string.reader_surface_pending),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
