package app.berilo.reader.ui.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.berilo.reader.R
import app.berilo.reader.translate.job.TierOffer
import app.berilo.reader.translate.job.TranslationPlan
import app.berilo.reader.translate.job.TranslationProgress
import app.berilo.reader.translate.prompts.StyleTier

/** Test tags for the surfaces whose presence or absence is the spending gate. */
internal const val TRANSLATE_CONFIRM_TAG = "translate_confirm"
internal const val TRANSLATE_ESTIMATE_TAG = "translate_estimate"
internal const val TRANSLATE_TIER_ECONOMY_TAG = "translate_tier_economy"
internal const val TRANSLATE_TIER_QUALITY_TAG = "translate_tier_quality"
internal const val TRANSLATE_PROGRESS_TAG = "translate_progress"

private val ScreenPadding = 24.dp
private val SectionSpacing = 20.dp
private val ItemSpacing = 8.dp

/** Minimum touch target, per docs/design_guidelines.md accessibility rules. */
private val MinTouchTarget = 48.dp

/**
 * The on-device translation flow (B7): pick, price, confirm, watch.
 *
 * E-ink first, per `docs/design_guidelines.md` principle 2: nothing on this screen animates.
 * The only progress indicator is **determinate** and only redraws when a batch commits — an
 * indeterminate bar or a spinner would repaint continuously and ghost badly on the Boox, and
 * would be pretending to know something it does not while a batch is in flight. The
 * pre-estimate and waiting states say what is happening in words instead.
 *
 * @param uiState Current step of the flow.
 * @param onPickSource Opens the document picker.
 * @param onTierSelected Switches the priced tier. Spends nothing.
 * @param onConfirm **The confirmation.** The only control on this screen that can spend money.
 * @param onCancelRun Stops a running job.
 * @param onRetry Resumes a stopped, retryable job.
 * @param onStartOver Returns to the picker.
 * @param onBack Leaves the flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    uiState: TranslateUiState,
    onPickSource: () -> Unit,
    onTierSelected: (StyleTier) -> Unit,
    onConfirm: () -> Unit,
    onCancelRun: () -> Unit,
    onRetry: () -> Unit,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.translate_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.translate_back_cd),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            when (uiState) {
                TranslateUiState.Idle -> IdleSection(onPickSource)
                TranslateUiState.Preparing ->
                    Text(
                        text = stringResource(R.string.translate_preparing),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                is TranslateUiState.Estimate ->
                    EstimateSection(
                        plan = uiState.plan,
                        tier = uiState.tier,
                        onTierSelected = onTierSelected,
                        onConfirm = onConfirm,
                        onStartOver = onStartOver,
                    )
                is TranslateUiState.Waiting -> WaitingSection(uiState.bookTitle)
                is TranslateUiState.Running ->
                    RunningSection(uiState.bookTitle, uiState.progress, onCancelRun)
                is TranslateUiState.Done -> DoneSection(uiState, onStartOver, onBack)
                is TranslateUiState.Failed -> FailedSection(uiState, onRetry, onStartOver)
            }
        }
    }
}

@Composable
private fun IdleSection(onPickSource: () -> Unit) {
    Text(stringResource(R.string.translate_idle_title), style = MaterialTheme.typography.titleLarge)
    Text(
        text = stringResource(R.string.translate_idle_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onPickSource) { Text(stringResource(R.string.translate_choose_file)) }
}

/**
 * The dry run — CLAUDE.md §4's "costs are visible and gated" made literal.
 *
 * Both tiers carry their own euro figure, so "higher quality, ~2x cost" is a comparison the
 * user makes against two real numbers rather than an adjective. The confirm button spells the
 * amount it is about to authorize into its own label: there is no button anywhere in this flow
 * that starts spending without naming the price on its face.
 */
@Composable
private fun EstimateSection(
    plan: TranslationPlan,
    tier: StyleTier,
    onTierSelected: (StyleTier) -> Unit,
    onConfirm: () -> Unit,
    onStartOver: () -> Unit,
) {
    val selected = plan.offer(tier)
    Column(
        modifier = Modifier.testTag(TRANSLATE_ESTIMATE_TAG),
        verticalArrangement = Arrangement.spacedBy(ItemSpacing),
    ) {
        Text(plan.bookTitle, style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.translate_estimate_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.translate_estimate_nothing_spent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plan.alreadyTargetLanguage) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.translate_estimate_same_language, plan.targetLang),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Text(stringResource(R.string.translate_estimate_chapters, plan.chapterCount))
        Text(
            stringResource(
                R.string.translate_estimate_segments,
                selected.estimate.translatableSegments,
                plan.totalSegments,
            ),
        )
        Text(stringResource(R.string.translate_estimate_model, plan.model))
        Text(stringResource(R.string.translate_estimate_style, selected.styleName))
        Text(stringResource(R.string.translate_estimate_calls, selected.apiCalls))
        Text(
            text = stringResource(R.string.translate_estimate_cost, formatEur(selected.costEur)),
            style = MaterialTheme.typography.titleMedium,
        )
    }

    HorizontalDivider()

    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        Text(
            text = stringResource(R.string.translate_quality_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        TierChoice(
            offer = plan.economy,
            label = stringResource(R.string.translate_tier_economy),
            selected = tier == StyleTier.ECONOMY,
            testTag = TRANSLATE_TIER_ECONOMY_TAG,
            onSelected = { onTierSelected(StyleTier.ECONOMY) },
        )
        TierChoice(
            offer = plan.quality,
            label = stringResource(R.string.translate_tier_quality),
            selected = tier == StyleTier.QUALITY,
            testTag = TRANSLATE_TIER_QUALITY_TAG,
            onSelected = { onTierSelected(StyleTier.QUALITY) },
        )
    }

    HorizontalDivider()

    val confirmLabel = stringResource(R.string.translate_confirm, formatEur(selected.costEur))
    val confirmDescription = stringResource(R.string.translate_confirm_cd)
    Button(
        onClick = onConfirm,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TRANSLATE_CONFIRM_TAG)
                .semantics { contentDescription = confirmDescription },
    ) {
        Text(confirmLabel)
    }
    TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.translate_cancel))
    }
}

@Composable
private fun TierChoice(
    offer: TierOffer,
    label: String,
    selected: Boolean,
    testTag: String,
    onSelected: () -> Unit,
) {
    // The whole row is the target, not just the radio dot: `docs/design_guidelines.md` requires
    // every touch target to be at least 48 dp, and a price is a thing you tap the label of.
    // `Role.RadioButton` plus a null `onClick` on the button itself keeps TalkBack announcing one
    // control rather than two.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelected)
                .heightIn(min = MinTouchTarget)
                .testTag(testTag),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = ItemSpacing)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.translate_tier_cost, formatEur(offer.costEur)),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun WaitingSection(bookTitle: String) {
    Text(stringResource(R.string.translate_waiting_title), style = MaterialTheme.typography.titleLarge)
    Text(
        text = stringResource(R.string.translate_waiting_body, bookTitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Progress from the engine's own [app.berilo.reader.translate.engine.TranslationStats].
 *
 * The resumability note is on screen rather than in a log: a multi-hour job on a tablet *will*
 * be interrupted, and a user who does not know the work survives it will not leave it running.
 */
@Composable
private fun RunningSection(
    bookTitle: String,
    progress: TranslationProgress?,
    onCancelRun: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(TRANSLATE_PROGRESS_TAG),
        verticalArrangement = Arrangement.spacedBy(ItemSpacing),
    ) {
        Text(
            text = stringResource(R.string.translate_running_title, bookTitle),
            style = MaterialTheme.typography.titleLarge,
        )
        if (progress != null) {
            // Determinate, and only redrawn when a batch commits — see the screen docstring.
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            progress.chapterIndex?.let { index ->
                Text(
                    text =
                        progress.chapterTitle?.let { title ->
                            stringResource(R.string.translate_running_chapter, index, title)
                        } ?: stringResource(R.string.translate_running_chapter_untitled, index),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(
                    R.string.translate_running_segments,
                    progress.processedSegments,
                    progress.totalSegments,
                ),
            )
            Text(
                text =
                    stringResource(
                        R.string.translate_running_spend,
                        formatEur(progress.costEur),
                        progress.apiCalls,
                    ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = stringResource(R.string.translate_running_resumable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancelRun) { Text(stringResource(R.string.translate_stop)) }
    }
}

@Composable
private fun DoneSection(
    state: TranslateUiState.Done,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = stringResource(R.string.translate_done_title, state.bookTitle),
        style = MaterialTheme.typography.titleLarge,
    )
    val progress = state.progress
    Text(
        text =
            if (progress == null || progress.apiCalls == 0) {
                stringResource(R.string.translate_done_free)
            } else {
                stringResource(
                    R.string.translate_done_spend,
                    formatEur(progress.costEur),
                    progress.apiCalls,
                )
            },
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.translate_done_open))
    }
    TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.translate_choose_file))
    }
}

@Composable
private fun FailedSection(
    state: TranslateUiState.Failed,
    onRetry: () -> Unit,
    onStartOver: () -> Unit,
) {
    Text(stringResource(R.string.translate_failed_title), style = MaterialTheme.typography.titleLarge)
    Text(
        text = state.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    if (state.retryable) {
        Text(
            text = stringResource(R.string.translate_failed_resumable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.translate_retry))
        }
    }
    TextButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.translate_start_over))
    }
}
