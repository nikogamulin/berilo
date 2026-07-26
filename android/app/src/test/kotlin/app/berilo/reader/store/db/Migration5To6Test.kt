package app.berilo.reader.store.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 5 -> 6 migration (B4), exercised against a hand-built version-5 database.
 *
 * Unlike [Migration4To5Test] (which transforms shipped columns), 5 -> 6 only adds three new
 * tables — the on-device translation cache. There is no pre-existing translation data to
 * transform, so the load-bearing assertion is the negative one: every row that already existed
 * across `books`, `highlights`, `dictionary_entries`, `interpretation_entries` and `sync_state`
 * must still be there afterwards, untouched, alongside the new empty tables.
 *
 * Room's `MigrationTestHelper` is not usable here — it reads exported schema JSON, and
 * `exportSchema` is off because turning it on breaks `kspReleaseKotlin` on this dependency set
 * (`docs/findings.md`). Building the old schema by hand, as [Migration4To5Test] does, is the
 * equivalent evidence.
 */
@RunWith(RobolectricTestRunner::class)
class Migration5To6Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** The version-5 schema, exactly as S3.2 shipped it. */
    private val schemaV5 =
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
                            object : SupportSQLiteOpenHelper.Callback(5) {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    schemaV5.forEach(db::execSQL)
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
        helper.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    private fun migrate() = MIGRATION_5_6.migrate(db)

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
    fun `the new translation-cache tables are created empty and writable`() {
        migrate()

        assertEquals(0, rowCount("translations"))
        assertEquals(0, rowCount("glossaries"))
        assertEquals(0, rowCount("calls"))

        db.execSQL(
            "INSERT INTO translations VALUES " +
                "('bh1','sh1','gpt-5-mini','sl','revise_v1','gh1','Prevod.',0.001,123)",
        )
        db.execSQL(
            "INSERT INTO glossaries VALUES ('bh1','gpt-5-mini','sl','glossary_v1','{}',123)",
        )
        db.execSQL(
            "INSERT INTO calls (bookHash, model, lang, kind, inputTokens, outputTokens, costEur, createdAt) " +
                "VALUES ('bh1','gpt-5-mini','sl','batch',10,20,0.001,123)",
        )

        assertEquals(1, rowCount("translations"))
        assertEquals(1, rowCount("glossaries"))
        assertEquals(1, rowCount("calls"))
    }

    private companion object {
        const val DB_NAME = "migration-5-6-test.db"
    }
}
