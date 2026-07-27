package app.berilo.reader.store.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 7 -> 8 migration (S3.7), exercised against a hand-built version-7 database.
 *
 * Two things are being proved, and the second is the one that a row-counting test cannot see.
 *
 * 1. **Nothing pre-existing moves.** Version 7 holds B4's paid `translations`/`glossaries`/
 *    `calls` cache and B9's `translation_flags`. Extending the destructive fallback over this
 *    bump would re-bill every book on the device.
 * 2. **The new tables match the entities column for column**, asserted with `PRAGMA table_info`
 *    — name, declared type, nullability and primary-key position. `docs/findings.md`
 *    (2026-07-27) records exactly why: `MIGRATION_5_6` declared `calls.id` without `NOT NULL`
 *    while Room generates it with, and because SQLite reports `notnull = 0` without the keyword
 *    and Room's `TableInfo` comparison is **exact**, every existing v5 install would have thrown
 *    on its first open. A fresh install was unaffected, so the row-counting test, the full suite
 *    and the merge review all passed. **Assert the schema, not just the rows.**
 *
 * The `userId`-first primary keys asserted below are not a schema detail: `docs/sync_api.md`
 * §8.2(1) requires per-user isolation to live in the key, and `vault_translations` in particular
 * is the row §8.3(1) says must never be shared across accounts.
 */
@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** The version-7 schema, exactly as B9 left it. */
    private val schemaV7 =
        listOf(
            """CREATE TABLE IF NOT EXISTS books (
                id TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL,
                filePath TEXT NOT NULL, coverPath TEXT, addedAt INTEGER NOT NULL,
                lastOpenedAt INTEGER, progressionJson TEXT, sourceLang TEXT, targetLang TEXT,
                updatedAt INTEGER NOT NULL DEFAULT 0, deletedAt INTEGER, PRIMARY KEY(id))""",
            """CREATE TABLE IF NOT EXISTS highlights (
                id TEXT NOT NULL, bookId TEXT NOT NULL, color TEXT NOT NULL,
                selectedText TEXT NOT NULL, note TEXT, locatorJson TEXT NOT NULL,
                chapterTitle TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                deletedAt INTEGER, PRIMARY KEY(id))""",
            "CREATE INDEX IF NOT EXISTS index_highlights_bookId ON highlights (bookId)",
            """CREATE TABLE IF NOT EXISTS dictionary_entries (
                word TEXT NOT NULL, sentenceHash TEXT NOT NULL, lang TEXT NOT NULL,
                model TEXT NOT NULL, definition TEXT NOT NULL, contextMeaning TEXT NOT NULL,
                baseForm TEXT NOT NULL, usageNote TEXT NOT NULL, costEur REAL NOT NULL,
                createdAt INTEGER NOT NULL, sentence TEXT NOT NULL DEFAULT '',
                updatedAt INTEGER NOT NULL DEFAULT 0, deletedAt INTEGER,
                PRIMARY KEY(word, sentenceHash, lang, model))""",
            """CREATE TABLE IF NOT EXISTS interpretation_entries (
                passageHash TEXT NOT NULL, lang TEXT NOT NULL, model TEXT NOT NULL,
                text TEXT NOT NULL, costEur REAL NOT NULL, createdAt INTEGER NOT NULL,
                PRIMARY KEY(passageHash, lang, model))""",
            """CREATE TABLE IF NOT EXISTS sync_state (
                entity TEXT NOT NULL, cursor TEXT, lastPushedAt INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(entity))""",
            """CREATE TABLE IF NOT EXISTS translations (
                bookHash TEXT NOT NULL, segmentHash TEXT NOT NULL, model TEXT NOT NULL,
                lang TEXT NOT NULL, promptVersion TEXT NOT NULL, glossaryHash TEXT NOT NULL,
                text TEXT NOT NULL, costEur REAL NOT NULL, createdAt INTEGER NOT NULL,
                PRIMARY KEY(bookHash, segmentHash, model, lang, promptVersion, glossaryHash))""",
            """CREATE TABLE IF NOT EXISTS glossaries (
                bookHash TEXT NOT NULL, model TEXT NOT NULL, lang TEXT NOT NULL,
                promptVersion TEXT NOT NULL, termsJson TEXT NOT NULL, createdAt INTEGER NOT NULL,
                PRIMARY KEY(bookHash, model, lang, promptVersion))""",
            """CREATE TABLE IF NOT EXISTS calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookHash TEXT NOT NULL,
                model TEXT NOT NULL, lang TEXT NOT NULL, kind TEXT NOT NULL,
                inputTokens INTEGER NOT NULL, outputTokens INTEGER NOT NULL,
                costEur REAL NOT NULL, createdAt INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS translation_flags (
                id TEXT NOT NULL, bookId TEXT NOT NULL, selectedText TEXT NOT NULL,
                comment TEXT, locatorJson TEXT NOT NULL, chapterTitle TEXT,
                cacheBookHash TEXT, cacheSegmentHash TEXT, cacheModel TEXT, cacheLang TEXT,
                cachePromptVersion TEXT, cacheGlossaryHash TEXT,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER,
                PRIMARY KEY(id))""",
            "CREATE INDEX IF NOT EXISTS index_translation_flags_bookId ON translation_flags (bookId)",
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper =
            FrameworkSQLiteOpenHelperFactory()
                .create(
                    SupportSQLiteOpenHelper.Configuration.builder(context)
                        .name(DB_NAME)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(7) {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    schemaV7.forEach(db::execSQL)
                                }

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = Unit
                            },
                        )
                        .build(),
                )
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        if (::helper.isInitialized) helper.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    private fun migrate() = MIGRATION_7_8.migrate(db)

    private fun rowCount(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun insertAllTablesOneRowEach() {
        db.execSQL(
            "INSERT INTO books VALUES " +
                "('b1','Naslov','Avtor','/f/b1.epub',NULL,1000,NULL,NULL,'en','sl',1000,NULL)",
        )
        db.execSQL(
            """INSERT INTO highlights VALUES
               ('h1','b1','AMBER','izbrano besedilo','moj zapisek','{"href":"/c1"}','I',100,200,NULL)""",
        )
        db.execSQL(
            """INSERT INTO dictionary_entries VALUES
               ('brook','abc','sl','gpt-5-mini','potok','vodotok','brook','sam.',0.0001,999,
               'The brook ran clear.',999,NULL)""",
        )
        db.execSQL(
            """INSERT INTO interpretation_entries VALUES
               ('phash1','sl','gpt-5-mini','Pomen odlomka.',0.0002,888)""",
        )
        db.execSQL("INSERT INTO sync_state VALUES ('highlights','cursor-1',777)")
        db.execSQL(
            "INSERT INTO translations VALUES " +
                "('bh1','sh1','gpt-5-mini','sl','revise_v1','gh1','Plačan prevod.',0.001,123)",
        )
        db.execSQL("INSERT INTO glossaries VALUES ('bh1','gpt-5-mini','sl','glossary_v1','{}',123)")
        db.execSQL(
            "INSERT INTO calls (bookHash, model, lang, kind, inputTokens, outputTokens, costEur, createdAt) " +
                "VALUES ('bh1','gpt-5-mini','sl','batch',10,20,0.001,123)",
        )
        db.execSQL(
            "INSERT INTO translation_flags VALUES " +
                "('f1','b1','slab prevod','moj predlog','{\"href\":\"/c1\"}','I'," +
                "'bh1','sh1','gpt-5-mini','sl','revise_v1','gh1',100,100,NULL)",
        )
    }

    @Test
    fun `every pre-existing table survives the migration with its rows intact`() {
        insertAllTablesOneRowEach()

        migrate()

        assertEquals(1, rowCount("books"))
        assertEquals(1, rowCount("highlights"))
        assertEquals(1, rowCount("dictionary_entries"))
        assertEquals(1, rowCount("interpretation_entries"))
        assertEquals(1, rowCount("sync_state"))
        assertEquals(1, rowCount("translations"))
        assertEquals(1, rowCount("glossaries"))
        assertEquals(1, rowCount("calls"))
        assertEquals(1, rowCount("translation_flags"))
    }

    @Test
    fun `the paid translation cache is still resolvable under its own six-column key`() {
        insertAllTablesOneRowEach()

        migrate()

        db.query(
            "SELECT text, costEur FROM translations WHERE bookHash = 'bh1' AND segmentHash = 'sh1' " +
                "AND model = 'gpt-5-mini' AND lang = 'sl' AND promptVersion = 'revise_v1' " +
                "AND glossaryHash = 'gh1'",
        ).use { cursor ->
            assertTrue("the cached translation is no longer resolvable", cursor.moveToFirst())
            assertEquals("Plačan prevod.", cursor.getString(0))
            assertEquals(0.001, cursor.getDouble(1), 1e-9)
        }
    }

    // --- schema assertions: the half a row count cannot see --------------------------------

    /**
     * `vault_translations`, column for column. The expected list is the shape Room generates
     * from [VaultTranslationEntity]; `docs/findings.md` (2026-07-27) is the reason it is spelled
     * out rather than sampled.
     */
    @Test
    fun `vault_translations matches its entity column for column`() {
        migrate()

        assertEquals(
            listOf(
                ColumnInfo("userId", "TEXT", notNull = true, pkPosition = 1),
                ColumnInfo("bookHash", "TEXT", notNull = true, pkPosition = 2),
                ColumnInfo("segmentHash", "TEXT", notNull = true, pkPosition = 3),
                ColumnInfo("model", "TEXT", notNull = true, pkPosition = 4),
                ColumnInfo("lang", "TEXT", notNull = true, pkPosition = 5),
                ColumnInfo("promptVersion", "TEXT", notNull = true, pkPosition = 6),
                ColumnInfo("glossaryHash", "TEXT", notNull = true, pkPosition = 7),
                ColumnInfo("uploadedAt", "INTEGER", notNull = true, pkPosition = 0),
            ),
            tableInfo("vault_translations"),
        )
    }

    /** `vault_books`, column for column, including every nullable column. */
    @Test
    fun `vault_books matches its entity column for column`() {
        migrate()

        assertEquals(
            listOf(
                ColumnInfo("userId", "TEXT", notNull = true, pkPosition = 1),
                ColumnInfo("bookHash", "TEXT", notNull = true, pkPosition = 2),
                ColumnInfo("enabled", "INTEGER", notNull = true, pkPosition = 0),
                ColumnInfo("objectPath", "TEXT", notNull = true, pkPosition = 0),
                ColumnInfo("kdfSalt", "BLOB", notNull = false, pkPosition = 0),
                ColumnInfo("algorithm", "TEXT", notNull = false, pkPosition = 0),
                ColumnInfo("sizeBytes", "INTEGER", notNull = true, pkPosition = 0),
                ColumnInfo("uploadedAt", "INTEGER", notNull = false, pkPosition = 0),
                ColumnInfo("updatedAt", "INTEGER", notNull = true, pkPosition = 0),
                ColumnInfo("deletedAt", "INTEGER", notNull = false, pkPosition = 0),
            ),
            tableInfo("vault_books"),
        )
    }

    /** `vault_glossaries`, column for column. */
    @Test
    fun `vault_glossaries matches its entity column for column`() {
        migrate()

        assertEquals(
            listOf(
                ColumnInfo("userId", "TEXT", notNull = true, pkPosition = 1),
                ColumnInfo("bookHash", "TEXT", notNull = true, pkPosition = 2),
                ColumnInfo("model", "TEXT", notNull = true, pkPosition = 3),
                ColumnInfo("lang", "TEXT", notNull = true, pkPosition = 4),
                ColumnInfo("promptVersion", "TEXT", notNull = true, pkPosition = 5),
                ColumnInfo("uploadedAt", "INTEGER", notNull = true, pkPosition = 0),
            ),
            tableInfo("vault_glossaries"),
        )
    }

    /**
     * The vault tables must carry **no** SQL `DEFAULT`: the entities declare no
     * `@ColumnInfo(defaultValue = …)`, and Room's `TableInfo` comparison would reject a default
     * this migration invented. Off-by-default (§8.2(4)) is enforced in `VaultDao`/
     * `VaultRepository`, where a missing row reads as off — not by a SQL default.
     */
    @Test
    fun `no vault column declares a SQL default Room does not expect`() {
        migrate()

        listOf("vault_books", "vault_translations", "vault_glossaries").forEach { table ->
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    assertTrue(
                        "$table.${cursor.getString(nameIndex)} declares a SQL DEFAULT that " +
                            "Room's TableInfo does not expect",
                        cursor.isNull(defaultIndex),
                    )
                }
            }
        }
    }

    @Test
    fun `the vault tables are created empty and writable`() {
        migrate()

        assertEquals(0, rowCount("vault_books"))
        assertEquals(0, rowCount("vault_translations"))
        assertEquals(0, rowCount("vault_glossaries"))

        db.execSQL(
            "INSERT INTO vault_books VALUES " +
                "('user_1','bh1',1,'vault/user_1/bh1.enc',X'00','AES-256-GCM',100,5,5,NULL)",
        )
        db.execSQL(
            "INSERT INTO vault_translations VALUES " +
                "('user_1','bh1','sh1','gpt-5-mini','sl','revise_v1','gh1',5)",
        )
        assertEquals(1, rowCount("vault_books"))
        assertEquals(1, rowCount("vault_translations"))
    }

    /**
     * Two accounts, same `book_hash`: both rows must coexist. This is §8.3(1) asserted at the
     * SQLite layer — with `userId` out of the primary key the second insert would be rejected as
     * a constraint violation or silently replace the first.
     */
    @Test
    fun `two users can hold the same book_hash without colliding - sync_api 8_3_1`() {
        migrate()

        db.execSQL(
            "INSERT INTO vault_translations VALUES " +
                "('user_alice','same-book','sh1','gpt-5-mini','sl','revise_v1','gh1',1)",
        )
        db.execSQL(
            "INSERT INTO vault_translations VALUES " +
                "('user_bob','same-book','sh1','gpt-5-mini','sl','revise_v1','gh1',2)",
        )

        assertEquals(
            "two accounts owning the same ISBN must occupy two rows, never one",
            2,
            rowCount("vault_translations"),
        )
    }

    @Test
    fun `the migration is idempotent under a repeated apply`() {
        insertAllTablesOneRowEach()

        migrate()
        migrate()

        assertEquals(1, rowCount("translations"))
        assertEquals(0, rowCount("vault_translations"))
    }

    /**
     * The strongest check available: reopen the migrated *file* through the real Room builder,
     * which runs Room's own `validateMigration` against the entity definitions. A `NOT NULL`,
     * type or default mismatch that every assertion above somehow missed surfaces here as the
     * `IllegalStateException` a real device would have thrown on first launch after upgrade.
     */
    @Test
    fun `a migrated v7 file opens as v8 through Room with every row still readable`() =
        runTest {
            insertAllTablesOneRowEach()
            helper.close()

            val context = ApplicationProvider.getApplicationContext<Context>()
            val database =
                Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
            try {
                assertEquals(
                    "Plačan prevod.",
                    database.translationCacheDao()
                        .getTranslation("bh1", "sh1", "gpt-5-mini", "sl", "revise_v1", "gh1"),
                )
                assertNotNull(database.highlightDao().getById("h1"))
                assertNotNull(database.translationFlagDao().getById("f1"))
                assertEquals(0.001, database.translationCacheDao().totalCost("bh1"), 1e-9)

                // And the new tables are usable through the DAO Room generated for them.
                database.vaultDao().upsertBook(
                    VaultBookEntity(
                        userId = "user_1",
                        bookHash = "bh1",
                        enabled = true,
                        objectPath = "vault/user_1/bh1.enc",
                        kdfSalt = ByteArray(16),
                        algorithm = "AES-256-GCM/PBKDF2-HMAC-SHA256",
                        sizeBytes = 42L,
                        uploadedAt = 5L,
                        updatedAt = 5L,
                        deletedAt = null,
                    ),
                )
                assertEquals(true, database.vaultDao().isEnabled("user_1", "bh1"))
                assertEquals(
                    "another account must see nothing of it",
                    null,
                    database.vaultDao().isEnabled("user_2", "bh1"),
                )
            } finally {
                database.close()
            }
        }

    /** One row of `PRAGMA table_info`, reduced to the four fields Room's `TableInfo` compares. */
    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val pkPosition: Int,
    )

    private fun tableInfo(table: String): List<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                columns.add(
                    ColumnInfo(
                        name = cursor.getString(nameIndex),
                        type = cursor.getString(typeIndex),
                        notNull = cursor.getInt(notNullIndex) == 1,
                        pkPosition = cursor.getInt(pkIndex),
                    ),
                )
            }
        }
        return columns
    }

    private companion object {
        const val DB_NAME = "migration-7-8-test.db"
    }
}
