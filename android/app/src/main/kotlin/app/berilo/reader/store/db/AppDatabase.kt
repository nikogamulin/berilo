package app.berilo.reader.store.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * App-wide Room database. Version 1 held the `books` table only (S2.1); version 2 (S2.4)
 * added `dictionary_entries` for the LLM dictionary cache; version 3 (S2.5) adds
 * `interpretation_entries` for the paragraph interpretation cache; version 4 (S2.6) adds
 * `highlights` for highlights/notes; version 5 (S3.2) makes the synced entities sync-shaped
 * (sync timestamps, tombstones, raw dictionary sentence) and adds `sync_state`; version 6 (B4)
 * adds the on-device translation cache (`translations`, `glossaries`, `calls`), the Kotlin
 * mirror of `translator/berilo/cache.py`; version 7 (B9) adds `translation_flags`, passages the
 * reader marked as badly translated; version 8 (S3.7) adds the personal book vault's device-side
 * bookkeeping (`vault_books`, `vault_translations`, `vault_glossaries`), every one of them keyed
 * `(userId, …)` per `docs/sync_api.md` §8.2(1).
 *
 * Versions 1–4 predate any released build, so the app still falls back to a destructive
 * migration for those; 4 -> 5 is a real [MIGRATION_4_5] because S2.11 put builds on a device
 * and highlights are now user data worth keeping; 5 -> 6 is [MIGRATION_5_6], purely additive
 * (no on-device translation data predates it); 6 -> 7 is [MIGRATION_6_7], additive over a
 * version that holds paid translation work and therefore must never be destructively migrated;
 * 7 -> 8 is [MIGRATION_7_8], additive for the same reason — see [app.berilo.reader.AppContainer].
 *
 * Schema export (`exportSchema`) stays off — turning it on pulls in Room's
 * kotlinx-serialization-based schema bundler on the KSP classpath, which clashes on this
 * dependency set (`docs/findings.md`).
 */
@Database(
    entities = [
        BookEntity::class,
        DictionaryEntryEntity::class,
        InterpretationEntryEntity::class,
        HighlightEntity::class,
        SyncStateEntity::class,
        TranslationEntity::class,
        GlossaryEntity::class,
        TranslationCallEntity::class,
        TranslationFlagEntity::class,
        VaultBookEntity::class,
        VaultTranslationEntity::class,
        VaultGlossaryEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(HighlightColorConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    abstract fun dictionaryDao(): DictionaryDao

    abstract fun interpretationDao(): InterpretationDao

    abstract fun highlightDao(): HighlightDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun translationCacheDao(): TranslationCacheDao

    abstract fun translationFlagDao(): TranslationFlagDao

    abstract fun vaultDao(): VaultDao

    companion object {
        const val DATABASE_NAME = "berilo.db"
    }
}
