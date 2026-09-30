package ai.passioncode.fabricvr.stt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `B-093`. **Five models share one directory and only the selected one could ever be deleted.**
 *
 * `FileModelStore` puts every model in the same folder so that switching between them keeps what
 * is already downloaded — which is right, and it is also why up to 1.4 GB can accumulate that
 * nothing lists and nothing can reclaim: `SettingsViewModel.removeModel()` resolves
 * `modelStore()`, which is *the currently selected model*, and deletes that one file. A person who
 * tried `large-turbo`, disliked the speed and went back to `small` has 574 MB they cannot see and
 * cannot remove without uninstalling the app.
 *
 * **The `.part` files are the half that is easy to miss and they are the bigger number.** A
 * cancelled transfer deletes its partial; a *crashed* one does not, and `ModelDownloader` keeps
 * the bytes deliberately on an ordinary failure so that pressing Download again costs only what is
 * missing (`H8`). Those live in the same directory under a name no `WhisperModel` mentions.
 */
class InstalledModelsTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File

    private fun models() = InstalledModels(root)

    /**
     * A file of exactly [bytes], **without allocating them**.
     *
     * `writeBytes(ByteArray(...))` is the obvious spelling and it asks the JVM for 574 MB of heap
     * for `large-turbo` — the first version of this class died on it. `setLength` makes a sparse
     * file: `length()` reports the full size, which is the only thing under test here, and the
     * filesystem stores nothing.
     */
    private fun put(model: WhisperModel, bytes: Long = model.bytes, partial: Boolean = false) {
        val name = model.fileName + if (partial) ".part" else ""
        java.io.RandomAccessFile(File(root, name), "rw").use { it.setLength(bytes) }
    }

    @org.junit.Before fun setUp() {
        root = temp.newFolder("models")
    }

    /** Nothing downloaded is an empty list and zero bytes, not a crash and not a null. */
    @Test fun `an empty store lists nothing`() {
        assertEquals(emptyList<InstalledModel>(), models().list())
        assertEquals(0L, models().totalBytes())
    }

    /** A directory that does not exist yet is the state before the first download. */
    @Test fun `a store that was never created lists nothing`() {
        val absent = InstalledModels(File(root, "never-made"))
        assertEquals(emptyList<InstalledModel>(), absent.list())
        assertEquals(0L, absent.totalBytes())
    }

    /**
     * **The headline: everything on disk, not only what is selected.** The order is the catalogue's
     * own, so a list rendered from it does not reshuffle when a file appears.
     */
    @Test fun `every downloaded model is listed with its size`() {
        put(WhisperModel.SMALL)
        put(WhisperModel.LARGE_TURBO)

        val listed = models().list()

        assertEquals(
            "the listing is not the catalogue's order, so a screen built on it reshuffles",
            listOf(WhisperModel.SMALL, WhisperModel.LARGE_TURBO),
            listed.map { it.model },
        )
        assertEquals(WhisperModel.SMALL.bytes, listed.first().bytes)
        assertTrue("a complete file was reported as partial", listed.all { it.complete })
        assertEquals(
            WhisperModel.SMALL.bytes + WhisperModel.LARGE_TURBO.bytes,
            models().totalBytes(),
        )
    }

    /**
     * A `.part` occupies exactly as much room as the bytes in it, and a person deciding what to
     * delete needs to see it. It is reported as **incomplete** rather than hidden, because the two
     * mean different things: one is a model, the other is a transfer somebody may want to finish.
     */
    @Test fun `an interrupted transfer is listed as incomplete`() {
        put(WhisperModel.MEDIUM, bytes = 4_096, partial = true)

        val listed = models().list()

        assertEquals(listOf(WhisperModel.MEDIUM), listed.map { it.model })
        assertEquals(4_096L, listed.single().bytes)
        assertFalse("a half-finished transfer was presented as a usable model", listed.single().complete)
        assertEquals(4_096L, models().totalBytes())
    }

    /**
     * A truncated file is **not** a model, and saying so here is what keeps this listing agreeing
     * with `FileModelStore.isPresent()` — which compares the length exactly, because a half-size
     * file passes a "roughly right" check and then fails inside whisper, where the error reads as
     * *couldn't transcribe* rather than *the model is broken*.
     */
    @Test fun `a truncated file is listed as incomplete`() {
        put(WhisperModel.TINY, bytes = 1_024)

        val listed = models().list()

        assertEquals(1_024L, listed.single().bytes)
        assertFalse("a truncated file was presented as a usable model", listed.single().complete)
    }

    /** Both halves of one model are one row and one number, not two. */
    @Test fun `a model with both a file and a leftover partial is counted once`() {
        put(WhisperModel.BASE, bytes = 2_048)
        put(WhisperModel.BASE, bytes = 512, partial = true)

        val listed = models().list()

        assertEquals(1, listed.size)
        assertEquals("the leftover .part was not counted", 2_560L, listed.single().bytes)
        assertEquals(2_560L, models().totalBytes())
    }

    /** **The other half of the row:** any model can be removed, not only the selected one. */
    @Test fun `removing a model that is not the selected one frees its bytes`() {
        put(WhisperModel.SMALL)
        put(WhisperModel.LARGE_TURBO)

        val freed = models().remove(WhisperModel.LARGE_TURBO)

        assertEquals(WhisperModel.LARGE_TURBO.bytes, freed)
        assertEquals(listOf(WhisperModel.SMALL), models().list().map { it.model })
        assertFalse(File(root, WhisperModel.LARGE_TURBO.fileName).exists())
        assertTrue("the selected model was removed too", File(root, WhisperModel.SMALL.fileName).isFile)
    }

    /** Removal takes the leftover transfer with it, or the room is not actually reclaimed. */
    @Test fun `removing a model also removes its leftover partial`() {
        put(WhisperModel.MEDIUM, bytes = 1_000)
        put(WhisperModel.MEDIUM, bytes = 2_000, partial = true)

        assertEquals(3_000L, models().remove(WhisperModel.MEDIUM))

        assertEquals(emptyList<InstalledModel>(), models().list())
        assertFalse(File(root, WhisperModel.MEDIUM.fileName + ".part").exists())
    }

    /** Removing what is not there is zero bytes freed, not a failure the screen has to render. */
    @Test fun `removing a model that is not there frees nothing`() {
        assertEquals(0L, models().remove(WhisperModel.TINY))
    }

    /**
     * *Free up space* in one press, without touching what the person is actually using.
     *
     * The exemption is a parameter rather than a read of the setting: this module does not know
     * which model is selected — that is a Keystore-backed setting `:app` owns — and a class that
     * guessed would delete the wrong one on the day the guess was wrong.
     */
    @Test fun `removeOthers keeps the model it is told to keep`() {
        put(WhisperModel.TINY)
        put(WhisperModel.SMALL)
        put(WhisperModel.MEDIUM, bytes = 64, partial = true)

        val freed = models().removeOthers(keep = WhisperModel.SMALL)

        assertEquals(WhisperModel.TINY.bytes + 64L, freed)
        assertEquals(listOf(WhisperModel.SMALL), models().list().map { it.model })
    }

    /** With nothing to keep, it clears the directory — the answer to *reclaim all of it*. */
    @Test fun `removeOthers with nothing to keep empties the store`() {
        put(WhisperModel.TINY)
        put(WhisperModel.BASE)

        assertEquals(WhisperModel.TINY.bytes + WhisperModel.BASE.bytes, models().removeOthers(keep = null))

        assertEquals(emptyList<InstalledModel>(), models().list())
    }

    /**
     * **A file this app did not put there is left alone, and is not counted either.**
     *
     * The directory is the app's own, so this is defence rather than a live case — but a sweep that
     * deletes by pattern in a directory whose contents it does not enumerate is how somebody's
     * unrelated file disappears, and `check-destructive.sh` exists in this repository because that
     * class of mistake has been made here before.
     */
    @Test fun `a file that is not a catalogued model is neither counted nor removed`() {
        File(root, "notes.txt").writeText("не трогай")
        put(WhisperModel.TINY)

        assertEquals(WhisperModel.TINY.bytes, models().totalBytes())
        models().removeOthers(keep = null)

        assertTrue("a file this app did not download was deleted", File(root, "notes.txt").isFile)
    }
}
