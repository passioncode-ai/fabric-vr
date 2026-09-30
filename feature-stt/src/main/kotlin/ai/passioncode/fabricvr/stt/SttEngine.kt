package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.notes.Transcript

/** Anything that can turn 16 kHz mono PCM into words. */
interface SttEngine {
    val name: String
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int = 16_000, langHint: String? = null): Result<Transcript>
}
