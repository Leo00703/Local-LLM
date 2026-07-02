//
// Created by Andrew Druk on 22.01.2024.
//

#ifndef LMPLAYGROUND_LLAMACPP_H
#define LMPLAYGROUND_LLAMACPP_H

#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"   // mtmd_helper_bitmap_init_from_buf / _eval_chunks

#include <atomic>

struct SamplerParams {
    int n_ctx;
    float temperature;
    float top_p;
    float repetition_penalty;
    int top_k;
    float min_p;
    uint32_t seed;
    int32_t thinking_budget; // -1 = unlimited
    std::string system_prompt;
};

class LlamaGenerationSession {
public:
    using ResponseCallback = std::function<void(const std::string&)>;

    LlamaGenerationSession();

    ~LlamaGenerationSession();

    void init(llama_model *model, const struct common_chat_templates *chat_tmpls, mtmd_context *mmctx, const SamplerParams &params);

    void printReport();

    // Returns: 0 = more tokens, 1 = done, 2 = tool calls detected
    int generate(const ResponseCallback& callback);

    int addMessage(const char *string, bool enableThinking);

    // Stage an encoded image (jpg/png/… bytes) for the NEXT addMessage. That
    // user turn is then evaluated as a multimodal turn via mtmd. A no-op in
    // effect if the model has no projector loaded (addMessage falls back to the
    // text path when mctx is null).
    void setImageData(const uint8_t *data, size_t len);

    // Request that any in-progress decode (prompt eval or token generation)
    // abort as soon as the engine next checks the abort callback. Thread-safe:
    // called from the cancel path while a worker thread is inside generate().
    void requestAbort();

    std::string getReport();

    void replayHistory(const std::vector<std::pair<std::string, std::string>>& history);

    // Tool calling
    void setTools(const char *toolsJson);
    std::string getToolCallsJson();
    int submitToolResults(const char *resultsJson, bool enableThinking);

    // Render the preamble (system prompt + tools description) by applying
    // the chat template with messages temporarily reduced to just the
    // system message and add_generation_prompt=false. Returns the rendered
    // preamble string, or empty on template failure / unsupported. Used by
    // the preamble KV-cache feature to compute a stable cache key and
    // boundary for the static prefix shared across all new sessions.
    std::string renderPreambleString(bool enableThinking);

    // Configure persistent preamble KV cache. Lazy: the actual load/save
    // happens inside the first addMessage() call. Pass empty path to
    // disable. Path is the cache prefix (we write <path>.bin and <path>.json).
    void setPreambleCachePath(const char* path, const char* fingerprint);

private:
    void finalizeResponse();

    // Multimodal turn: evaluate a rendered prompt + one staged image through
    // mtmd (fresh KV; image encode + non-causal mask + M-RoPE handled inside
    // mtmd_helper_eval_chunks), leaving logits on the last token so generate()
    // samples straight away. Dispatched from addMessage. See setImageData.
    int addImageMessage(const char *text, bool enableThinking);

    common_chat_params renderTemplate(bool enableThinking, bool addGenerationPrompt = true);

    // First-message-only: try to load a saved preamble KV state from
    // [preamble_cache_path], or (on miss) prefill the preamble and save
    // it for next time. On either success path, [prev_rendered_prompt]
    // and [prev_len] are populated so the existing prefix-match logic in
    // addMessage feeds only the user-message delta to the model. Returns
    // true if the preamble was set up (cache hit OR miss-then-saved),
    // false if the preamble couldn't be derived or fed (caching disabled
    // for this turn — addMessage falls back to its normal full-prefill).
    bool tryPreambleCache(bool enableThinking);

    // (Re)create the tool-calling sampler — a common_sampler that applies the
    // chat template's tool-call grammar (lazy, with triggers) plus the
    // reasoning budget, exactly like llama.cpp's reference path. Used only while
    // tools are active; normal chat keeps using [smpl] unchanged.
    void recreateToolSampler(const common_chat_params &render);
    void destroyToolSampler();

    const struct llama_vocab * vocab = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    const struct common_chat_templates * chat_tmpls = nullptr;
    bool prev_had_thinking = false;
    bool prev_enable_thinking = false;
    std::string prev_rendered_prompt;
    std::vector<common_chat_msg> messages;
    std::vector<std::string> additional_stops;
    int prev_len = 0;
    std::vector<llama_token> prompt_tokens;
    // Most recent full prompt rendered with addGenerationPrompt=true (the
    // string we fed to the model right before generation started). Saved
    // so submitToolResults can roll back to this point and reuse the KV
    // cache, feeding only the tool-result delta instead of re-tokenizing
    // the entire conversation. Empty until a turn has been fed.
    std::string last_full_prompt;
    // KV-cache position (in tokens) immediately after [last_full_prompt]
    // was fed. Used by the tool-call detection path to truncate the
    // model's generated tokens out of the cache while preserving the
    // prompt prefix.
    int last_prompt_end_pos = 0;
    llama_token last_token;
    llama_batch batch;
    std::string response;
    common_chat_parser_params parser_params;
    bool parser_initialized = false;
    SamplerParams sampler_params;
    bool budget_sampler_added = false;
    // Set by requestAbort() (cancel path), read by the llama abort callback
    // during decode so Stop can interrupt the prompt-eval phase, not just the
    // between-token loop. Cleared at the start of each addMessage/submitToolResults.
    std::atomic<bool> abort_requested{false};
    // Tool-calling sampler (grammar + reasoning budget + sampling), built from
    // the rendered template when tools are enabled. Null for normal chat, where
    // [smpl] is used instead. See recreateToolSampler.
    common_sampler * gsmpl = nullptr;

    // Tool calling state
    std::vector<common_chat_tool> tools;
    bool tools_enabled = false;
    std::vector<common_chat_tool_call> pending_tool_calls;
    int tool_call_counter = 0;

    // Persistent preamble cache. Set by setPreambleCachePath(). Consulted
    // exactly once per session (the first time addMessage runs with
    // prev_len == 0). preamble_attempted prevents retrying after a
    // graceful skip — keeps subsequent addMessage calls fast and
    // predictable.
    std::string preamble_cache_path;
    std::string preamble_cache_fingerprint;
    bool preamble_attempted = false;

    // ── Vision (multimodal) ──────────────────────────────────────────────
    // Projector context, borrowed from the owning LlamaModel (NOT owned here —
    // the model frees it in unloadModel). Null for text-only models.
    mtmd_context *mctx = nullptr;
    // Encoded image bytes staged by setImageData(), consumed by the next turn.
    std::vector<uint8_t> pending_image_data;
    // Set by an image turn so generate() skips its own prompt decode —
    // mtmd_helper_eval_chunks already ran llama_decode with logits on the last
    // token. Cleared as soon as generate() honors it.
    bool skip_first_decode = false;
};

class LlamaModel {
public:
    LlamaModel() = default;
    ~LlamaModel() = default;

    LlamaGenerationSession* createGenerationSession(const SamplerParams &params);
    int getContextTrainSize();
    void loadModel(const std::string &modelPath,
                   int32_t n_gpu_layers,
                   llama_progress_callback progress_callback,
                   void* progress_callback_user_data,
                   bool disableRepack = false);

    uint64_t getModelSize();

    std::string getModelReport();

    bool supportsThinking();

    bool supportsToolCalling();

    // Load a multimodal projector (mmproj) and attach it to this already-loaded
    // text model, enabling image input. CPU-only (use_gpu=false). Returns true
    // if the mtmd context initialised and supports vision. Safe to call again
    // (replaces any previous projector). Frees on unloadModel().
    bool loadMmproj(const std::string &mmprojPath);

    // True once a vision-capable mmproj has been loaded via loadMmproj().
    bool supportsVision();

    bool isLoaded() const { return model != nullptr; }

    void unloadModel();

private:
    llama_model *model = nullptr;
    common_chat_templates_ptr chat_tmpls;
    // Multimodal projector context (image encoder). Null until loadMmproj()
    // succeeds; owned here and freed in unloadModel().
    mtmd_context *mctx = nullptr;
};

#endif //LMPLAYGROUND_LLAMACPP_H
