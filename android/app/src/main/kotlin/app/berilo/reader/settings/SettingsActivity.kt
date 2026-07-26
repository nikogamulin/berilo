package app.berilo.reader.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.BeriloApplication
import app.berilo.reader.sync.ui.AccountActivity
import app.berilo.reader.ui.theme.BeriloTheme

/** Single entry point for the Settings screen (S2.3), reached from the library top bar. */
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels {
        val repository = (application as BeriloApplication).container.settingsRepository
        SettingsViewModel.Factory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeriloTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                SettingsScreen(
                    uiState = uiState,
                    onOpenAiKeyChanged = viewModel::onOpenAiKeyChanged,
                    onAnthropicKeyChanged = viewModel::onAnthropicKeyChanged,
                    onModelChanged = viewModel::onModelChanged,
                    onTargetLangChanged = viewModel::onTargetLangChanged,
                    onTestOpenAiKey = { viewModel.testKey(LlmProvider.OPENAI) },
                    onTestAnthropicKey = { viewModel.testKey(LlmProvider.ANTHROPIC) },
                    onBack = { finish() },
                    onOpenAccount = { startActivity(AccountActivity.newIntent(this)) },
                )
            }
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
