package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The outbox on its own: the hand-off, and the disk (`REQ-046`, audit `H1`).
 *
 * The view-model tier is where the *behaviour* is asserted (`DictationSurvivalTest`); this is the
 * two properties that tier cannot see — that a claim is atomic under real threads, and that a
 * transcript survives the process.
 */
class DictationOutboxTest {

    @get:Rule val temp = TemporaryFolder()

    private fun transcript(text: String) =
        Transcript(text, "ru", SttSource.LOCAL_FALLBACK, "whisper-small-q5_1", 4_200)

    @Test fun `an offered dictation is the one that comes back`() {
        val outbox = DictationOutbox()

        val entry = outbox.offer(transcript("не потеряй меня"), "/audio/1.wav")

        assertEquals(entry, outbox.pending.value)
        assertEquals("/audio/1.wav", entry.id)
        assertTrue("the recording is not recognised as owned", outbox.owns("/audio/1.wav"))
        assertFalse("an unrelated path was claimed as owned", outbox.owns("/audio/2.wav"))
    }

    /** A dictation whose recording could not be written still has an identity. */
    @Test fun `a dictation with no recording is keyed by its text`() {
        val outbox = DictationOutbox()

        val entry = outbox.offer(transcript("без файла"), null)

        assertEquals("text:без файла", entry.id)
        assertFalse("null was treated as an owned path", outbox.owns(null))
    }

    @Test fun `claiming takes it out, and a second claim gets nothing`() {
        val outbox = DictationOutbox()
        val entry = outbox.offer(transcript("однажды"), "/audio/1.wav")

        assertNotNull(outbox.claim(entry.id))
        assertNull("the entry was handed out twice", outbox.claim(entry.id))
        assertNull(outbox.pending.value)
    }

    @Test fun `claiming a different id changes nothing`() {
        val outbox = DictationOutbox()
        outbox.offer(transcript("моя"), "/audio/1.wav")

        assertNull(outbox.claim("/audio/other.wav"))
        assertNotNull("an unrelated claim emptied the outbox", outbox.pending.value)
    }

    /**
     * **Exactly once, under real threads.** Both hosts can be alive at the same moment and both
     * drain this flow; a read-then-write would hand the same dictation to two `NotesViewModel`s
     * and write the person's words as two notes. A test dispatcher cannot show this — it runs
     * one coroutine at a time — so this one uses a pool and a latch.
     */
    @Test fun `sixteen threads claiming at once produce exactly one winner`() {
        val outbox = DictationOutbox()
        val entry = outbox.offer(transcript("одна заметка"), "/audio/1.wav")
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val done = CountDownLatch(16)

        repeat(16) {
            pool.execute {
                go.await()
                if (outbox.claim(entry.id) != null) winners.incrementAndGet()
                done.countDown()
            }
        }
        go.countDown()
        assertTrue("the claimers did not finish", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals("the dictation was handed out ${winners.get()} times", 1, winners.get())
    }

    /**
     * A held entry belongs to the surface that made it — the editor, which appends rather than
     * making a note of its own — and is invisible to the drain of last resort until the hold
     * goes.
     */
    @Test fun `a held entry is not drainable until it is released`() {
        val outbox = DictationOutbox()
        outbox.offer(transcript("в редакторе"), "/audio/1.wav", holder = "editor")

        assertNull("a held entry was offered to the drain", outbox.drainable())

        outbox.release("editor")

        assertNotNull("releasing the hold did not free the entry", outbox.drainable())
        assertNotNull("the release lost the dictation", outbox.pending.value)
    }

    /** Only its own hold: a release from elsewhere must not free somebody else's dictation. */
    @Test fun `a release by another holder does nothing`() {
        val outbox = DictationOutbox()
        outbox.offer(transcript("чужая"), "/audio/1.wav", holder = "editor-a")

        outbox.release("editor-b")

        assertNull("another surface's release freed a held entry", outbox.drainable())
    }

    // ---- the disk half: a transcript outlives the process that produced it ----

    @Test fun `a dictation survives a new process`() {
        val file = File(temp.newFolder("outbox"), "dictation.tsv")
        DictationOutbox(file).offer(transcript("пережить\tперенос\nстроки"), "/audio/1.wav")

        val restored = DictationOutbox(file).restore()

        assertNotNull("nothing came back from disk", restored)
        assertEquals("пережить\tперенос\nстроки", restored!!.transcript.text)
        assertEquals("/audio/1.wav", restored.audioPath)
        assertEquals("ru", restored.transcript.language)
        assertEquals(SttSource.LOCAL_FALLBACK, restored.transcript.source)
        assertEquals("whisper-small-q5_1", restored.transcript.engine)
        assertEquals(4_200L, restored.transcript.durationMs)
        assertNull("a restored entry came back held by a surface that no longer exists", restored.holder)
    }

    /**
     * A **settled** dictation does not come back. It said *claimed* until `B-237`: the claim erased
     * the file before the note existed, and this test was the contract that kept it that way.
     */
    @Test fun `a settled dictation does not come back on the next launch`() {
        val file = File(temp.newFolder("outbox"), "dictation.tsv")
        val outbox = DictationOutbox(file)
        val entry = outbox.offer(transcript("уже записана"), "/audio/1.wav")

        outbox.claim(entry.id)
        assertTrue("the claim erased the file before the note existed (B-237)", file.exists())
        outbox.settle(entry)

        assertFalse("the file outlived the settle", file.exists())
        assertNull("a committed dictation came back as a second note", DictationOutbox(file).restore())
    }

    /**
     * A half-written or hand-edited file is discarded rather than surfaced. Leaving it would make
     * every launch retry the same unparseable content, and a transcript nobody can read is not
     * evidence of anything.
     */
    @Test fun `unreadable content is dropped rather than restored`() {
        val file = File(temp.newFolder("outbox"), "dictation.tsv")
        file.writeText("audio\t/audio/1.wav\n")

        assertNull(DictationOutbox(file).restore())
        assertFalse("the unreadable file was left to be retried for ever", file.exists())
    }

    @Test fun `an outbox with no file is simply in memory`() {
        val outbox = DictationOutbox()
        outbox.offer(transcript("без диска"), "/audio/1.wav")

        assertNotNull(outbox.pending.value)
        assertNull("a memory-only outbox restored something", DictationOutbox().restore())
    }

    /**
     * **The one file whose whole purpose is surviving a hard stop was never flushed.**
     *
     * `persist`'s own comment cites `Vault.write` — which writes a sibling, **`fsync`s it**, and
     * renames it over the target, and whose KDoc says in as many words that without the flush the
     * rename can reach the disk before the bytes it commits, *"the same loss with a longer fuse"*.
     * This class did the first and third steps and not the second, while being the one place in
     * the product that exists because the process dies: a headset taken off mid-dictation is the
     * situation, not an edge of it.
     *
     * **`fsync` cannot be observed from a JVM test**, so what is asserted is the ORDER — the same
     * argument `writeFileAtomically` makes for injecting its commit step. At the moment the
     * filesystem is asked to flush, the scratch file must already hold the whole transcript and
     * the target must not exist yet.
     */
    @Test fun `the transcript reaches the disk before the rename that commits it`() {
        val dir = temp.newFolder("outbox")
        val file = File(dir, "dictation.tsv")
        var scratchAtFlush: String? = null
        var targetExistedAtFlush = true
        val outbox = DictationOutbox(file, flush = { stream ->
            scratchAtFlush = File(dir, "dictation.tsv.tmp").readText()
            targetExistedAtFlush = file.exists()
            stream.fd.sync()
        })

        outbox.offer(transcript("не потеряй меня"), "/audio/1.wav")

        assertNotNull(
            "the outbox never asked the filesystem to flush: a hard stop loses the words it holds",
            scratchAtFlush,
        )
        assertTrue(
            "the flush came before the bytes it was supposed to commit",
            scratchAtFlush!!.contains("не потеряй меня"),
        )
        assertFalse(
            "the flush came after the rename, which is the same loss with a longer fuse",
            targetExistedAtFlush,
        )
    }

    /**
     * **`claim` erased the file after letting go of the entry, so an `offer` in that window had
     * its file deleted while memory kept it.**
     *
     * `claim` compare-and-sets `_pending` to null and then calls `erase()`, which deleted the file
     * outright — it took no argument, so it could not tell the entry it was ending from the one
     * that had arrived a microsecond later. Both surfaces are alive at once by construction, which
     * is why the claim is a CAS in the first place; the erase was the half that did not get the
     * same treatment. The result is a dictation that is in memory and not on disk, so the next
     * hard stop loses words the outbox had already been given.
     *
     * The window is a real interleaving between two threads and the seam is injected for the
     * reason the vault's commit step is: a timing test would pass on a machine that happened not
     * to schedule it.
     */
    @Test fun `a dictation offered while a claim is in flight keeps its file`() {
        val file = File(temp.newFolder("outbox"), "dictation.tsv")
        lateinit var outbox: DictationOutbox
        outbox = DictationOutbox(file, duringClaim = {
            outbox.offer(transcript("вторая"), "/audio/2.wav")
        })
        val first = outbox.offer(transcript("первая"), "/audio/1.wav")

        assertNotNull("the claim did not answer the entry it was given", outbox.claim(first.id))
        // The first becomes durable and is settled — the erase that used to sit inside the claim.
        outbox.settle(first)

        assertEquals(
            "the second dictation was lost from memory as well",
            "/audio/2.wav",
            outbox.pending.value?.id,
        )
        assertTrue("the claim erased a file that belonged to a newer dictation", file.isFile)
        assertEquals(
            "the dictation that arrived during the claim did not survive the process",
            "вторая",
            DictationOutbox(file).restore()?.transcript?.text,
        )
    }

    /**
     * **Nothing is published before it is durable** (`B-245`). `offer` set the in-memory entry and
     * then wrote the file, so a drain could claim, commit and settle the entry in between — the
     * settle found no file to erase, the write then put an already-written dictation on disk, and
     * the next launch wrote it a second time. At the moment the bytes are flushed, no surface may
     * yet be able to see the entry.
     */
    @Test fun `an offer is on disk before any surface can claim it`() {
        val file = File(temp.newFolder("outbox"), "dictation.tsv")
        var visibleAtFlush: PendingDictation? = null
        lateinit var outbox: DictationOutbox
        outbox = DictationOutbox(file, flush = { stream ->
            visibleAtFlush = outbox.pending.value
            stream.fd.sync()
        })

        outbox.offer(transcript("сначала на диск"), "/audio/1.wav")

        assertNull("the entry was claimable before it was durable: $visibleAtFlush", visibleAtFlush)
        assertNotNull(outbox.pending.value)
    }

    /**
     * **Every restored dictation's recording is spared, not only the first's** (seam verification
     * of `DEC-0095`). The launch sweep spared `restore()`'s return value, and a queued entry's
     * scratch recording older than a day was deleted before the drain reached it.
     */
    @Test fun `every restored dictation names its recording for the launch sweep`() {
        val file = File(temp.newFolder("outbox"), "dictation.tsv")
        val first = DictationOutbox(file)
        val a = first.offer(transcript("первая"), "/audio/a.wav")
        first.claim(a.id)
        first.offer(transcript("вторая"), "/audio/b.wav")

        val next = DictationOutbox(file)
        next.restore()

        assertEquals(setOf("/audio/a.wav", "/audio/b.wav"), next.waitingAudio())
    }
}
