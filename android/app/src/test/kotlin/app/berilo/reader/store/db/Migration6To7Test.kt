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
 * The 6 -> 7 migration (B9), exercised against a hand-built version-6 database.
 *
 * Version 6 is the first version that holds *paid* work — B4's `translations`/`glossaries`/
 * `calls` cache — so the load-bearing assertion here is not that the new table appears but that
 * nothing else moved. Extending `fallbackToDestructiveMigration` over this bump would silently
 * re-bill every book on the device.
 *
 * Room's `MigrationTestHelper` is unusable (no exported schema — see [Migration5To6Test]), so
 * the version-6 schema is built by hand, exactly as its two predecessors do. The last test goes
 * one step further than either: it opens the migrated *file* through the real Room builder, so
 * Room's own `validateMigration` checks every column, type and index name against
 * [TranslationFlagEntity]. A hand-written `CREATE INDEX` under a name Room does not expect
 * passes every row assertion in this class and still crashes the app on first launch after
 * upgrade; only that open catches it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration6To7Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** The version-6 schema, exactly as B4 shipped it. */
    private val schemaV6 =
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
            // `NOT NULL` on the autoincrement key is not decoration: SQLite's `table_info`
            // pragma reports `notnull = 0` without it, and Room's TableInfo comparison is
            // exact, so the same table declared either way is two different schemas as far as
            // `validateMigration` is concerned. This is the shape Room itself creates, and
            // therefore the shape a valid version-6 database has.
            //
            // MIGRATION_5_6 declares it WITHOUT `NOT NULL` (`Migrations.kt`) — reported, not
            // fixed here: it is a B4 defect on the 5 -> 6 path and unrelated to this story.
            """CREATE TABLE IF NOT EXISTS calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookHash TEXT NOT NULL,
                model TEXT NOT NULL, lang TEXT NOT NULL, kind TEXT NOT NULL,
                inputTokens INTEGER NOT NULL, outputTokens INTEGER NOT NULL,
                costEur REAL NOT NULL, createdAt INTEGER NOT NULL)""",
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
                            object : SupportSQLiteOpenHelper.Callback(6) {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    schemaV6.forEach(db::execSQL)
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

    private fun migrate() = MIGRATION_6_7.migrate(db)

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

        db.query("SELECT title, sourceLang, targetLang FROM books WHERE id = 'b1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Naslov", cursor.getString(0))
            assertEquals("en", cursor.getString(1))
            assertEquals("sl", cursor.getString(2))
        }
        db.query("SELECT selectedText, note FROM highlights WHERE id = 'h1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("izbrano besedilo", cursor.getString(0))
            assertEquals("moj zapisek", cursor.getString(1))
        }
        db.query("SELECT definition, sentence FROM dictionary_entries WHERE word = 'brook'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("potok", cursor.getString(0))
            assertEquals("The brook ran clear.", cursor.getString(1))
        }
        db.query("SELECT text FROM interpretation_entries WHERE passageHash = 'phash1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Pomen odlomka.", cursor.getString(0))
        }
        db.query("SELECT cursor, lastPushedAt FROM sync_state WHERE entity = 'highlights'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("cursor-1", cursor.getString(0))
            assertEquals(777, cursor.getLong(1))
        }
    }

    @Test
    fun `the paid translation cache is byte-identical after the migration`() {
        // The specific reason this migration is real rather than destructive: `translations`
        // holds text that cost money. Row count alone would pass under a mutation that
        // rewrote the text, and resolution under the runtime's own six-column key is what
        // proves the rows are still reachable rather than merely present (docs/findings.md,
        // the migration-verification recipe).
        insertAllTablesOneRowEach()

        migrate()

        db.query(
            "SELECT text, costEur FROM translations WHERE bookHash = 'bh1' AND segmentHash = 'sh1' " +
                "AND model = 'gpt-5-mini' AND lang = 'sl' AND promptVersion = 'revise_v1' " +
                "AND glossaryHash = 'gh1'",
        ).use { cursor ->
            assertTrue("the cached translation is no longer resolvable under its own key", cursor.moveToFirst())
            assertEquals("Plačan prevod.", cursor.getString(0))
            assertEquals(0.001, cursor.getDouble(1), 1e-9)
        }
        db.query("SELECT termsJson FROM glossaries WHERE bookHash = 'bh1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
        }
        db.query("SELECT costEur FROM calls WHERE bookHash = 'bh1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0.001, cursor.getDouble(0), 1e-9)
        }
    }

    @Test
    fun `the flags table is created empty, indexed and writable`() {
        migrate()

        assertEquals(0, rowCount("translation_flags"))
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_translation_flags_bookId'",
        ).use { cursor ->
            assertTrue("Room derives this index name from @Index(\"bookId\") and validates it", cursor.moveToFirst())
        }

        db.execSQL(
            "INSERT INTO translation_flags VALUES " +
                "('f1','b1','slab prevod','moj predlog','{\"href\":\"/c1\"}','I'," +
                "'bh1','sh1','gpt-5-mini','sl','revise_v1','gh1',100,100,NULL)",
        )
        assertEquals(1, rowCount("translation_flags"))
    }

    @Test
    fun `the migration is idempotent under a repeated apply`() {
        insertAllTablesOneRowEach()

        migrate()
        migrate()

        assertEquals(1, rowCount("translations"))
        assertEquals(0, rowCount("translation_flags"))
    }

    @Test
    fun `a migrated v6 file opens at the current schema version with every row still readable`() =
        runTest {
            insertAllTablesOneRowEach()
            helper.close()

            val context = ApplicationProvider.getApplicationContext<Context>()
            val database =
                Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                    // The whole chain up to [AppDatabase]'s current version, not just 6 -> 7:
                    // Room migrates the file to whatever version the @Database declares, so this
                    // list has to grow with every schema bump or the reopen fails with
                    // "A migration from 6 to N was required but not found" (S3.7 added 7 -> 8).
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
            try {
                // Opening runs the migration AND Room's own schema validation — the only check
                // that compares the hand-written DDL above against TranslationFlagEntity.
                assertEquals(
                    "Plačan prevod.",
                    database.translationCacheDao()
                        .getTranslation("bh1", "sh1", "gpt-5-mini", "sl", "revise_v1", "gh1"),
                )
                assertNotNull(database.highlightDao().getById("h1"))
                assertEquals(0.001, database.translationCacheDao().totalCost("bh1"), 1e-9)

                database.translationFlagDao().insert(
                    TranslationFlagEntity(
                        id = "f1",
                        bookId = "b1",
                        selectedText = "slab prevod",
                        comment = null,
                        locatorJson = "{}",
                        chapterTitle = null,
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                )
                assertNotNull(database.translationFlagDao().getById("f1"))
            } finally {
                database.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-6-7-test.db"
    }
}
