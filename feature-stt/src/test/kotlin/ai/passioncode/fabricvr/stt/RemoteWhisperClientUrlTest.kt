package ai.passioncode.fabricvr.stt

import ai.passioncode.fabricvr.common.AppError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DEC-0005` in one table. The point is not that cleartext is bad — it is that a whisper-server on
 * the person's own LAN cannot have a certificate, while a typo pointing at the open internet must
 * not quietly send a recording in the clear.
 */
class RemoteWhisperClientUrlTest {

    private fun accepted(url: String) = RemoteWhisperClient.validateBaseUrl(url).isSuccess

    @Test fun `http to a private address is accepted`() {
        assertTrue(accepted("http://192.168.1.20:8080"))
        assertTrue(accepted("http://10.1.2.3:8080"))
        assertTrue(accepted("http://172.16.4.5:8080"))
        assertTrue(accepted("http://127.0.0.1:8080"))
        assertTrue(accepted("http://whisper.local:8080"))
    }

    @Test fun `http to a public host is refused`() {
        assertFalse(accepted("http://example.com:8080"))
        assertFalse(accepted("http://8.8.8.8"))
        assertFalse(accepted("http://172.32.0.1"))
    }

    @Test fun `https is accepted anywhere`() {
        assertTrue(accepted("https://example.com"))
        assertTrue(accepted("https://192.168.1.20:8443"))
    }

    @Test fun `a refusal names the insecure url`() {
        val failure = RemoteWhisperClient.validateBaseUrl("http://example.com")
        val error = (failure.exceptionOrNull() as SttException).error
        assertTrue(error is AppError.InsecureUrl)
    }

    @Test fun `nonsense is refused rather than parsed`() {
        assertFalse(accepted("not a url"))
        assertFalse(accepted(""))
        assertFalse(accepted("ftp://192.168.1.20"))
    }
}
