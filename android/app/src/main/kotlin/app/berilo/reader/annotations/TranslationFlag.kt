package app.berilo.reader.annotations

import app.berilo.reader.store.db.TranslationFlagEntity

/**
 * Identity of the cached translation a flagged passage was matched to (B9).
 *
 * These are the six columns of the translation cache's primary key, in the same order and with
 * the same meanings as [app.berilo.reader.store.db.TranslationEntity]. Together they name the
 * exact run that produced the flagged text — model, prompt version and glossary included — so
 * a bad translation can later be re-examined against the conditions that created it rather than
 * against whatever the defaults happen to be by then.
 *
 * [segmentHash] is the closest thing to the source text this database holds: `translations`
 * stores only the sha1 of the stripped source, so the prose itself needs the source EPUB.
 */
data class TranslationProvenance(
    val bookHash: String,
    val segmentHash: String,
    val model: String,
    val lang: String,
    val promptVersion: String,
    val glossaryHash: String,
)

/**
 * A passage the reader marked as badly translated — the UI-layer model over
 * [TranslationFlagEntity].
 *
 * @property comment The user's suggestion or complaint; null for a bare flag. Both are valid
 *   and both are stored: a flag with no words still says "look here".
 * @property provenance The matched translation-cache key, or null when the passage could not be
 *   matched to any cached translation. Never a reason to refuse the flag.
 */
data class TranslationFlag(
    val id: String,
    val bookId: String,
    val selectedText: String,
    val comment: String?,
    val locatorJson: String,
    val chapterTitle: String?,
    val provenance: TranslationProvenance?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Maps a persisted [TranslationFlagEntity] to the [TranslationFlag] the UI layer consumes. */
fun TranslationFlagEntity.toDomain(): TranslationFlag =
    TranslationFlag(
        id = id,
        bookId = bookId,
        selectedText = selectedText,
        comment = comment,
        locatorJson = locatorJson,
        chapterTitle = chapterTitle,
        provenance = toProvenance(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/**
 * The six `cache*` columns as a [TranslationProvenance], or null.
 *
 * They are written as a group and read as a group: a row with only some of them set would mean
 * "matched a cache entry that has no model", which cannot happen, so anything short of all six
 * is treated as unmatched rather than half-reported.
 */
private fun TranslationFlagEntity.toProvenance(): TranslationProvenance? {
    return TranslationProvenance(
        bookHash = cacheBookHash ?: return null,
        segmentHash = cacheSegmentHash ?: return null,
        model = cacheModel ?: return null,
        lang = cacheLang ?: return null,
        promptVersion = cachePromptVersion ?: return null,
        glossaryHash = cacheGlossaryHash ?: return null,
    )
}
