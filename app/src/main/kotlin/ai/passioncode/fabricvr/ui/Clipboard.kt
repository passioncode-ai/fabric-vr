package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The system clipboard, reachable from something that is not a composition (`DEC-0012`, `B-212`).
 *
 * `LocalClipboardManager` is a Compose local, and a view model must not hold one — but the copy
 * that `DEC-0012` promises belongs where the commit is known to have happened, which since
 * `DEC-0068` is `NotesViewModel.commitDictation` on the application scope, with no composition
 * necessarily alive at all. So the seam is this function: a `Context` and a string, injected into
 * the view model as a lambda so a test can count copies without an Android framework.
 *
 * **The label is the app's name, not an identifier.** The system clipboard UI shows a clip's
 * label, and `DEC-0071` says no internal identifier reaches a person.
 */
internal fun Context.copyPlainText(text: String) {
    val manager = getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
}
