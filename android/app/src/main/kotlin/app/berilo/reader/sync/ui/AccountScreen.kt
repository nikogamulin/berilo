package app.berilo.reader.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import app.berilo.reader.sync.SyncOutcome
import app.berilo.reader.sync.SyncStatus
import app.berilo.reader.sync.auth.AccountState

/**
 * Account and sync screen (S3.2).
 *
 * Follows `design_guidelines.md`: no engagement mechanics, no marketing for the cloud service,
 * and an explicit statement of what does and does not leave the device — which on this screen
 * is the single most important piece of text, not fine print.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    uiState: AccountUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onUsePasswordChanged: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
    onSubmitCode: () -> Unit,
    onStartOver: () -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.account_back_cd),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier.padding(padding)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.account_privacy),
                style = MaterialTheme.typography.bodyMedium,
            )

            VaultStatement(enabledBooks = uiState.vaultEnabledBooks)

            when (val account = uiState.account) {
                AccountState.Unconfigured ->
                    Text(
                        text = stringResource(R.string.account_unconfigured),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                AccountState.Loading -> Text(stringResource(R.string.account_loading))
                is AccountState.SignedIn ->
                    SignedIn(
                        email = account.email,
                        uiState = uiState,
                        onSignOut = onSignOut,
                        onSyncNow = onSyncNow,
                    )
                AccountState.SignedOut ->
                    when (uiState.step) {
                        SignInStep.IDENTIFY ->
                            Identify(
                                uiState = uiState,
                                onEmailChanged = onEmailChanged,
                                onPasswordChanged = onPasswordChanged,
                                onUsePasswordChanged = onUsePasswordChanged,
                                onSignIn = onSignIn,
                                onSignUp = onSignUp,
                            )
                        SignInStep.CODE ->
                            EnterCode(
                                uiState = uiState,
                                onCodeChanged = onCodeChanged,
                                onSubmitCode = onSubmitCode,
                                onStartOver = onStartOver,
                            )
                    }
            }

            uiState.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * What the vault does and does not do with the user's books (S3.7).
 *
 * This screen is the user's only honest account of where their books are, so the text is stated
 * plainly and **not overstated**: `docs/sync_api.md` §8.2(4) makes the vault opt-in per book and
 * off by default, §8.2(2) makes the bytes unreadable to the server, and §8.3(3) forbids any
 * sharing surface. All three are claims the user can check, and `AccountScreenTest` asserts the
 * copy against the implemented default rather than against itself.
 *
 * Deliberately carries no switch. The decision is per book and belongs on the book, not on a
 * global toggle that would make "off for everything" one mistap away from "on for everything" —
 * and CLAUDE.md §9 records what happens when an action is hosted somewhere other than the state
 * it acts on.
 */
@Composable
private fun VaultStatement(enabledBooks: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.account_vault_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                if (enabledBooks == VAULT_ENABLED_BOOKS_DEFAULT) {
                    stringResource(R.string.account_vault_explainer)
                } else {
                    stringResource(R.string.account_vault_enabled_count, enabledBooks)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Identify(
    uiState: AccountUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onUsePasswordChanged: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = uiState.email,
            onValueChange = onEmailChanged,
            label = { Text(stringResource(R.string.account_email_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.account_use_password))
            Switch(checked = uiState.usePassword, onCheckedChange = onUsePasswordChanged)
        }

        if (uiState.usePassword) {
            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChanged,
                label = { Text(stringResource(R.string.account_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
            )
        } else {
            Text(
                text = stringResource(R.string.account_code_explainer),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = onSignIn,
            enabled = !uiState.busy && uiState.email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (uiState.busy) R.string.account_working else R.string.account_sign_in,
                ),
            )
        }

        TextButton(
            onClick = onSignUp,
            enabled = !uiState.busy && uiState.email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.account_sign_up))
        }
    }
}

@Composable
private fun EnterCode(
    uiState: AccountUiState,
    onCodeChanged: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onStartOver: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.account_code_sent, uiState.email),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = uiState.code,
            onValueChange = onCodeChanged,
            label = { Text(stringResource(R.string.account_code_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
        )
        Button(
            onClick = onSubmitCode,
            enabled = !uiState.busy && uiState.code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (uiState.busy) R.string.account_working else R.string.account_verify,
                ),
            )
        }
        TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.account_start_over))
        }
    }
}

@Composable
private fun SignedIn(
    email: String?,
    uiState: AccountUiState,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.account_signed_in_as, email.orEmpty()),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = syncSummary(uiState.syncStatus), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onSyncNow,
            enabled = uiState.syncStatus !is SyncStatus.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.account_sync_now))
        }
        TextButton(
            onClick = onSignOut,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.account_sign_out))
        }
        Text(
            text = stringResource(R.string.account_sign_out_explainer),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One plain sentence for the last round. Deliberately states offline as a fact rather than an
 * error: on an e-reader that spends most of its life without a connection, a red failure
 * message for the normal case would train the user to ignore the status line entirely.
 */
@Composable
private fun syncSummary(status: SyncStatus): String =
    when (status) {
        SyncStatus.Idle -> stringResource(R.string.account_sync_idle)
        SyncStatus.Running -> stringResource(R.string.account_sync_running)
        is SyncStatus.Completed ->
            when (val outcome = status.report.outcome) {
                SyncOutcome.Success ->
                    stringResource(
                        R.string.account_sync_ok,
                        status.report.pulled,
                        status.report.pushed,
                    )
                SyncOutcome.Offline -> stringResource(R.string.account_sync_offline)
                SyncOutcome.SignedOut -> stringResource(R.string.account_sync_signed_out)
                is SyncOutcome.RateLimited -> stringResource(R.string.account_sync_rate_limited)
                is SyncOutcome.Failed ->
                    stringResource(R.string.account_sync_failed, outcome.message)
            }
    }
