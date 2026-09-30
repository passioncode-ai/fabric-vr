package ai.passioncode.fabricvr

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `B-209`, the third level. **The application scope had no owner for what escaped its children.**
 *
 * `Graph.scope` is `SupervisorJob() + Dispatchers.Default`. A `SupervisorJob` keeps one failed
 * child from cancelling its siblings and says nothing about where that child's exception goes:
 * with no `CoroutineExceptionHandler` in the context, every coroutine launched here is its own
 * root and a throw that leaves its body reaches the process's uncaught-exception route. On a
 * headset that is a crash with no sentence attached — for work the person cannot see and did not
 * ask for, such as the vault mirror's collector failing on a full disk.
 *
 * `app/ui/Guarded.kt` is the same argument one layer up, for `viewModelScope` (`DEC-0084`), and
 * this handler is deliberately its twin: **it logs before it degrades, and it degrades rather than
 * deciding.** There is no screen to tell here — that is what makes the log line the whole of the
 * honesty — so the event name is fixed and asserted rather than left to a reader.
 */
class GraphScopeGuardTest {

    @Test fun `the application scope carries a handler for what escapes its children`() {
        assertNotNull(
            "Graph.scope has no CoroutineExceptionHandler: a throw in a background child is an " +
                "uncaught crash with nothing attached (B-209)",
            Graph.scope.coroutineContext[CoroutineExceptionHandler],
        )
    }

    /**
     * And it must say so. `Log2` degrades to stderr where there is no Android runtime, which is
     * exactly this tier — so the log line is readable here, and a handler that merely swallowed
     * would pass the assertion above while making the failure invisible, which is the other way
     * to lie about a vault that is out of sync.
     */
    @Test fun `a child that throws is logged rather than handed to the process`() {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true))
        try {
            runBlocking {
                Graph.scope.launch { throw IOException("the removal journal could not be written") }.join()
            }
        } finally {
            System.setErr(original)
        }

        assertTrue(
            "nothing named the escape: the exception went to the process's uncaught route, which " +
                "on the headset is a crash. Saw: ${captured.toString().take(400)}",
            captured.toString().contains("app.scope.escaped"),
        )
    }
}
