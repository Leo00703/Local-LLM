#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "gguf.h"
#include "llama.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <iostream>
#include <exception>
#include <cstdlib>
#include <csignal>
#include <unistd.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <mutex>
#include <deque>

class AndroidLogBuf : public std::streambuf {
protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        __android_log_print(ANDROID_LOG_INFO, "Llama", "%.*s", (int)n, s);
        return n;
    }

    int overflow(int c) override {
        if (c != EOF) {
            char c_as_char = static_cast<char>(c);
            __android_log_write(ANDROID_LOG_INFO, "Llama", &c_as_char);
        }
        return c;
    }
};

#define TAG "llama-android.cpp"

// ── Native log capture (mmproj-load diagnostics) ─────────────────────────────
// The real reason mtmd/clip refuses a projector (n_embd mismatch, unknown
// projector type, allocation failure, …) is only ever emitted to the log
// callback and lost to logcat — which the user can't easily read. To surface it
// in the UI, loadMmproj() brackets its mtmd_init_from_file call with
// native_log_capture_begin()/_end(); while capturing, ERROR/WARN log lines are
// also appended (bounded) to a buffer that becomes LlamaModel::mmproj_error.
static std::mutex g_capture_mutex;
static std::string g_capture_buf;
static bool g_capturing = false;

void native_log_capture_begin() {
    std::lock_guard<std::mutex> lk(g_capture_mutex);
    g_capturing = true;
    g_capture_buf.clear();
}

std::string native_log_capture_end() {
    std::lock_guard<std::mutex> lk(g_capture_mutex);
    g_capturing = false;
    return g_capture_buf;
}

// ── Recent native errors (load diagnostics) ─────────────────────────────────
// Why a model or context failed to load is only ever said through the log callback, and
// logcat is out of reach for a sideloaded app on a phone with no adb. Keep the last few
// ERROR lines so the UI can show the reason next to "Couldn't load model". A GGML_ASSERT
// does not go through the log at all and ends the process, so ggml's abort callback also
// writes its message to a file that the next process start reads back (see
// install_abort_note), because the :llama process is gone by the time the UI asks.
static std::mutex g_errors_mutex;
static std::deque<std::string> g_recent_errors;
static std::string g_abort_note_path;

static void remember_error(const char * msg) {
    if (msg == nullptr) return;
    std::string line(msg);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    if (line.empty()) return;
    if (line.size() > 400) line.resize(400);
    std::lock_guard<std::mutex> lk(g_errors_mutex);
    g_recent_errors.push_back(std::move(line));
    while (g_recent_errors.size() > 12) g_recent_errors.pop_front();
}

static void forget_errors() {
    std::lock_guard<std::mutex> lk(g_errors_mutex);
    g_recent_errors.clear();
}

// The remembered lines joined by newlines, ASCII only (NewStringUTF aborts on invalid UTF-8).
static std::string recent_errors() {
    std::lock_guard<std::mutex> lk(g_errors_mutex);
    std::string out;
    for (const auto & l : g_recent_errors) {
        if (!out.empty()) out += '\n';
        out += l;
    }
    for (char & c : out) {
        if (static_cast<unsigned char>(c) >= 0x80) c = '?';
    }
    return out;
}

static void abort_note_callback(const char * message) {
    if (message == nullptr) return;
    __android_log_print(ANDROID_LOG_FATAL, TAG, "%s", message);
    if (g_abort_note_path.empty()) return;
    if (FILE * f = fopen(g_abort_note_path.c_str(), "w")) {
        fputs(message, f);
        fflush(f);
        fsync(fileno(f));
        fclose(f);
    }
}

// Read back (and delete) the message a previous process left when ggml aborted it, then
// arm the callback for this one.
static void install_abort_note(const char * stateDir) {
    if (stateDir == nullptr || stateDir[0] == '\0') return;
    g_abort_note_path = std::string(stateDir) + "/last_native_abort.txt";
    if (FILE * f = fopen(g_abort_note_path.c_str(), "r")) {
        char buf[2100];
        const size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        fclose(f);
        buf[n] = '\0';
        std::remove(g_abort_note_path.c_str());
        remember_error((std::string("The engine stopped itself: ") + buf).c_str());
    }
    ggml_set_abort_callback(abort_note_callback);
}

static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    // llama.cpp/mtmd already formatted the message; [fmt] is the final text
    // (and may itself contain %-specifiers). Pass it as a "%s" argument, NOT
    // as the format string — otherwise any %-token in the log is interpreted
    // as a conversion and reads past [data] (garbage / crash). [data] is the
    // user pointer (always NULL here) and must not be treated as a vararg.
    (void) data;
    if (level == GGML_LOG_LEVEL_ERROR)     __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", fmt);
    else __android_log_print(ANDROID_LOG_DEFAULT, TAG, "%s", fmt);

    if (level == GGML_LOG_LEVEL_ERROR) remember_error(fmt);

    if (fmt != nullptr &&
        (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN)) {
        std::lock_guard<std::mutex> lk(g_capture_mutex);
        // Keep the buffer bounded; the last error line is what matters.
        if (g_capturing && g_capture_buf.size() < 4000) {
            g_capture_buf.append(fmt);
        }
    }
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_probeModelMetadata(JNIEnv *env, jobject thiz, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    struct gguf_init_params params = { /*.no_alloc =*/ true, /*.ctx =*/ nullptr };
    struct gguf_context * gguf_ctx = gguf_init_from_file(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (gguf_ctx == nullptr) {
        return nullptr;
    }

    // Read general.name
    std::string name;
    int64_t name_key = gguf_find_key(gguf_ctx, "general.name");
    if (name_key >= 0) {
        name = gguf_get_val_str(gguf_ctx, name_key);
    }

    // Check tokenizer.chat_template existence
    int64_t template_key = gguf_find_key(gguf_ctx, "tokenizer.chat_template");
    bool has_chat_template = (template_key >= 0);

    gguf_free(gguf_ctx);

    // Return String[] { name, hasChatTemplate }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(2, stringClass, nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(name.c_str()));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(has_chat_template ? "true" : "false"));

    return result;
}

// GPUs whose Vulkan driver mishandles the CLIP vision graph. The encoder runs
// on Vulkan by default (much faster than CPU); these specific parts fall back
// to CPU. Matched as a substring of the ggml device description (the Vulkan
// deviceName). Denylist, not allowlist: an untested bad GPU still crashes, but
// only into the survivable :llama-restart recovery path (the crash sentinel).
static bool clipVulkanIsKnownBad(const char *gpuDescription) {
    if (gpuDescription == nullptr) return false;
    static const char *kDeny[] = {
        "PowerVR",         // Tensor G5 (Pixel 10): CLIP encode slow AND numerically wrong
        "Mali-G52",        // Galaxy A32 (Helio G80) etc.: SIGSEGV in ggml_vk_create_buffer
        "Adreno (TM) 610", // SD 680/685: SIGSEGV in ggml_vk_init/ggml_vk_create_buffer
    };
    for (const char *needle : kDeny) {
        if (strstr(gpuDescription, needle) != nullptr) return true;
    }
    return false;
}

// --- Vulkan CLIP crash sentinel ---------------------------------------------
// Catches GPUs not on the static denylist after a single crash. The inflight
// marker brackets the Vulkan vision-encoder init (the SIGSEGV happens inside
// the GPU driver and can't be caught); if it's still present at the next
// process start, that attempt crashed -> promote to a permanent block so CLIP
// runs on CPU from then on. An empty stateDir disables the sentinel (tests).
static std::string g_clipStateDir;
static std::string clipInflightPath() { return g_clipStateDir + "/vulkan_clip.inflight"; }
static std::string clipBlockedPath()  { return g_clipStateDir + "/vulkan_clip.blocked"; }
static std::string clipStrikePath()   { return g_clipStateDir + "/vulkan_clip.strike"; }

static bool clipFileExists(const std::string &path) {
    FILE *f = fopen(path.c_str(), "r");
    if (f != nullptr) { fclose(f); return true; }
    return false;
}
static void clipTouchFile(const std::string &path) {
    FILE *f = fopen(path.c_str(), "w");
    if (f != nullptr) fclose(f);
}

void clipSentinelInit(const std::string &stateDir) {
    g_clipStateDir = stateDir;
    if (g_clipStateDir.empty()) return;
    if (clipFileExists(clipInflightPath())) {
        // The last Vulkan vision attempt didn't clear its marker → the :llama
        // process died mid-init/encode. That's USUALLY a GPU driver crash, but
        // could also be unrelated (Android low-memory kill, force-stop/swipe-away,
        // or an abort on a concurrent generation thread). Require TWO such strikes
        // before permanently disabling Vulkan vision, so one spurious process
        // death doesn't silently downgrade a working GPU to CPU forever.
        remove(clipInflightPath().c_str());
        if (clipFileExists(clipStrikePath())) {
            clipTouchFile(clipBlockedPath());
            remove(clipStrikePath().c_str());
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "Vulkan CLIP failed twice; disabling Vulkan vision (using CPU)");
        } else {
            clipTouchFile(clipStrikePath());
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "Vulkan CLIP attempt did not complete (strike 1 of 2)");
        }
    } else {
        // Previous attempt completed (or there was none): clear a stale single
        // strike so the two strikes must be roughly consecutive, not a lifetime
        // tally — an intermittent one-off doesn't accumulate toward a block.
        remove(clipStrikePath().c_str());
    }
}
bool clipSentinelVulkanBlocked() {
    return !g_clipStateDir.empty() && clipFileExists(clipBlockedPath());
}
void clipSentinelBeginVulkanAttempt() {
    if (!g_clipStateDir.empty()) clipTouchFile(clipInflightPath());
}
void clipSentinelEndVulkanAttempt() {
    if (!g_clipStateDir.empty()) remove(clipInflightPath().c_str());
}

extern "C" JNIEXPORT int
JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_init(JNIEnv *env, jobject object, jstring nativeLibDir, jstring stateDir) {

    // The Vulkan-CLIP crash sentinel persists a marker across :llama restarts
    // in this app-private dir; a marker that survived a restart means the last
    // Vulkan vision attempt crashed the process -> block Vulkan vision.
    if (stateDir != nullptr) {
        const char *sd = env->GetStringUTFChars(stateDir, nullptr);
        if (sd != nullptr) {
            clipSentinelInit(sd);
            install_abort_note(sd);
            env->ReleaseStringUTFChars(stateDir, sd);
        }
    }

    // Redirect std::cerr to logcat. Must be static: std::cerr keeps the
    // streambuf pointer forever, so a stack-allocated buffer here would
    // dangle the moment init() returns (use-after-return on any later
    // std::cerr write from native code).
    static AndroidLogBuf androidLogBuf;
    std::cerr.rdbuf(&androidLogBuf);

    llama_log_set(log_callback, NULL);
    // Route the multimodal (mtmd/clip) logs through the same sink so mmproj
    // load diagnostics show up in logcat.
    mtmd_log_set(log_callback, NULL);

    // With GGML_BACKEND_DL=ON the CPU backend lives in separate
    // libggml-cpu-*.so files alongside libllamacpp.so. dlopen them so
    // llama_model_load_from_file has a backend to bind tensors to.
    //
    // libggml-vulkan.so is dlopened here too; its backend registration
    // enumerates the GPU's physical-device properties, and those Vulkan-Hpp
    // calls THROW on a non-conformant driver — a throw ggml does not guard.
    // Catch it here (rather than let it cross the JNI boundary and
    // std::terminate the :llama service) and fall back to whatever backends
    // did load; the CPU backend registers first, so the LLM still works.
    try {
        if (nativeLibDir != nullptr) {
            const char *path = env->GetStringUTFChars(nativeLibDir, nullptr);
            std::string dir = (path != nullptr) ? std::string(path) : std::string();
            if (path != nullptr) {
                env->ReleaseStringUTFChars(nativeLibDir, path);
            }
            ggml_backend_load_all_from_path(dir.c_str());
        } else {
            ggml_backend_load_all();
        }
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, "Llama",
                            "backend load threw (GPU driver?): %s — continuing on CPU", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "Llama",
                            "backend load threw a non-std exception — continuing on CPU");
    }

    // Pick the CLIP vision-encoder backend now that the Vulkan backend (if any)
    // is loaded and its devices are enumerable. clip.cpp selects by device name
    // via the MTMD_BACKEND_DEVICE env var (e.g. "Vulkan0"). Default to the GPU
    // (much faster than CPU vision) and fall back to "CPU" for denylisted or
    // sentinel-blocked GPUs. Mobile GPUs register as IGPU (not GPU), so
    // enumerate rather than dev_by_type(GPU). Reading device name/description is
    // safe (no buffer allocation — that's where the driver crash happens).
    // Dev override: `setprop debug.lmp.mtmd_backend <name>`.
    const char *mtmd_backend = "CPU";
    const char *gpu_name = nullptr;
    const char *gpu_desc = nullptr;
    size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);
        __android_log_print(ANDROID_LOG_INFO, TAG, "ggml device %zu: name=%s type=%d desc=%s",
                            i, name ? name : "?", (int) type, desc ? desc : "?");
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            gpu_name = name;
            gpu_desc = desc;
        }
    }
    if (gpu_name != nullptr && !clipVulkanIsKnownBad(gpu_desc)
            && !clipSentinelVulkanBlocked()) {
        mtmd_backend = gpu_name;
    }
    char backend_override[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lmp.mtmd_backend", backend_override) > 0) {
        mtmd_backend = backend_override;
    }
    setenv("MTMD_BACKEND_DEVICE", mtmd_backend, 1);
    __android_log_print(ANDROID_LOG_INFO, TAG, "vision backend: %s (gpu=%s)",
                        mtmd_backend, gpu_desc ? gpu_desc : "none");

    llama_backend_init();
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_systemInfo(JNIEnv *env, jobject object) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_recentErrors(JNIEnv *env, jobject object) {
    return env->NewStringUTF(recent_errors().c_str());
}

extern "C" JNIEXPORT jobject
JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_loadModel(JNIEnv *env,
                   jobject activity,
                   jstring modelPath,
                   jobject progressCallback,
                   jboolean disableRepack,
                   jint gpuLayers) {

    struct CallbackContext {
        JNIEnv *env;
        jobject progressCallback;
    };

    forget_errors();
    auto* model = new LlamaModel();
    CallbackContext ctx = {env, progressCallback};
    const char* utfModelPath = env->GetStringUTFChars(modelPath, nullptr);
    model->loadModel(utfModelPath,
                     gpuLayers,
                     [](float progress, void *ctx) -> bool {
                            auto* context = static_cast<CallbackContext*>(ctx);
                            jclass clazz = context->env->GetObjectClass(context->progressCallback);
                            jmethodID methodId = context->env->GetMethodID(clazz, "onProgress", "(F)V");
                            context->env->CallVoidMethod(context->progressCallback, methodId, progress);
                            return true;
                     },
                     &ctx,
                     disableRepack == JNI_TRUE
                     );
    env->ReleaseStringUTFChars(modelPath, utfModelPath);

    if (!model->isLoaded()) {
        delete model;
        return nullptr;
    }

    jclass clazz = env->FindClass("com/druk/llamacpp/jni/NativeLlamaModel");
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "()V");
    jobject obj = env->NewObject(clazz, constructor);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    env->SetLongField(obj, fid, (long) model);
    return obj;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getModelSize(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return 0;
    }
    return model->getModelSize();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getModelReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return env->NewStringUTF("");
    }
    auto report = model->getModelReport();
    return env->NewStringUTF(report.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsThinking(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    return model->supportsThinking() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_unloadModel(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return;
    }
    env->SetLongField(thiz, fid, 0);
    model->unloadModel();
    delete model;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getContextTrainSize(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return 0;
    }
    return model->getContextTrainSize();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getRecommendedContextSize(
        JNIEnv *env, jobject thiz, jlong deviceRamBytes, jint kvBytesPerElemX16) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return 0;
    }
    return model->getRecommendedContextSize((int64_t) deviceRamBytes, (int) kvBytesPerElemX16);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_createSession(JNIEnv *env, jobject thiz,
                                                  jint contextSize,
                                                  jfloat temperature,
                                                  jfloat topP,
                                                  jfloat repetitionPenalty,
                                                  jint topK,
                                                  jfloat minP,
                                                  jint seed,
                                                  jint thinkingBudget,
                                                  jstring systemPrompt,
                                                  jint kvCacheType,
                                                  jboolean speculativeEnabled,
                                                  jint specNDraft) {

    jclass clazz1 = env->GetObjectClass(thiz);
    jfieldID fid1 = env->GetFieldID(clazz1, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid1);
    if (model == nullptr) {
        return nullptr;
    }

    SamplerParams params;
    params.n_ctx = contextSize;
    params.temperature = temperature;
    params.top_p = topP;
    params.repetition_penalty = repetitionPenalty;
    params.top_k = topK;
    params.min_p = minP;
    params.seed = (seed < 0) ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
    params.thinking_budget = thinkingBudget;
    params.kv_cache_type = kvCacheType;
    params.speculative_enabled = (speculativeEnabled == JNI_TRUE);
    params.spec_n_draft = specNDraft;
    if (systemPrompt != nullptr) {
        const char* utfSystemPrompt = env->GetStringUTFChars(systemPrompt, nullptr);
        if (utfSystemPrompt != nullptr) {
            params.system_prompt = utfSystemPrompt;
            env->ReleaseStringUTFChars(systemPrompt, utfSystemPrompt);
        }
    }

    jclass clazz2 = env->FindClass("com/druk/llamacpp/jni/NativeLlamaSession");
    jmethodID constructor = env->GetMethodID(clazz2, "<init>", "()V");
    jobject obj = env->NewObject(clazz2, constructor);

    LlamaGenerationSession* session = model->createGenerationSession(params);
    if (session == nullptr) {
        return nullptr;
    }
    jclass clazz3 = env->GetObjectClass(obj);
    jfieldID fid3 = env->GetFieldID(clazz3, "nativeHandle", "J");
    env->SetLongField(obj, fid3, (long)session);

    return obj;
}

// Number of trailing bytes of [s] that form an INCOMPLETE UTF-8 sequence, or 0
// if the string ends on a character boundary. The streamed response is built
// token by token, and a token can end in the middle of a multi-byte character
// (an emoji split across tokens, byte-fallback pieces). NewStringUTF aborts the
// process on invalid UTF-8 under CheckJNI (on by default for debuggable apps,
// which is every build this fork ships), so the partial tail must not cross.
static size_t incompleteUtf8Tail(const std::string &s) {
    const size_t n = s.size();
    // The last sequence starts at most 3 bytes back; walk over continuation bytes.
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        const unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;                    // continuation byte
        size_t expected = 1;                                  // ASCII / unknown lead
        if ((c & 0xE0) == 0xC0) expected = 2;
        else if ((c & 0xF0) == 0xE0) expected = 3;
        else if ((c & 0xF8) == 0xF0) expected = 4;
        return back < expected ? back : 0;
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL Java_com_druk_llamacpp_jni_NativeLlamaSession_generate
        (JNIEnv *env, jobject obj, jobject callback) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);
    if (session == nullptr) {
        return 1;
    }

    jclass javaClass = env->FindClass("com/druk/llamacpp/LlamaGenerationCallback");
    jmethodID onFullResponseId = env->GetMethodID(javaClass, "onFullResponse", "(Ljava/lang/String;)V");

    return session->generate(
            [env, onFullResponseId, callback](const std::string &fullResponse) {
                // Drop a trailing half-written character. The next callback carries
                // the whole string again, so what reaches Kotlin stays an
                // append-only prefix of the final text.
                const size_t partial = incompleteUtf8Tail(fullResponse);
                std::string trimmed;  // only filled (copied) when a tail is dropped
                const char *text = fullResponse.c_str();
                if (partial != 0) {
                    trimmed = fullResponse.substr(0, fullResponse.size() - partial);
                    text = trimmed.c_str();
                }
                jstring jResponse = env->NewStringUTF(text);
                if (jResponse != nullptr) {
                    env->CallVoidMethod(callback, onFullResponseId, jResponse);
                    env->DeleteLocalRef(jResponse);
                }
            }
    );
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_addMessage(JNIEnv *env,
                                                         jobject thiz,
                                                         jstring message,
                                                         jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return 1;
    }

    const char* utfMessage = env->GetStringUTFChars(message, nullptr);
    int rc = session->addMessage(utfMessage, enableThinking);
    env->ReleaseStringUTFChars(message, utfMessage);
    return rc;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setImageData(JNIEnv *env,
                                                           jobject thiz,
                                                           jbyteArray data) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }

    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    session->setImageData(reinterpret_cast<const uint8_t*>(bytes), (size_t) len);
    // JNI_ABORT: read-only, don't copy the (possibly large) buffer back.
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_attachProjector(JNIEnv *env,
                                                              jobject thiz,
                                                              jobject jmodel) {
    jclass sessionClazz = env->GetObjectClass(thiz);
    jfieldID sessionFid = env->GetFieldID(sessionClazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, sessionFid);
    if (session == nullptr || jmodel == nullptr) {
        return JNI_FALSE;
    }
    jclass modelClazz = env->GetObjectClass(jmodel);
    jfieldID modelFid = env->GetFieldID(modelClazz, "nativeHandle", "J");
    auto *model = (LlamaModel*)env->GetLongField(jmodel, modelFid);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    mtmd_context *proj = model->getProjector();
    session->setProjector(proj);
    return (proj != nullptr && model->supportsVision()) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getLastImageTokens(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return 0;
    return (jint) session->getLastImageTokens();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_requestAbort(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }
    session->requestAbort();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_printReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }
    session->printReport();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return env->NewStringUTF("");
    }
    auto report = session->getReport();
    auto string = env->NewStringUTF(report.c_str());
    return string;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_replayHistory(JNIEnv *env,
                                                             jobject thiz,
                                                             jobjectArray userMessages,
                                                             jobjectArray assistantMessages) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }

    int len = env->GetArrayLength(userMessages);
    std::vector<std::pair<std::string, std::string>> history;
    history.reserve(len);

    for (int i = 0; i < len; i++) {
        auto jUser = (jstring) env->GetObjectArrayElement(userMessages, i);
        auto jAssistant = (jstring) env->GetObjectArrayElement(assistantMessages, i);
        const char* user = env->GetStringUTFChars(jUser, nullptr);
        const char* assistant = env->GetStringUTFChars(jAssistant, nullptr);
        history.emplace_back(user, assistant);
        env->ReleaseStringUTFChars(jUser, user);
        env->ReleaseStringUTFChars(jAssistant, assistant);
        env->DeleteLocalRef(jUser);
        env->DeleteLocalRef(jAssistant);
    }

    session->replayHistory(history);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsToolCalling(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) return JNI_FALSE;
    return model->supportsToolCalling() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsVision(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) return JNI_FALSE;
    return model->supportsVision() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_loadMmproj(JNIEnv *env, jobject thiz, jstring mmprojPath) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) return JNI_FALSE;
    const char* utfPath = env->GetStringUTFChars(mmprojPath, nullptr);
    bool ok = model->loadMmproj(utfPath);
    env->ReleaseStringUTFChars(mmprojPath, utfPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getMmprojError(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(model->getMmprojError().c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_setChatTemplateOverride(JNIEnv *env, jobject thiz, jstring tmpl) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr || tmpl == nullptr) return JNI_FALSE;
    const char *chars = env->GetStringUTFChars(tmpl, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    const std::string text(chars);
    env->ReleaseStringUTFChars(tmpl, chars);
    return model->setChatTemplateOverride(text) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_setImageMaxTokens(JNIEnv *env, jobject thiz, jint n) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) return;
    model->setImageMaxTokens((int) n);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setTools(JNIEnv *env, jobject thiz, jstring toolsJson) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return;
    const char* utfJson = env->GetStringUTFChars(toolsJson, nullptr);
    session->setTools(utfJson);
    env->ReleaseStringUTFChars(toolsJson, utfJson);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getToolCallsJson(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return env->NewStringUTF("[]");
    auto json = session->getToolCallsJson();
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_submitToolResults(JNIEnv *env, jobject thiz,
                                                                 jstring resultsJson,
                                                                 jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return 1;
    const char* utfJson = env->GetStringUTFChars(resultsJson, nullptr);
    int result = session->submitToolResults(utfJson, enableThinking);
    env->ReleaseStringUTFChars(resultsJson, utfJson);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_renderPreambleString(JNIEnv *env, jobject thiz,
                                                                    jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return env->NewStringUTF("");
    auto preamble = session->renderPreambleString(enableThinking);
    return env->NewStringUTF(preamble.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setPreambleCachePath(JNIEnv *env, jobject thiz,
                                                                    jstring path,
                                                                    jstring fingerprint) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return;
    const char* utfPath = path != nullptr ? env->GetStringUTFChars(path, nullptr) : nullptr;
    const char* utfFp   = fingerprint != nullptr ? env->GetStringUTFChars(fingerprint, nullptr) : nullptr;
    session->setPreambleCachePath(utfPath, utfFp);
    if (utfPath != nullptr) env->ReleaseStringUTFChars(path, utfPath);
    if (utfFp   != nullptr) env->ReleaseStringUTFChars(fingerprint, utfFp);
}

extern "C" JNIEXPORT void JNICALL Java_com_druk_llamacpp_jni_NativeLlamaSession_destroy
        (JNIEnv *env, jobject obj) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);

    if (session != nullptr) {
        env->SetLongField(obj, fid, (long)nullptr);
        delete session;
        __android_log_print(ANDROID_LOG_DEBUG, "Llama", "Destroy");
    }
}
