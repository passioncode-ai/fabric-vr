// Runs the REAL `fabricvr_whisper.cpp` on this machine and counts what it pins and releases.
//
// **Why this exists.** The bridge's two defects in the `2026-09-22` audit are invisible to every
// check the project had. `scripts/check-native.sh` compiles the file with `-fsyntax-only`, which
// says it is a translation unit and nothing else; `WhisperEngineCancellationTest` drives the
// KOTLIN contract through `WhisperNativeCalls`, and that seam exists precisely because the native
// call cannot be made from a JVM test. So *"the `GetFloatArrayElements` pin leaks on every throw"*
// and *"`GetStringUTFChars` is not null-checked, so an OOM gives `std::string::assign(nullptr)`"*
// were code reads with nothing able to convict them.
//
// This links the bridge against `shim/jni.h`, `shim/android/log.h` and the fakes below, so every
// one of those paths can be provoked and *counted*. It is driven by `NativeBridgeMemoryTest`,
// which compiles and runs it; run by hand it prints one line per scenario and exits non-zero on
// the first failure.
//
// **What it deliberately cannot say:** that the file builds for arm64 (that is `check-native.sh`)
// or that whisper.cpp does what this fake does. The fakes here are the smallest thing that makes
// the bridge's own control flow reachable, and nothing more.
#include <jni.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "whisper.h"

// ---------------------------------------------------------------------------
// The JVM, as far as the bridge can tell
// ---------------------------------------------------------------------------

namespace harness {

/** Every `GetFloatArrayElements` and every `ReleaseFloatArrayElements`. Equal or the pin leaked. */
int float_pins = 0;
int float_releases = 0;

/** The same pair for `GetStringUTFChars`. */
int string_pins = 0;
int string_releases = 0;

/** Make the next `GetFloatArrayElements` fail, the way an out-of-memory JVM would. */
bool fail_float_pin = false;

/** Make the next `GetStringUTFChars` fail. This is the input `initContext` checks and the
 *  transcribe path did not. */
bool fail_string_pin = false;

/** What the fake `whisper_full` does when it is reached. */
enum class Run { OK, FAILS, THROWS };
Run run_behaviour = Run::OK;

/** Whether the fake `whisper_full` was reached at all — the abort path must not reach it. */
bool run_entered = false;

/**
 * Zeroes the counters and nothing else.
 *
 * Called AFTER the context is created, because `initContext` reads the model path through the
 * same `GetStringUTFChars` — counting that would make every string assertion below off by one and
 * the failure injectors fire on the wrong call.
 */
void zero_counts() {
    float_pins = 0;
    float_releases = 0;
    string_pins = 0;
    string_releases = 0;
}

void reset() {
    zero_counts();
    fail_float_pin = false;
    fail_string_pin = false;
    run_behaviour = Run::OK;
    run_entered = false;
}

struct FloatArray {
    std::vector<float> data;
};

struct Utf8 {
    std::string text;
};

struct Bytes {
    std::vector<jbyte> data;
};

}  // namespace harness

jsize JNIEnv::GetArrayLength(jarray array) {
    return static_cast<jsize>(reinterpret_cast<harness::FloatArray *>(array)->data.size());
}

jfloat *JNIEnv::GetFloatArrayElements(jfloatArray array, jboolean * /*isCopy*/) {
    if (harness::fail_float_pin) {
        harness::fail_float_pin = false;
        return nullptr;
    }
    ++harness::float_pins;
    return reinterpret_cast<harness::FloatArray *>(array)->data.data();
}

void JNIEnv::ReleaseFloatArrayElements(jfloatArray /*array*/, jfloat * /*elems*/, jint /*mode*/) {
    ++harness::float_releases;
}

const char *JNIEnv::GetStringUTFChars(jstring str, jboolean * /*isCopy*/) {
    if (harness::fail_string_pin) {
        harness::fail_string_pin = false;
        return nullptr;
    }
    ++harness::string_pins;
    return reinterpret_cast<harness::Utf8 *>(str)->text.c_str();
}

void JNIEnv::ReleaseStringUTFChars(jstring /*str*/, const char * /*chars*/) {
    ++harness::string_releases;
}

jbyteArray JNIEnv::NewByteArray(jsize length) {
    auto *bytes = new harness::Bytes();
    bytes->data.resize(static_cast<std::size_t>(length));
    return reinterpret_cast<jbyteArray>(bytes);
}

void JNIEnv::SetByteArrayRegion(jbyteArray array, jsize start, jsize len, const jbyte *buf) {
    auto *bytes = reinterpret_cast<harness::Bytes *>(array);
    std::memcpy(bytes->data.data() + start, buf, static_cast<std::size_t>(len));
}

jstring JNIEnv::NewStringUTF(const char *chars) {
    auto *utf8 = new harness::Utf8();
    utf8->text = (chars == nullptr) ? "" : chars;
    return reinterpret_cast<jstring>(utf8);
}

extern "C" int __android_log_print(int, const char *, const char *, ...) { return 0; }
extern "C" int __android_log_write(int, const char *, const char *) { return 0; }

// ---------------------------------------------------------------------------
// whisper.cpp, as far as the bridge can tell
// ---------------------------------------------------------------------------

struct whisper_context {
    int unused;
};

namespace {
whisper_context g_context{};
}  // namespace

extern "C" {

void whisper_log_set(ggml_log_callback, void *) {}

struct whisper_context_params whisper_context_default_params(void) {
    whisper_context_params params{};
    return params;
}

struct whisper_context *whisper_init_from_file_with_params(const char *, struct whisper_context_params) {
    return &g_context;
}

void whisper_free(struct whisper_context *) {}

struct whisper_full_params whisper_full_default_params(enum whisper_sampling_strategy) {
    whisper_full_params params{};
    return params;
}

int whisper_full(struct whisper_context *, struct whisper_full_params, const float *, int) {
    harness::run_entered = true;
    switch (harness::run_behaviour) {
        case harness::Run::OK:
            return 0;
        case harness::Run::FAILS:
            return -1;
        case harness::Run::THROWS:
            // **The case the audit is about.** whisper.cpp is built with `-fexceptions` and this
            // stands in for anything at all that can throw out of a run — an allocation inside
            // ggml, a `std::bad_alloc` from the decoder's own buffers. What it stands in for does
            // not matter to the bridge; that the bridge must not leak the pin does.
            throw std::runtime_error("whisper_full threw");
    }
    return 0;
}

int whisper_full_n_segments(struct whisper_context *) { return 1; }

const char *whisper_full_get_segment_text(struct whisper_context *, int) { return "готово"; }

int whisper_full_lang_id(struct whisper_context *) { return 0; }

const char *whisper_lang_str(int) { return "en"; }

// The bridge's own entry points, as the JVM would call them.
jlong Java_ai_passioncode_fabricvr_stt_WhisperNative_initContext(JNIEnv *, jobject, jstring);
void Java_ai_passioncode_fabricvr_stt_WhisperNative_freeContext(JNIEnv *, jobject, jlong);
jlong Java_ai_passioncode_fabricvr_stt_WhisperNative_beginRun(JNIEnv *, jobject, jlong);
jbyteArray Java_ai_passioncode_fabricvr_stt_WhisperNative_transcribe(
        JNIEnv *, jobject, jlong, jlong, jfloatArray, jint, jstring, jint);
void Java_ai_passioncode_fabricvr_stt_WhisperNative_cancel(JNIEnv *, jobject, jlong, jlong);
jboolean Java_ai_passioncode_fabricvr_stt_WhisperNative_wasCancelled(JNIEnv *, jobject, jlong, jlong);
jstring Java_ai_passioncode_fabricvr_stt_WhisperNative_detectedLanguage(JNIEnv *, jobject, jlong);

}  // extern "C"

// ---------------------------------------------------------------------------
// The scenarios
// ---------------------------------------------------------------------------

namespace {

int g_failures = 0;

void check(bool condition, const char *what) {
    if (condition) {
        std::printf("ok: %s\n", what);
    } else {
        std::printf("FAIL: %s\n", what);
        ++g_failures;
    }
}

/** Equal counts, or a `jfloat*` is still pinned in a JVM heap nothing will ever unpin. */
void checkPinsBalance(const char *what) {
    if (harness::float_pins == harness::float_releases) {
        std::printf("ok: %s (pinned %d, released %d)\n", what, harness::float_pins, harness::float_releases);
    } else {
        std::printf("FAIL: %s — pinned %d, released %d\n", what, harness::float_pins, harness::float_releases);
        ++g_failures;
    }
}

JNIEnv g_env;
harness::FloatArray g_audio{std::vector<float>(1600, 0.1f)};
harness::Utf8 g_language{"ru"};

/**
 * A context and a run id, with the counters zeroed afterwards.
 *
 * Every scenario starts here, so what the assertions below count is one `transcribe` call and
 * never the set-up around it.
 */
struct Fresh {
    jlong ctx;
    jlong run;
};

Fresh fresh() {
    harness::reset();
    harness::Utf8 path{"/models/ggml-small.bin"};
    const jlong ctx = Java_ai_passioncode_fabricvr_stt_WhisperNative_initContext(
            &g_env, nullptr, reinterpret_cast<jstring>(&path));
    const jlong run = Java_ai_passioncode_fabricvr_stt_WhisperNative_beginRun(&g_env, nullptr, ctx);
    harness::zero_counts();
    return Fresh{ctx, run};
}

void release(jlong ctx) {
    Java_ai_passioncode_fabricvr_stt_WhisperNative_freeContext(&g_env, nullptr, ctx);
}

jbyteArray transcribe(jlong ctx, jlong run) {
    return Java_ai_passioncode_fabricvr_stt_WhisperNative_transcribe(
            &g_env, nullptr, ctx, run,
            reinterpret_cast<jfloatArray>(&g_audio), 4,
            reinterpret_cast<jstring>(&g_language), 5);
}

}  // namespace


int main() {
    // **Unbuffered, and it is not a nicety.** One scenario here provokes an undefined-behaviour
    // path that, before the fix, does not fail a check — it kills the process. A buffered stdout
    // loses everything printed before the signal, so the report would be a bare exit code and the
    // reader would have to guess which scenario died.
    std::setvbuf(stdout, nullptr, _IONBF, 0);

    // ---- a run that succeeds gives every pin back ----------------------------------------
    {
        const Fresh f = fresh();
        const jbyteArray out = transcribe(f.ctx, f.run);
        check(out != nullptr, "a successful run returns a transcript");
        checkPinsBalance("a successful run releases the audio array");
        check(harness::string_pins == 1, "the language argument was never read at all");
        check(harness::string_pins == harness::string_releases,
              "a successful run releases the language string");
        release(f.ctx);
    }

    // ---- a run whisper reports as failed gives every pin back ----------------------------
    {
        const Fresh f = fresh();
        harness::run_behaviour = harness::Run::FAILS;
        const jbyteArray out = transcribe(f.ctx, f.run);
        check(out == nullptr, "a failed run returns no transcript");
        checkPinsBalance("a failed run releases the audio array");
        check(harness::string_pins == harness::string_releases,
              "a failed run releases the language string");
        release(f.ctx);
    }

    // ---- **the audit's case (a)**: a throw between the pin and the release ----------------
    //
    // `GetFloatArrayElements` was released at two explicit call sites and at neither of them on
    // the way out of `catch (...)`, and between the pin and those sites sit a `std::string`
    // assignment, a `lock_guard` and `whisper_full` itself — three things that can throw in a
    // library compiled with `-fexceptions`. Every such throw leaked the pin, and a leaked pin on
    // a ten-minute dictation is megabytes the JVM heap cannot move or collect.
    {
        const Fresh f = fresh();
        harness::run_behaviour = harness::Run::THROWS;
        const jbyteArray out = transcribe(f.ctx, f.run);
        check(harness::run_entered, "the throwing run was never reached, so this proves nothing");
        check(out == nullptr, "a throwing run returns no transcript");
        checkPinsBalance("a run that throws releases the audio array");
        check(harness::string_pins == harness::string_releases,
              "a run that throws releases the language string");
        release(f.ctx);
    }

    // ---- a cancel that lands before the run starts gives the pin back ---------------------
    {
        const Fresh f = fresh();
        Java_ai_passioncode_fabricvr_stt_WhisperNative_cancel(&g_env, nullptr, f.ctx, f.run);
        const jbyteArray out = transcribe(f.ctx, f.run);
        check(out == nullptr, "a run cancelled before it started returns no transcript");
        check(!harness::run_entered, "a cancelled run still called whisper_full");
        check(Java_ai_passioncode_fabricvr_stt_WhisperNative_wasCancelled(&g_env, nullptr, f.ctx, f.run) == JNI_TRUE,
              "a cancelled run is not recorded as cancelled");
        checkPinsBalance("a run cancelled before it started releases the audio array");
        release(f.ctx);
    }

    // ---- **the audit's case (b)**: `GetStringUTFChars` returning null ---------------------
    //
    // `initContext` checks this and the transcribe path did not, so an out-of-memory JVM handed
    // `nullptr` to `std::string::assign` — which is `strlen(nullptr)` one call down, inside the
    // speech engine, with the microphone's output in a pinned array. This scenario is where the
    // harness earns its keep: BEFORE the fix the process does not fail this check, it **dies**,
    // and `NativeBridgeMemoryTest` reads that as the red it is.
    {
        const Fresh f = fresh();
        harness::fail_string_pin = true;
        const jbyteArray out = transcribe(f.ctx, f.run);
        check(out == nullptr, "a language string that could not be read still produced a transcript");
        check(!harness::run_entered, "a run went ahead after the language could not be read");
        checkPinsBalance("a language string that could not be read still releases the audio array");
        check(harness::float_pins == 1, "the audio array was never pinned, so the balance above is vacuous");
        release(f.ctx);
    }

    // ---- the audio array itself failing to pin -------------------------------------------
    {
        const Fresh f = fresh();
        harness::fail_float_pin = true;
        const jbyteArray out = transcribe(f.ctx, f.run);
        check(out == nullptr, "an unpinnable audio array still produced a transcript");
        check(!harness::run_entered, "a run went ahead with no audio to run on");
        check(harness::float_releases == 0, "a pin that never happened was released anyway");
        release(f.ctx);
    }

    if (g_failures == 0) {
        std::printf("ALL BRIDGE SCENARIOS GREEN\n");
        return 0;
    }
    std::printf("%d bridge scenario(s) failed\n", g_failures);
    return 1;
}
