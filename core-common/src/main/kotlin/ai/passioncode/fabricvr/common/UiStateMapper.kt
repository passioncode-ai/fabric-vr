package ai.passioncode.fabricvr.common

import androidx.annotation.StringRes

/**
 * The only place an [AppError] becomes words. Every branch returns a distinct, non-empty message —
 * an unmapped error still renders, because silence is the failure this class exists to prevent.
 *
 * It returns string **ids**, not strings: the wording lives in `res/values/strings.xml`, so a
 * translation or a copy pass never touches this file, and a test asserts the choice rather than the
 * sentence. Strings are EN drafts: the brand pack and the copy pass are deferred (carry-over row 1).
 */
object UiStateMapper {

    fun map(error: AppError): UiMessage = when (error) {
        is AppError.Permission ->
            if (error.permanent) {
                UiMessage(R.string.error_permission_blocked, listOf(error.what), UiAction.OPEN_APP_SETTINGS)
            } else {
                UiMessage(R.string.error_permission_needed, listOf(error.what), UiAction.GRANT_PERMISSION)
            }

        AppError.ModelBusy ->
            UiMessage(R.string.error_model_busy)

        // **The size, at the first place it can be known.** `D-05`: a person met this sentence,
        // pressed *Download*, and learned on the progress bar that they had committed to 190 MB.
        // The name and the bytes were already carried here and thrown away, because this branch
        // took no arguments. Zero falls back to the sentence without a size rather than
        // announcing "0 MB", which is the shape `mb()` refuses everywhere else.
        //
        // **And a NAME, not the key** (`REQ-061`, `B-157`). It read *"small, 190 MB"* — `small`
        // is the enum's key, and `WhisperEngine` passes `ggml-small-q5_1.bin` down the same
        // field. A caller with no `nameRes` therefore gets the sentence **without** a name, on
        // the same principle as the size: an incomplete true sentence beats a complete one with
        // an internal identifier in it.
        is AppError.ModelMissing -> if (error.bytes > 0 && error.nameRes != 0) {
            UiMessage(
                R.string.error_model_missing_size,
                listOf(UiMessage.Res(error.nameRes), mb(error.bytes)),
                UiAction.DOWNLOAD_MODEL,
                UiAction.WRITE_NOTE,
            )
        } else {
            UiMessage(R.string.error_model_missing, action = UiAction.DOWNLOAD_MODEL, secondary = UiAction.WRITE_NOTE)
        }

        is AppError.ModelDownload -> when (error.reason) {
            AppError.ModelDownload.Reason.NETWORK ->
                UiMessage(R.string.error_model_network, action = UiAction.RETRY_LOAD)
            AppError.ModelDownload.Reason.CHECKSUM ->
                UiMessage(R.string.error_model_checksum, action = UiAction.RETRY_LOAD)
            // With the two numbers when the refusal happened before the transfer, because
            // "there isn't room" alone does not say how much to free and cannot be disproved.
            AppError.ModelDownload.Reason.DISK -> {
                val need = error.needBytes
                val free = error.freeBytes
                if (need != null && free != null) {
                    UiMessage(R.string.error_model_disk_detail, listOf(mb(need), mb(free)), UiAction.RETRY_LOAD)
                } else {
                    UiMessage(R.string.error_model_disk, action = UiAction.RETRY_LOAD)
                }
            }
            AppError.ModelDownload.Reason.CANCELLED ->
                UiMessage(R.string.error_model_cancelled, action = UiAction.DOWNLOAD_MODEL)
        }

        // **The status, and a next step** (`REQ-061`, audit `M27`). It said *"The speech server
        // answered 500."* and offered nothing — and it named the wrong thing, because a cloud
        // endpoint raises the same error through `CloudTranscriptionClient`. It reaches a person
        // only when the remote failed AND there is no local model to fall back to
        // (`SttRouter`), so the address and the key they typed into Settings are the only two
        // things they can act on.
        is AppError.RemoteStt ->
            UiMessage(R.string.error_remote_stt, listOf(error.status), UiAction.OPEN_SETTINGS)

        // `B-244`: the service answered and the answer was not a transcript. The commonest cause
        // is an address that reaches something else — a portal, a proxy — so Settings is still
        // the one place the person can act, and "refused (200)" would have been false.
        is AppError.RemoteSttUnreadable ->
            UiMessage(R.string.error_remote_stt_unreadable, action = UiAction.OPEN_SETTINGS)

        // `B-258`. The export refused to ship a truncated file (`DEC-0093`). The file's name is a
        // note's UUID — nothing on the headset can find it by that — so it goes to the log and the
        // sentence says what KIND of file it was, which decides what the person can do: a
        // recording can be left out, a note cannot (`DEC-0071`: no raw identifier on screen).
        is AppError.ExportUnreadable -> {
            Log2.w("export.unreadable", "file" to error.file, "recording" to error.recording)
            if (error.recording) {
                UiMessage(R.string.error_export_recording_unreadable, action = UiAction.EXPORT_NOTES_ONLY)
            } else {
                UiMessage(R.string.error_export_note_unreadable)
            }
        }

        is AppError.SttFailed ->
            UiMessage(R.string.error_stt_failed, action = UiAction.RETRY_LOAD)

        // **`DEC-0020` cut the assistant and `:app` does not link `:feature-assistant`**, so
        // nothing in a build a person can install constructs this. The four sentences this
        // branch used to choose between — a rejected key, spent credits, a rate limit, anything
        // else — all named OpenRouter, a service the product no longer has, and `REQ-061`
        // deleted them: four strings nobody can reach are four strings every copy pass and every
        // future translation pays for. The branch stays because the shape does —
        // `:feature-assistant` is still in the tree and its own tests construct it — and it
        // answers with the one sentence that is true of every installable build.
        is AppError.OpenRouter ->
            UiMessage(R.string.error_assistant_absent)

        // **Not the assistant's.** `CloudTranscriptionClient` raises this when the cloud
        // transcription key is missing, and the sentence said *"Add an OpenRouter key to use the
        // assistant"* — the wrong credential, for a feature `DEC-0020` deleted, sending the
        // person to look for a field that is not on the screen.
        AppError.NoApiKey ->
            UiMessage(R.string.error_no_api_key, action = UiAction.OPEN_SETTINGS)

        // The provider reaches the words as a NAME, never as its key (`REQ-061`): it read *"No
        // key saved for cloud."*, and a person does not have a thing called `cloud`.
        is AppError.SttNotConfigured -> when (error.missing) {
            AppError.SttNotConfigured.Missing.ADDRESS -> UiMessage(
                R.string.error_stt_no_address,
                listOf(UiMessage.Res(providerNameFor(error.provider))),
                UiAction.OPEN_SETTINGS,
            )
            AppError.SttNotConfigured.Missing.KEY -> UiMessage(
                R.string.error_stt_no_key,
                listOf(UiMessage.Res(providerNameFor(error.provider))),
                UiAction.OPEN_SETTINGS,
            )
        }

        is AppError.InsecureUrl ->
            UiMessage(R.string.error_insecure_url, action = UiAction.OPEN_SETTINGS)

        // `B-216`. The host, because it is the only part of the event the person can compare
        // against what they typed — and a sentence about cleartext would be false here, since the
        // hop this refuses can be `https` at both ends.
        is AppError.RedirectRefused ->
            UiMessage(R.string.error_redirect_refused, listOf(error.host), UiAction.OPEN_SETTINGS)

        // The address, when the caller knew one. `M12`: "No answer from the network" is not
        // something a person can act on — they configured a host, and that host is what did not
        // answer. No host falls back to the general sentence rather than leaving a gap in it.
        is AppError.Network -> error.host?.let { host ->
            UiMessage(R.string.error_network_host, listOf(host), UiAction.RETRY_LOAD)
        } ?: UiMessage(R.string.error_network, action = UiAction.RETRY_LOAD)

        // **The operation reaches the words, and never as its key.** `M12`: `Storage` has carried
        // `op` since the taxonomy was written and this branch ignored it, so an export that could
        // not be finished and a keystore that would not open both read *"Couldn't save. Your text
        // is still here."* — a sentence about the person's text, for a failure their text was
        // never in. `vault.export` is an internal name and no raw key may reach a person
        // (`REQ-061`), so the op chooses a **sentence**; an op with none falls back to the
        // general one rather than inventing a fifth nobody wrote.
        // **`RETRY_LOAD` here means "try what this surface was doing again", and it is the
        // mapper's only honest answer** (`REQ-047`). An error carries no memory of the act that
        // produced it, so nothing at this layer can tell a failed dictation commit from a failed
        // list read — the view model that knows swaps in `RETRY_DICTATION` or `RETRY_SPACE` on
        // the message it raises. Guessing here is exactly how one `RETRY` came to mean "reload
        // the list" under a sentence about an unsaved dictation.
        is AppError.Storage ->
            UiMessage(storageTextFor(error.op), action = UiAction.RETRY_LOAD)

        // **The class name goes to the log, not to the screen** (`REQ-061`, audit `M27`).
        // *"Something failed: NullPointerException."* is a stack frame with a full stop after
        // it: it tells the person nothing they can act on, and it tells them the app does not
        // know what it is doing. The diagnosis is still wanted — `DEC-0023`'s crash report is
        // how a second headset hands one back without a laptop — so it is emitted here, which
        // is the one place every unknown failure passes through.
        is AppError.Unknown -> {
            Log2.e(
                "ui.unknown_failure",
                error.cause,
                "type" to (error.cause?.javaClass?.simpleName ?: "unknown"),
            )
            UiMessage(R.string.error_unknown, action = UiAction.RETRY_LOAD)
        }
    }

    /**
     * The name a person reads for a speech provider, chosen from the stable key.
     *
     * A `when` over the keys rather than a shared enum: `SttProvider` lives in `:feature-stt`,
     * which `:core-common` must not depend on — the error taxonomy is below the features, not
     * beside them. An unknown key falls back to the general phrase rather than printing itself.
     */
    @StringRes
    private fun providerNameFor(key: String): Int = when (key) {
        "cloud" -> R.string.provider_name_cloud
        "server" -> R.string.provider_name_server
        else -> R.string.provider_name_other
    }

    /**
     * Which sentence an [AppError.Storage] operation gets.
     *
     * The keys are the ones the code actually passes — grep `AppError.Storage(` — and they are
     * matched in full rather than by prefix, because `vault.audio` is a **write** (adopting a
     * recording into the note's folder) while `vault.audio.remove` is a deletion, and a prefix
     * rule would file the second under the first.
     */
    private fun storageTextFor(op: String): Int = when (op) {
        "upsert", "dailyNote", "write", "vault.write", "vault.audio" -> R.string.error_storage_save
        "vault.export", "export.create", "export.open", "export.publish" -> R.string.error_storage_export
        "vault.audio.remove", "vault.audio.sweep" -> R.string.error_storage_recording
        "keystore" -> R.string.error_storage_keystore
        // `vault.remove`, `vault.restore`, `vault.purge`, `vault.import`, `vault.audio.usage`,
        // `delete`, and whatever is added next. A general sentence is not a gap — it is the
        // honest answer while nobody has written a specific one, and it is never the key.
        else -> R.string.error_storage
    }
}

/**
 * Megabytes, because a byte count is not a number a person can act on — and **never "0 MB"**.
 *
 * Truncating toward zero turned a refusal on a headset with 900 kB free into *"…and this headset
 * has 0 MB free"*, which is one number and a zero where the string exists to give two. Below a
 * megabyte it says so in the unit the person can check.
 */
private fun mb(bytes: Long): String = when {
    bytes < 1_000_000 -> "${(bytes / 1_000).coerceAtLeast(1)} kB"
    else -> "${bytes / 1_000_000} MB"
}
