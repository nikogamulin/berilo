package app.berilo.reader.sync

import app.berilo.reader.annotations.FakeHighlightDao
import app.berilo.reader.dictionary.FakeDictionaryDao
import app.berilo.reader.store.db.BookEntity
import app.berilo.reader.store.db.HighlightColor
import app.berilo.reader.store.db.HighlightEntity
import app.berilo.reader.store.importer.FakeBookDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sync round itself, driven against a `MockWebServer` speaking the contract
 * (`docs/sync_api.md`). These cover the S3.2 Verify line's substance: the drain protocol,
 * last-write-wins, and that a rejected push loses nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var bookDao: FakeBookDao
    private lateinit var highlightDao: FakeHighlightDao
    private lateinit var dictionaryDao: FakeDictionaryDao
    private lateinit var syncStateDao: FakeSyncStateDao

    /** Every request the server saw, so tests can assert on what was actually sent. */
    private val pushBodies = mutableListOf<JsonObject>()
    private val pullBodies = mutableListOf<JsonObject>()

    private val json = Json { ignoreUnknownKeys = true }

    /** Programmed pull pages, keyed by entity; each call pops the next one. */
    private val pullPages = mutableMapOf<String, MutableList<String>>()

    /** Programmed push responses, keyed by entity; each call pops the next one. */
    private val pushResponses = mutableMapOf<String, MutableList<String>>()

    @Before
    fun setUp() {
        bookDao = FakeBookDao()
        highlightDao = FakeHighlightDao()
        dictionaryDao = FakeDictionaryDao()
        syncStateDao = FakeSyncStateDao()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return if (request.path?.endsWith("/sync/pull") == true) {
                        pullBodies += body
                        val entity =
                            body["entities"]!!.jsonArray.first().jsonPrimitive.content
                        val page =
                            pullPages[entity]?.removeFirstOrNull() ?: emptyPage(entity)
                        MockResponse().setResponseCode(200).setBody(page)
                    } else {
                        pushBodies += body
                        val entity = body["entities"]!!.jsonObject.keys.first()
                        val items = body["entities"]!!.jsonObject[entity]!!.jsonArray.size
                        val response =
                            pushResponses[entity]?.removeFirstOrNull() ?: allApplied(entity, items)
                        MockResponse().setResponseCode(200).setBody(response)
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun emptyPage(entity: String) =
        """{"entities":{"$entity":{"rows":[],"next_cursor":null,"has_more":false}}}"""

    private fun allApplied(entity: String, count: Int): String {
        val results = (0 until count).joinToString(",") { """{"key":{},"status":"applied"}""" }
        return """{"results":{"$entity":[$results]}}"""
    }

    private fun engine(): SyncEngine =
        SyncEngine(
            api =
                SyncApi(
                    baseUrl = server.url("/api/v1").toString(),
                    tokenProvider = { "test-jwt" },
                    ioDispatcher = UnconfinedTestDispatcher(),
                ),
            bookDao = bookDao,
            highlightDao = highlightDao,
            dictionaryDao = dictionaryDao,
            syncStateDao = syncStateDao,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private fun highlight(
        id: String = "11111111-1111-4111-8111-111111111111",
        updatedAt: Long = 2_000L,
        note: String? = null,
        deletedAt: Long? = null,
    ) =
        HighlightEntity(
            id = id,
            bookId = "hash-1",
            color = HighlightColor.AMBER,
            selectedText = "besedilo",
            note = note,
            locatorJson = """{"href":"/c1.xhtml"}""",
            chapterTitle = "I",
            createdAt = 1_000L,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
        )

    private suspend fun seedBook(updatedAt: Long = 1_000L) {
        bookDao.insert(
            BookEntity(
                id = "hash-1",
                title = "Naslov",
                authors = "Avtor",
                filePath = "/files/books/hash-1.epub",
                coverPath = null,
                addedAt = 1_000L,
                lastOpenedAt = null,
                progressionJson = null,
                updatedAt = updatedAt,
            ),
        )
    }

    @Test
    fun `every request carries the Clerk bearer token`() = runTest {
        engine().sync()
        val request = server.takeRequest()
        assertEquals("Bearer test-jwt", request.getHeader("Authorization"))
    }

    /**
     * §4's drain protocol: the cursor is durable only once a page reports `has_more: false`.
     * Persisting mid-drain would make "somewhere in the middle" indistinguishable from "fully
     * caught up" after a crash.
     */
    @Test
    fun `a multi-page pull persists only the final cursor`() = runTest {
        pullPages[SyncEntities.HIGHLIGHTS] =
            mutableListOf(
                """{"entities":{"highlights":{"rows":[],"next_cursor":"cursor-page-1","has_more":true}}}""",
                """{"entities":{"highlights":{"rows":[],"next_cursor":"cursor-final","has_more":false}}}""",
            )

        engine().sync()

        assertEquals(
            "only the cursor from the last page may be durable",
            "cursor-final",
            syncStateDao.get(SyncEntities.HIGHLIGHTS)?.cursor,
        )
        // The second request must have resumed from the first page's cursor, not restarted.
        val highlightPulls =
            pullBodies.filter {
                it["entities"]!!.jsonArray.first().jsonPrimitive.content == SyncEntities.HIGHLIGHTS
            }
        assertEquals(2, highlightPulls.size)
        assertEquals(
            "cursor-page-1",
            highlightPulls[1]["cursors"]!!.jsonObject[SyncEntities.HIGHLIGHTS]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a newer server highlight overwrites the local copy`() = runTest {
        seedBook()
        highlightDao.insert(highlight(updatedAt = 1_000L, note = "local"))
        pullPages[SyncEntities.HIGHLIGHTS] =
            mutableListOf(
                """{"entities":{"highlights":{"rows":[{
                    "id":"11111111-1111-4111-8111-111111111111","book_hash":"hash-1",
                    "color":"SKY","selected_text":"besedilo","note":"from the server",
                    "locator_json":{"href":"/c1.xhtml"},"chapter_title":"I",
                    "created_at":"1970-01-01T00:00:01Z",
                    "updated_at":"${SyncTime.toWire(9_000L)}"
                }],"next_cursor":"c","has_more":false}}}""",
            )

        engine().sync()

        val stored = highlightDao.getById("11111111-1111-4111-8111-111111111111")
        assertEquals("from the server", stored?.note)
        assertEquals(HighlightColor.SKY, stored?.color)
    }

    /** The other half of last-write-wins: an older server row must not clobber a newer edit. */
    @Test
    fun `an older server highlight does not overwrite a newer local edit`() = runTest {
        seedBook()
        highlightDao.insert(highlight(updatedAt = 9_000L, note = "newer local edit"))
        pullPages[SyncEntities.HIGHLIGHTS] =
            mutableListOf(
                """{"entities":{"highlights":{"rows":[{
                    "id":"11111111-1111-4111-8111-111111111111","book_hash":"hash-1",
                    "color":"SKY","selected_text":"besedilo","note":"stale",
                    "locator_json":{"href":"/c1.xhtml"},
                    "updated_at":"${SyncTime.toWire(1_000L)}"
                }],"next_cursor":"c","has_more":false}}}""",
            )

        engine().sync()

        assertEquals(
            "newer local edit",
            highlightDao.getById("11111111-1111-4111-8111-111111111111")?.note,
        )
    }

    /** A tombstone pulled from another device must remove the highlight here too. */
    @Test
    fun `a pulled tombstone hides the local highlight`() = runTest {
        seedBook()
        highlightDao.insert(highlight(updatedAt = 1_000L))
        pullPages[SyncEntities.HIGHLIGHTS] =
            mutableListOf(
                """{"entities":{"highlights":{"rows":[{
                    "id":"11111111-1111-4111-8111-111111111111","book_hash":"hash-1",
                    "color":"AMBER","selected_text":"besedilo",
                    "locator_json":{"href":"/c1.xhtml"},
                    "updated_at":"${SyncTime.toWire(9_000L)}",
                    "deleted_at":"${SyncTime.toWire(9_000L)}"
                }],"next_cursor":"c","has_more":false}}}""",
            )

        engine().sync()

        assertNull(highlightDao.getById("11111111-1111-4111-8111-111111111111"))
        assertNotNull(
            "the tombstone itself must remain",
            highlightDao.getAnyById("11111111-1111-4111-8111-111111111111"),
        )
    }

    @Test
    fun `dirty rows are pushed and the watermark advances`() = runTest {
        seedBook(updatedAt = 5_000L)
        highlightDao.insert(highlight(updatedAt = 6_000L))

        val report = engine().sync()

        assertTrue(report.isSuccess)
        assertEquals(5_000L, syncStateDao.get(SyncEntities.BOOKS_METADATA)?.lastPushedAt)
        assertEquals(6_000L, syncStateDao.get(SyncEntities.HIGHLIGHTS)?.lastPushedAt)
        assertEquals(2, report.pushed)
    }

    @Test
    fun `nothing is pushed twice once the watermark covers it`() = runTest {
        seedBook(updatedAt = 5_000L)
        engine().sync()
        val firstRoundPushes = pushBodies.size

        pushBodies.clear()
        engine().sync()

        assertTrue("the first round must have pushed something", firstRoundPushes > 0)
        assertTrue("a clean round must push nothing", pushBodies.isEmpty())
    }

    /**
     * The zero-data-loss guarantee. When the server rejects an item, the watermark must not
     * step over it — otherwise that edit is silently dropped and never retried.
     */
    @Test
    fun `a rejected push leaves the row dirty for the next round`() = runTest {
        seedBook(updatedAt = 5_000L)
        highlightDao.insert(highlight(id = "11111111-1111-4111-8111-111111111111", updatedAt = 6_000L))
        pushResponses[SyncEntities.HIGHLIGHTS] =
            mutableListOf(
                """{"results":{"highlights":[{"key":{},"status":"error","error":"missing_book"}]}}""",
            )

        val report = engine().sync()

        assertEquals(
            "the watermark must not advance past a rejected item",
            0L,
            syncStateDao.get(SyncEntities.HIGHLIGHTS)?.lastPushedAt ?: 0L,
        )
        assertTrue(
            "the row must still be queued",
            highlightDao.dirtySince(0L, 10).any { it.id == "11111111-1111-4111-8111-111111111111" },
        )
        // The book ahead of it in the push order was accepted, so exactly one item counted:
        // one entity failing must not roll back another entity's progress.
        assertEquals(1, report.pushed)
        assertEquals(5_000L, syncStateDao.get(SyncEntities.BOOKS_METADATA)?.lastPushedAt)
    }

    /** Losing last-write-wins means adopting the server's row, per §1.3. */
    @Test
    fun `a conflicting push adopts the server row`() = runTest {
        seedBook()
        highlightDao.insert(highlight(updatedAt = 6_000L, note = "local loses"))
        pushResponses[SyncEntities.HIGHLIGHTS] =
            mutableListOf(
                """{"results":{"highlights":[{"key":{},"status":"conflict","server_row":{
                    "id":"11111111-1111-4111-8111-111111111111","book_hash":"hash-1",
                    "color":"ROSE","selected_text":"besedilo","note":"server wins",
                    "locator_json":{"href":"/c1.xhtml"},
                    "updated_at":"${SyncTime.toWire(9_000L)}"
                }}]}}""",
            )

        val report = engine().sync()

        assertEquals(1, report.conflicts)
        val stored = highlightDao.getById("11111111-1111-4111-8111-111111111111")
        assertEquals("server wins", stored?.note)
        assertEquals(HighlightColor.ROSE, stored?.color)
    }

    /** Offline is a normal state on an e-reader, not a failure, and must not lose the queue. */
    @Test
    fun `going offline mid-round keeps every pending row queued`() = runTest {
        seedBook(updatedAt = 5_000L)
        server.shutdown()

        val report = engine().sync()

        assertEquals(SyncOutcome.Offline, report.outcome)
        assertEquals(0L, syncStateDao.get(SyncEntities.BOOKS_METADATA)?.lastPushedAt ?: 0L)
        assertTrue(bookDao.metadataDirtySince(0L, 10).isNotEmpty())
    }

    @Test
    fun `an expired session stops the round without touching watermarks`() = runTest {
        seedBook(updatedAt = 5_000L)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse()
                        .setResponseCode(401)
                        .setBody("""{"error":{"code":"unauthorized","message":"expired"}}""")
            }

        val report = engine().sync()

        assertEquals(SyncOutcome.SignedOut, report.outcome)
        assertEquals(0L, syncStateDao.get(SyncEntities.BOOKS_METADATA)?.lastPushedAt ?: 0L)
    }

    /**
     * Vocabulary rows that predate the `sentence` column can never be accepted (the server
     * requires it), so they are skipped and counted rather than retried forever.
     */
    @Test
    fun `vocabulary without a stored sentence is skipped and reported`() = runTest {
        dictionaryDao.upsert(
            app.berilo.reader.store.db.DictionaryEntryEntity(
                word = "brook",
                sentenceHash = "abc",
                lang = "sl",
                model = "gpt-5-mini",
                definition = "potok",
                contextMeaning = "vodotok",
                baseForm = "brook",
                usageNote = "samostalnik",
                costEur = 0.0,
                createdAt = 1_000L,
                sentence = "",
                updatedAt = 1_000L,
            ),
        )

        val report = engine().sync()

        assertEquals(1, report.skippedVocabulary)
        assertTrue(
            "a row that can never be accepted must not be pushed",
            pushBodies.none { it["entities"]!!.jsonObject.containsKey(SyncEntities.VOCABULARY) },
        )
    }

    /** The invariant, asserted on the actual traffic: no book bytes or paths ever go out. */
    @Test
    fun `no request body ever contains a book file path`() = runTest {
        seedBook(updatedAt = 5_000L)
        highlightDao.insert(highlight(updatedAt = 6_000L))

        engine().sync()

        pushBodies.forEach { body ->
            val text = body.toString()
            assertTrue(
                "a sync request must never carry a file path: $text",
                !text.contains("/files/books/") && !text.contains(".epub"),
            )
        }
    }
}
