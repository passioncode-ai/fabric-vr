package ai.passioncode.fabricvr.vault

import ai.passioncode.fabricvr.notes.Note
import ai.passioncode.fabricvr.notes.SttSource
import ai.passioncode.fabricvr.notes.Transcript
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownVaultTest {

    @get:Rule val temp = TemporaryFolder()

    private val note = Note(
        id = "7f3c",
        title = "Panel size",
        body = "keep it under 2064x2208 #idea #панели",
        tags = setOf("idea", "панели"),
        createdAt = 1_758_240_000_000,
        updatedAt = 1_758_240_600_000,
    )

    @Test fun `markdown round-trips the note`() {
        val parsed = MarkdownSerializer.parse(MarkdownSerializer.toMarkdown(note))
        assertNotNull(parsed)
        assertEquals(note.id, parsed!!.id)
        assertEquals(note.title, parsed.title)
        assertEquals(note.body, parsed.body)
        assertEquals(note.tags, parsed.tags)
        assertEquals(note.createdAt, parsed.createdAt)
        assertEquals(note.updatedAt, parsed.updatedAt)
    }

    @Test fun `a transcript round-trips with its language and engine`() {
        val spoken = note.copy(
            body = "",
            transcript = Transcript("сказано вслух", "ru", SttSource.LOCAL_FALLBACK, "whisper-small", 4200),
        )
        val parsed = MarkdownSerializer.parse(MarkdownSerializer.toMarkdown(spoken))!!
        assertEquals("сказано вслух", parsed.transcript!!.text)
        assertEquals("ru", parsed.transcript!!.language)
        assertEquals(SttSource.LOCAL_FALLBACK, parsed.transcript!!.source)
        assertEquals("whisper-small", parsed.transcript!!.engine)
    }

    @Test fun `a title with a colon, a hash and quotes survives a yaml reader`() {
        val awkward = note.copy(title = "Panel: size #1 \"final\"")
        val markdown = MarkdownSerializer.toMarkdown(awkward)

        val titleLine = markdown.lines().first { it.startsWith("title:") }
        assertTrue("a colon in a bare scalar breaks the whole block: $titleLine", titleLine.contains("\""))
        assertEquals(awkward.title, MarkdownSerializer.parse(markdown)!!.title)
    }

    @Test fun `a body containing the transcript heading is not split`() {
        val tricky = note.copy(body = "before\n\n## Transcript\n\nstill my own body")
        val parsed = MarkdownSerializer.parse(MarkdownSerializer.toMarkdown(tricky))!!

        assertEquals(tricky.body, parsed.body)
        assertEquals(null, parsed.transcript)
    }

    @Test fun `tags with punctuation round-trip`() {
        val tagged = note.copy(tags = setOf("work-log", "deep_work", "идея"))
        val parsed = MarkdownSerializer.parse(MarkdownSerializer.toMarkdown(tagged))!!

        assertEquals(tagged.tags, parsed.tags)
    }

    @Test fun `parse refuses a file that is not a note`() {
        assertEquals(null, MarkdownSerializer.parse("just some text"))
        assertEquals(null, MarkdownSerializer.parse("---\nno-id: here\n---\nbody"))
    }

    @Test fun `write puts the file under notes year month and remove deletes it`() = runTest {
        val vault = FileVault(temp.newFolder("vault"))
        val file = vault.write(note).getOrThrow()

        assertTrue(file.exists())
        assertTrue(file.path.endsWith("/7f3c.md"))
        assertTrue(File(vault.root, "notes").walkTopDown().any { it.name == "7f3c.md" })

        vault.remove(note.id, note.createdAt).getOrThrow()
        assertFalse(file.exists())
    }

    @Test fun `a write failure is reported instead of thrown`() = runTest {
        val blocked = File(temp.newFile("not-a-directory"), "under-a-file")
        val result = FileVault(blocked).write(note)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VaultException)
    }
}
