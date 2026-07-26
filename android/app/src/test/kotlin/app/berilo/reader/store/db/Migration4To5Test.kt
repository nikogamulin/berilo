package app.berilo.reader.store.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The 4 -> 5 migration, exercised against a hand-built version-4 database.
 *
 * This is the app's first real migration and the first one that runs over shipped user data
 * (S2.11 put builds on a Boox), so "it compiles" is not evidence: the test asserts that a
 * highlight written under version 4 is still readable afterwards, and that the backfills are
 * the honest values rather than `now()`.
 *
 * Room's own `MigrationTestHelper` is not usable here — it reads exported schema JSON, and
 * `exportSchema` is off because turning it on breaks `kspReleaseKotlin` on this dependency set
 * (`docs/findings.md`). Building the old schema by hand is the equivalent evidence.
 */
@RunWith(RobolectricTestRunner::class)
class Migration4To5Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** The version-4 schema, exactly as S2.6 shipped it. */
    private val schemaV4 =
        listOf(
            """CREATE TABLE IF NOT EXISTS books (
                id TEXT NOT NULL, title TEXT NOT NULL, authors TEXT NOT NULL,
                filePath TEXT NOT NULL, coverPath TEXT, addedAt INTEGER NOT NULL,
                lastOpenedAt INTEGER, progressionJson TEXT, PRIMARY KEY(id))""",
            """CREATE TABLE IF NOT EXISTS highlights (
                id TEXT NOT NULL, bookId TEXT NOT NULL, color TEXT NOT NULL,
                selectedText TEXT NOT NULL, note TEXT, locatorJson TEXT NOT NULL,
                chapterTitle TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                PRIMARY KEY(id))""",
            """CREATE TABLE IF NOT EXISTS dictionary_entries (
                word TEXT NOT NULL, sentenceHash TEXT NOT NULL, lang TEXT NOT NULL,
                model TEXT NOT NULL, definition TEXT NOT NULL, contextMeaning TEXT NOT NULL,
                baseForm TEXT NOT NULL, usageNote TEXT NOT NULL, costEur REAL NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(word, sentenceHash, lang, model))""",
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
                            object : SupportSQLiteOpenHelper.Callback(4) {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    schemaV4.forEach(db::execSQL)
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

    private fun migrate() = MIGRATION_4_5.migrate(db)

    private fun queryOne(sql: String): List<String?> =
        db.query(sql).use { cursor ->
            assertTrue("expected a row from: $sql", cursor.moveToFirst())
            (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }

    @Test
    fun `a highlight written under version 4 survives the migration`() {
        db.execSQL(
            """INSERT INTO highlights VALUES
               ('h1','b1','AMBER','izbrano besedilo','moj zapisek','{"href":"/c1"}','I',100,200)""",
        )

        migrate()

        val row = queryOne("SELECT selectedText, note, chapterTitle, deletedAt FROM highlights WHERE id='h1'")
        assertEquals("izbrano besedilo", row[0])
        assertEquals("moj zapisek", row[1])
        assertEquals("I", row[2])
        assertNull("an existing highlight must not become a tombstone", row[3])
    }

    /**
     * The backfill must be the row's real last-write time, not the migration's clock. Stamping
     * `now()` would make every pre-existing row look newer than the server's copy and win
     * last-write-wins against it.
     */
    @Test
    fun `book updatedAt is backfilled from addedAt, not from the clock`() {
        db.execSQL(
            "INSERT INTO books VALUES ('b1','Naslov','Avtor','/f/b1.epub',NULL,1234,NULL,NULL)",
        )

        migrate()

        val row = queryOne("SELECT updatedAt, deletedAt, sourceLang, targetLang FROM books WHERE id='b1'")
        assertEquals("1234", row[0])
        assertNull(row[1])
        assertNull("no language may be invented for an existing row", row[2])
        assertNull(row[3])
    }

    @Test
    fun `vocabulary updatedAt is backfilled from createdAt and sentence starts empty`() {
        db.execSQL(
            """INSERT INTO dictionary_entries VALUES
               ('brook','abc','sl','gpt-5-mini','potok','vodotok','brook','sam.',0.0001,999)""",
        )

        migrate()

        val row =
            queryOne(
                "SELECT updatedAt, sentence, deletedAt FROM dictionary_entries WHERE word='brook'",
            )
        assertEquals("999", row[0])
        // The hash cannot be inverted, so the raw sentence is genuinely unknown for old rows.
        assertEquals("", row[1])
        assertNull(row[2])
    }

    @Test
    fun `the sync_state table is created empty`() {
        migrate()

        db.query("SELECT COUNT(*) FROM sync_state").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-4-5-test.db"
    }
}
