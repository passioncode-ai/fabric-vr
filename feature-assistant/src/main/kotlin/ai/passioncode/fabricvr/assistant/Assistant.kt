package ai.passioncode.fabricvr.assistant

import kotlinx.coroutines.flow.Flow

/** The chat, as the UI sees it: a question in, tokens out, with the notes already attached. */
class Assistant(
    private val client: OpenRouterClient,
    private val contextBuilder: NotesContextBuilder,
    private val model: () -> String = { Models.DEFAULT },
) {
    data class Ask(val events: Flow<StreamEvent>, val usedTitles: List<String>)

    suspend fun ask(question: String, history: List<ChatMessage> = emptyList()): Ask {
        val context = contextBuilder.build(question)
        val messages = buildList {
            add(ChatMessage.system(NotesContextBuilder.systemPrompt(context.text)))
            addAll(history)
            add(ChatMessage.user(question))
        }
        return Ask(client.stream(model(), messages), context.titles)
    }
}
