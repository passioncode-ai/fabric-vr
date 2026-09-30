package ai.passioncode.fabricvr.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopWordsTest {

    @Test fun `question words carry no signal in either language`() {
        assertFalse(StopWords.isSignal("what"))
        assertFalse(StopWords.isSignal("What"))
        assertFalse(StopWords.isSignal("что"))
        assertFalse(StopWords.isSignal("про"))
    }

    @Test fun `short fragments are not searched`() {
        assertFalse(StopWords.isSignal("a"))
        assertFalse(StopWords.isSignal("на"))
    }

    @Test fun `real words are signal`() {
        assertTrue(StopWords.isSignal("panel"))
        assertTrue(StopWords.isSignal("панель"))
        assertTrue(StopWords.isSignal("whisper"))
    }
}
