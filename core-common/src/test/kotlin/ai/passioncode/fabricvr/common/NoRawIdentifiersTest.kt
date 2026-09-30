package ai.passioncode.fabricvr.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `REQ-061`, audit `M27` — **no internal identifier reaches a person's eyes.**
 *
 * `AppError` carries the identifiers a failure was about: a provider key, a model key, a storage
 * operation, the class of a throwable. Three of the four reached the screen verbatim — *"No key
 * saved for cloud"*, *"Something failed: NullPointerException"*, and a model banner reading
 * *"small, 190 MB"*. `Storage.op` was fixed by `M12` and its rule is the one generalised here:
 * an identifier **chooses** a sentence or a nested resource, and is never substituted into one.
 *
 * The mechanical form of that rule is the first test: **no argument of a `UiMessage` is a raw
 * `String`.** A number is a number, a size has been formatted, and anything that is a name is a
 * [UiMessage.Res]. It is decidable, so the next identifier added to `AppError` fails here rather
 * than appearing in a banner.
 */
@RunWith(RobolectricTestRunner::class)
class NoRawIdentifiersTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The identifier-bearing shapes, each with a value that would be unmistakable on a screen. */
    private val identifierCarrying = listOf(
        AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.ADDRESS),
        AppError.SttNotConfigured("server", AppError.SttNotConfigured.Missing.KEY),
        AppError.ModelMissing("ggml-small-q5_1", 190_085_487L),
        AppError.ModelMissing("none configured"),
        AppError.Storage("vault.purge"),
        AppError.Storage("vault.audio.sweep"),
        AppError.Unknown(NullPointerException("boom")),
        AppError.OpenRouter(429),
        // `B-258`'s first version put the file name on screen — a note's UUID, which nothing on the
        // headset can locate — and this list, written by hand, did not know the shape existed.
        AppError.ExportUnreadable("3f9a1c2e-0000-4000-8000-00000000abcd~1727.wav", recording = true, cause = null),
        AppError.ExportUnreadable("3f9a1c2e-0000-4000-8000-00000000abcd.md", recording = false, cause = null),
    )

    @Test fun `no message substitutes a raw identifier into its sentence`() {
        identifierCarrying.forEach { error ->
            val args = UiStateMapper.map(error).args
            assertTrue(
                "$error puts a raw String into the person's line of sight: $args",
                args.none { it is String },
            )
        }
    }

    /** And the rendered sentences, because the rule above is only worth its consequences. */
    @Test fun `the rendered sentence never contains an internal name`() {
        val forbidden = listOf(
            "NullPointerException",
            "ggml-small-q5_1",
            "none configured",
            "vault.purge",
            "vault.audio.sweep",
            "OpenRouter",
            "3f9a1c2e",
        )

        identifierCarrying.forEach { error ->
            val text = UiStateMapper.map(error).text(context)
            forbidden.forEach { token ->
                assertFalse("$error rendered '$token' to the person: $text", text.contains(token))
            }
        }
    }

    /**
     * The provider is named in words. `"for cloud."` was the whole sentence's tail and it is the
     * shape this asserts against — a person does not have a thing called `cloud`.
     */
    @Test fun `a provider that is not configured is named in words`() {
        val cloud = UiStateMapper.map(
            AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.KEY),
        ).text(context)
        val server = UiStateMapper.map(
            AppError.SttNotConfigured("server", AppError.SttNotConfigured.Missing.ADDRESS),
        ).text(context)

        assertTrue("the cloud service is not named: $cloud", cloud.contains("cloud service"))
        assertTrue("the whisper server is not named: $server", server.contains("whisper server"))
    }

    /**
     * An unknown failure still has to be diagnosable, so the class name goes to `Log2` — where
     * `DEC-0023`'s crash report can carry it off the headset — and never to the screen.
     */
    @Test fun `an unknown failure is a sentence, and offers a way on`() {
        val message = UiStateMapper.map(AppError.Unknown(NullPointerException("boom")))

        assertEquals(emptyList<Any>(), message.args)
        assertEquals(UiAction.RETRY_LOAD, message.action)
        assertTrue(message.text(context).isNotBlank())
    }

    /**
     * *"The speech server answered 500."* named the wrong thing — a cloud endpoint raises the
     * same error — and offered nothing to do about it. It reaches a person only when the remote
     * failed **and** there is no local model to fall back to (`SttRouter`), so the next step is
     * the address and key they typed into Settings.
     */
    @Test fun `a refused remote transcription says what to do next`() {
        val message = UiStateMapper.map(AppError.RemoteStt(500))

        assertEquals(listOf(500), message.args)
        assertEquals(UiAction.OPEN_SETTINGS, message.action)
        assertFalse(
            "one server, one name: this still calls it a speech server",
            message.text(context).contains("speech server"),
        )
    }

    /**
     * `DEC-0020` cut the assistant and `:app` does not link `:feature-assistant`, so nothing a
     * person can install constructs this. The four OpenRouter sentences it used to choose
     * between named a service the product no longer has; one honest sentence replaces them.
     */
    @Test fun `the cut assistant does not name a service this build has not got`() {
        val text = UiStateMapper.map(AppError.OpenRouter(402)).text(context)

        assertFalse(text.contains("OpenRouter"))
        assertTrue(text.isNotBlank())
    }
}
