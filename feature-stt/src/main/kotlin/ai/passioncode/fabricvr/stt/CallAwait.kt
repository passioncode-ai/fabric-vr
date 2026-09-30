package ai.passioncode.fabricvr.stt

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * An OkHttp call that a cancelled coroutine actually stops (`H9`).
 *
 * Both remote clients used to do this instead:
 *
 * ```
 * val call = client.newCall(request)
 * currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
 * call.execute().use { … }
 * ```
 *
 * which reads as cancellation and is not. `invokeOnCompletion` fires when the job **completes**,
 * and a job parked inside the synchronous `execute()` completes only when that call returns — so
 * the handler cancelled the call at the moment the call was ending anyway. A dismissed sheet kept
 * uploading the whole recording for up to the client's call timeout (60 s for whisper-server,
 * 120 s for the cloud), holding `LocalWhisperOwner`'s mutex throughout, so the next dictation
 * queued behind a transcription nobody was waiting for.
 *
 * The asynchronous form has a real cancellation seam: `invokeOnCancellation` runs the moment the
 * coroutine is cancelled, whether or not the response has arrived, and `Call.cancel()` closes the
 * socket immediately.
 *
 * **The caller still owns the [Response] and must close it** — this returns the response, not the
 * body, because the clients read and size-limit the body themselves. A response that arrives after
 * the coroutine has already been cancelled is closed here instead, since nobody is left to do it.
 *
 * One function in one file rather than a copy in each client: two implementations of a
 * cancellation contract is how one of them silently stops being cancellable.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    // Registered BEFORE `enqueue`, so a coroutine that is already cancelled cannot slip a request
    // onto the wire between the two.
    continuation.invokeOnCancellation { runCatching { cancel() } }

    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // `cancel()` above surfaces here as an IOException on the dispatcher thread. Reporting
            // it would turn "the person dismissed the sheet" into "check the network"; the
            // continuation is already resumed with the cancellation, and resuming twice is a
            // no-op that would only hide this.
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // The race the `use { }` at the call site cannot cover: cancelled after the bytes
            // arrived but before anyone could take ownership of them.
            continuation.resume(response) { _: Throwable -> runCatching { response.close() } }
        }
    })
}
