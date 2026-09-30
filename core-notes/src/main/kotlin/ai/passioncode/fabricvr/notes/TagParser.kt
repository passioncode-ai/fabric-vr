package ai.passioncode.fabricvr.notes

/**
 * Tags come from the text the person already wrote: `#idea` in a body becomes the tag `idea`.
 * Unicode letters count, so `#идея` is a tag too — the product is bilingual by design.
 */
object TagParser {

    private val pattern = Regex("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_-]+)")

    fun parse(text: String): Set<String> =
        pattern.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .filter { it.isNotBlank() && it.any(Char::isLetterOrDigit) }
            .toSet()
}
