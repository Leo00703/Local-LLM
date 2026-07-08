// Tiny JNI shim: force a shared library into the linker's GLOBAL symbol group.
//
// The LiteRT-LM Maven AAR (0.13.1) does NOT bundle the GPU TopK samplers, so we
// hand-bundle libLiteRtTopKOpenClSampler.so in jniLibs. But that sampler has
// ~166 UNDEFINED LiteRt* symbols (led by LiteRtCreateEnvironment) and does NOT
// list libLiteRt.so in its DT_NEEDED. The runtime dlopens the sampler with
// RTLD_LOCAL, and libLiteRt.so is only pulled in as a DT_NEEDED of the AAR's
// liblitertlm_jni.so via System.loadLibrary (which is RTLD_LOCAL), so it lands
// in that JNI lib's LOCAL group, never the namespace GLOBAL group. The sampler
// therefore cannot resolve its symbols and the runtime silently falls back to
// CPU sampling, which makes multi-token prediction a net decode regression.
//
// Fix (LiteRT-LM issue #2211, option 3): dlopen libLiteRt.so with RTLD_GLOBAL
// BEFORE the runtime loads the sampler. That promotes libLiteRt.so into the
// app classloader namespace's global group, so the sampler's later dlopen can
// resolve every LiteRt* symbol against it. No binary surgery (patchelf would
// corrupt the GNU hash table and get rejected by the Android linker).

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdio>

#define LOG_TAG "LiteRtPreload"

extern "C" JNIEXPORT jstring JNICALL
Java_com_druk_lmplayground_litert_LiteRtEngine_nativePreloadGlobal(
        JNIEnv* env, jobject /*thiz*/, jstring jname) {
    const char* name = env->GetStringUTFChars(jname, nullptr);

    void* handle = dlopen(name, RTLD_NOW | RTLD_GLOBAL);

    char result[256];
    if (handle != nullptr) {
        snprintf(result, sizeof(result), "ok");
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "dlopen RTLD_GLOBAL ok: %s", name);
    } else {
        const char* err = dlerror();
        snprintf(result, sizeof(result), "fail: %s", err ? err : "unknown");
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "dlopen failed: %s -> %s", name, err ? err : "unknown");
    }

    env->ReleaseStringUTFChars(jname, name);
    return env->NewStringUTF(result);
}
