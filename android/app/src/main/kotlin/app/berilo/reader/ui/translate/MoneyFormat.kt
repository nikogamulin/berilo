package app.berilo.reader.ui.translate

import java.util.Locale

/** Below this many euro the cent-precision form would round a real cost to "EUR 0.00". */
private const val SUB_CENT_THRESHOLD_EUR = 0.10

private const val EURO_SIGN = "€"

/**
 * Render a cost in euro for display.
 *
 * Two decimals for a book-sized figure, four below [SUB_CENT_THRESHOLD_EUR]. The switch is the
 * point: a resumed run that legitimately costs EUR 0.0031 must not be shown as "EUR 0.00", which
 * reads as "free" — the one number CLAUDE.md §4 exists to keep honest.
 *
 * [Locale.US] is fixed so the decimal separator never depends on device locale; a cost rendered
 * as "0,70" against an estimate rendered as "0.70" would look like two different numbers.
 *
 * @param eur The amount in euro.
 */
fun formatEur(eur: Double): String {
    val pattern = if (eur > 0.0 && eur < SUB_CENT_THRESHOLD_EUR) "%.4f" else "%.2f"
    return EURO_SIGN + String.format(Locale.US, pattern, eur)
}
