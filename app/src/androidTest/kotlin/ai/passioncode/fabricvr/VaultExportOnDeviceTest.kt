package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.ui.VaultExport
import ai.passioncode.fabricvr.vault.FileVault
import ai.passioncode.fabricvr.vault.VaultExporter
import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of the export no JVM test can reach: whether Horizon OS's `MediaStore` accepts a
 * Downloads row from this app at all.
 *
 * Everything above it — the zip, the walk, publish-or-discard — is covered off-device. This asserts
 * the one platform fact the whole decision rests on: that `/sdcard/Download` is writable by an app
 * with **no permission** on `minSdk 34`, and that the entry becomes visible only when it is
 * complete. It matters because `adb uninstall` — which a release signature change forces — deletes
 * everything else the person has.
 */
@RunWith(AndroidJUnit4::class)
class VaultExportOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val written = mutableListOf<Long>()

    @After fun tearDown() {
        written.forEach { id ->
            runCatching {
                context.contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                    null, null,
                )
            }
        }
    }

    @Test
    fun theVaultReachesSharedStorageAndTheArchiveOpens() {
        runBlocking {
        val root = File(context.cacheDir, "export-probe-${System.nanoTime()}").apply { mkdirs() }
        val vault = FileVault(root)
        val note = NotesRepository.newNote("на шлеме", "тело заметки", now = 1_767_225_600_000L)
        vault.write(note).getOrThrow()

        val outcome = VaultExport.create(context, VaultExporter(vault))
            .run(includeAudio = false)
            .getOrThrow()

        assertEquals("the archive did not carry the one note in the vault", 1, outcome.summary.noteFiles)
        assertTrue("the path reported is not in shared storage: ${outcome.displayPath}",
            outcome.displayPath.startsWith("/sdcard/Download"))

        // Read it back through the resolver, which is how anything else on the device would.
        val uri = android.net.Uri.parse(outcome.handle)
        ContentUris.parseId(uri).let(written::add)
        val names = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) { names += e.name; zip.closeEntry(); e = zip.nextEntry }
            }
        }
        assertTrue("the published archive does not contain the note: $names",
            names.any { it.endsWith("${note.id}.md") })

        // Visible, i.e. not left pending — a half-written archive must never look like a backup.
        val pending = context.contentResolver
            .query(uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("the archive was published but is still marked pending", 0, pending)

        root.deleteRecursively()
        }
    }
}
