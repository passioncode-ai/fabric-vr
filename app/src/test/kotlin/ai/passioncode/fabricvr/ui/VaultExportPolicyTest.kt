package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.vault.FileVault
import ai.passioncode.fabricvr.vault.VaultExporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The publish-or-discard rule, on its own.
 *
 * `ContentResolver.insert`, `delete` and `openOutputStream` are final methods on a final class, so
 * a fake resolver cannot exist and a plain JVM test throws "not mocked" on the first line. The
 * seam is therefore above them — [ExportStore] — and this is the class that makes the rule that
 * matters testable: **a failed export must leave nothing behind**, because an archive that looks
 * complete and is not is the one a person relies on before an uninstall.
 */
class VaultExportPolicyTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeStore(private val stream: OutputStream = ByteArrayOutputStream()) : ExportStore {
        val events = mutableListOf<String>()
        var refuseCreate = false
        var refuseOpen = false
        var refusePublish = false
        /** The thread each store call ran on — `B-246` is about which one that was. */
        val threads = mutableListOf<String>()
        override fun beginPending(name: String): String? {
            threads += Thread.currentThread().name
            events += "begin:$name"
            return if (refuseCreate) null else "content://downloads/1"
        }
        override fun openStream(handle: String): OutputStream? {
            events += "open"
            return if (refuseOpen) null else stream
        }
        override fun publish(handle: String): String? {
            threads += Thread.currentThread().name
            events += "publish"
            return if (refusePublish) null else "/sdcard/Download/x.zip"
        }
        override fun discard(handle: String) { events += "discard" }
    }

    private fun vaultWithOneNote(): FileVault {
        val v = FileVault(temp.newFolder("vault"), io = Dispatchers.Unconfined)
        kotlinx.coroutines.runBlocking {
            v.write(NotesRepository.newNote("заметка", "тело", now = 1_767_225_600_000L)).getOrThrow()
        }
        return v
    }

    private fun export(
        store: FakeStore,
        vault: FileVault = vaultWithOneNote(),
        io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ) =
        VaultExport(
            VaultExporter(vault, Dispatchers.Unconfined),
            store,
            now = { LocalDateTime.of(2026, 9, 21, 3, 4) },
            io = io,
        )

    /**
     * **A publish that did not take is not a success** (`B-246`). `IS_PENDING = 0` was an `update`
     * whose result was thrown away, so a refused update left the zip hidden — and eligible for
     * MediaStore's own cleanup — while the screen showed its path as done. The person then
     * uninstalls believing a backup exists.
     */
    @Test fun `a publish that did not take is a failure, and leaves nothing pending`() = runTest {
        val store = FakeStore().apply { refusePublish = true }

        val result = export(store).run(includeAudio = false)

        assertTrue("a hidden archive was reported as exported: $result", result.isFailure)
        assertEquals(listOf("begin:fabric-vr-vault-2026-09-21-0304.zip", "open", "publish", "discard"), store.events)
    }

    /** `B-246`: `ContentResolver` work belongs off the thread that draws (`R2`, `DEC-0031`). */
    @Test fun `the store is driven on the io dispatcher it is given`() = runTest {
        val store = FakeStore()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "export-io") }
        try {
            export(store, io = pool.asCoroutineDispatcher()).run(includeAudio = false).getOrThrow()
        } finally {
            pool.shutdown()
        }
        assertTrue("the store ran on ${store.threads}", store.threads.isNotEmpty() && store.threads.all { it == "export-io" })
    }

    @Test fun `a successful export is published, not left pending`() = runTest {
        val store = FakeStore()

        val outcome = export(store).run(includeAudio = false).getOrThrow()

        assertEquals(listOf("begin:fabric-vr-vault-2026-09-21-0304.zip", "open", "publish"), store.events)
        assertEquals(1, outcome.summary.noteFiles)
        assertEquals("/sdcard/Download/x.zip", outcome.displayPath)
    }

    @Test fun `a failed export leaves no file behind`() = runTest {
        // The stream refuses on the first write, which is what a full disk does.
        val store = FakeStore(object : OutputStream() {
            override fun write(b: Int) = throw IOException("no space left on device")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("no space left on device")
        })

        val result = export(store).run(includeAudio = false)

        assertTrue("a failed export reported success", result.isFailure)
        assertTrue(
            "a half-written archive was left claiming to be a backup: ${store.events}",
            store.events.contains("discard"),
        )
        assertTrue("it was published anyway", !store.events.contains("publish"))
    }

    @Test fun `a store that cannot open a stream discards the pending row`() = runTest {
        val store = FakeStore().apply { refuseOpen = true }

        val result = export(store).run(includeAudio = false)

        assertTrue(result.isFailure)
        assertEquals(listOf("begin:fabric-vr-vault-2026-09-21-0304.zip", "open", "discard"), store.events)
    }

    @Test fun `a store that cannot create an entry fails without opening anything`() = runTest {
        val store = FakeStore().apply { refuseCreate = true }

        val result = export(store).run(includeAudio = false)

        assertTrue(result.isFailure)
        assertEquals(listOf("begin:fabric-vr-vault-2026-09-21-0304.zip"), store.events)
    }

    @Test fun `the archive is named for the moment it was taken`() = runTest {
        val store = FakeStore()

        export(store).run(includeAudio = false).getOrThrow()

        assertTrue(
            "a person with three exports cannot tell them apart: ${store.events.first()}",
            store.events.first().endsWith("fabric-vr-vault-2026-09-21-0304.zip"),
        )
    }

    /**
     * Found by an independent verification pass. `VaultExporter` closes the stream through its own
     * `ZipOutputStream.use`, and `VaultExport.run` wrapped the call in a second `use` — so the sink
     * was closed twice on the happy path, and a `close()` that throws escaped as an exception
     * instead of a `Result.failure`, with `discard` never reaching the store. A pending, invisible,
     * half-written row is exactly what "a failed export leaves nothing" promised could not happen.
     */
    @Test fun `a sink that throws on close still leaves nothing behind`() = runTest {
        var closes = 0
        val store = FakeStore(object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
            override fun close() { closes++; throw IOException("the card was pulled") }
        })

        val result = export(store).run(includeAudio = false)

        assertTrue("a throwing close was reported as a successful export", result.isFailure)
        assertTrue(
            "a half-written archive was left pending and invisible: ${store.events}",
            store.events.contains("discard"),
        )
        assertEquals("the sink was closed more than once", 1, closes)
    }

    private fun unused(): File = temp.root
}
