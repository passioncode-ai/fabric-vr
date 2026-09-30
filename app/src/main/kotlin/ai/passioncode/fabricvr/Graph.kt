package ai.passioncode.fabricvr

import android.content.Context
import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.KeystoreSecureSettings
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.SecureSettings
import ai.passioncode.fabricvr.notes.NotesRepository
import ai.passioncode.fabricvr.notes.RoomNotesRepository
import ai.passioncode.fabricvr.notes.db.NotesDatabase
import ai.passioncode.fabricvr.stt.AudioRecorder
import ai.passioncode.fabricvr.stt.FileModelStore
import ai.passioncode.fabricvr.stt.InstalledModels
import ai.passioncode.fabricvr.stt.ModelDownloader
import ai.passioncode.fabricvr.stt.ModelDownloads
import ai.passioncode.fabricvr.stt.ModelStore
import ai.passioncode.fabricvr.stt.SttProvider
import ai.passioncode.fabricvr.stt.WhisperModel
import ai.passioncode.fabricvr.stt.SttEngine
import ai.passioncode.fabricvr.stt.FailingEngine
import ai.passioncode.fabricvr.stt.SttRouter
import ai.passioncode.fabricvr.stt.LocalWhisperOwner
import ai.passioncode.fabricvr.stt.WhisperEngine
import ai.passioncode.fabricvr.vault.FileVault
import ai.passioncode.fabricvr.vault.ReconcileSummary
import ai.passioncode.fabricvr.vault.RemovalJournal
import ai.passioncode.fabricvr.vault.Vault
import ai.passioncode.fabricvr.vault.VaultImporter
import ai.passioncode.fabricvr.vault.VaultMirror
import ai.passioncode.fabricvr.vault.VaultReconciler
import ai.passioncode.fabricvr.vault.VaultZipImporter
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The object graph, built once. Manual DI: the app is small enough that a framework would be noise. */
object Graph {
    lateinit var appContext: Context
        private set

    /**
     * The application scope: created once, never cancelled, and outliving every activity, panel and
     * view model. Work belongs here only when being cancelled would lose something the person
     * cannot get back — the vault mirror's collector, and the editor's last write, which navigation
     * would otherwise cancel mid-flight. A `SupervisorJob`, so one failed child does not take the
     * rest of the app's background work with it.
     *
     * **The invariant, because the membership has grown and `I-29` is about exactly that.** It now
     * carries five coroutines — the mirror, the settings carry-over, the trash purge, the scratch
     * sweep and the Keystore warm — and every one of them is bounded work that finishes. Nothing
     * long-running, nothing that loops for the life of the process except the mirror's collector,
     * which is what the scope exists for, and nothing whose failure should be silent: it is
     * `Dispatchers.Default`, so a blocking call here starves a pool sized to the core count. Work
     * that waits on IO says so with its own `withContext`.
     *
     * **Model downloads are NOT here**, and that is the invariant's first real test: they have
     * their own `downloadScope` below, because a 574 MB transfer is precisely the long-running
     * cancellable work this scope must not silently acquire.
     *
     * **It carries [scopeGuard]**, which is the difference between a background failure and a
     * crash — see that property for the whole argument.
     */
    val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeGuard)
    }

    /**
     * **What owns an exception that escapes a coroutine on [scope]** (`B-209`).
     *
     * A `SupervisorJob` decides that one failed child does not cancel its siblings. It decides
     * **nothing** about where that child's exception goes: with no `CoroutineExceptionHandler` in
     * the context, every coroutine launched here is its own root, so a throw that leaves its body
     * fails no parent, reaches no `fold`, and lands on the process's uncaught-exception route —
     * which on the headset is a crash with no sentence attached. `B-209` is that path walked end
     * to end: `RemovalJournal.persist` throws on a full disk, the mirror's collector is a bare
     * `launch` here, and a person whose disk filled up lost the app rather than one deletion.
     *
     * **It is the twin of `app/ui/Guarded.kt`** (`DEC-0084`), deliberately and for the same
     * reason: `viewModelScope` carries no handler either, and the answer there was one place that
     * logs before it forwards. The difference is that there IS no screen behind this scope — its
     * work is the mirror, a purge, a sweep, a warm — so the log line is the whole of the honesty
     * available, and the event name is fixed here rather than left to whoever reads a stack trace.
     *
     * A handler does not make the work succeed and does not make the scope resumable where it was
     * not already: the child that threw is finished either way. What it changes is that the
     * process lives, and that the escape is named.
     */
    internal val scopeGuard = CoroutineExceptionHandler { _, failure ->
        Log2.e("app.scope.escaped", failure, "kind" to failure::class.java.simpleName)
    }

    val settings: SecureSettings by lazy { KeystoreSecureSettings(appContext) }

    private val database: NotesDatabase by lazy { NotesDatabase.open(appContext) }

    // `beforeDelete` journals the removal before the row goes, so a death before the mirror runs
    // cannot bring a deleted note back (`B-239`, `DEC-0089`).
    val notes: NotesRepository by lazy {
        RoomNotesRepository(database.noteDao(), beforeDelete = { id, createdAt -> removalJournal.record(id, createdAt) })
    }

    val vault: Vault by lazy { FileVault(File(appContext.filesDir, "vault")) }

    /**
     * Deletions the vault still owes, written down before they are attempted.
     *
     * One instance, shared by the mirror that records them and the reconciler that settles them
     * (`H5`, `M7`). Two would be two files' worth of truth about one question — they default to
     * the same path, so they would not even disagree loudly.
     */
    val removalJournal: RemovalJournal by lazy { RemovalJournal(vault.root) }

    val vaultMirror: VaultMirror by lazy { VaultMirror(notes, vault, removalJournal) }

    /**
     * The other direction: the vault back into the database. See [VaultImporter] — it is what
     * makes `CONTEXT.md`'s "the database is an index over the vault" a fact rather than a wish,
     * and what turns `DEC-0026`'s exported archive into a restore path.
     */
    val vaultImporter: VaultImporter by lazy { VaultImporter(vault, notes) }

    /**
     * What compares the index with the files it indexes, in both directions (`H5`, `H6`, `M3`,
     * `M7`). It replaces `VaultImporter.importIfEmpty`, whose gate was one `recent(1)` read: an
     * import killed halfway left the database non-empty and was therefore never finished.
     */
    val vaultReconciler: VaultReconciler by lazy {
        VaultReconciler(vault, notes, vaultImporter, removalJournal)
    }

    /** An exported archive, back into the vault. `REQ-054`; the picker half lives in Settings. */
    val vaultZipImporter: VaultZipImporter by lazy { VaultZipImporter(vault, vaultReconciler) }

    /**
     * The startup reconcile, so the first UI read can wait for it.
     *
     * `H6`'s second half: the import used to be launched and never awaited, so `dailyNote(today)`
     * could win the race and create a note for a day the vault already had one for — a duplicate
     * on the launch after a restore. Null before [init], which is what a test that never calls
     * [init] sees, and what makes awaiting it a no-op there.
     */
    @Volatile
    var reconciled: Deferred<Result<ReconcileSummary>>? = null
        private set

    /**
     * Where a finished dictation waits for a note (`REQ-046`). Process-wide and disk-backed, so
     * it outlives the activity that produced it and, within one recording, the process.
     */
    val dictationOutbox: DictationOutbox by lazy {
        DictationOutbox(File(File(appContext.filesDir, "outbox"), "dictation.tsv"))
    }

    /**
     * The one seam that makes a dictation perceptible without looking at the panel (`REQ-062`,
     * audit `M22`).
     *
     * Process-wide because the haptic channel is attached and detached by whichever surface is
     * alive — `ImmersiveActivity` has an API for it, the panel has none — and a per-view-model
     * instance could not hold that. The switch is read **per cue** rather than captured, so
     * turning it off in Settings is immediate; the read is a cached boolean and not a Keystore
     * decrypt, because a decrypt between "the microphone opened" and "say so" is the one place
     * a stall is unforgivable (the same argument [copyTranscript] records).
     */
    val feedbackCues: FeedbackCues by lazy {
        PlatformFeedbackCues(enabled = { cuesEnabled }, audio = ToneCueChannel())
    }

    /**
     * The cached value of [SecureSettings.KEY_FEEDBACK_CUES]. Absent means on (`REQ-062`), which
     * is why it starts true: the first cue must not be silent while a Keystore read is in
     * flight. [refreshFeedbackCues] is called at start-up and after the switch is written.
     */
    @Volatile
    var cuesEnabled: Boolean = true
        private set

    suspend fun refreshFeedbackCues() {
        cuesEnabled = withContext(io) { settings.get(SecureSettings.KEY_FEEDBACK_CUES) != "false" }
    }

    /** What Settings calls the moment the switch moves, ahead of the Keystore write landing. */
    fun setFeedbackCues(value: Boolean) { cuesEnabled = value }

    /** Where a crash is written down. One place, so Settings and the export cannot disagree. */
    val crashes: File get() = crashDir(appContext)

    /** The archive route out of app-private storage. See `DEC-0026`. */
    val vaultExport: ai.passioncode.fabricvr.ui.VaultExport by lazy {
        ai.passioncode.fabricvr.ui.VaultExport.create(
            appContext,
            ai.passioncode.fabricvr.vault.VaultExporter(
                vault,
                extras = { mapOf("crashes.log" to File(crashes, "crashes.log")) },
            ),
        )
    }

    /**
     * The one directory every model lives in, so switching between them keeps what is downloaded.
     *
     * **Public since `B-199`.** It was private and [modelStore] was the only way in, which
     * resolves exactly one model — so the app could delete the selected model's file and nothing
     * else, and up to 1.395 GB across the other four sat with no listing and no way to reclaim
     * it. Enumerating a directory is a different question from opening one file in it, and
     * [installedModels] is what asks it.
     */
    val modelRoot: File get() = File(appContext.filesDir, "models")

    /**
     * What is on disk under [modelRoot], and the way to get any of it back (`B-093`, `B-199`).
     *
     * It enumerates the **catalogue**, never the directory, so a file this app did not download
     * is neither counted nor deleted. Nothing here suspends: `DEC-0031` puts the dispatcher in
     * the layer that blocks, and `SettingsViewModel` wraps these calls in its own `withContext`
     * the way it already wraps its model-store reads.
     */
    val installedModels: InstalledModels by lazy { InstalledModels(modelRoot) }

    /**
     * **R1: anything here that reads the Keystore or the disk is `suspend` and switches its own
     * thread.** See the rule in `docs/modules/app.md`. It is not a style preference — a
     * `settings.get` is an AndroidKeyStore AES-GCM decrypt inside a `@Synchronized` block, and
     * every one of these was reached from a click handler on `Dispatchers.Main.immediate`.
     *
     * The boundary is here rather than on `SecureSettings.get` **by decision, not by oversight**:
     * that interface has two implementations, six key constants and callers in three modules
     * including one whose `apiKey: () -> String?` parameter is not ours to change. Putting the
     * suspend boundary at `Graph`, which every app-side caller already goes through, buys the same
     * property at a tenth of the diff. Moving it inward is its own task.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO

    /** Which on-device model the person chose. Every model lives in the same directory, so
     *  switching back to one already downloaded costs nothing. */
    suspend fun whisperModel(): WhisperModel =
        withContext(io) { WhisperModel.byKey(settings.get(SecureSettings.KEY_STT_MODEL)) }

    /**
     * For a model the caller already knows. **No settings read, so no suspend** — and the split is
     * deliberate: this used to be one function with `model: WhisperModel = whisperModel()`, and a
     * default argument that quietly decrypts is exactly the shape that put five Keystore reads on
     * the drawing thread. [LocalWhisperOwner] takes a reference to this one.
     */
    fun modelStore(model: WhisperModel): ModelStore = FileModelStore(modelRoot, model)

    /** For the chosen model, which has to be read first. */
    suspend fun currentModelStore(): ModelStore = modelStore(whisperModel())

    suspend fun modelDownloader(model: WhisperModel? = null): ModelDownloader =
        ModelDownloader(modelStore(model ?: whisperModel()), freeBytes = ::freeModelBytes)

    /**
     * How much room the device will actually give us for a model.
     *
     * `getAllocatableBytes` rather than `StatFs` where the platform has it: it counts space
     * Android could reclaim from other apps' caches, which is the number it will really hand
     * over. `StatFs` is the pessimistic one and would sometimes refuse a download that would have
     * succeeded — and a **false** refusal is the worse failure here, because the person has no
     * way to disprove it. `StatFs` is the fallback, and any failure at all falls back to "plenty",
     * because a probe that cannot answer must not become a refusal.
     */
    private fun freeModelBytes(): Long = runCatching {
        val storage = appContext.getSystemService(android.os.storage.StorageManager::class.java)
        val uuid = storage.getUuidForPath(modelRoot)
        storage.getAllocatableBytes(uuid)
    }.recoverCatching {
        android.os.StatFs(modelRoot.path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /**
     * Downloads, one per model, outliving the screen that started them. See [ModelDownloads].
     *
     * **Its own scope, deliberately not [scope].** A 190–574 MB transfer is exactly the
     * long-running cancellable work that `I-29` says must not silently join the scope carrying
     * the app's small startup coroutines — and it belongs on `IO`, where a stalled socket parks a
     * pooled thread rather than one of `Default`'s core-count workers.
     */
    private val downloadScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val downloads: ModelDownloads by lazy { ModelDownloads(downloadScope) { model -> modelDownloader(model) } }

    val recorder: AudioRecorder by lazy { AudioRecorder(appContext) }

    /**
     * The one whisper context this process may hold, and the only thing allowed to construct or
     * free it. See [LocalWhisperOwner] for why it is a class in `feature-stt` rather than three
     * lines here: the rule it enforces is whisper's, and the three lines were an ANR (`I-04`).
     */
    val localWhisper: LocalWhisperOwner by lazy { LocalWhisperOwner(::modelStore) }

    /**
     * Runs [block] against speech routing for the current settings, then takes the engine back.
     *
     * **It hands over a use, not an engine, and that is the change.** `sttEngine()` returned an
     * `SttRouter` holding a `local` engine — a reference that outlived whatever lock built it, so
     * no owner could promise the context was still alive when it was called. Here the router is
     * built and used inside [LocalWhisperOwner.use], and nothing escapes.
     *
     * Rebuilt per call on purpose: every part of it is a setting the person may change at any
     * moment — where transcription is attempted first, which cloud endpoint, which on-device model.
     *
     * @param onWait see [LocalWhisperOwner.use]. The wait can still be half a minute; what changed
     *   is that the interface is alive during it and can say so.
     */
    suspend fun <T> withStt(
        onWait: (WhisperModel) -> Unit = {},
        block: suspend (SttEngine) -> T,
    ): T {
        // ONE hop, not six. `whisperModel()`, `sttProvider()` and the four decrypts inside
        // `remoteEngineFor` are microseconds each and every separate `withContext` to the same
        // dispatcher is a context switch that costs more than the work.
        val routing = withContext(io) { whisperModel() to remoteEngine(sttProvider()) }
        return localWhisper.use(routing.first, onWait) { local ->
            decoding = local as? WhisperEngine
            try {
                block(SttRouter(local = local, remote = routing.second))
            } finally {
                decoding = null
            }
        }
    }

    /**
     * The on-device engine currently inside a [withStt], or null.
     *
     * `@Volatile` because it is written on whichever coroutine is transcribing and read by a
     * screen's poll on Main.
     *
     * **A field here rather than a member of `SttEngine`.** Progress belongs to whisper and to
     * nothing else: a cloud endpoint returns one answer at the end and a whisper-server returns
     * one answer at the end, so widening the interface would put a method on two implementations
     * that can only ever answer zero. `LocalWhisperOwner` hands out a `ClosableSttEngine`, which
     * is the narrow contract it should hand out; this is `:app` — which already knows the whole
     * graph — asking the concrete type the one question only it can answer.
     */
    @Volatile
    private var decoding: WhisperEngine? = null

    /**
     * How far the running transcription has got, 0..100 (`B-178`).
     *
     * **A poll, and that is whisper's decision rather than this project's** (`DEC-0060`):
     * `progress_callback` fires on native worker threads that are not attached to the JVM, so the
     * native side writes an atomic and the Kotlin side reads it. The cost of asking is a volatile
     * load; the cost of not asking is nothing, which is what makes a cadence a screen's business.
     *
     * Zero when nothing local is running — including a cloud or whisper-server transcription,
     * which reports no progress at all and must not be drawn as a bar stuck at zero. See
     * [ai.passioncode.fabricvr.ui.VoiceViewModel.transcriptionProgress] for what the screen does
     * with that.
     */
    fun transcriptionProgress(): Int = decoding?.progressPercent() ?: 0

    suspend fun sttProvider(): SttProvider =
        withContext(io) { SttProvider.byKey(settings.get(SecureSettings.KEY_STT_PROVIDER)) }

    /**
     * One rule for where speech goes, for both entry points. See [remoteEngineFor].
     *
     * Four Keystore decrypts happen inside it, which is why it is `suspend` and why the spec that
     * only named `sttEngine()`'s own body would have left the defect in place: the decrypts moved
     * into `SttRouting.kt` when `T-008` extracted the rule, and a fix that wrapped the caller
     * would have compiled, passed and changed nothing.
     */
    private suspend fun remoteEngine(provider: SttProvider): SttEngine? =
        withContext(io) { remoteEngineFor(provider, settings) }

    /**
     * Runs [block] against an engine for one **explicit** choice of provider and model.
     *
     * This is *Transcribe again*: re-running a stored recording with a different model must not
     * silently change which model the app uses from then on. It no longer disposes of anything —
     * [localWhisper] owns the context and evicts rather than duplicating, so the model asked for
     * here becomes the loaded one and the next ordinary dictation pays the load. That is
     * deliberate; see [LocalWhisperOwner].
     */
    suspend fun <T> withEngine(
        provider: SttProvider,
        model: WhisperModel,
        block: suspend (SttEngine) -> T,
    ): T = when (provider) {
        // `remoteEngine` never answers null for a chosen provider — an unconfigured one is a
        // refusal that says which part is missing. The elvis is the compiler's price for a
        // nullable return whose one null is LOCAL, and it refuses in the same words rather than
        // inventing a second answer to the question this function exists to stop duplicating.
        SttProvider.CLOUD, SttProvider.SERVER -> block(
            remoteEngine(provider)
                ?: FailingEngine(
                    AppError.SttNotConfigured(provider.key, AppError.SttNotConfigured.Missing.ADDRESS),
                ),
        )
        // One line, where there used to be a branch. Re-running a stored recording with a model
        // that is not the selected one built a SECOND `WhisperEngine` and closed it in a
        // `finally` — two native contexts at once, up to 1.11 GB, and a blocking free on Main.
        // The owner evicts instead: the re-run costs the next ordinary dictation about two
        // seconds of model load, and the app stops being able to allocate a gigabyte of native
        // memory inside a click handler. The `model == whisperModel()` read that used to decide
        // this sat outside the lock (`I-08`); the decision is now made inside one.
        SttProvider.LOCAL -> localWhisper.use(model, block = block)
    }

    /**
     * Whether a finished dictation goes to the system clipboard. Absent means **on** (`DEC-0012`).
     *
     * A `suspend` read like every other setting, because it is a Keystore decrypt and `DEC-0031`
     * puts the dispatcher in the layer that blocks. The screen reads it once into state rather
     * than at the moment of the copy: a decrypt on the path between "the words are ready" and
     * "they are on the clipboard" is the one place a stall is unforgivable.
     */
    suspend fun copyTranscript(): Boolean =
        withContext(io) { settings.get(SecureSettings.KEY_COPY_TRANSCRIPT) != "false" }

    suspend fun sttLanguage(): String =
        withContext(io) { settings.get(SecureSettings.KEY_STT_LANGUAGE) ?: "auto" }

    /**
     * True when this launch moved an upgrading person onto their old whisper-server. Read once by
     * Settings, which says so: a migration that changes where a person's voice goes must not be
     * silent about it. See [carryOverSettings].
     */
    @Volatile
    var carriedOverServer: Boolean = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        // **Before the mirror starts, and awaited by the first UI read.** The reconcile compares
        // the database with the vault in both directions and heals what it finds; starting the
        // mirror first would let it observe a database the reconcile is still filling, and
        // letting the composition run first lets `dailyNote(today)` create a note for a day the
        // vault already holds one for (`H6`). `NotesViewModel` waits on [reconciled].
        reconciled = vaultReconciler.start(scope)
        scope.launch {
            reconciled?.await()
                ?.onSuccess { summary ->
                    if (summary.imported > 0 || summary.remirrored > 0 || summary.removalsRetried > 0) {
                        Log2.i(
                            "vault.reconciled",
                            "imported" to summary.imported,
                            "remirrored" to summary.remirrored,
                            "removals" to summary.removalsRetried,
                        )
                    }
                }
                ?.onFailure { Log2.e("vault.reconcile.failed", it) }
        }
        vaultMirror.start(scope)

        // Launched, never awaited: `init` runs from `FabricVrApp.onCreate` on the main thread, and
        // the first Keystore use — key generation included — happens inside a synchronized block.
        scope.launch { carriedOverServer = carryOverSettings(settings) }

        // Same reason, and it is why [cuesEnabled] defaults to on rather than to unknown: the
        // read is a Keystore decrypt and the first dictation must not be silent waiting for it.
        scope.launch { refreshFeedbackCues() }

        // The trash exists so Undo can put a recording back; a trash nothing empties is the
        // unbounded voice archive of G-02 with an extra directory. Once per launch is enough:
        // the retention is seven days, so the sweep has no deadline to meet, and a periodic job
        // would be machinery for a deletion that can wait until the app is next opened.
        scope.launch {
            vault.purgeTrash().onSuccess { swept ->
                if (swept > 0) Log2.i("vault.trash.swept", "notes" to swept)
            }
        }

        // The other half of the same problem, and the one nothing addressed at all: a recording
        // that never became a note. See [sweepScratchAudio] for why it has an age threshold rather
        // than sweeping everything it finds.
        //
        // **The outbox is read back first, and its recording is spared** (`REQ-046`). A dictation
        // the last process did not finish committing is restored here; its WAV is younger than
        // the sweep's threshold in every realistic case, but "in every realistic case" is what
        // the threshold already says, and the one file this launch is about to turn into a note
        // must not depend on it twice.
        scope.launch {
            dictationOutbox.restore()
            // Every waiting dictation's recording, not only the first's (`DEC-0095`): the ones
            // queued behind it are drained later and their scratch WAVs may already be a day old.
            sweepScratchAudio(
                File(appContext.filesDir, "audio"),
                spare = dictationOutbox.waitingAudio(),
            )
        }

        // `I-21`. `KeystoreSecureSettings.secretKey()` is `@Synchronized` and generates the key on
        // first use — asking StrongBox first, which most headsets lack, catching the failure and
        // asking again. So the FIRST `get()` blocked its caller for two key generations, and its
        // caller was the person's first tap on a fresh install: the moment the first-run
        // experience is least able to afford it. Warming it here costs nothing anyone is waiting
        // on. `runCatching` because a Keystore that cannot be opened is a real state and it is
        // reported where it is read, not from a warm-up.
        scope.launch { runCatching { settings.get(SecureSettings.KEY_STT_MODEL) } }
    }
}
