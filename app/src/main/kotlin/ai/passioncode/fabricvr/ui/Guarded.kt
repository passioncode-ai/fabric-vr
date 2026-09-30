package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.runCatchingCancellable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * **The one place a view model is allowed to start a coroutine** (`B-159`).
 *
 * `viewModelScope` is `SupervisorJob() + Dispatchers.Main.immediate` and carries **no**
 * `CoroutineExceptionHandler`. Every coroutine launched into it is therefore its own root: an
 * exception that leaves its body does not fail a parent, does not reach a `fold`, and does not
 * reach the person — it goes to the process's uncaught-exception route. On a headset that is a
 * crash with no sentence attached; under `runTest` it is collected against **whichever test
 * happens to be running**, which is how `B-159` was found. Four seams read in one coroutine from
 * `SettingsViewModel.init`, one construction site missing one of them, and five runs produced four
 * different red cases, none of them the test that built the view model.
 *
 * `scripts/check-seams.sh` refuses a bare `viewModelScope.launch` anywhere under
 * `app/.../ui/` except this file, so the rule is a gate rather than a habit. That is the whole
 * value: the thirty-seventh launch is written next week by somebody who has not read this KDoc.
 *
 * **It logs before it forwards, and it forwards rather than deciding.** A guard that only caught
 * would be the other way to make a screen lie — the read fails, nothing escapes, and the defaults
 * are drawn as if they had been read. So [onFailure] is where each view model says what its own
 * person should see, and the log line happens whether or not one is given: an escape is a defect
 * in the launch's own body, and it must leave a trace even where the screen has nowhere to put it.
 *
 * `runCatchingCancellable` rather than `try`/`catch`: a cancelled coroutine throws, and a guard
 * that reported cancellation as a failure would put "Couldn't save" on the screen every time the
 * person left a screen mid-write.
 */
internal fun ViewModel.launchGuarded(
    /** What the person sees. Empty where the surface has nowhere to put it; the log still fires. */
    onFailure: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = launchGuarded(viewModelScope, onFailure, block)

/**
 * The same guarantee on a scope the view model does not own — in practice `Graph.scope` (`B-215`).
 *
 * **`DEC-0084`'s letter stopped at `viewModelScope` and its argument does not.** Work that must
 * outlive a screen is launched on the application scope on purpose: a dictation's commit, a
 * thirteen-to-forty-second decode, a recording being kept. That scope is
 * `SupervisorJob() + Dispatchers.Default` and carries no `CoroutineExceptionHandler` either, so a
 * throw out of one of those bodies takes the same route as a throw out of a `viewModelScope`
 * one — the process's uncaught handler, which on a headset is a crash with no sentence attached.
 * The 2026-09-22 audit found three of them around calls that genuinely throw: `Graph.sttLanguage`
 * is a Keystore decrypt, `Vault.adoptOrKeep` is file IO.
 *
 * **It keeps the same name deliberately.** `tools/check_main_thread.py` scans the body of every
 * block whose call starts with one of three spellings, and a new wrapper called anything else
 * would be a blind spot the day it was written — which is exactly the side-finding `DEC-0084`
 * records, arriving through a change that made the code better. An overload is one word the
 * scanner already knows.
 *
 * It is still a `ViewModel` extension so the log line names which one escaped, and it returns the
 * `Job` because a caller that has to wait for the write — `EditorViewModel.delete` — needs it.
 */
internal fun ViewModel.launchGuarded(
    scope: CoroutineScope,
    onFailure: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = scope.launch {
    runCatchingCancellable { block() }.onFailure { failure ->
        Log2.e("vm.launch.escaped", failure, "vm" to this@launchGuarded::class.java.simpleName)
        onFailure(failure)
    }
}
