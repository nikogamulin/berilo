package app.berilo.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.berilo.reader.R

/**
 * Settings screen: one screen, plain list, no chrome beyond a back affordance — per
 * `docs/design_guidelines.md`. Each provider's key field is masked with a show/hide
 * toggle and a "Test key" doctor-style smoke call; the model picker and target language
 * apply immediately (persisted by [SettingsViewModel] on every change, no explicit Save).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onOpenAiKeyChanged: (String) -> Unit,
    onAnthropicKeyChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onTargetLangChanged: (String) -> Unit,
    onTestOpenAiKey: () -> Unit,
    onTestAnthropicKey: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back_cd))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ApiKeySection(
                label = stringResource(R.string.settings_openai_key_label),
                key = uiState.openaiKey,
                onKeyChanged = onOpenAiKeyChanged,
                testState = uiState.openaiTestState,
                onTestKey = onTestOpenAiKey,
            )
            HorizontalDivider()
            ApiKeySection(
                label = stringResource(R.string.settings_anthropic_key_label),
                key = uiState.anthropicKey,
                onKeyChanged = onAnthropicKeyChanged,
                testState = uiState.anthropicTestState,
                onTestKey = onTestAnthropicKey,
            )
            HorizontalDivider()
            ModelPicker(selectedModel = uiState.model, onModelChanged = onModelChanged)
            HorizontalDivider()
            OutlinedTextField(
                value = uiState.targetLang,
                onValueChange = onTargetLangChanged,
                label = { Text(stringResource(R.string.settings_target_lang_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ApiKeySection(
    label: String,
    key: String,
    onKeyChanged: (String) -> Unit,
    testState: KeyTestState,
    onTestKey: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = key,
            onValueChange = onKeyChanged,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(
                        stringResource(
                            if (revealed) R.string.settings_hide_key else R.string.settings_show_key,
                        ),
                    )
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTestKey, enabled = testState !is KeyTestState.Testing) {
                Text(stringResource(R.string.settings_test_key))
            }
            KeyTestStatusText(testState)
        }
    }
}

@Composable
private fun KeyTestStatusText(testState: KeyTestState) {
    when (testState) {
        KeyTestState.Idle -> Unit
        KeyTestState.Testing -> Text(stringResource(R.string.settings_testing))
        is KeyTestState.Success -> Text(text = testState.summary, color = MaterialTheme.colorScheme.primary)
        is KeyTestState.Failure -> Text(text = testState.message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ModelPicker(selectedModel: String, onModelChanged: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.settings_model_label), style = MaterialTheme.typography.titleMedium)
        AVAILABLE_MODELS.forEach { model ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = model == selectedModel, onClick = { onModelChanged(model) })
                Text(text = model)
            }
        }
    }
}
