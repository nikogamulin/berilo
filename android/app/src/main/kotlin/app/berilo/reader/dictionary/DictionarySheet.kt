package app.berilo.reader.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import java.util.Locale

/** Fraction of screen height the sheet is capped at (design_guidelines.md: "Max height 40% of
 * screen" for the dictionary sheet). */
private const val MAX_HEIGHT_FRACTION = 0.4f

/**
 * The dictionary bottom sheet: text-first, e-ink-friendly (no motion beyond the sheet's own
 * entrance), rendering [uiState] per `docs/design_guidelines.md`'s "Dictionary sheet"
 * component note — headword, definition, contextual meaning, source sentence quoted, cost
 * footer.
 *
 * Not shown (caller returns early) when [uiState] is [DictionaryUiState.Idle].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySheet(uiState: DictionaryUiState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (uiState is DictionaryUiState.Idle) return

    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * MAX_HEIGHT_FRACTION)

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            val headword =
                when (uiState) {
                    DictionaryUiState.Idle -> ""
                    is DictionaryUiState.Loading -> uiState.word
                    is DictionaryUiState.Error -> uiState.word
                    is DictionaryUiState.Success -> uiState.definition.word
                }
            DictionaryHeader(word = headword, onDismiss = onDismiss)
            when (uiState) {
                DictionaryUiState.Idle -> Unit
                is DictionaryUiState.Loading -> DictionaryLoadingContent()
                is DictionaryUiState.Error -> DictionaryErrorContent(uiState)
                is DictionaryUiState.Success -> DictionarySuccessContent(uiState)
            }
        }
    }
}

@Composable
private fun DictionaryLoadingContent() {
    Row(modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        Text(text = stringResource(R.string.dictionary_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DictionaryErrorContent(state: DictionaryUiState.Error) {
    val message =
        when (state.kind) {
            DictionaryErrorKind.NETWORK -> stringResource(R.string.dictionary_error_network)
            DictionaryErrorKind.AUTH -> stringResource(R.string.dictionary_error_auth)
            DictionaryErrorKind.OTHER -> state.message.ifBlank { stringResource(R.string.dictionary_error_generic) }
        }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
}

@Composable
private fun DictionarySuccessContent(state: DictionaryUiState.Success) {
    val definition = state.definition

    if (definition.definition.isNotBlank()) {
        Text(
            text = definition.definition,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (definition.contextMeaning.isNotBlank()) {
        Text(
            text = definition.contextMeaning,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (definition.baseForm.isNotBlank()) {
        Text(
            text = stringResource(R.string.dictionary_base_form, definition.baseForm),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (definition.usageNote.isNotBlank()) {
        Text(
            text = definition.usageNote,
            style = MaterialTheme.typography.labelLarge,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        text = state.sentence,
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )

    DictionaryFooter(costEur = state.costEur, fromCache = state.fromCache)
}

/** Sheet header: the `ic_translate` glyph identifies this as a dictionary lookup at a glance
 * (distinct from [app.berilo.reader.interpretation.InterpretationSheet]'s header), the headword
 * fills the remaining width, and an explicit close affordance replaces the previous
 * swipe-or-tap-scrim-only dismiss — the sheet had no visible way to leave it. */
@Composable
private fun DictionaryHeader(word: String, onDismiss: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_translate),
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(20.dp),
        )
        Text(text = word, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.dictionary_dismiss_cd))
        }
    }
}

/** Cost + cached badge, shown small per design_guidelines.md restraint ("no percentages
 * shouting" applied here to cost too). */
@Composable
private fun DictionaryFooter(costEur: Double, fromCache: Boolean) {
    Surface(modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)) {
        Row {
            if (fromCache) {
                Text(
                    text = stringResource(R.string.dictionary_cached_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Text(
                text = stringResource(R.string.dictionary_cost, String.format(Locale.US, "%.4f", costEur)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
