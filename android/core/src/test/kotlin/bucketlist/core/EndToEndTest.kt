package bucketlist.core

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SETUP_TOKEN = "e2e-setup-token"
private const val PASSPHRASE = "correct horse battery"
private const val SERVER_START_POLLS = 100
private const val SERVER_START_POLL_MS = 50L
private val fastParams = KdfParams(ByteArray(16) { 3 }, 1, 64, 1)

class EndToEndTest {
    private var process: Process? = null
    private val tempDirs = mutableListOf<File>()
    private lateinit var dataDir: File

    @AfterTest
    fun cleanUp() {
        process?.destroy()
        process?.waitFor()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun serverBinary(): File {
        val path = System.getenv("BUCKET_SERVER_BIN") ?: error("BUCKET_SERVER_BIN is not set")
        val binary = File(path)
        check(binary.canExecute()) { "build the server first: cd server && go build -o bucketlist-server . (looked for $path)" }
        return binary
    }

    private fun startServer(): String {
        val port = ServerSocket(0).use { it.localPort }
        dataDir = Files.createTempDirectory("bucket-e2e").toFile().also { tempDirs += it }
        val builder = ProcessBuilder(serverBinary().path)
        builder.environment().putAll(mapOf("BUCKET_ADDR" to "127.0.0.1:$port", "BUCKET_DATA" to dataDir.path, "BUCKET_SETUP_TOKEN" to SETUP_TOKEN))
        builder.redirectErrorStream(true).redirectOutput(File(dataDir.parentFile, "${dataDir.name}.log").also { tempDirs += it })
        process = builder.start()
        repeat(SERVER_START_POLLS) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return "http://127.0.0.1:$port"
            Thread.sleep(SERVER_START_POLL_MS)
        }
        error("server did not start")
    }

    private fun createList(url: String): BucketSession =
        BucketSession.createAccount(BucketApi(url), SETUP_TOKEN, PASSPHRASE.toCharArray(), fastParams).session

    private fun secondPhone(url: String): BucketSession = BucketSession.unlock(BucketApi(url), PASSPHRASE.toCharArray()).session

    @Test
    fun bothPhonesShareOneList() {
        val url = startServer()
        val mine = createList(url)
        val surf = mine.add("Learn to surf", "Cornwall, summer")
        mine.add("See the northern lights", "")

        val theirs = secondPhone(url)
        val seen = theirs.load()
        assertEquals(setOf("Learn to surf", "See the northern lights"), seen.items.map { it.title }.toSet())
        assertEquals(0, seen.unreadableCount)

        val ticked = theirs.setDone(seen.items.first { it.id == surf.id }, true, nowMillis = 1_900_000_000_000)
        assertTrue(ticked.isDone)

        val back = mine.load()
        assertEquals(listOf("Learn to surf"), back.done.map { it.title })
        assertEquals(1_900_000_000_000, back.done.single().doneAtMillis)
        assertEquals(listOf("See the northern lights"), back.toDo.map { it.title })
    }

    @Test
    fun editingKeepsTheItemAndItsDates() {
        val url = startServer()
        val session = createList(url)
        val item = session.add("Paris", "", nowMillis = 1_000)
        val done = session.setDone(item, true, nowMillis = 2_000)
        session.edit(done, "  Paris in spring ", " Eiffel tower at night ")
        val reloaded = session.load().items.single()
        assertEquals(item.id, reloaded.id)
        assertEquals("Paris in spring", reloaded.title)
        assertEquals("Eiffel tower at night", reloaded.notes)
        assertEquals(1_000, reloaded.createdAtMillis)
        assertEquals(2_000, reloaded.doneAtMillis)
        val undone = session.setDone(reloaded, false)
        assertFalse(undone.isDone)
        assertFalse(session.load().items.single().isDone)
    }

    @Test
    fun deletedItemsAreGoneForEveryone() {
        val url = startServer()
        val session = createList(url)
        val keep = session.add("Keep me", "")
        val drop = session.add("Delete me", "")
        session.delete(drop)
        assertEquals(listOf(keep.id), secondPhone(url).load().items.map { it.id })
    }

    @Test
    fun theWrongPasskeyIsRejected() {
        val url = startServer()
        createList(url)
        val error = assertFailsWith<ApiException> { BucketSession.unlock(BucketApi(url), "wrong passkey here".toCharArray()) }
        assertEquals(401, error.status)
    }

    @Test
    fun theSetupTokenOnlyWorksOnce() {
        val url = startServer()
        createList(url)
        val error = assertFailsWith<ApiException> {
            BucketSession.createAccount(BucketApi(url), SETUP_TOKEN, "another passkey".toCharArray(), fastParams)
        }
        assertTrue(error.status == 403 || error.status == 409, "status was ${error.status}")
        assertEquals(0, secondPhone(url).load().items.size)
    }

    @Test
    fun aWrongSetupTokenCannotCreateTheList() {
        val url = startServer()
        val error = assertFailsWith<ApiException> {
            BucketSession.createAccount(BucketApi(url), "not-the-token", PASSPHRASE.toCharArray(), fastParams)
        }
        assertEquals(403, error.status)
    }

    @Test
    fun theServerOnlyEverSeesCiphertext() {
        val url = startServer()
        val session = createList(url)
        session.add("Climb Kilimanjaro", "with Sam in 2028")
        val stored = dataDir.walkTopDown().filter { it.isFile }.map { it.readBytes().toString(Charsets.ISO_8859_1) }.toList()
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.none { "Kilimanjaro" in it || "Sam in 2028" in it })
    }

    @Test
    fun aLockedSessionRefusesToWork() {
        val url = startServer()
        val session = createList(url)
        session.lock()
        assertFailsWith<IllegalStateException> { session.add("Too late", "") }
    }

    @Test
    fun fingerprintStyleUnlockWithTheExportedKeySeesTheSameList() {
        val url = startServer()
        val first = BucketSession.createAccount(BucketApi(url), SETUP_TOKEN, PASSPHRASE.toCharArray(), fastParams)
        first.session.add("Road trip", "")
        val exported = first.session.exportListKey()
        val api = BucketApi(url)
        api.login(first.authKey)
        val restored = BucketSession.withListKey(api, exported)
        assertEquals(listOf("Road trip"), restored.load().items.map { it.title })
    }
}
