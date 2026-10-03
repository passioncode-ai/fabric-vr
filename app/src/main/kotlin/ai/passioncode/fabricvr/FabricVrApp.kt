package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.CrashLog
import ai.passioncode.fabricvr.common.Log2
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.StrictMode
import java.io.File

class FabricVrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before Graph.init on purpose: a failure inside Graph.init — an unreadable Keystore, a
        // database that will not open — is exactly the kind worth catching, and it is the kind
        // that leaves the person with a window that closes and nothing to hand back.
        installCrashHandler()
        installStrictMode()
        Graph.init(this)
        recordSystemKills(this)
    }

    /**
     * The system is short of memory, or this app's UI has just been hidden: give back the whisper
     * context, up to 574 MB of native memory nothing else will reclaim (LC-08, audit F1,
     * `DEC-0102`). The release waits for a decode in flight; see [MemoryTrim].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Graph.memoryTrim.onTrimMemory(level)
    }

    /**
     * Names main-thread disk work in the log, in debug builds only.
     *
     * `penaltyLog`, never `penaltyDeath`. Death turns any third-party disk read — Room's, the
     * Spatial SDK's, Compose's font loading — into a crash on somebody else's schedule, and the
     * suite would be red for reasons nobody here can fix. What this is for is the measurement
     * `T-019` owes: drive one dictation and read `adb logcat -s StrictMode`, counting only the
     * violations attributed to this package.
     */
    private fun installStrictMode() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * Records what we can catch, then lets the platform do what it was going to do.
     *
     * Chaining to the previous handler rather than replacing it is the whole of the contract: a
     * handler that swallows the exception leaves the process alive in a state nobody designed,
     * which is worse than the crash.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // `runCatching` because this runs on a thread that is already dying: a handler that
            // throws replaces a diagnosable crash with an undiagnosable one.
            runCatching {
                CrashLog.record(
                    crashDir(this),
                    error,
                    mapOf(
                        "thread" to thread.name,
                        "build" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        "variant" to if (BuildConfig.DEBUG) "debug" else "release",
                    ),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

internal fun crashDir(context: Context): File = File(context.filesDir, "crash")

/**
 * The half our own handler cannot see: a native abort inside whisper, a low-memory kill, an ANR.
 * The system keeps its own record of why a process died and it survives a reboot; ours does not
 * exist for those, because the process was gone before any Kotlin ran.
 *
 * Read once per launch and watermarked, so the same death is not recorded on every start — and so
 * the file does not fill with the same entry, which is the shape that made `ApplicationExitInfo`
 * useless the last time somebody reached for it.
 */
internal fun recordSystemKills(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        val prefs = context.getSharedPreferences("fabricvr_crash", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN_UNTIL, 0L)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val reasons = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        var newest = seen
        reasons.asReversed()
            .filter { it.timestamp > seen && it.reason in WORTH_RECORDING }
            .forEach { info ->
                newest = maxOf(newest, info.timestamp)
                CrashLog.record(
                    crashDir(context),
                    SystemKill("${nameOf(info.reason)} (status ${info.status}): ${info.description}"),
                    mapOf("source" to "the system's own record, not this app's handler"),
                )
            }
        if (newest > seen) prefs.edit().putLong(SEEN_UNTIL, newest).commit()
        Log2.i("crash.system_kills.read", "new" to (if (newest > seen) 1 else 0))
    }
}

/** Not a real failure, a carrier: [CrashLog] describes throwables and this is one so it can. */
private class SystemKill(message: String) : RuntimeException(message) {
    override fun fillInStackTrace(): Throwable = this   // the stack here is ours, not the crash's
}

private fun nameOf(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_CRASH -> "an unhandled exception"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash"
    ApplicationExitInfo.REASON_ANR -> "the app stopped responding"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "the headset ran out of memory"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much of some resource"
    else -> "reason $reason"
}

private val WORTH_RECORDING = setOf(
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
)

private const val SEEN_UNTIL = "exit_info_seen_until"
private const val MAX_EXITS = 16
