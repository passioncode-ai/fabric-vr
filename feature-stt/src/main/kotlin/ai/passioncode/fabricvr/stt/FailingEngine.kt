package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.notes.Transcript

/**
 * An engine that refuses, with a reason.
 *
 * Asking for a provider that is not configured is a normal thing for a person to do — they picked
 * the cloud before pasting a key. Returning null would make every caller invent its own wording for
 * that; this says it once, in the taxonomy everything else already speaks.
 */
class FailingEngine(private val error: AppError) : SttEngine {
    override val name: String = "unconfigured"
    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int, langHint: String?): Result<Transcript> =
        Result.failure(SttException(error))
}
