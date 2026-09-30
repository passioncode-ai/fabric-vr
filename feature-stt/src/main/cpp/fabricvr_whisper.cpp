// JNI bridge to whisper.cpp for Fabric VR.
//
// The difference from the upstream examples/whisper.android bridge is deliberate and is the
// reason this file exists: upstream's jni.c hardcodes `params.language = "en"`, which makes
// Russian dictation impossible. Here the language arrives from Kotlin and "auto" is passed
// through as the auto-detect value whisper.h documents.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "FabricVR-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

/**
 * Per-context run state, read by whisper's own threads and written by the JVM's.
 *
 * **Nothing the callbacks touch calls into JNI, and that is the whole design.** `abort_callback`
 * fires before every ggml computation and `progress_callback` once per segment; both run on
 * whisper's worker threads, which are not attached to the JVM. Attaching a thread per invocation
 * would cost more than the work being cancelled, and a callback that can throw across the JNI
 * boundary calls `std::terminate`. An atomic load and no calls out is the version that cannot take
 * the app down — which is why `abort_now` exists beside `cancelled_runs` rather than the callback
 * taking a lock and searching a set on every ggml op.
 *
 * Kotlin asks for an id with `beginRun()`, names it in `cancel()` and `transcribe()`, and reads
 * `progress` by polling — the same exchange in the other direction, needing no thread attachment
 * either.
 *
 * **Why a run has an identity at all** (`B-181`). This was one `cancelled` flag per context, and
 * `transcribe` cleared it as its first act so that a cancel landing *between* two runs could not
 * abort the next one — a real property, and `I-26` is what breaking it looks like. But
 * `invokeOnCancellation` is registered on the Kotlin side before the blocking call is submitted,
 * so a cancel could also land in the window *before* the run's own first act: it set a flag that
 * same run then wiped, and `whisper_full` ran to completion with nobody waiting for it. Naming the
 * run keeps the first property by construction — a flag set for run N is not run N+1's to clear —
 * and stops losing the second case. The clearing rule is gone with it.
 */
struct RunState {
    /** Issues run ids. Monotonic; 0 is never issued and means "no run". */
    std::atomic<long long> next_run{0};

    /** The run `whisper_full` is executing, or 0. Written under `g_runs_mutex`. */
    long long active_run{0};

    /**
     * What `abort_callback` reads, and the only field it reads.
     *
     * True exactly while the active run is one somebody has cancelled. Set at a run's entry from
     * `cancelled_runs`, and by `cancel` when it names the active run.
     */
    std::atomic<bool> abort_now{false};

    /**
     * Runs somebody asked to stop, whether or not they ever started. Guarded by `g_runs_mutex`.
     *
     * It is a set rather than a single slot because a cancel can arrive for a run that has not
     * started, and because `wasCancelled` is asked about a run that has already ended — one slot
     * answers the second of those wrongly as soon as anything else is cancelled.
     *
     * **Bounded, and the bound is stated because an unbounded one would be a slow leak.** An entry
     * is erased when its run starts; an entry for a run that never starts would otherwise live
     * until `freeContext`. Past `kMaxPendingCancels` the oldest is dropped, which can only ever
     * lose a cancel for a run that has not started after that many others also did not — on a
     * context whose runs are serialised, that is not a state this app can reach, and losing the
     * oldest is a better failure than growing without limit inside a speech engine.
     */
    std::set<long long> cancelled_runs;

    std::atomic<int> progress{0};
};

constexpr std::size_t kMaxPendingCancels = 64;

std::mutex g_runs_mutex;
std::map<jlong, std::shared_ptr<RunState>> g_runs;

/** For `transcribe`, which owns the run: creates the entry when it is absent. */
std::shared_ptr<RunState> run_state_for(jlong ptr) {
    std::lock_guard<std::mutex> guard(g_runs_mutex);
    auto it = g_runs.find(ptr);
    if (it != g_runs.end()) {
        return it->second;
    }
    auto state = std::make_shared<RunState>();
    g_runs[ptr] = state;
    return state;
}

/**
 * For the three query entry points, which must never create one.
 *
 * `cancel`, `progress` and `wasCancelled` used the inserting form, and all three are reachable
 * with a handle `freeContext` has already released — `WhisperEngine` asks `wasCancelled` about a
 * pointer captured before its guard, and `invokeOnCancellation` can fire `cancel` on the same
 * stale value. Each such call **resurrected** a map node keyed by a dead address that nothing
 * erases again, so `forget_run_state`'s own comment — that erasing stops a later cancel
 * resurrecting an entry — was false of the function that does the resurrecting. Small per
 * occurrence and unbounded in principle, and it made the key space addresses rather than live
 * contexts, so a reused address could answer about a previous context.
 */
std::shared_ptr<RunState> run_state_of(jlong ptr) {
    std::lock_guard<std::mutex> guard(g_runs_mutex);
    auto it = g_runs.find(ptr);
    return it == g_runs.end() ? nullptr : it->second;
}

void forget_run_state(jlong ptr) {
    std::lock_guard<std::mutex> guard(g_runs_mutex);
    g_runs.erase(ptr);
}

/**
 * Claims [run] as the active one and says whether it has already been cancelled (`B-181`).
 *
 * This is what replaced *clear the flag, whatever it was for*. It asks the one question that rule
 * was a blunt answer to — **is a cancel meant for me?** — so a cancel that arrived for this run
 * between the Kotlin registration and here is honoured, while one that arrived for the run before
 * it is not this run's to obey.
 */
bool begin_active_run(const std::shared_ptr<RunState> &state, jlong run) {
    std::lock_guard<std::mutex> guard(g_runs_mutex);
    state->active_run = run;
    // **Read, not erased.** `wasCancelled` is asked about this run AFTER it returns, and it is
    // what decides between *you stopped it* and *your dictation is gone* — so a cancel that
    // arrived before the run started has to still be findable when the run is over. The entries
    // go when the context does, or when the cap below pushes them out.
    const bool cancelled = state->cancelled_runs.count(run) > 0;
    state->abort_now.store(cancelled, std::memory_order_relaxed);
    return cancelled;
}

/** Whether somebody asked [run] to stop — the record `wasCancelled` and `transcribe` both read. */
bool was_cancelled(const std::shared_ptr<RunState> &state, jlong run) {
    std::lock_guard<std::mutex> guard(g_runs_mutex);
    return state->cancelled_runs.count(run) > 0;
}

/**
 * Releases the active slot.
 *
 * After this, a cancel naming any run finds nothing executing and sets no abort flag — which is
 * the property the old clear-at-entry rule was protecting, now held at the run's end where it
 * costs nothing rather than at the next run's start where it lost cancels.
 */
void end_active_run(const std::shared_ptr<RunState> &state, jlong run) {
    std::lock_guard<std::mutex> guard(g_runs_mutex);
    if (state->active_run == run) {
        state->active_run = 0;
        state->abort_now.store(false, std::memory_order_relaxed);
    }
}

/**
 * The audio array's pin, released however this scope is left (`B-216`'s neighbour in the
 * `2026-09-22` audit).
 *
 * **`GetFloatArrayElements` was released at exactly two call sites and neither of them was on the
 * way out of a throw.** Between the pin and those sites sit `GetStringUTFChars` plus a
 * `std::string::assign`, a `lock_guard` — which throws `system_error` rather than blocking for
 * ever — and `whisper_full` itself, in a library compiled with `-fexceptions`. The `catch (...)`
 * at the bottom of `transcribe` is *outside* the release path, so every one of those throws
 * returned `nullptr` to Kotlin with the array still pinned: for a ten-minute dictation that is
 * megabytes the JVM can neither move nor collect, for the life of the process.
 *
 * A destructor is the only construct that survives all of them at once, which is why this is a
 * type and not three more `Release` calls. The counting proof is `bridge_harness.cpp`, which
 * provokes the throw and compares pins against releases.
 */
class PinnedFloats {
public:
    PinnedFloats(JNIEnv *env, jfloatArray array)
        : env_(env), array_(array), data_(env->GetFloatArrayElements(array, nullptr)) {}

    ~PinnedFloats() {
        // `JNI_ABORT`: the samples are read-only to us, so there is nothing to copy back.
        // Nothing here throws — these are JNI calls, not C++ ones — but a destructor that did
        // would call `std::terminate`, so the guard is stated rather than assumed.
        if (data_ != nullptr) {
            env_->ReleaseFloatArrayElements(array_, data_, JNI_ABORT);
        }
    }

    PinnedFloats(const PinnedFloats &) = delete;
    PinnedFloats &operator=(const PinnedFloats &) = delete;

    /** Null when the JVM could not give us the array — out of memory, and the caller must stop. */
    jfloat *get() const { return data_; }

private:
    JNIEnv *env_;
    jfloatArray array_;
    jfloat *data_;
};

/**
 * The active-run slot, claimed and released the same way, and for the same reason.
 *
 * `end_active_run` sat after `whisper_full` and was skipped by every throw that skipped the
 * release above. The leak it left is smaller and self-healing — the next run's
 * `begin_active_run` overwrites both fields — but "self-healing by the next run" is a property
 * nobody stated and the next run may be minutes away. Paired with its claim, it is simply true.
 */
class ActiveRun {
public:
    ActiveRun(std::shared_ptr<RunState> state, jlong run)
        : state_(std::move(state)), run_(run), cancelled_(begin_active_run(state_, run_)) {}

    ~ActiveRun() {
        // `end_active_run` takes a mutex, and `lock` can throw. In a destructor that means
        // `std::terminate` — the whole app, for a bookkeeping failure that cannot actually
        // happen on a correctly used `std::mutex`. Swallowed deliberately, and only here.
        try {
            end_active_run(state_, run_);
        } catch (...) {
        }
    }

    ActiveRun(const ActiveRun &) = delete;
    ActiveRun &operator=(const ActiveRun &) = delete;

    /** Whether a cancel naming this run had already arrived when it claimed the slot. */
    bool cancelled_before_start() const { return cancelled_; }

private:
    std::shared_ptr<RunState> state_;
    jlong run_;
    bool cancelled_;
};

/**
 * A `shared_ptr` is handed to the callbacks as raw `user_data`, and the caller holds its own copy
 * for the duration of `whisper_full` — so the state cannot be freed under a worker thread even if
 * `freeContext` arrives from another thread mid-run.
 */
bool should_abort(void *user_data) {
    auto *state = static_cast<RunState *>(user_data);
    return state != nullptr && state->abort_now.load(std::memory_order_relaxed);
}

void on_progress(struct whisper_context * /*ctx*/, struct whisper_state * /*st*/,
                 int progress, void *user_data) {
    auto *state = static_cast<RunState *>(user_data);
    if (state != nullptr) {
        state->progress.store(progress, std::memory_order_relaxed);
    }
}

/**
 * whisper's and ggml's own diagnostics, into logcat (`B-191`).
 *
 * Without this they go to **stderr**, which on Android goes nowhere a person or an operator can
 * read: when `whisper_init_from_file_with_params` refuses a model, the reason it printed —
 * a bad magic number, a tensor the build cannot handle, an allocation that failed — was lost,
 * and the Kotlin side saw only a zero. That is the other half of why a corrupt model was
 * undiagnosable.
 *
 * **WARN and above only, and that bound is the privacy rule rather than a volume one.** The
 * loader announces itself at INFO with the model's full path (`loading model from '…'`), and the
 * store's root is a constructor argument — a directory a person's name can be in. Errors and
 * warnings are what a failure needs; the guided tour of a successful load is not. It also keeps
 * the callback what it has to be: a comparison and one `__android_log_write`, on whatever thread
 * ggml happens to be on, with no JNI in it — the same rule the two run callbacks above obey,
 * for the same reason.
 */
void forward_log(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr || level < GGML_LOG_LEVEL_WARN) {
        return;
    }
    __android_log_write(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                        TAG, text);
}

std::once_flag g_log_once;

/** The last path component. The directory above it belongs to whoever configured the store. */
const char *file_name_of(const char *path) {
    const char *slash = std::strrchr(path, '/');
    return (slash != nullptr && slash[1] != '\0') ? slash + 1 : path;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_initContext(
        JNIEnv *env, jobject /*thiz*/, jstring model_path) {
    try {
        // Once per process, before the first load, because the loader's own explanation of a
        // refusal is the thing that was going to stderr and vanishing (`B-191`).
        std::call_once(g_log_once, [] { whisper_log_set(forward_log, nullptr); });

        const char *path = env->GetStringUTFChars(model_path, nullptr);
        if (path == nullptr) {
            return 0;
        }
        whisper_context_params cparams = whisper_context_default_params();
        cparams.use_gpu = false;
        whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
        if (ctx == nullptr) {
            // The FILE NAME, not the path: the directory is the store's, and a store can be
            // rooted anywhere — including somewhere a person's own name is.
            LOGE("failed to init context from %s", file_name_of(path));
        }
        env->ReleaseStringUTFChars(model_path, path);
        return reinterpret_cast<jlong>(ctx);
    } catch (const std::exception &e) {
        LOGE("initContext threw: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("initContext threw");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_freeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    // The run state goes with the context. It is a `shared_ptr` and `transcribe` holds its own
    // copy for the duration of the call, so erasing the map entry here cannot free it under a
    // worker thread mid-run — it only stops the next `cancel` resurrecting an entry for a
    // pointer that no longer exists.
    forget_run_state(ptr);
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

// Returns the transcript as UTF-8 bytes, or nullptr on any failure — which the Kotlin side maps to
// AppError.SttFailed. Bytes rather than a jstring because NewStringUTF expects *modified* UTF-8 and
// whisper can legitimately produce a four-byte codepoint.
//
// The whole body is guarded: this library is built with -fexceptions, and a C++ exception crossing
// the JNI boundary calls std::terminate, which would take the app down instead of failing a note.
/**
 * Reserves an identity for one transcription (`B-181`).
 *
 * Kotlin calls this before it registers `invokeOnCancellation`, so every cancel that can possibly
 * be asked for names a run that already exists. Monotonic per context; 0 is never issued.
 */
JNIEXPORT jlong JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_beginRun(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    auto state = run_state_for(ptr);
    return static_cast<jlong>(state->next_run.fetch_add(1, std::memory_order_relaxed) + 1);
}

JNIEXPORT jbyteArray JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_transcribe(
        JNIEnv *env, jobject /*thiz*/, jlong ptr, jlong run, jfloatArray audio,
        jint threads, jstring language, jint beam_size) {
  try {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr || audio == nullptr) {
        return nullptr;
    }

    const jsize n = env->GetArrayLength(audio);

    // **A scope, so the pin and the run slot go back at exactly the point the hand-written
    // releases used to run, and also on every path that used to skip them.** Everything inside
    // can throw — `std::string::assign`, `std::lock_guard`, `whisper_full` in a library built
    // with `-fexceptions` — and the `catch (...)` at the bottom of this function sits outside
    // every `Release` call there was, so a throw returned `nullptr` to Kotlin with the audio
    // still pinned in the JVM heap for the life of the process. `bridge_harness.cpp` provokes
    // that throw and counts the pins against the releases.
    {
        // **Owned, not released by hand** — see [PinnedFloats].
        PinnedFloats pinned(env, audio);
        if (pinned.get() == nullptr) {
            // Out of memory while copying the array: whisper_full would dereference null.
            LOGE("could not access the audio array");
            return nullptr;
        }

        // Beam search is the accuracy baseline whisper was published with; greedy is the speed
        // compromise. A short dictation is exactly where the difference shows, because there is no
        // surrounding context for a wrong word to be corrected by.
        const bool beam = beam_size > 1;
        whisper_full_params params = whisper_full_default_params(
                beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
        if (beam) {
            params.beam_search.beam_size = beam_size;
        }

        params.translate        = false;
        // Each dictation is its own utterance. Carrying the previous one as context is how whisper
        // starts completing a sentence nobody said.
        params.no_context       = true;
        params.single_segment   = false;
        params.print_realtime   = false;
        params.print_progress   = false;
        params.print_timestamps = false;
        params.print_special    = false;
        params.n_threads        = threads;

        // Suppress the non-speech tokens whisper otherwise emits for room noise — "(музыка)",
        // "[BLANK_AUDIO]" and their kin, which arrive as text a person then has to delete.
        params.suppress_blank   = true;
        params.suppress_nst     = true;

        // The temperature ladder: decode at 0, and only if the result looks degenerate — high entropy
        // or low average log probability — try again warmer. Without it a single bad decode is final,
        // and a bad decode on a short phrase is the whole note.
        params.temperature      = 0.0f;
        params.temperature_inc  = 0.2f;
        params.entropy_thold    = 2.4f;
        params.logprob_thold    = -1.0f;
        params.no_speech_thold  = 0.6f;

        std::string lang;
        if (language != nullptr) {
            const char *l = env->GetStringUTFChars(language, nullptr);
            if (l == nullptr) {
                // **`initContext` checks this and this did not** (audit `2026-09-22`). JNI returns
                // null when the JVM cannot allocate the copy, and `std::string::assign(nullptr)` is
                // `strlen(nullptr)` one call down — undefined behaviour inside the speech engine,
                // with the microphone's output pinned beside it. An `OutOfMemoryError` is pending in
                // the JVM either way; returning lets it surface as the failure it is rather than as
                // a segmentation fault, and `PinnedFloats` gives the array back on the way out.
                LOGE("could not read the language argument");
                return nullptr;
            }
            lang.assign(l);
            env->ReleaseStringUTFChars(language, l);
        }
        // whisper.h: "for auto-detection, set to nullptr, "" or "auto""
        params.language        = lang.empty() ? "auto" : lang.c_str();
        params.detect_language = false;

        // **The two callbacks that make a run answerable** (`B-147`, `B-118`, `B-146`).
        //
        // Without `abort_callback`, `whisper_full` is a blocking call no coroutine cancellation can
        // interrupt: *Transcribing…* was a state a person could only wait out, and on a ten-minute
        // dictation against a thermally limited headset that is minutes. It also made a model switch
        // queue behind the run it could not stop.
        //
        // The state is fetched and **held** for the whole call, so a `freeContext` arriving from
        // another thread cannot free it under a worker.
        //
        // **`begin_active_run` replaced *clear the flag* here** (`B-181`). The clear protected a real
        // property — a cancel landing between two runs must not abort the next one — and paid for it
        // by wiping a cancel that had just been set for THIS run, in the window between Kotlin
        // registering `invokeOnCancellation` and this line. Asking whether this run was cancelled
        // answers the same question without the loss, and the answer is taken once, under the lock.
        auto state = run_state_for(ptr);
        // Claimed by [ActiveRun], so the slot is released however this function is left — including
        // by a throw out of `whisper_full`, which used to skip `end_active_run` entirely.
        ActiveRun active(state, run);
        state->progress.store(0, std::memory_order_relaxed);
        params.abort_callback             = should_abort;
        params.abort_callback_user_data   = state.get();
        params.progress_callback          = on_progress;
        params.progress_callback_user_data = state.get();

        if (active.cancelled_before_start()) {
            // Nobody is waiting for this any more, and `whisper_full` on a ten-minute dictation is
            // minutes of a thermally limited headset. `wasCancelled` will say why it stopped.
            LOGI("transcription cancelled before it started");
            return nullptr;
        }

        const int rc = whisper_full(ctx, params, pinned.get(), n);

        // **100 is stored here, not by the callback.** whisper computes `progress_cur` at the TOP of
        // its main loop and returns after the last chunk without calling back again, so the highest
        // value the callback ever writes is one chunk short of the end — and both KDocs said 0..100.
        // A determinate bar would have parked below full for every successful run.
        if (rc == 0) {
            state->progress.store(100, std::memory_order_relaxed);
        }

        if (rc != 0) {
            // **A cancelled run is not a failed one**, and the difference reaches the person: one is
            // "you stopped it", the other is "your dictation is gone". `whisper_full` reports both as
            // a non-zero return, so the record of what was cancelled is what tells them apart.
            // `wasCancelled` reads the same record, about the same run.
            if (was_cancelled(state, run)) {
                LOGI("transcription cancelled at %d%%", state->progress.load(std::memory_order_relaxed));
            } else {
                LOGE("whisper_full failed: %d", rc);
            }
            return nullptr;
        }
    }  // the pin and the active-run slot go back here, where the hand-written releases were

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) {
            text.append(seg);
        }
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    return out;
  } catch (const std::exception &e) {
    LOGE("transcribe threw: %s", e.what());
    return nullptr;
  } catch (...) {
    LOGE("transcribe threw");
    return nullptr;
  }
}

/**
 * Ask [run] to stop. Safe from any thread, and safe before it starts or after it ends (`B-181`).
 *
 * **It names the run, which is what makes both rules hold at once.** A cancel for the run that is
 * about to start is remembered and honoured at its entry — that is the window the old
 * clear-at-entry rule silently swallowed. A cancel for a run that has already ended sets no abort
 * flag, because nothing is active, so the next run is untouched: the property the clear existed
 * for, kept by construction.
 */
JNIEXPORT void JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_cancel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr, jlong run) {
    auto state = run_state_of(ptr);
    if (state == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(g_runs_mutex);
    state->cancelled_runs.insert(run);
    // Bounded. Past the cap the oldest id goes — never the one executing, because losing that
    // would turn a person's Cancel into a run that keeps going.
    while (state->cancelled_runs.size() > kMaxPendingCancels) {
        auto oldest = state->cancelled_runs.begin();
        if (*oldest == state->active_run) {
            ++oldest;
        }
        if (oldest == state->cancelled_runs.end()) {
            break;
        }
        state->cancelled_runs.erase(oldest);
    }
    if (state->active_run == run) {
        state->abort_now.store(true, std::memory_order_relaxed);
    }
}

/** 0..100 for the run in progress, or for the last one that finished. */
JNIEXPORT jint JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_progress(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    auto state = run_state_of(ptr);
    return state ? state->progress.load(std::memory_order_relaxed) : 0;
}

/** Whether [run] ended because somebody asked it to, rather than because it broke. */
JNIEXPORT jboolean JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_wasCancelled(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr, jlong run) {
    auto state = run_state_of(ptr);
    return (state && was_cancelled(state, run)) ? JNI_TRUE : JNI_FALSE;
}

// The language whisper detected for the last run ("ru", "en", ...); empty when unknown.
JNIEXPORT jstring JNICALL
Java_ai_passioncode_fabricvr_stt_WhisperNative_detectedLanguage(
        JNIEnv *env, jobject /*thiz*/, jlong ptr) {
  try {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }
    const int id = whisper_full_lang_id(ctx);
    // A language tag is ASCII, so NewStringUTF is safe here in a way it is not for a transcript.
    const char *lang = (id >= 0) ? whisper_lang_str(id) : "";
    return env->NewStringUTF(lang == nullptr ? "" : lang);
  } catch (...) {
    return env->NewStringUTF("");
  }
}

}  // extern "C"
