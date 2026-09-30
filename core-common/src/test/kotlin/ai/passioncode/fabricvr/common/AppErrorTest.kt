package ai.passioncode.fabricvr.common

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classification table, as a table.
 *
 * `M12`: every `IOException` that was not one of three named shapes fell through to
 * [AppError.Storage], so a whisper-server that refused the connection reached the person as
 * *"Couldn't save. Your text is still here."* — a sentence about their note, about a failure that
 * had nothing to do with their note. `ConnectException`, `SSLException`, `NoRouteToHostException`
 * and `SocketException` are all `IOException`s and all four are the network.
 *
 * A table rather than four assertions because the defect is a **missing row**, and a missing row
 * is what a list of hand-written assertions cannot show you.
 */
class AppErrorTest {

    private val host = "whisper.local:9000"

    private val table: List<Pair<Throwable, String>> = listOf(
        ConnectException("Connection refused") to "Network",
        SSLException("handshake failed") to "Network",
        NoRouteToHostException("No route to host") to "Network",
        SocketException("Socket closed") to "Network",
        // The control. Without it a mapping that answered `Network` to everything would pass the
        // four rows above and be wrong about the case this taxonomy started with.
        IOException("No space left on device") to "Storage",
    )

    @Test fun `every network failure is classified as the network, and a disk failure is not`() {
        val wrong = table.mapNotNull { (throwable, expected) ->
            val actual = throwable.toAppError(op = "vault.write", host = host)::class.simpleName
            if (actual == expected) null else "${throwable::class.simpleName} -> $actual, expected $expected"
        }

        assertEquals("a throwable reached the wrong half of the taxonomy", emptyList<String>(), wrong)
    }

    /**
     * **The host, because "No answer from the network" is not actionable.** The person configured
     * an address; the thing that did not answer is that address, and it is the only part of the
     * sentence they can check or correct.
     */
    @Test fun `a network failure carries the host it could not reach`() {
        val error = ConnectException("Connection refused").toAppError(op = "remote-stt", host = host)

        assertEquals(host, (error as AppError.Network).host)
    }

    /** A caller that does not know the host still gets the right shape, with no host in it. */
    @Test fun `a caller with no host still gets a network error`() {
        val error = SSLException("handshake failed").toAppError(op = "remote-stt")

        assertTrue(error is AppError.Network)
        assertEquals(null, (error as AppError.Network).host)
    }
}
