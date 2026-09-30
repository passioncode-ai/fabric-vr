package ai.passioncode.fabricvr

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import ai.passioncode.fabricvr.ui.FabricApp

/** The 2D panel: what the Horizon OS shell shows beside a streamed desktop. */
class PanelActivity : ComponentActivity() {

    private var pending: ((Boolean, Boolean) -> Unit)? = null

    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val permanent = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            pending?.invoke(granted, permanent)
            pending = null
        }

    private var pendingArchive: ((Uri?) -> Unit)? = null

    private val archive =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingArchive?.invoke(result.data?.data)
            pendingArchive = null
        }

    private val requester = object : PermissionRequester {
        /**
         * `onDismissed` is never called here, and that is the platform rather than an omission:
         * `ActivityResultContracts.RequestPermission` reduces an empty `grantResults` to `false`
         * inside androidx before this callback runs, so a dismissal and a refusal are the same
         * value by the time anything of ours sees them. It is reported as an ordinary refusal —
         * never a permanent one, because `shouldShowRequestPermissionRationale` is still true
         * after a cancel — so the screen offers *Ask again*. See `REQ-048` and `ImmersiveActivity`,
         * which is a plain Activity and can tell.
         */
        override fun requestRecordAudio(
            onResult: (Boolean, Boolean) -> Unit,
            onDismissed: () -> Unit,
        ) {
            pending = onResult
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }

        override fun openAppSettings(): Boolean = startAppSettings()
    }

    /**
     * `REQ-054`. `StartActivityForResult` rather than `ActivityResultContracts.OpenDocument`,
     * because that contract's `input` is a MIME array and it sets no `type` at all — Horizon
     * OS's picker then offers everything, and the point of `openArchiveIntent` is that one
     * intent is shared with `ImmersiveActivity`, which has no result registry and must build the
     * intent by hand. One intent, two hosts, no chance of them filtering differently.
     */
    private val picker = object : ArchivePicker {
        override fun pickArchive(onChosen: (Uri?) -> Unit) {
            pendingArchive = onChosen
            // A picker that cannot be opened must say nothing happened rather than take the
            // panel down with an ActivityNotFoundException.
            runCatching { archive.launch(openArchiveIntent()) }.onFailure {
                pendingArchive = null
                onChosen(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FabricApp(
                permissionRequester = requester,
                archivePicker = picker,
                onEnterSpace = { onSpaceRequested ->
                    // Guarded: a headset without the VR category would otherwise throw
                    // ActivityNotFoundException and take the panel down with it.
                    runCatching { startActivity(ImmersiveActivity.intent(this)) }
                        .onFailure { onSpaceRequested(it) }
                },
            )
        }
    }
}
