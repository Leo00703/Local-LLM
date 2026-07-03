//
// Created by Andrew Druk on 24.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"
#include "chat.h"

#include "console.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
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
    // model_params.n_gpu_layers = n_gpu_layers;
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
    if (model == nullptr) {
        return false;
    }
    // Replace any previously loaded projector.
    if (mctx != nullptr) {
        mtmd_free(mctx);
        mctx = nullptr;
    }
    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = false;         // CPU-only vision encoder (Phase 1)
    mparams.print_timings = false;
    mparams.warmup = false;          // no warmup encode pass at load time
    // Image-encode threads: same count the generation session uses for decode
    // (mtmd only reads this at encode time, never during init).
    mparams.n_threads = std::max(1, std::min(4, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    // Guard the projector load: an incompatible/oversized mmproj (or an OOM
    // while loading the CLIP weights) can THROW from mtmd_init_from_file. If
    // that propagated across the JNI boundary it would std::terminate the
    // whole :llama service — taking the app down and breaking every subsequent
    // model load until restart. Catch it so a bad projector just means "no
    // vision" and the text model stays usable.
    try {
        mctx = mtmd_init_from_file(mmprojPath.c_str(), model, mparams);
    } catch (...) {
        LOG_ERR("%s: mmproj load threw for '%s'\n", __func__, mmprojPath.c_str());
        mctx = nullptr;
        return false;
    }
    if (mctx == nullptr) {
        LOG_ERR("%s: failed to load mmproj '%s'\n", __func__, mmprojPath.c_str());
        return false;
    }
    return mtmd_support_vision(mctx);
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
