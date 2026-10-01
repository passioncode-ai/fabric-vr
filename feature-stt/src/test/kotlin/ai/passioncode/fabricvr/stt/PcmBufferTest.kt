package ai.passioncode.fabricvr.stt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording used to be an `ArrayList<Short>`.
 *
 * On ART a `java.lang.Short` outside the −128…127 cache is a sixteen-byte object plus a four-byte
 * array slot — **about twenty bytes for a two-byte sample** — so a minute of dictation retained
 * ~19 MB and churned the same again in per-chunk lists, on a headset with no swap that is also
 * holding 190–574 MB of whisper weights (`E-02`). And `addAll` reallocated and copied an array of
 * up to 960 000 references **inside the lock the main thread waits on** at the end of every
 * recording (`I-10`) — the two findings are one defect seen from two sides.
 *
 * One caveat that keeps the arithmetic honest: near-silence samples DO fall in the cache and are
 * interned, so a quiet recording retained less. That the memory usage depended on the loudness of
 * the room is its own reason to be rid of it.
 */
class PcmBufferTest {

    private fun samples(n: Int, from: Int = 0) = ShortArray(n) { (from + it).toShort() }

    @Test fun `appending then draining returns exactly the samples appended`() {
        // Past the initial capacity on purpose: growth is where a hand-rolled array loses data.
        val buffer = PcmBuffer(maxSamples = 100_000)
        val first = samples(AudioRecorder.SAMPLE_RATE - 10)
        val second = samples(2_000, from = AudioRecorder.SAMPLE_RATE)

        assertTrue(buffer.append(first, first.size))
        assertTrue(buffer.append(second, second.size))

        assertArrayEquals("a growth boundary lost or reordered samples", first + second, buffer.drain())
    }

    /** Only [count] of the source array is the recording; the rest is last chunk's leftovers. */
    @Test fun `only the first count samples of a reused chunk are taken`() {
        val buffer = PcmBuffer(maxSamples = 1_000)
        val reused = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        buffer.append(reused, 3)

        assertArrayEquals(shortArrayOf(1, 2, 3), buffer.drain())
    }

    @Test fun `the buffer holds no more than its maximum`() {
        val buffer = PcmBuffer(maxSamples = 50)

        assertTrue(buffer.append(samples(40), 40))
        assertFalse("the cap did not refuse the chunk that crossed it", buffer.append(samples(40, from = 40), 40))

        assertEquals("the recording grew past its maximum", 50, buffer.drain().size)
        assertFalse("a full buffer must keep refusing", buffer.append(samples(1), 1))
    }

    /**
     * What is kept at the cap matters: it is the **beginning** of what the person said, and the
     * chunk that crosses the line is truncated rather than dropped. Discarding it would lose up to
     * a buffer's worth of speech at the exact moment the app tells them it is stopping.
     */
    @Test fun `the chunk that crosses the cap is truncated, not dropped`() {
        val buffer = PcmBuffer(maxSamples = 5)
        buffer.append(shortArrayOf(1, 2, 3), 3)

        buffer.append(shortArrayOf(4, 5, 6, 7), 4)

        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), buffer.drain())
    }

    @Test fun `drain does not clear, and clear resets the size`() {
        val buffer = PcmBuffer(maxSamples = 1_000)
        buffer.append(samples(10), 10)

        assertEquals("drain emptied the buffer; stopAndTranscribe drains before the job is joined", 10, buffer.drain().size)
        assertEquals(10, buffer.drain().size)

        buffer.clear()
        assertEquals(0, buffer.drain().size)
        assertEquals(0, buffer.sampleCount)
    }

    /**
     * `I-10`'s correctness half, and it must pass **before and after**: this task makes the lock
     * cheaper, it does not remove it. The KDoc it replaces records a real
     * `ConcurrentModificationException`, and deleting synchronisation to fix a *performance*
     * finding would be re-opening a correctness one.
     */
    @Test fun `concurrent appends and drains never lose or duplicate a sample`() {
        val producers = 8
        val perProducer = 2_000
        val buffer = PcmBuffer(maxSamples = producers * perProducer)
        val pool = Executors.newFixedThreadPool(producers + 1)
        val go = CountDownLatch(1)
        val done = CountDownLatch(producers)
        val drains = AtomicInteger(0)
        // **The overlap is arranged, not hoped for.** The drainer used to be one more task in the
        // pool, and nothing made it run before eight producers of 2 000 one-sample appends were
        // done. On a two-core hosted runner they were sometimes done first, and the last assertion
        // failed with "the drainer never ran" over a buffer that had lost nothing (PR #1's CI
        // run, 2026-10-01; reproduced every time by delaying the drainer 500 ms). Each producer
        // now stops halfway until the drainer has copied a non-empty buffer, so a drain is certain
        // to land between appends, whatever the scheduler does.
        val overlapped = CountDownLatch(1)

        repeat(producers) { p ->
            pool.execute {
                go.await()
                // One sample at a time, which is the worst case for the lock and the likeliest
                // shape to interleave badly.
                repeat(perProducer) { i ->
                    if (i == perProducer / 2) overlapped.await(30, TimeUnit.SECONDS)
                    buffer.append(shortArrayOf((p + 1).toShort()), 1)
                }
                done.countDown()
            }
        }
        pool.execute {
            go.await()
            while (done.count > 0L) {
                val copy = buffer.drain()
                drains.incrementAndGet()
                if (copy.isNotEmpty()) overlapped.countDown()
            }
        }
        go.countDown()
        check(done.await(60, TimeUnit.SECONDS)) { "the producers did not finish — a deadlock, not a failed assertion" }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        val drained = buffer.drain()
        assertEquals("samples were lost or duplicated under contention", producers * perProducer, drained.size)
        val counts = drained.toList().groupingBy { it }.eachCount()
        repeat(producers) { p ->
            assertEquals("producer ${p + 1} lost samples", perProducer, counts[(p + 1).toShort()])
        }
        assertTrue("the drainer never ran, so nothing was concurrent", drains.get() > 0)
        assertEquals("no drain landed while the producers were mid-way, so nothing was concurrent", 0L, overlapped.count)
    }

    /**
     * The crude one, and the only assertion here that fails if somebody reintroduces boxing:
     * a `ShortArray` of n samples is 2n bytes and doubling growth keeps it under 2× the content.
     * An `ArrayList<Short>` of the same recording is about ten times the raw audio.
     */
    @Test fun `the retained size is proportional to the samples, not ten times them`() {
        val minute = AudioRecorder.SAMPLE_RATE * 60
        val buffer = PcmBuffer(maxSamples = minute * 2)
        val chunk = samples(1_600)

        repeat(minute / chunk.size) { buffer.append(chunk, chunk.size) }

        assertEquals(minute, buffer.sampleCount)
        assertTrue(
            "the backing array is ${buffer.capacity} shorts for $minute samples — more than doubling growth explains",
            buffer.capacity <= minute * 2,
        )
    }
}
