package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.FabricHttp
import ai.passioncode.fabricvr.common.InsecureHopException
import ai.passioncode.fabricvr.common.Log2
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import ai.passioncode.fabricvr.common.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.Call
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadProgress {
    data class Running(val bytes: Long, val total: Long) : DownloadProgress
    data class Done(val file: File, val sha256: String) : DownloadProgress

    /**
     * @param resumable whether usable bytes survived on disk, so pressing *Download* again costs
     *   only what is still missing rather than the whole 190–574 MB again (`H8`).
     *
     *   It is a property of the **transfer**, not of the error, which is why it lives here and not
     *   in `AppError.ModelDownload`: the same `Reason.NETWORK` is resumable after a dropped
     *   connection and not resumable after a refused redirect, because in the second case the
     *   bytes on disk came from somewhere this app will not trust. Computed from the file rather
     *   than asserted by whoever emitted it.
     */
    data class Failed(val error: AppError, val resumable: Boolean = false) : DownloadProgress
}

/**
 * Fetches the speech model once, with progress, and verifies it. A partial or corrupt file is
 * deleted rather than left behind pretending to be a model.
 */
/**
 * Fetches the speech model once, with progress, and verifies it. A partial or corrupt file is
 * deleted rather than left behind pretending to be a model.
 *
 * Its client is its own: the default OkHttp read timeout is ten seconds, which is a reasonable
 * number for an API call and a wrong one for 190 MB over headset Wi-Fi, where a brief stall would
 * throw the whole download away.
 */
class ModelDownloader(
    private val store: ModelStore,
    private val client: OkHttpClient = SHARED,
    private val url: String = store.downloadUrl,
    /**
     * How much room the device will actually give us, in bytes.
     *
     * A parameter because it is the only way the refusal can be tested, and because the two
     * production answers differ: `StorageManager.getAllocatableBytes` counts space Android could
     * reclaim from other apps' caches — the number it will really hand over — while `StatFs` is
     * the pessimistic one and would sometimes refuse a download that would have succeeded. A
     * false refusal is the worse failure here, because the person has no way to disprove it.
     * `Graph` supplies the real probe; the default is the local filesystem's own answer.
     */
    private val freeBytes: () -> Long = { store.modelFile().parentFile?.usableSpace ?: Long.MAX_VALUE },
    /**
     * Where the bytes go. Only a test passes this: `ENOSPC` cannot be provoked from a unit test
     * any other way, and a classifier nobody has watched classify is a claim.
     */
    private val openSink: (File, Boolean) -> java.io.OutputStream = { file, append ->
        java.io.FileOutputStream(file, append)
    },
    /**
     * How many times one `download()` will try before it gives up — the transfer's own budget,
     * not a per-press one (`H8`).
     *
     * A parameter for two reasons and both are tests: a test that is about the `ENOSPC`
     * classifier and not about the retry sets it to 1, so it needs one enqueued response and says
     * what it means; and the retry's own test sets it to the production value explicitly rather
     * than inheriting it.
     */
    private val maxAttempts: Int = MAX_ATTEMPTS,
    /**
     * The wait between attempts. Injectable because the production numbers are seconds and a
     * test that really waited seven of them would be deleted by the first person in a hurry —
     * and because the *sequence* of waits is the thing worth asserting.
     */
    private val retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    /**
     * A redirect may move the download to a CDN of the same vendor — `resolve/main` always does —
     * but not to a different one, which is the cheapest way to swap a 190 MB payload. The allowed
     * suffix is derived from the configured URL rather than hard-coded, so pointing the downloader
     * at a mirror (or a test server) is a decision the caller makes, not one this class forbids.
     */
    /**
     * The download host plus whatever the store names as the vendor's own. Compared on the
     * registrable suffix, so one entry covers a CDN's subdomains.
     */
    private val allowedHostSuffixes: Set<String> =
        (store.allowedRedirectHosts + url.toHttpUrlOrNull()?.host.orEmpty())
            .filter { it.isNotEmpty() }
            .map(::registrableSuffix)
            .toSet()

    /** Resumes where a previous attempt stopped, rather than starting the 190 MB again. */
    private fun request(partial: File): Request {
        val builder = Request.Builder().url(url)
        val have = if (partial.exists()) partial.length() else 0L
        if (have > 0) builder.header("Range", "bytes=$have-")
        return builder.build()
    }
    /**
     * How one attempt ended. Only the loop below turns any of these into a terminal event, so
     * there is exactly one place where a `Failed` can leave this class.
     */
    private sealed interface Attempt {
        data class Done(val sha: String) : Attempt

        /** Nothing another attempt would change: the disk is full, the digest is wrong, the host is not the vendor's. */
        data class Fatal(val error: AppError.ModelDownload) : Attempt

        /** The connection went away. The bytes on disk are still a valid prefix. */
        data class Retry(val cause: Throwable) : Attempt

        /**
         * `416 Range Not Satisfiable` — the server says the bytes on disk are **not** a prefix of
         * what it is serving. Resuming onto them would hand back a file that fails the digest
         * after a full download, so they go and the transfer starts again from zero.
         */
        data object Restart : Attempt
    }

    /**
     * The transfer: one or more attempts at the same `.part` file, to a finished model or to a
     * failure that is honest about what survived.
     *
     * **The retry lives here rather than in [ModelDownloads]**, which is the other candidate
     * because it owns the job. Everything a retry must not lose is in this class: the `.part`
     * file, the byte offset the next `Range` header is computed from, and the digest replayed
     * over what is already on disk. A retry above would have to re-create a downloader, and —
     * worse — would have to publish a `Failed` to the shared `StateFlow` and then take it back,
     * because `Failed` is a terminal value that every screen renders. Here, a transfer that
     * recovers is one the person never sees fail. [ModelDownloads] keeps the invariant it exists
     * for, one writer per model, and the retries happen inside that one writer's job.
     *
     * **A non-2xx answer is not retried.** A 503 is a server that answered; a dropped socket is
     * the Wi-Fi, and only the second is what `H8` is about. Retrying an answer would also mean
     * guessing which status codes are transient, which is a table nobody here can justify.
     */
    fun download(): Flow<DownloadProgress> = flow {
        val target = store.modelFile()
        val partial = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()

        var attempt = 1
        var restarted = false
        while (true) {
            when (val outcome = cancellingCallOnCancel { live -> attempt(target, partial, live) }) {
                is Attempt.Done -> {
                    emit(DownloadProgress.Done(target, outcome.sha))
                    return@flow
                }
                is Attempt.Fatal -> {
                    emit(DownloadProgress.Failed(outcome.error, resumable = partial.hasBytes()))
                    return@flow
                }
                Attempt.Restart -> {
                    partial.delete()
                    if (restarted) {
                        // A second 416 after the range was dropped is a server that is simply
                        // broken; asking it a third time is a loop, not a retry.
                        Log2.w("stt.model.range_refused_twice")
                        emit(DownloadProgress.Failed(AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK)))
                        return@flow
                    }
                    restarted = true
                }
                is Attempt.Retry -> {
                    if (attempt >= maxAttempts) {
                        Log2.e("stt.model.download_failed", outcome.cause, "attempts" to attempt)
                        emit(
                            DownloadProgress.Failed(
                                AppError.ModelDownload(classify(outcome.cause), outcome.cause),
                                resumable = partial.hasBytes(),
                            ),
                        )
                        return@flow
                    }
                    val wait = BACKOFF_MS shl (attempt - 1)
                    Log2.w("stt.model.retry", "attempt" to attempt, "wait_ms" to wait)
                    retryDelay(wait)
                    attempt++
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Runs one attempt with its HTTP call cancelled **the moment this coroutine is** (`B-250`).
     *
     * The attempt reads the body with blocking calls on an IO thread, and a coroutine parked in a
     * blocking `read()` cannot observe its own cancellation until the read returns — on a stalled
     * connection, `SHARED`'s 90 s read timeout. `ModelDownloads.cancel` joins the transfer (`M15`),
     * so *Cancel* sat for a minute and a half. A child coroutine is cancelled immediately when its
     * parent is, whatever the parent's thread is doing; its `finally` cancels the call, which closes
     * the socket and unparks the read. `invokeOnCompletion` could not do this — it fires on
     * completion, and a job parked in a read has not completed (`DEC-0060` found the same trap).
     * On a normal finish the watcher is cancelled too and cancels a call that is already done,
     * which is a no-op.
     */
    private suspend fun <T> cancellingCallOnCancel(block: suspend (AtomicReference<Call?>) -> T): T =
        coroutineScope {
            val live = AtomicReference<Call?>(null)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { live.get()?.cancel() }
            }
            try { block(live) } finally { watcher.cancel() }
        }

    /** Usable bytes to resume on to. An empty `.part` is not a head start, it is a file. */
    private fun File.hasBytes(): Boolean = exists() && length() > 0

    /**
     * One attempt, emitting only [DownloadProgress.Running]. Every terminal event is the loop's.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadProgress>.attempt(
        target: File,
        partial: File,
        live: AtomicReference<Call?>,
    ): Attempt {
        // **Before the first byte is requested**, which is the whole point. The complaint this
        // closes is 190 MB of headset Wi-Fi spent to discover there was never room for it
        // (`G-07`). Only what is still missing is required: a download that is 95% finished must
        // not be refused on a headset with 6% free, and `A-22` is that the resume path had never
        // run at all.
        val alreadyOnDisk = if (partial.exists()) partial.length() else 0L
        val need = ((store.expectedBytes - alreadyOnDisk).coerceAtLeast(0L) * SPACE_MARGIN_NUM) / SPACE_MARGIN_DEN
        val free = runCatching { freeBytes() }.getOrDefault(Long.MAX_VALUE)
        if (store.expectedBytes > 0 && free < need) {
            Log2.w("stt.model.no_space", "need" to need, "free" to free)
            return Attempt.Fatal(
                AppError.ModelDownload(AppError.ModelDownload.Reason.DISK, needBytes = need, freeBytes = free),
            )
        }

        val call = client.newCall(request(partial)).also(live::set)
        val response = runCatchingCancellable { call.execute() }
            .getOrElse { failure ->
                // **A cancel that lands before the headers is the cancel** (seam verification of
                // `B-250`): the watcher cancelled the call, `execute()` threw the socket's
                // `IOException`, and this used to become a retry that kept the partial.
                if (!currentCoroutineContext().isActive) {
                    partial.delete()
                    throw CancellationException("download cancelled").initCause(failure)
                }
                // **A refused hop is not a hiccup, so it is not retried** (`B-187`). Three more
                // attempts would follow the same redirect to the same host and be refused three
                // more times, seven seconds apart. The bytes go for the same reason a refused
                // vendor redirect deletes them below: what is on disk came from a host this app
                // will not trust, and must not become the next attempt's head start.
                if (failure is InsecureHopException) {
                    partial.delete()
                    return Attempt.Fatal(AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK))
                }
                return Attempt.Retry(failure)
            }

        return response.use { r ->
            // `resolve/main` redirects to a CDN; following it is fine, following it off the vendor
            // entirely is not — a redirect is the cheapest way to swap a 190 MB payload.
            val finalHost = r.request.url.host
            if (allowedHostSuffixes.none { finalHost == it || finalHost.endsWith(".$it") }) {
                Log2.w("stt.model.redirect_refused", "host" to finalHost, "allowed" to allowedHostSuffixes)
                // Deleted, and therefore never resumable: bytes from a host this app refuses to
                // trust must not become the head start of the next attempt.
                partial.delete()
                return Attempt.Fatal(AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK))
            }
            // `416 Range Not Satisfiable`. The `.part` on disk is not a prefix of what this
            // server is serving — the model was re-published, or the file is left over from
            // another one. Keeping those bytes would cost the whole download and then fail the
            // digest, so the loop drops them and asks again without a range.
            if (r.code == 416) {
                Log2.w("stt.model.range_refused", "have" to partial.length())
                return Attempt.Restart
            }
            val body = r.body
            if (!r.isSuccessful || body == null) {
                return Attempt.Fatal(AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK))
            }
            // 206 means the server honoured the range and we keep what is on disk; 200 means it did
            // not, and resuming onto old bytes would corrupt the file.
            val resuming = r.code == 206 && partial.exists()
            if (!resuming) {
                // The server refused the range, so the WHOLE model is coming — but the check
                // above only asked for the bytes that were missing. On a nearly-full headset
                // with a nearly-complete partial that is a mid-transfer failure where an up-front
                // refusal was owed.
                if (alreadyOnDisk > 0 && store.expectedBytes > 0) {
                    val whole = (store.expectedBytes * SPACE_MARGIN_NUM) / SPACE_MARGIN_DEN
                    val roomNow = runCatching { freeBytes() }.getOrDefault(Long.MAX_VALUE) + alreadyOnDisk
                    if (roomNow < whole) {
                        Log2.w("stt.model.no_space", "need" to whole, "free" to roomNow)
                        partial.delete()
                        return Attempt.Fatal(
                            AppError.ModelDownload(
                                AppError.ModelDownload.Reason.DISK,
                                needBytes = whole,
                                freeBytes = roomNow,
                            ),
                        )
                    }
                }
                partial.delete()
            }
            val already = if (resuming) partial.length() else 0L
            val total = body.contentLength().takeIf { it > 0 }?.plus(already) ?: store.expectedBytes
            val digest = MessageDigest.getInstance("SHA-256")
            if (resuming) {
                partial.inputStream().use { existing ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = existing.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
            var written = already
            var lastReported = already
            val interrupted: Attempt? = try {
                body.byteStream().use { input ->
                    openSink(partial, resuming).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            // One emission per 64 KB chunk is ~3000 updates for this model, and a
                            // progress bar redrawn that often is jank rather than information.
                            if (written - lastReported >= PROGRESS_STEP_BYTES || written == total) {
                                lastReported = written
                                emit(DownloadProgress.Running(written, total))
                            }
                        }
                    }
                }
                null
            } catch (c: CancellationException) {
                // The person pressed Cancel. Deleting the partial file is right — they asked for
                // this transfer to stop existing — and calling it a network error is not. This is
                // the ONE throwable that still deletes, and the line below is why (`H8`).
                partial.delete()
                throw c
            } catch (t: Throwable) {
                // **A read the cancel itself broke is the cancel** (`B-250`). Since the watcher
                // cancels the call, a Cancel pressed during a blocking read arrives here as the
                // socket's `IOException` rather than as a `CancellationException` — and must get
                // the Cancel arm's answer, not a retry of a transfer the person stopped.
                if (!currentCoroutineContext().isActive) {
                    partial.delete()
                    throw CancellationException("download cancelled").initCause(t)
                }
                // **The bytes stay.** This used to be `partial.delete()` for every throwable
                // alike, so a Wi-Fi hiccup at 560 of 574 MB cost all 574 again, and the resume
                // path this class carries — `Range`, the 206 check, the digest replay — could
                // only engage after a process death (`H8`, `A-22`). The loop above decides
                // whether to try again; what it must not lose is on disk by the time it does.
                Log2.w("stt.model.attempt_failed", "written" to written, "cause" to t.javaClass.simpleName)
                // Except a full disk, which is not a hiccup: three more attempts are seven
                // seconds of waiting for the same answer, and the bytes already written are
                // exactly what the person would free space in order to keep. `G-06` is why this
                // is a separate reason at all.
                if (classify(t) == AppError.ModelDownload.Reason.DISK) {
                    Attempt.Fatal(AppError.ModelDownload(AppError.ModelDownload.Reason.DISK, t))
                } else {
                    Attempt.Retry(t)
                }
            }
            if (interrupted != null) return interrupted

            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (store.expectedSha256.isNotBlank() && !sha.equals(store.expectedSha256, ignoreCase = true)) {
                // Not resumable, and deleted for that reason: these bytes are wrong, and resuming
                // on to them would fail the same way after every future download.
                partial.delete()
                return Attempt.Fatal(AppError.ModelDownload(AppError.ModelDownload.Reason.CHECKSUM))
            }
            if (store.expectedBytes > 0 && written != store.expectedBytes) {
                partial.delete()
                return Attempt.Fatal(AppError.ModelDownload(AppError.ModelDownload.Reason.CHECKSUM))
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return Attempt.Fatal(AppError.ModelDownload(AppError.ModelDownload.Reason.DISK))
            }
            Attempt.Done(sha)
        }
    }

    /**
     * Out of space, or not.
     *
     * Everything that is not out-of-space stays `NETWORK`, deliberately: a classifier that
     * guessed at five network sub-cases would be inventing distinctions the person cannot act on.
     * And `FileNotFoundException` is **not** folded into `DISK` — on an existing directory it is
     * usually a permissions problem, and telling that person to free up room is the same defect
     * in the other direction. `G-06`'s own proposal did both of those things.
     */
    internal companion object {
        /**
         * One client for every download, not one per construction.
         *
         * It was a **default argument** (`I-28`), so every `ModelDownloader(...)` allocated an
         * OkHttp dispatcher, its thread pool and a connection pool, and discarded them with the
         * object — and `Graph` builds one of these per settings read.
         *
         * The timeouts are unchanged and so is their reason: the default read timeout is ten
         * seconds, right for an API call and wrong for 190 MB over headset Wi-Fi, where one brief
         * stall would throw the whole download away.
         *
         * Built through [FabricHttp] like the two remote clients (`B-187`). It carries no
         * credential, so the hop rule buys less here than it does there — but the vendor
         * allow-list below is checked **after** the redirects have been followed, and a policy
         * that is enforced on two of three clients is a policy somebody will read as optional.
         */
        val SHARED: OkHttpClient by lazy {
            FabricHttp.builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        }
        const val PROGRESS_STEP_BYTES = 1L shl 20

        /**
         * Three tries at one transfer, which is two waits: one second, then two.
         *
         * The waits double (`BACKOFF_MS shl (attempt - 1)`), so raising this to four would add
         * the four-second wait the plan names without changing anything else. Three is where a
         * headset's Wi-Fi hiccup stops looking like a hiccup — beyond it the person is better
         * served by being told, with the bytes kept, than by a fourth silent attempt.
         */
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 1_000L

        /**
         * Ten per cent over the bytes still missing. Integer arithmetic rather than a `Double`,
         * because 574 041 195 × 1.1 in floating point is a number nobody can reproduce by hand
         * from a log line.
         *
         * The margin exists because the filesystem needs metadata, and because a headset within
         * ten per cent of full will fail the next thing it does anyway. `renameTo` needs nothing
         * extra — source and target share a directory — so the requirement is the model's own
         * size and no more.
         */
        /** Deep enough for any real wrapping; finite, which is the point. */
        const val MAX_CAUSE_DEPTH = 16

        const val SPACE_MARGIN_NUM = 11L
        const val SPACE_MARGIN_DEN = 10L

        internal fun classify(t: Throwable): AppError.ModelDownload.Reason =
            if (isNoSpace(t)) AppError.ModelDownload.Reason.DISK else AppError.ModelDownload.Reason.NETWORK

        /**
         * Walks the cause chain, and matches on **both** the errno and the message.
         *
         * `FileOutputStream.write` surfaces `ENOSPC` as an `IOException` whose *cause* is the
         * `ErrnoException`, so reading only `t.message` misses it. The message form is what most
         * Android versions actually produce (`write failed: ENOSPC (No space left on device)`);
         * the errno form is the one that survives a locale change. Which fires on this hardware
         * is unmeasured — `B-124` — so both stay, and both would stay anyway.
         */
        internal fun isNoSpace(t: Throwable): Boolean =
            // `.take` because the walk is otherwise unbounded: a cause chain that CYCLES would
            // spin here for ever, inside a catch block, on an IO thread, with no cancellation
            // check. Nothing in OkHttp or the JDK is known to build one — and bounding it is free.
            generateSequence(t, Throwable::cause).take(MAX_CAUSE_DEPTH).any { cause ->
                (cause as? android.system.ErrnoException)?.errno == android.system.OsConstants.ENOSPC ||
                    cause.message?.contains("ENOSPC") == true
            }

        fun registrableSuffix(host: String): String {
            val labels = host.split('.')
            return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
        }
    }
}
