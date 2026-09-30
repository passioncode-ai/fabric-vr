// A JNI surface just wide enough to compile and RUN `fabricvr_whisper.cpp` on this machine.
//
// **Why a shim and not the NDK's own header.** `scripts/check-native.sh` already compiles the
// bridge against the real `jni.h` from the NDK sysroot — that is what says the file is still a
// translation unit for Android. What nothing could do was *execute* it: the bridge's failure
// modes are a pinned `jfloat*` that is never released and a `std::string::assign(nullptr)`, and
// neither is visible to a compiler. `NativeBridgeMemoryTest` links this shim, a fake `whisper`
// and the REAL bridge into a host binary, provokes each failure and counts the pins. The two
// checks answer different questions and both are needed: this one cannot say the file builds for
// arm64, and that one cannot say a throw releases anything.
//
// Nothing here models the JVM. Every declaration is the narrowest shape the bridge actually
// calls, so a bridge that started using a new JNI function fails to compile here rather than
// passing against a fiction.
#ifndef FABRICVR_TEST_SHIM_JNI_H
#define FABRICVR_TEST_SHIM_JNI_H

#include <cstdint>

typedef int8_t jbyte;
typedef uint8_t jboolean;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef int32_t jsize;

#define JNI_TRUE 1
#define JNI_FALSE 0

// The mode the bridge passes to `ReleaseFloatArrayElements`: discard the copy, the array is
// read-only to us.
#define JNI_ABORT 2

#define JNIEXPORT
#define JNICALL

struct _jobject;
typedef _jobject *jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jarray;
typedef jarray jfloatArray;
typedef jarray jbyteArray;

/**
 * The environment, as member functions, which is the C++ shape the bridge is written against.
 *
 * Defined by the harness, not here: the bodies are what count the pins, and a header that also
 * held the counters would make them per-translation-unit.
 */
struct JNIEnv {
    jsize GetArrayLength(jarray array);

    /** Returns null when the harness is asked to simulate an allocation failure. */
    jfloat *GetFloatArrayElements(jfloatArray array, jboolean *isCopy);
    void ReleaseFloatArrayElements(jfloatArray array, jfloat *elems, jint mode);

    /** Returns null when the harness is asked to simulate an allocation failure. */
    const char *GetStringUTFChars(jstring str, jboolean *isCopy);
    void ReleaseStringUTFChars(jstring str, const char *chars);

    jbyteArray NewByteArray(jsize length);
    void SetByteArrayRegion(jbyteArray array, jsize start, jsize len, const jbyte *buf);
    jstring NewStringUTF(const char *bytes);
};

#endif  // FABRICVR_TEST_SHIM_JNI_H
