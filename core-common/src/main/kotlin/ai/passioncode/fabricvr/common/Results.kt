package ai.passioncode.fabricvr.common

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` with the one exception that must never be caught.
 *
 * A cancelled coroutine throws, and `runCatching` turns that into a failure like any other — so
 * leaving the editor mid-save reported "Couldn't save. Your text is still here.", and cancelling a
 * download reported "Check the network." Worse, the coroutine stopped honouring its own
 * cancellation. Everything in this app that wraps suspending work uses this instead.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
