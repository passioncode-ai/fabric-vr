// logcat, on a machine that has none. See `shim/jni.h` for why this shim exists at all.
//
// The harness swallows the bridge's diagnostics rather than printing them: a scenario that
// deliberately provokes a failure would otherwise bury its own verdict in the bridge's `LOGE`.
#ifndef FABRICVR_TEST_SHIM_ANDROID_LOG_H
#define FABRICVR_TEST_SHIM_ANDROID_LOG_H

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

#ifdef __cplusplus
extern "C" {
#endif

int __android_log_print(int prio, const char *tag, const char *fmt, ...);
int __android_log_write(int prio, const char *tag, const char *text);

#ifdef __cplusplus
}
#endif

#endif  // FABRICVR_TEST_SHIM_ANDROID_LOG_H
