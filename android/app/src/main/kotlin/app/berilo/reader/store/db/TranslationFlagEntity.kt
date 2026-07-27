package app.berilo.reader.store.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A passage the reader marked as a bad translation (B9).
 *
 * First-class user-created data, shaped like [HighlightEntity]: a stable id, a book reference,
 * the exact text the user saw, a Readium locator, a denormalized chapter title, and the
 * `updatedAt`/`deletedAt` pair every synced entity in this schema carries (S3.2,
 * `docs/sync_api.md` [OPEN-4]). B9 builds no sync client; it only declines to make one
 * impossible.
 *
 * **The six `cache*` columns are the translation-cache key**, not book metadata — they are
 * copied verbatim from the [TranslationEntity] row whose `text` the flagged passage was matched
 * against, so the exact model, prompt and glossary that produced the bad output can be
 * recovered later. `cacheLang` is therefore the *target* language of the translation (the
 * cache's own `lang` column), never the book's source language. All six are null together when
 * no cached translation matched: an unmatched flag is still worth far more than no flag, so
 * matching failure is never a reason to refuse the write.
 *
 * The source *text* itself is deliberately absent: `translations` stores `segmentHash`, a sha1
 * of the stripped source text, and a hash cannot be inverted. [cacheSegmentHash] is the durable
 * pointer to it — resolving it back to prose needs the source EPUB, which is out of this
 * story's scope.
 *
 * @property id Stable random id (UUID string).
 * @property bookId FK-shaped reference to [BookEntity.id] (no `@ForeignKey`, matching every
 *   other table in this schema).
 * @property selectedText The translated text exactly as the user saw and selected it.
 * @property comment The user's suggestion or complaint, or null for a bare "this is wrong".
 * @property locatorJson Readium Locator JSON ([app.berilo.reader.reader.LocatorCodec]) of the
 *   flagged passage, so the reader can jump back to it.
 * @property chapterTitle Enclosing chapter title at flagging time, or null.
 * @property cacheBookHash Matched [TranslationEntity.bookHash], or null when unmatched.
 * @property cacheSegmentHash Matched [TranslationEntity.segmentHash] — the sha1 of the source
 *   segment's stripped text — or null when unmatched.
 * @property cacheModel Model that produced the flagged translation, or null when unmatched.
 * @property cacheLang Target language the flagged translation was produced in, or null.
 * @property cachePromptVersion Translation-style prompt identity, or null when unmatched.
 * @property cacheGlossaryHash Glossary identity injected into that prompt, or null.
 * @property createdAt Epoch-millis timestamp of creation.
 * @property updatedAt Epoch-millis timestamp of the last edit.
 * @property deletedAt Epoch-millis tombstone, or null for a live row.
 */
@Entity(tableName = "translation_flags", indices = [Index("bookId")])
data class TranslationFlagEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val selectedText: String,
    val comment: String?,
    val locatorJson: String,
    val chapterTitle: String?,
    val cacheBookHash: String? = null,
    val cacheSegmentHash: String? = null,
    val cacheModel: String? = null,
    val cacheLang: String? = null,
    val cachePromptVersion: String? = null,
    val cacheGlossaryHash: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
