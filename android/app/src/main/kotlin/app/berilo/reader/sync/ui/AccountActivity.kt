package app.berilo.reader.sync.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.berilo.reader.BeriloApplication
import app.berilo.reader.ui.theme.BeriloTheme

/** Account and cloud-sync screen (S3.2), reached from Settings. */
class AccountActivity : ComponentActivity() {

    private val viewModel: AccountViewModel by viewModels {
        val container = (application as BeriloApplication).container
        AccountViewModel.Factory(container.authGateway, container.syncManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeriloTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                AccountScreen(
                    uiState = uiState,
                    onEmailChanged = viewModel::onEmailChanged,
                    onPasswordChanged = viewModel::onPasswordChanged,
                    onCodeChanged = viewModel::onCodeChanged,
                    onUsePasswordChanged = viewModel::onUsePasswordChanged,
                    onSignIn = viewModel::onSignIn,
                    onSignUp = viewModel::onSignUp,
                    onSubmitCode = viewModel::onSubmitCode,
                    onStartOver = viewModel::onStartOver,
                    onSignOut = viewModel::onSignOut,
                    onSyncNow = viewModel::onSyncNow,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, AccountActivity::class.java)
    }
}
