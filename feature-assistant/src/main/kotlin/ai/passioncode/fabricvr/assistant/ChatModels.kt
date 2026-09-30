package ai.passioncode.fabricvr.assistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
    }
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
)

@Serializable
data class StreamChunk(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
) {
    @Serializable data class Choice(val delta: Delta? = null, @SerialName("finish_reason") val finishReason: String? = null)
    @Serializable data class Delta(val content: String? = null)
    @Serializable data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
    )
}

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null) {
    @Serializable data class ErrorBody(val code: Int = 0, val message: String = "")
}

sealed interface StreamEvent {
    data class Token(val text: String) : StreamEvent
    data class Usage(val prompt: Int, val completion: Int) : StreamEvent
    data object Done : StreamEvent
}

object Models {
    const val DEFAULT = "anthropic/claude-sonnet-5"
    const val FAST = "anthropic/claude-haiku-4.5"
    val known = listOf(DEFAULT, FAST)
}
