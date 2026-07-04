//
// Created by Andrew Druk on 24.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"
#include "chat.h"

#include "console.h"
#include "ggml-backend.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <mutex>

#include <csignal>
#include <unistd.h>
#include <android/log.h>
#include <fcntl.h>

void LlamaModel::loadModel(const std::string &modelPath,
                           int32_t n_gpu_layers,
                           llama_progress_callback progress_callback,
                           void * progress_callback_user_data,
                           bool disableRepack) {

    // initialize the model
    llama_model_params model_params = llama_model_default_params();
    // GPU offload is opt-in (the experimental "GPU acceleration" setting →
    // n_gpu_layers>0). When ON, offload the LLM layers to Vulkan. When OFF, keep
    // the LLM entirely on CPU AND pin it to a CPU-only device list: n_gpu_layers=0
    // alone is NOT enough, because with a Vulkan backend loaded (GGML_BACKEND_DL)
    // the scheduler still reserves a Vulkan compute buffer and routes part of the
    // decode graph to it, corrupting image-conditioned decode for M-RoPE vision
    // models. gpu_enabled also gates the CLIP vision encoder (see loadMmproj).
    gpu_enabled = (n_gpu_layers > 0);
    model_params.n_gpu_layers = gpu_enabled ? n_gpu_layers : 0;
    if (!gpu_enabled) {
        static ggml_backend_dev_t cpu_only_devices[2] = { nullptr, nullptr };
        cpu_only_devices[0] = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (cpu_only_devices[0] != nullptr) {
            model_params.devices = cpu_only_devices;
        }
    }
    model_params.progress_callback = progress_callback;
    model_params.progress_callback_user_data = progress_callback_user_data;
    // Weight repacking (the CPU "extra" buffer types) copies quantized
    // weights into a freshly-allocated RAM buffer, which defeats mmap and
    // forces the whole model resident. For models that don't fit in RAM the
    // caller disables it so weights stay memory-mapped and load successfully
    // (slower matmuls, but they fit).
    model_params.use_extra_bufts = !disableRepack;
    model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (model == nullptr) {
        LOG_ERR("%s: failed to load model '%s'\n", __func__, modelPath.c_str());
        return;
    }
    chat_tmpls = common_chat_templates_init(model, "");
}

LlamaGenerationSession* LlamaModel::createGenerationSession(const SamplerParams &params) {
    if (model == nullptr) {
        return nullptr;
    }
    auto *session = new LlamaGenerationSession();
    session->init(model, chat_tmpls.get(), mctx, params);
    return session;
}

int LlamaModel::getContextTrainSize() {
    if (model == nullptr) {
        return 0;
    }
    return llama_model_n_ctx_train(model);
}

uint64_t LlamaModel::getModelSize() {
    if (this->model == nullptr) {
        return 0;
    }
    return llama_model_size(this->model);
}

bool LlamaModel::supportsThinking() {
    if (!chat_tmpls) {
        return false;
    }
    return common_chat_templates_support_enable_thinking(chat_tmpls.get());
}

bool LlamaModel::supportsToolCalling() {
    if (!chat_tmpls) {
        return false;
    }
    auto caps = common_chat_templates_get_caps(chat_tmpls.get());
    auto it = caps.find("supports_tools");
    return it != caps.end() && it->second;
}

bool LlamaModel::loadMmproj(const std::string &mmprojPath) {
    mmproj_error.clear();
    if (model == nullptr) {
        mmproj_error = "text model not loaded";
        return false;
    }
    // Replace any previously loaded projector.
    if (mctx != nullptr) {
        mtmd_free(mctx);
        mctx = nullptr;
    }
    mtmd_context_params mparams = mtmd_context_params_default();
    // GPU vision encoder — only when the experimental GPU-acceleration setting is
    // on (gpu_enabled, from loadModel). When on, the ACTUAL device is still chosen
    // by MTMD_BACKEND_DEVICE (set in native init from the GPU denylist + crash
    // sentinel), which resolves to "CPU" for unknown-bad/previously-crashed GPUs.
    // When off, CPU vision (the safe default) — no Vulkan CLIP.
    mparams.use_gpu = gpu_enabled;
    mparams.print_timings = false;
    // Is the CLIP encoder going to run on Vulkan? Only if GPU is enabled AND init
    // picked a GPU device (MTMD_BACKEND_DEVICE != "CPU").
    const char *clip_backend = std::getenv("MTMD_BACKEND_DEVICE");
    bool clip_on_vulkan = gpu_enabled && (clip_backend != nullptr && strcmp(clip_backend, "CPU") != 0);
    // On Vulkan, warm up at load so the GPU compute-buffer allocation + first
    // encode happen at load time — INSIDE the crash-sentinel bracket below. A
    // driver that survives weight-load but faults during compute then crashes
    // HERE (caught → CPU next launch) instead of later on the first image
    // (unguarded). CPU vision skips warmup for a faster load.
    mparams.warmup = clip_on_vulkan;
    // Image-encode threads: same count the generation session uses for decode
    // (mtmd only reads this at encode time, never during init).
    mparams.n_threads = std::max(1, std::min(4, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    // User "image detail" slider: cap the tokens an image may use (higher =
    // more resolution). 0 leaves the model's metadata default untouched.
    if (image_max_tokens_pref > 0) {
        mparams.image_max_tokens = image_max_tokens_pref;
    }
    // Guard the projector load: an incompatible/oversized mmproj (or an OOM
    // while loading the CLIP weights) can THROW from mtmd_init_from_file. If
    // that propagated across the JNI boundary it would std::terminate the
    // whole :llama service — taking the app down and breaking every subsequent
    // model load until restart. Catch it so a bad projector just means "no
    // vision" and the text model stays usable.
    // mtmd_init_from_file swallows its own exceptions (logs e.what() via the log
    // callback, returns nullptr), so the real reason only reaches the log. Tee
    // the log around the call to recover it for the UI.
    // The Vulkan CLIP init (incl. the warmup encode enabled above) can SIGSEGV
    // inside the GPU driver on some devices — an uncatchable crash, not a C++
    // exception the try/catch below can trap. Bracket it with the sentinel so a
    // crash here is detected on the NEXT launch and Vulkan vision is disabled
    // (CLIP falls back to CPU). CPU encodes never hit this, so only mark Vulkan.
    if (clip_on_vulkan) clipSentinelBeginVulkanAttempt();
    native_log_capture_begin();
    try {
        mctx = mtmd_init_from_file(mmprojPath.c_str(), model, mparams);
    } catch (...) {
        LOG_ERR("%s: mmproj load threw for '%s'\n", __func__, mmprojPath.c_str());
        mctx = nullptr;
    }
    std::string captured = native_log_capture_end();
    // Reached only if mtmd_init returned (success or caught failure) without
    // crashing — clear the marker so this load isn't counted as a crash.
    if (clip_on_vulkan) clipSentinelEndVulkanAttempt();
    if (mctx == nullptr) {
        LOG_ERR("%s: failed to load mmproj '%s'\n", __func__, mmprojPath.c_str());
        mmproj_error = captured.empty() ? "projector failed to load" : captured;
        return false;
    }
    if (!mtmd_support_vision(mctx)) {
        mmproj_error = "this projector has no vision encoder";
        return false;
    }
    return true;
}

bool LlamaModel::supportsVision() {
    return mctx != nullptr && mtmd_support_vision(mctx);
}

std::string LlamaModel::getModelReport() {
    if (model == nullptr) {
        return "";
    }

    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));

    uint64_t n_params = llama_model_n_params(model);
    int n_ctx_train = llama_model_n_ctx_train(model);

    std::ostringstream report;
    report << "Model\n";
    report << "  Architecture: " << desc << "\n";

    if (n_params >= 1000000000ULL) {
        report << "  Parameters: " << std::fixed << std::setprecision(2)
               << (n_params / 1e9) << "B\n";
    } else {
        report << "  Parameters: " << std::fixed << std::setprecision(0)
               << (n_params / 1e6) << "M\n";
    }

    report << "  Training context: " << n_ctx_train << "\n";

    // Compute backend — lets the user VERIFY whether the model is really running
    // on the GPU (OpenCL) or on the CPU. If GPU was requested and the OpenCL
    // backend registered a GPU device, ggml enumerates it here (with its real
    // OpenCL device name, e.g. "QUALCOMM Adreno(TM) 830"); if OpenCL failed to
    // load/init on this device, no GPU device is present → it silently ran on
    // CPU, which this line makes visible.
    report << "Compute: ";
    if (gpu_enabled) {
        const char *gpu_desc = nullptr;
        size_t nd = ggml_backend_dev_count();
        for (size_t i = 0; i < nd; i++) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            if (d == nullptr) continue;
            ggml_backend_dev_type t = ggml_backend_dev_type(d);
            if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                gpu_desc = ggml_backend_dev_description(d);
            }
        }
        if (gpu_desc != nullptr) {
            int n_layer = llama_model_n_layer(model);
            report << "GPU (OpenCL): " << gpu_desc
                   << " (" << n_layer << "/" << n_layer << " layers)\n";
        } else {
            report << "CPU (GPU requested, but no OpenCL device is available)\n";
        }
    } else {
        report << "CPU\n";
    }

    return report.str();
}

void LlamaModel::unloadModel() {
    // Free the projector before the text model it references.
    if (mctx != nullptr) {
        mtmd_free(mctx);
        mctx = nullptr;
    }
    chat_tmpls.reset();
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
}
