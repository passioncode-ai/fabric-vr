package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript

/**
 * A note as a Markdown file with YAML front-matter. This is the copy the person owns: the database
 * is an index over these files, so the format has to survive being read by anything else.
 */
object MarkdownSerializer {

    /** Written on its own line so a body that happens to contain the heading cannot be mistaken for it. */
    private const val TRANSCRIPT_MARKER = "<!-- fabricvr:transcript -->"

    fun toMarkdown(note: Note): String = buildString {
        appendLine("---")
        appendLine("id: ${yaml(note.id)}")
        appendLine("title: ${yaml(note.title)}")
        appendLine("created: ${note.createdAt}")
        appendLine("updated: ${note.updatedAt}")
        appendLine("tags: [${note.tags.sorted().joinToString(", ") { yaml(it) }}]")
        note.dayKey?.let { appendLine("day: ${yaml(it)}") }
        note.audioPath?.let { appendLine("audio: ${yaml(it)}") }
        note.transcript?.let {
            appendLine("lang: ${yaml(it.language)}")
            appendLine("engine: ${yaml(it.engine)}")
            appendLine("source: ${it.source.name}")
            appendLine("duration_ms: ${it.durationMs}")
            it.fallbackReason?.let { reason -> appendLine("fallback: ${yaml(reason::class.simpleName.orEmpty())}") }
        }
        appendLine("---")
        appendLine()
        append(note.body)
        note.transcript?.let {
            if (it.text.isNotBlank() && it.text != note.body) {
                appendLine()
                appendLine()
                appendLine(TRANSCRIPT_MARKER)
                appendLine("## Transcript")
                appendLine()
                append(it.text)
            }
        }
    }

    fun parse(text: String): Note? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return null
        val header = text.substring(3, end).trim().lines()
            .mapNotNull { line ->
                val at = line.indexOf(':')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }.toMap()
        val id = header["id"]?.let(::unyaml) ?: return null
        var body = text.substring(end + 4).trimStart('\n')
        var transcriptText: String? = null
        val markerAt = body.indexOf(TRANSCRIPT_MARKER)
        if (markerAt >= 0) {
            transcriptText = body.substring(markerAt + TRANSCRIPT_MARKER.length)
                .removePrefix("\n")
                .removePrefix("## Transcript")
                .trim()
            body = body.substring(0, markerAt).trimEnd()
        }
        return Note(
            id = id,
            title = unyaml(header["title"].orEmpty()),
            body = body.trimEnd('\n'),
            tags = splitTags(header["tags"].orEmpty()),
            createdAt = header["created"]?.toLongOrNull() ?: 0L,
            updatedAt = header["updated"]?.toLongOrNull() ?: 0L,
            dayKey = header["day"]?.let(::unyaml),
            audioPath = header["audio"]?.let(::unyaml),
            transcript = header["lang"]?.let { lang ->
                Transcript(
                    text = transcriptText.orEmpty(),
                    language = unyaml(lang),
                    source = header["source"]?.let { s -> runCatching { SttSource.valueOf(s) }.getOrNull() }
                        ?: SttSource.LOCAL,
                    engine = unyaml(header["engine"].orEmpty()),
                    durationMs = header["duration_ms"]?.toLongOrNull() ?: 0L,
                )
            },
        )
    }

    /**
     * A bare scalar is only safe while it contains nothing YAML reads as syntax. A title like
     * `Panel: size` broke the **whole** front-matter block in any real reader, and one starting with
     * `#` became a comment — so anything suspicious is quoted and escaped.
     */
    private fun yaml(value: String): String {
        val needsQuotes = value.isEmpty() ||
            value != value.trim() ||
            value.first() in "-?:,[]{}#&*!|>%@`'\"" ||
            value.any { it in ":#\n\"\\" || it == '\t' }
        if (!needsQuotes) return value
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun unyaml(value: String): String {
        if (value.length < 2 || !value.startsWith('"') || !value.endsWith('"')) return value
        val inner = value.substring(1, value.length - 1)
        val out = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val next = inner[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }

    /** Splits a YAML flow sequence, respecting quoted items that may contain a comma. */
    private fun splitTags(raw: String): Set<String> {
        val inner = raw.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return emptySet()
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == ',' && !quoted -> { items.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i += 1
        }
        items.add(current.toString())
        return items.map { unyaml(it.trim()) }.filter { it.isNotBlank() }.toSet()
    }
}
