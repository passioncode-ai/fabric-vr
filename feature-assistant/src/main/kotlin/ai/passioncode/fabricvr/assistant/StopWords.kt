package ai.passioncode.fabricvr.assistant

/**
 * Words that carry no search signal in the two languages this product speaks. Without them a
 * question like "what did I decide about the panel?" searches for "what", "did" and "about" too,
 * and in an AND query that means nothing matches at all.
 */
object StopWords {

    private val english = setOf(
        "the", "and", "for", "are", "but", "not", "you", "with", "this", "that", "from", "what",
        "did", "was", "were", "have", "has", "had", "into", "about", "then", "than", "when",
        "where", "which", "there", "here", "your", "mine", "can", "could", "would", "should",
    )

    private val russian = setOf(
        "что", "как", "это", "или", "если", "того", "было", "быть", "есть", "для", "над", "под",
        "при", "про", "там", "тут", "они", "она", "оно", "мой", "моя", "мои", "уже", "ещё",
        "чтобы", "когда", "где", "который", "которая",
    )

    private val all = english + russian

    fun isSignal(term: String): Boolean = term.length >= 3 && term.lowercase() !in all
}
