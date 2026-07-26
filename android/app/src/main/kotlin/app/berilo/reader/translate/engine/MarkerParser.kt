package app.berilo.reader.translate.engine

import app.berilo.reader.translate.model.pythonStrip

/**
 * The `[[n]]` marker protocol, ported from `translator/berilo/translate.py`.
 *
 * This file is where the segment-integrity guarantee (CLAUDE.md §2) is actually enforced: a
 * batch reply is accepted only when it maps **exactly 1:1** onto the segments that were sent —
 * same count, every index in `1..n` present exactly once, none empty. Anything else is a parse
 * failure that sends the caller down the retry ladder. Nothing is ever silently dropped.
 */

/**
 * Marker wrapping each segment's ordinal, e.g. `[[1]]`.
 *
 * **Anchored to the start of a line**, because that is where `numberedSourceBlock` puts it and
 * where every system prompt demands it back ("each on its own line"). A `[[2]]` occurring
 * *inside* a translation ("element `[[2]]` of the array") is prose, not a marker (A3, review
 * finding 14) — before anchoring it forced a strict retry and possibly a per-segment fallback,
 * which is pure wasted spend.
 *
 * [RegexOption.UNIX_LINES] is not decoration. Java's `MULTILINE` lets `^` match after a
 * carriage return, and after U+0085 NEXT LINE, U+2028 and U+2029, as well as after a newline;
 * Python's `re.MULTILINE` matches only after a newline. Without it a reply carrying a bare
 * carriage return would parse differently on the tablet than on the workstation.
 */
private val ANCHORED_MARKER_RE =
    Regex("""^[ \t]*\[\[(\d+)]]""", setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES))

/**
 * The pre-anchoring pattern, kept as a **second parsing attempt** so a reply that puts several
 * markers on one line still parses exactly as it did before A3 anchored the first pass.
 *
 * This is what makes anchoring cost-monotone: it can only *remove* retries, never add one. Do
 * not delete this second pass to "simplify" the parser — a single-line reply would then start
 * costing a strict retry it never used to.
 */
private val LOOSE_MARKER_RE = Regex("""\[\[(\d+)]]""")

/**
 * A batch reply did not map 1:1 onto the segments that were sent.
 *
 * The Kotlin counterpart of the `ValueError` `translate.py`'s ladder catches: a *recoverable*
 * signal that this rung failed, never a reason to abandon the book.
 */
class MarkerMappingException(message: String) : RuntimeException(message)

/**
 * Parse a `[[n]] translation` reply into an ordered list of translations.
 *
 * Markers are looked for at the start of a line first; if that does not produce a 1:1 mapping,
 * the reply is re-scanned with the historical unanchored pattern before failing. See
 * [ANCHORED_MARKER_RE] and [LOOSE_MARKER_RE] for why both passes exist.
 *
 * @param text The model's reply.
 * @param expectedCount The number of segments that were sent.
 * @return Exactly [expectedCount] non-empty translations, in order `1..n`.
 * @throws MarkerMappingException If the reply has missing, extra, duplicate or empty markers
 *   under **both** scans.
 */
fun parseNumberedResponse(text: String, expectedCount: Int): List<String> =
    try {
        splitOnMarkers(text, ANCHORED_MARKER_RE, expectedCount)
    } catch (anchored: MarkerMappingException) {
        // Fall through to the historical unanchored scan, which also owns the diagnostic
        // message when the reply is genuinely not 1:1.
        splitOnMarkers(text, LOOSE_MARKER_RE, expectedCount)
    }

/**
 * Split [text] on [pattern]'s markers into a 1:1 list of translations.
 *
 * @param text The model's reply.
 * @param pattern Marker pattern to scan with (anchored or loose).
 * @param expectedCount The number of segments that were sent.
 * @return [expectedCount] non-empty translations, in order `1..n`.
 * @throws MarkerMappingException If this pattern does not yield exactly that mapping.
 */
private fun splitOnMarkers(
    text: String,
    pattern: Regex,
    expectedCount: Int,
): List<String> {
    val matches = pattern.findAll(text).toList()
    val parsed = mutableMapOf<Long, String>()
    matches.forEachIndexed { i, match ->
        // Python's ints are unbounded, so `[[99999999999999999999]]` is a legal (absurd) key
        // there rather than an overflow. Long covers every marker a model could plausibly emit;
        // anything wider collapses to a sentinel that can never be in 1..n, which is the same
        // outcome Python reaches by a different route.
        val index = match.groupValues[1].toLongOrNull() ?: Long.MIN_VALUE
        val start = match.range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
        parsed[index] = text.substring(start, end).pythonStrip()
    }

    if (matches.size != expectedCount || parsed.size != expectedCount) {
        throw MarkerMappingException(
            "expected $expectedCount numbered segments, " +
                "found ${matches.size} markers (${parsed.size} distinct)",
        )
    }
    return (1L..expectedCount.toLong()).map { n ->
        val value = parsed[n]
        if (value.isNullOrEmpty()) {
            throw MarkerMappingException("segment [[$n]] missing or empty in reply")
        }
        value
    }
}
