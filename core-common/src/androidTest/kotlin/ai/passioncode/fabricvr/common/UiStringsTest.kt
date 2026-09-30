package ai.passioncode.fabricvr.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mapper returns string ids, so the words themselves are only checked where a resource table
 * exists. A JVM test asserting on ids would pass just as happily against an id whose string was
 * deleted; this one opens the table and reads every message the product can show.
 */
@RunWith(AndroidJUnit4::class)
class UiStringsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val PLACEHOLDER = Regex("%\\d+\\$")

    private val all = listOf(
        AppError.Permission("microphone"),
        AppError.Permission("microphone", permanent = true),
        AppError.ModelMissing("ggml-small-q5_1"),
        // `T-032`'s two-placeholder string, and the THIRD time one reached the product without
        // reaching this list. The count in the comment above is the point of keeping it.
        AppError.ModelMissing("small", 190_085_487L, R.string.action_download),
        AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK),
        AppError.ModelDownload(AppError.ModelDownload.Reason.CHECKSUM),
        AppError.ModelDownload(AppError.ModelDownload.Reason.DISK),
        AppError.ModelDownload(AppError.ModelDownload.Reason.CANCELLED),
        // `T-021`'s two. The DISK shape with both numbers is the project's ONLY error string with
        // a second placeholder, which is exactly what the assertion below exists to catch — and
        // it was added to the product without being added here. Twice now, in the same list.
        AppError.ModelDownload(AppError.ModelDownload.Reason.DISK, needBytes = 574_041_195, freeBytes = 1_000_000),
        AppError.ModelBusy,
        AppError.RemoteStt(503),
        AppError.SttFailed("whisper"),
        // One case: `REQ-061` collapsed the four OpenRouter sentences into one, because
        // `DEC-0020` cut the assistant and no installable build can construct this shape.
        AppError.OpenRouter(401),
        AppError.NoApiKey,
        AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.ADDRESS),
        AppError.SttNotConfigured("server", AppError.SttNotConfigured.Missing.KEY),
        // Absent from this list until T-008 although it has been mapped since DEC-0005 (F-14) —
        // the omission this list exists to prevent, sitting inside the list itself.
        AppError.InsecureUrl("http://api.example.com/v1"),
        // `B-216`, and `error_redirect_refused` is a placeholder string — which is what this list
        // exists for: the ones that keep reaching the product without reaching it.
        AppError.RedirectRefused("cdn.example.com"),
        AppError.Network(java.io.IOException("down")),
        // `M12`'s pair. `error_network_host` is a placeholder string and this list exists because
        // placeholder strings keep reaching the product without reaching it — the comment above
        // counts three; this is the fourth, added in the same change as the string.
        AppError.Network(java.net.ConnectException("refused"), host = "whisper.local:9000"),
        AppError.Storage("upsert"),
        // One case per storage sentence. `Storage` is one shape with five messages now, and the
        // shape check below would be satisfied by any one of them.
        AppError.Storage("vault.export"),
        AppError.Storage("vault.audio.remove"),
        AppError.Storage("keystore"),
        AppError.Storage("vault.purge"),
        AppError.Unknown(IllegalStateException("boom")),
    )

    @Test
    fun everyMappedErrorResolvesToWordsAPersonCanRead() {
        all.forEach { error ->
            val text = UiStateMapper.map(error).text(context)
            assertTrue("blank message for $error", text.isNotBlank())
            // ANY placeholder, not just the first. `error_model_disk_detail` takes two, and an
            // assertion that knows only about `%1$` would have passed it with `%2$s` on screen.
            assertTrue(
                "unformatted placeholder in \"$text\"",
                !PLACEHOLDER.containsMatchIn(text),
            )
        }
    }

    /**
     * `F-14`. The row in `verification.md` claimed "every one of the 17 `AppError` shapes"; there
     * are **eleven** shapes and seventeen *cases*, and two shapes had no case at all. A count in
     * a document cannot notice that, so the claim is structural now: `AppError` is `sealed`, and
     * a twelfth shape fails this test rather than quietly widening the gap.
     *
     * The same assertion runs on the JVM in `UiStateMapperTest`, where it executes on every
     * build. This copy exists because **this** list is the one the ledger row is about, and two
     * lists drift apart the moment only one of them is checked.
     */
    @Test
    fun everyAppErrorShapeIsCovered() {
        fun shapesOf(k: kotlin.reflect.KClass<out AppError>): List<String> =
            if (k.sealedSubclasses.isEmpty()) listOfNotNull(k.simpleName)
            else k.sealedSubclasses.flatMap { shapesOf(it) }
        val shapes = AppError::class.sealedSubclasses.flatMap { shapesOf(it) }.toSet()
        val covered = all.map { it::class.simpleName }.toSet()

        assertEquals("an AppError shape with no case", emptySet<String>(), shapes - covered)
    }

    /**
     * `REQ-061`: the provider reaches the sentence as a **name**, never as its key — it read
     * *"No key saved for cloud."* and a person has nothing called `cloud`.
     */
    @Test
    fun aMissingSpeechProviderNamesTheProviderInWords() {
        val text = UiStateMapper.map(
            AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.KEY),
        ).text(context)
        assertTrue("the provider is not named: \"$text\"", text.contains("cloud service"))
    }

    /** `M12`. The host is in the sentence, and no internal operation key is in any of them. */
    @Test
    fun theUnreachableHostIsNamedAndNoStorageKeyIsLeaked() {
        val network = UiStateMapper.map(
            AppError.Network(java.net.ConnectException("refused"), host = "whisper.local:9000"),
        ).text(context)
        assertTrue("the host did not reach the sentence: \"$network\"", network.contains("whisper.local:9000"))

        listOf("upsert", "vault.export", "vault.audio.remove", "keystore", "vault.purge").forEach { op ->
            val text = UiStateMapper.map(AppError.Storage(op)).text(context)
            assertTrue("the internal operation key reached the person: \"$text\"", !text.contains(op))
        }
    }

    /**
     * The status reaches the sentence; **the class name does not** (`REQ-061`). It used to —
     * *"Something failed: IllegalStateException."* — and the diagnosis now goes to `Log2`,
     * which is what `DEC-0023`'s crash report carries off the headset.
     */
    @Test
    fun theStatusReachesTheSentenceAndTheClassNameDoesNot() {
        assertTrue(UiStateMapper.map(AppError.RemoteStt(503)).text(context).contains("503"))
        assertTrue(
            !UiStateMapper.map(AppError.Unknown(IllegalStateException("boom")))
                .text(context).contains("IllegalStateException"),
        )
    }
}
