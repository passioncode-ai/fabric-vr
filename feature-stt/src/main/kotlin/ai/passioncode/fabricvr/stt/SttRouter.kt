package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import kotlinx.coroutines.CancellationException

/**
 * Prefers a configured whisper-server and degrades to the on-device engine when it does not answer.
 * The degradation is visible: the transcript records [SttSource.LOCAL_FALLBACK] and the sheet says so.
 */
class SttRouter(
    private val local: SttEngine?,
    private val remote: SttEngine?,
) : SttEngine {

    override val name: String = "router"

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> {
        if (remote != null) {
            val attempt = remote.transcribe(pcm, sampleRate, langHint)
            attempt.onSuccess { return Result.success(it) }
            val error = (attempt.exceptionOrNull() as? SttException)?.error
            Log2.w("stt.remote.failed", "fallback" to (local != null))
            if (local == null) {
                return Result.failure(SttException(error ?: AppError.RemoteStt(0)))
            }
            // The reason travels with the transcript: the sheet shows it, the vault records it.
            //
            // **And when the fallback fails too, it is the REMOTE's reason that survives**
            // (`M13`). `map` does not touch a failure, so the local engine's answer used to
            // replace the remote's without a word — and the local engine's commonest answer is
            // `ModelMissing`, which offers a **Download**. Somebody whose cloud key had expired
            // was therefore told the speech model was not on the headset and invited to commit to
            // 190–574 MB that would not have fixed anything, while the 401 they could have acted
            // on was dropped on the floor. `REQ-058` closed this for `local == null` above and
            // left this branch, which is the one a person actually reaches.
            //
            // The remote is what they chose, so the remote is what is reported. The fallback's
            // own failure is logged instead of shown: it is the answer to a question nobody
            // asked, and two sentences in one banner is how neither gets read.
            return local.transcribe(pcm, sampleRate, langHint)
                .map { it.copy(source = SttSource.LOCAL_FALLBACK, fallbackReason = error) }
                .recoverCatching { fallbackFailure ->
                    // **A cancelled fallback is not a failed one**, and `runCatchingCancellable`
                    // is the rule this keeps: the engines throw a `CancellationException` rather
                    // than returning it, so this arm is normally unreachable — it is here because
                    // an engine that did return one would otherwise have the person's own *stop*
                    // rewritten into the remote's error.
                    if (fallbackFailure is CancellationException) throw fallbackFailure
                    Log2.w(
                        "stt.fallback.failed",
                        "remote" to (error?.let { it::class.simpleName } ?: "unclassified"),
                        "local" to fallbackFailure::class.simpleName.orEmpty(),
                    )
                    // No classified reason from the remote means there is nothing better to say
                    // than what the fallback said, and saying nothing would be worse than both.
                    throw if (error != null) SttException(error) else fallbackFailure
                }
        }
        if (local == null) {
            return Result.failure(SttException(AppError.ModelMissing("none configured")))
        }
        return local.transcribe(pcm, sampleRate, langHint)
    }
}
