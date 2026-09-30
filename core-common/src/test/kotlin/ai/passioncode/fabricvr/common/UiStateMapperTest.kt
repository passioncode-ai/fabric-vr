package ai.passioncode.fabricvr.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateMapperTest {

    private val all = listOf(
        AppError.Permission("microphone"),
        AppError.Permission("microphone", permanent = true),
        AppError.ModelMissing("ggml-small-q5_1"),
        // **The size branch, which is a SECOND two-placeholder string.** The comment in
        // `UiStringsTest` counts how many times a placeholder string has been added to the
        // product without being added to these lists; `error_model_missing_size` was the third.
        //
        // **It takes a `nameRes` since `REQ-061`** and the branch is now conditional on one:
        // `small` is the enum's key, and a key is not a name a person chose between. A caller
        // with no name gets the sentence without one, which is why the case above still maps to
        // `error_model_missing`.
        AppError.ModelMissing("small", 190_085_487L, R.string.action_download),
        AppError.ModelDownload(AppError.ModelDownload.Reason.NETWORK),
        AppError.ModelDownload(AppError.ModelDownload.Reason.CHECKSUM),
        AppError.ModelDownload(AppError.ModelDownload.Reason.DISK),
        AppError.ModelDownload(AppError.ModelDownload.Reason.CANCELLED),
        AppError.ModelDownload(AppError.ModelDownload.Reason.DISK, needBytes = 574_041_195, freeBytes = 1_000_000),
        AppError.ModelBusy,
        AppError.RemoteStt(503),
        // `B-244`: a 200 that is not a transcript. Its own sentence — "refused (200)" would be false.
        AppError.RemoteSttUnreadable(200),
        // `B-258`: a recording names itself and offers the notes-only export; a note only names itself.
        AppError.ExportUnreadable("n.wav", recording = true, cause = null),
        AppError.ExportUnreadable("n.md", recording = false, cause = null),
        AppError.SttFailed("whisper"),
        // **One case, because there is one sentence now.** `DEC-0020` cut the assistant and
        // `:app` does not link `:feature-assistant`, so no installable build can construct this
        // at all; the four OpenRouter sentences this used to fan out into named a service the
        // product has not got, and `REQ-061` deleted them.
        AppError.OpenRouter(401),
        AppError.NoApiKey,
        // Both were absent until the shape check above was written, and both are the shapes a
        // reader would most expect to be here: `SttNotConfigured` is `DEC-0024`'s whole point —
        // a chosen provider refusing out loud — and `InsecureUrl` is `DEC-0005`'s, the guard
        // that stops a person's voice going to an address they did not mean (`F-14`).
        AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.ADDRESS),
        AppError.SttNotConfigured("server", AppError.SttNotConfigured.Missing.KEY),
        AppError.InsecureUrl("http://api.example.com/v1"),
        // `B-216`. Its own shape rather than a second use of `InsecureUrl`, because the hop it
        // refuses can be `https` at both ends and the cleartext sentence would be false.
        AppError.RedirectRefused("cdn.example.com"),
        AppError.Network(java.io.IOException("down")),
        // `M12`'s half of the pair: the same shape once it knows what did not answer. Both are
        // listed because the branch forks on `host` and a list holding one arm proves one arm.
        AppError.Network(java.net.ConnectException("refused"), host = "whisper.local:9000"),
        AppError.Storage("upsert"),
        // Four operations, four sentences. `Storage` carried `op` from the beginning and the
        // mapper threw it away, so *"Couldn't save. Your text is still here."* was the answer to
        // a failed export and to a keystore that would not open (`M12`).
        AppError.Storage("vault.export"),
        AppError.Storage("vault.audio.remove"),
        AppError.Storage("keystore"),
        AppError.Unknown(IllegalStateException("boom")),
    )

    /**
     * `F-14`. **The list is a list somebody has to keep true, and nobody did.**
     *
     * `verification.md`'s `REQ-009` claimed "every one of the 17 `AppError` shapes"; there are
     * eleven shapes and seventeen *cases*, and two shapes — `InsecureUrl`, the one `DEC-0005`
     * exists to create, and `SttNotConfigured` — were in no case at all. A count in a document
     * cannot notice that. `AppError` is `sealed`, so `sealedSubclasses` is exhaustive by
     * construction and a twelfth shape fails this test instead of quietly widening the gap.
     */
    @Test fun `every AppError shape is covered`() {
        // **Recursive.** `sealedSubclasses` is one level deep, so a shape nested inside another
        // sealed shape would not be counted — the gap this assertion exists to close, one layer
        // down.
        fun shapesOf(k: kotlin.reflect.KClass<out AppError>): List<String> =
            if (k.sealedSubclasses.isEmpty()) listOfNotNull(k.simpleName)
            else k.sealedSubclasses.flatMap { shapesOf(it) }
        val shapes = AppError::class.sealedSubclasses.flatMap { shapesOf(it) }.toSet()
        val covered = all.map { it::class.simpleName }.toSet()

        assertEquals("an AppError shape with no case", emptySet<String>(), shapes - covered)
    }

    // The mapper returns ids now, so these assert the choice. That the ids resolve to real,
    // non-blank strings is a property of the resource file and is checked on the device, in
    // UiStringsTest — a JVM test cannot open a resource table and would pass for the wrong reason.

    @Test fun `every error maps to a real string resource`() {
        all.forEach { error ->
            assertNotEquals("no message for $error", 0, UiStateMapper.map(error).textRes)
        }
    }

    @Test fun `no two errors produce the same message`() {
        val messages = all.map { UiStateMapper.map(it).let { m -> m.textRes to m.args } }
        assertEquals("distinct messages expected", messages.size, messages.toSet().size)
    }

    @Test fun `a refused permission offers to grant it and a blocked one opens settings`() {
        assertEquals(UiAction.GRANT_PERMISSION, UiStateMapper.map(AppError.Permission("microphone")).action)
        assertEquals(
            UiAction.OPEN_APP_SETTINGS,
            UiStateMapper.map(AppError.Permission("microphone", permanent = true)).action,
        )
    }

    @Test fun `a missing model offers the download`() {
        assertEquals(UiAction.DOWNLOAD_MODEL, UiStateMapper.map(AppError.ModelMissing("m")).action)
    }

    /**
     * `D-05`: a person met *"The speech model isn't on this headset yet"*, pressed **Download**,
     * and learned on the progress bar that they had committed to 190 MB. The name and the size
     * were already carried into this mapper and thrown away, because the branch took no
     * arguments — the banner is the first place in the session where the cost is knowable and it
     * was the last.
     */
    @Test fun `a missing model names the model and its size`() {
        val message = UiStateMapper.map(
            AppError.ModelMissing("small", 190_085_487L, R.string.action_download),
        )

        // The NAME is a resource the app resolves, never the key `small` (`REQ-061`, `B-157`).
        assertEquals(listOf(UiMessage.Res(R.string.action_download), "190 MB"), message.args)
        assertEquals(UiAction.DOWNLOAD_MODEL, message.action)
    }

    /**
     * And a refusal that cannot be repaired here offers the way round it. `T-028` put the text
     * editor back, so this is the first release in which the offer is not a lie.
     */
    @Test fun `a missing model offers writing a note instead`() {
        assertEquals(
            UiAction.WRITE_NOTE,
            UiStateMapper.map(AppError.ModelMissing("small", 1, R.string.action_download)).secondary,
        )
        assertEquals(UiAction.WRITE_NOTE, UiStateMapper.map(AppError.ModelMissing("small")).secondary)
    }

    /**
     * An unknown size falls back to the sentence without one. `mb()` refuses to print "0 MB"
     * everywhere else in this file and a banner reading *"small, 0 MB"* would be worse than the
     * sentence it replaced.
     *
     * **An unknown NAME falls back the same way** (`REQ-061`): `WhisperEngine` raises this shape
     * carrying `ggml-small-q5_1.bin` and `SttRouter` carrying `none configured`, and an
     * incomplete true sentence beats a complete one with a file name in it.
     */
    @Test fun `an unknown size or name does not reach the person`() {
        assertEquals(emptyList<Any>(), UiStateMapper.map(AppError.ModelMissing("small")).args)
        assertEquals(
            R.string.error_model_missing,
            UiStateMapper.map(AppError.ModelMissing("ggml-small-q5_1.bin", 190_085_487L)).textRes,
        )
    }

    /**
     * `REQ-061`. **A missing cloud key sends the person to Settings; the cut assistant offers
     * nothing, because there is nothing to do about a feature that is not in the build.**
     *
     * `NoApiKey` is raised by `CloudTranscriptionClient`, not by the assistant — its sentence
     * said *"Add an OpenRouter key to use the assistant"*, which is the wrong credential for a
     * feature `DEC-0020` deleted, and it sent the person to look for a field no screen has.
     */
    @Test fun `a missing cloud key sends the user to settings and the cut assistant offers nothing`() {
        assertEquals(UiAction.OPEN_SETTINGS, UiStateMapper.map(AppError.NoApiKey).action)
        assertNull(UiStateMapper.map(AppError.OpenRouter(401)).action)
        assertEquals(
            "every OpenRouter status is the same sentence now",
            UiStateMapper.map(AppError.OpenRouter(401)),
            UiStateMapper.map(AppError.OpenRouter(503, message = "no provider")),
        )
    }

    /**
     * **`REQ-061` reverses the second half of this.** *"The speech server answered 503."* with
     * no control was the defect, not the design: it reaches a person only when the remote failed
     * AND no local model could take over (`SttRouter`), and the address and key they typed into
     * Settings are the only two things they can change.
     */
    /** `B-258`. A damaged recording is reported, and the way round it is offered — without its file name. */
    @Test fun `an unreadable recording offers the notes-only export`() {
        val message = UiStateMapper.map(AppError.ExportUnreadable("n.wav", recording = true, cause = null))
        assertEquals(R.string.error_export_recording_unreadable, message.textRes)
        assertEquals("a file name is not a thing a person can find on the headset", emptyList<Any>(), message.args)
        assertEquals(UiAction.EXPORT_NOTES_ONLY, message.action)
    }

    /** A note cannot be left out of a backup, so nothing is offered but its name. */
    @Test fun `an unreadable note is named and offers no partial export`() {
        val message = UiStateMapper.map(AppError.ExportUnreadable("n.md", recording = false, cause = null))
        assertEquals(R.string.error_export_note_unreadable, message.textRes)
        assertEquals(emptyList<Any>(), message.args)
        assertEquals(null, message.action)
    }

    /** `B-244`. Not the refusal sentence: the service answered, and the answer was not words. */
    @Test fun `an unreadable remote answer is not called a refusal`() {
        val message = UiStateMapper.map(AppError.RemoteSttUnreadable(200))
        assertEquals(R.string.error_remote_stt_unreadable, message.textRes)
        assertEquals(UiAction.OPEN_SETTINGS, message.action)
    }

    @Test fun `a remote stt failure carries the status and says what to do next`() {
        val message = UiStateMapper.map(AppError.RemoteStt(503))
        assertEquals(listOf(503), message.args)
        assertEquals(UiAction.OPEN_SETTINGS, message.action)
    }

    /**
     * `REQ-061`. It **named the class to the person** — *"Something failed:
     * IllegalStateException."* — which is a stack frame with a full stop after it. The class is
     * still wanted for a diagnosis, so the branch logs it through `Log2`, where `DEC-0023`'s
     * crash report can carry it off the headset without a laptop.
     */
    @Test fun `an unknown failure is a sentence, not a class name`() {
        val message = UiStateMapper.map(AppError.Unknown(IllegalStateException("boom")))
        assertEquals(emptyList<Any>(), message.args)
        assertEquals(UiAction.RETRY_LOAD, message.action)
    }

    /**
     * `M12`. `Storage` has carried the operation since the taxonomy was written and this mapper
     * ignored it, so an export that could not be finished and a note that could not be saved read
     * the same: *"Couldn't save. Your text is still here."* — a sentence about the person's text,
     * shown for a failure their text was never in.
     *
     * The operation reaches the words as a **chosen sentence**, never as the key: `vault.export`
     * is an internal name and no raw key may reach a person (`REQ-061`). An operation with no
     * sentence of its own falls back to the general one rather than inventing a fifth.
     */
    @Test fun `a storage failure says which operation failed`() {
        assertEquals(R.string.error_storage_export, UiStateMapper.map(AppError.Storage("vault.export")).textRes)
        assertEquals(R.string.error_storage_export, UiStateMapper.map(AppError.Storage("export.create")).textRes)
        assertEquals(R.string.error_storage_save, UiStateMapper.map(AppError.Storage("upsert")).textRes)
        assertEquals(R.string.error_storage_save, UiStateMapper.map(AppError.Storage("vault.write")).textRes)
        assertEquals(
            R.string.error_storage_recording,
            UiStateMapper.map(AppError.Storage("vault.audio.remove")).textRes,
        )
        assertEquals(R.string.error_storage_keystore, UiStateMapper.map(AppError.Storage("keystore")).textRes)
        assertEquals(R.string.error_storage, UiStateMapper.map(AppError.Storage("vault.purge")).textRes)
    }

    /** The address the person configured is the only part of a network failure they can act on. */
    @Test fun `an unreachable host is named`() {
        val message = UiStateMapper.map(
            AppError.Network(java.net.ConnectException("refused"), host = "whisper.local:9000"),
        )

        assertEquals(R.string.error_network_host, message.textRes)
        assertEquals(listOf<Any>("whisper.local:9000"), message.args)
        assertEquals(UiAction.RETRY_LOAD, message.action)
    }

    @Test fun `throwables are classified rather than swallowed`() {
        assertTrue(java.net.UnknownHostException("x").toAppError() is AppError.Network)
        assertTrue(java.net.SocketTimeoutException("x").toAppError() is AppError.Network)
        assertTrue(java.io.IOException("x").toAppError("write") is AppError.Storage)
        assertTrue(IllegalArgumentException("x").toAppError() is AppError.Unknown)
    }
    @Test
    fun `a missing speech address and a missing cloud key say different things`() {
        val address = UiStateMapper.map(
            AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.ADDRESS),
        )
        val key = UiStateMapper.map(
            AppError.SttNotConfigured("cloud", AppError.SttNotConfigured.Missing.KEY),
        )
        val cloudClient = UiStateMapper.map(AppError.NoApiKey)

        assertNotEquals("a missing address and a missing key read the same", address.textRes, key.textRes)
        assertNotEquals(
            "the routing refusal and the cloud client's own refusal read the same",
            cloudClient.textRes,
            key.textRes,
        )
        assertEquals(UiAction.OPEN_SETTINGS, key.action)
        // A NAME, not the provider's key: it read "No key saved for cloud." (`REQ-061`).
        assertEquals(listOf<Any>(UiMessage.Res(R.string.provider_name_cloud)), key.args)
    }

}
