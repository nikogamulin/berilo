package app.berilo.reader.interpretation

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

/** Fraction of screen height the sheet is capped at. Taller than the dictionary sheet's 0.4f
 * (`DictionarySheet.MAX_HEIGHT_FRACTION`) because interpretations are multi-sentence prose —
 * R4 readability needs the extra room, with the body still scrollable beyond it. */
private const val MAX_HEIGHT_FRACTION = 0.8f

/**
 * The paragraph interpretation bottom sheet: text-first, e-ink-friendly, reusing
 * [app.berilo.reader.dictionary.DictionarySheet]'s design language — loading/error states
 * mapped the same way (NETWORK/AUTH distinct), a scrollable body for long answers, and a
 * cost/cached footer.
 *
 * Not shown (caller returns early) when [uiState] is [InterpretationUiState.Idle].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterpretationSheet(uiState: InterpretationUiState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (uiState is InterpretationUiState.Idle) return

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
            InterpretationHeader(onDismiss = onDismiss)
            when (uiState) {
                InterpretationUiState.Idle -> Unit
                InterpretationUiState.Loading -> InterpretationLoadingContent()
                is InterpretationUiState.Error -> InterpretationErrorContent(uiState)
                is InterpretationUiState.Success -> InterpretationSuccessContent(uiState)
            }
        }
    }
}

@Composable
private fun InterpretationLoadingContent() {
    Row(modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
        Text(text = stringResource(R.string.interpretation_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InterpretationErrorContent(state: InterpretationUiState.Error) {
    val message =
        when (state.kind) {
            InterpretationErrorKind.NETWORK -> stringResource(R.string.interpretation_error_network)
            InterpretationErrorKind.AUTH -> stringResource(R.string.interpretation_error_auth)
            InterpretationErrorKind.OTHER -> state.message.ifBlank { stringResource(R.string.interpretation_error_generic) }
        }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
}

@Composable
private fun InterpretationSuccessContent(state: InterpretationUiState.Success) {
    Text(
        text = state.passage,
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = state.text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp),
    )

    InterpretationFooter(costEur = state.costEur, fromCache = state.fromCache)
}

/** Sheet header: the `ic_interpret` glyph identifies this as a paragraph interpretation at a
 * glance (distinct from [app.berilo.reader.dictionary.DictionarySheet]'s header), and an
 * explicit close affordance replaces the previous swipe-or-tap-scrim-only dismiss — the sheet
 * had no visible way to leave it. */
@Composable
private fun InterpretationHeader(onDismiss: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_interpret),
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(20.dp),
        )
        Text(
            text = stringResource(R.string.interpretation_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.interpretation_dismiss_cd))
        }
    }
}

/** Cost + cached badge, shown small per design_guidelines.md restraint (matches
 * `DictionarySheet`'s footer treatment). */
@Composable
private fun InterpretationFooter(costEur: Double, fromCache: Boolean) {
    Surface(modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)) {
        Row {
            if (fromCache) {
                Text(
                    text = stringResource(R.string.interpretation_cached_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Text(
                text = stringResource(R.string.interpretation_cost, String.format(Locale.US, "%.4f", costEur)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
