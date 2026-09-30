package ai.passioncode.fabricvr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ai.passioncode.fabricvr.common.Log2

/**
 * Asking for the microphone, hoisted out of the composition.
 *
 * `rememberLauncherForActivityResult` resolves an `ActivityResultRegistryOwner` through the view
 * tree, and the Spatial SDK's compose panel provides only Lifecycle, ViewModelStore and
 * SavedStateRegistry owners — its host is a plain `android.app.Activity`, not a `ComponentActivity`.
 * So the request belongs to whichever activity is hosting, and the screens receive a lambda.
 */
interface PermissionRequester {
    /**
     * @param onResult (granted, permanentlyDenied) — the second is true only after a hard refusal.
     * @param onDismissed the dialog went away **unanswered**, which is not a refusal
     *   (`REQ-048`, `H10`). AOSP signals it with an empty `grantResults`; the caller's guard
     *   lifts and nothing on the screen changes, because the person has said nothing. Only the
     *   immersive host can tell the two apart — `ActivityResultContracts.RequestPermission`
     *   collapses a dismissal to `false` before anything of ours sees it, so `PanelActivity`
     *   reports it as an ordinary, non-permanent refusal and this is never called there.
     */
    fun requestRecordAudio(
        onResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit,
        onDismissed: () -> Unit = {},
    )

    /**
     * Opens the system page where a refusal can be undone.
     *
     * Once the microphone is permanently denied the app may not ask again — `requestPermissions`
     * returns immediately with a refusal and no prompt is shown. The only route back is the
     * system's own page, and until `T-009` every branch that offered *Open app settings* went to
     * **this app's** Settings screen, which cannot grant anything: a person who tapped *Deny* twice
     * had permanently lost voice notes, which is the product (`D-02`).
     *
     * @return false when nothing on this device can show that page, so the caller can say so
     * instead of the shell taking the panel down with an `ActivityNotFoundException`.
     */
    fun openAppSettings(): Boolean
}

/**
 * Choosing an exported archive from the system picker, hoisted for [PermissionRequester]'s reason.
 *
 * `REQ-054`'s app half. `ACTION_OPEN_DOCUMENT` returns a result, and
 * `rememberLauncherForActivityResult` needs an `ActivityResultRegistryOwner` the Spatial SDK's
 * compose panel does not provide — its host is a plain `android.app.Activity`. So the pick
 * belongs to whichever activity is hosting, exactly as the microphone request does, and Settings
 * receives a lambda.
 *
 * A separate interface rather than a third method on `PermissionRequester`: one is about a
 * permission and this is not, and a screen that needs neither should be able to say so in its
 * parameter list.
 */
interface ArchivePicker {
    /**
     * @param onChosen the archive the person picked, or null when they picked nothing. The URI
     *   is read once, immediately, by whoever asked — `ACTION_OPEN_DOCUMENT` grants access for
     *   the life of the process and no longer, so storing it would store a handle that stops
     *   working.
     */
    fun pickArchive(onChosen: (Uri?) -> Unit)
}

/**
 * The intent both hosts send, in one place so they cannot disagree about the types.
 *
 * **The wildcard type beside `application/zip`, and it is not laziness.** Horizon OS's picker
 * types a file from the provider that supplied it, and an archive that arrived over a share, a
 * download or `adb push` is routinely offered as `application/octet-stream` or with no type at
 * all — so a filter naming only the correct type shows the person an empty picker containing
 * the file they are looking at. The unpacker refuses anything that is not an export, entry by
 * entry (`VaultZipImporter`), which is where a type claim should be checked anyway.
 */
internal fun openArchiveIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "*/*"))

/**
 * The one implementation of [PermissionRequester.openAppSettings], shared by both hosts.
 *
 * Measured on a Quest 3 (Horizon OS 207) on 2026-09-21: the intent resolves to
 * `com.oculus.vrshell.intents.AndroidIntentsRelayActivity` — Meta's relay rather than AOSP
 * Settings — so it will not throw. What the relay then shows is a human observation and is on the
 * device-gate ledger, not asserted here.
 */
internal fun Context.startAppSettings(): Boolean {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(packageManager) == null) {
        Log2.w("permission.app_settings.unavailable")
        return false
    }
    return runCatching { startActivity(intent); true }.getOrElse {
        Log2.w("permission.app_settings.failed", "reason" to it::class.java.simpleName)
        false
    }
}
