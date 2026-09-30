package ai.passioncode.fabricvr.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagParserTest {

    @Test fun `parses a simple tag`() {
        assertEquals(setOf("idea"), TagParser.parse("this is an #idea worth keeping"))
    }

    @Test fun `lowercases and de-duplicates`() {
        assertEquals(setOf("idea"), TagParser.parse("#Idea and another #IDEA"))
    }

    @Test fun `parses a cyrillic tag`() {
        assertEquals(setOf("идея"), TagParser.parse("вот #идея на потом"))
    }

    @Test fun `stops at punctuation`() {
        assertEquals(setOf("idea", "plan"), TagParser.parse("#idea, then #plan."))
    }

    @Test fun `ignores a bare hash and a hash inside a word`() {
        assertTrue(TagParser.parse("# alone and issue#42 and C#").isEmpty())
    }

    @Test fun `keeps hyphens and underscores`() {
        assertEquals(setOf("work-log", "deep_work"), TagParser.parse("#work-log #deep_work"))
    }
}
