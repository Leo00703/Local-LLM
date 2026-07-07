//
// Created by Andrew Druk on 22.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"

#include "common.h"
#include "chat.h"
#include "console.h"
#include "llama.h"
#include "log.h"
#include "reasoning-budget.h"
#include "nlohmann/json.hpp"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <iomanip>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <mutex>

#include <unistd.h>
#include <android/log.h>
#include <asm-generic/fcntl.h>
#include <fcntl.h>

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

bool is_valid_utf8(const char * string) {
    if (!string) {
        return true;
    }

    const unsigned char *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
}

LlamaGenerationSession::LlamaGenerationSession() = default;

LlamaGenerationSession::~LlamaGenerationSession() {
    // Free the speculative framework FIRST: it borrows ctx (target) and ctx_dft.
    if (spec != nullptr) {
        common_speculative_free(spec);
        spec = nullptr;
    }
    if (ctx != nullptr) {
        llama_free(ctx);
    }
    if (ctx_dft != nullptr) {
        llama_free(ctx_dft);
    }
    if (smpl != nullptr) {
        llama_sampler_free(smpl);
    }
    destroyToolSampler();
}

void LlamaGenerationSession::destroyToolSampler() {
    if (gsmpl != nullptr) {
        common_sampler_free(gsmpl);
        gsmpl = nullptr;
    }
}

void LlamaGenerationSession::recreateToolSampler(const common_chat_params &render) {
    destroyToolSampler();
    if (render.grammar.empty()) {
        return;
    }

    common_params_sampling sp;
    // Mirror the hand-built chain so non-grammar behavior matches normal chat.
    sp.top_k             = sampler_params.top_k;
    sp.top_p             = sampler_params.top_p;
    sp.min_p             = sampler_params.min_p;
    sp.temp              = sampler_params.temperature;
    sp.penalty_repeat    = sampler_params.repetition_penalty;
    sp.penalty_last_n    = 256;
    sp.seed              = sampler_params.seed;
    sp.min_keep          = 1;

    // Tool-call grammar (lazy, with triggers) from the chat template — this is
    // what constrains the model to emit valid tool calls.
    sp.grammar           = common_grammar(COMMON_GRAMMAR_TYPE_TOOL_CALLS, render.grammar);
    sp.grammar_lazy      = render.grammar_lazy;
    sp.grammar_triggers  = render.grammar_triggers;
    sp.generation_prompt = render.generation_prompt;

    // Reasoning budget (matches tools/cli/cli.cpp). For lazy grammars this also
    // provides thinking-block suppression even when the budget is unlimited.
    if (!render.thinking_end_tag.empty()) {
        sp.reasoning_budget_tokens = sampler_params.thinking_budget;
        if (!render.thinking_start_tag.empty()) {
            sp.reasoning_budget_start = common_tokenize(vocab, render.thinking_start_tag, false, true);
        }
        sp.reasoning_budget_end    = common_tokenize(vocab, render.thinking_end_tag, false, true);
        sp.reasoning_budget_forced = common_tokenize(vocab, render.thinking_end_tag, false, true);
    }

    gsmpl = common_sampler_init(llama_get_model(ctx), sp);
    LOGi("Tool sampler created: grammar_len=%zu lazy=%d triggers=%zu",
         render.grammar.size(), (int)render.grammar_lazy, render.grammar_triggers.size());
}

void LlamaGenerationSession::init(llama_model *model, const struct common_chat_templates *tmpls, mtmd_context *mmctx, const SamplerParams &params, bool gpu_enabled) {

    vocab = llama_model_get_vocab(model);
    chat_tmpls = tmpls;
    mctx = mmctx;

    int n_threads = std::max(1, std::min(4, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    int n_ctx_train = llama_model_n_ctx_train(model);
    int n_ctx = std::min(params.n_ctx, n_ctx_train);
    LOGi("Model training context: %d, using: %d", n_ctx_train, n_ctx);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = std::min(n_ctx, 512);
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    // KV-cache quantization (user-selectable, CPU only). Q8_0 ~= half the KV
    // memory at near-zero quality loss; Q4_0 is smaller with a small quality
    // cost. Quantized *V* requires Flash Attention. The OpenCL GPU backend's FA
    // kernels are F16/F32 only (no quantized-KV variants), so a quantized KV
    // cache on the GPU crashes context creation. Therefore: keep the KV cache
    // F16 whenever the model runs on the GPU; KV quant applies on CPU only.
    ggml_type kv_type = GGML_TYPE_F16;
    if (!gpu_enabled) {
        switch (params.kv_cache_type) {
            case 1: kv_type = GGML_TYPE_Q8_0; break;
            case 2: kv_type = GGML_TYPE_Q4_0; break;
            default: kv_type = GGML_TYPE_F16; break;
        }
    }
    ctx_params.type_k = kv_type;
    ctx_params.type_v = kv_type;
    if (kv_type != GGML_TYPE_F16) {
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    LOGi("KV cache type: %d (0=F16 1=Q8_0 2=Q4_0), flash_attn forced=%d",
         params.kv_cache_type, kv_type != GGML_TYPE_F16);

    // Self-MTP speculative decoding: Qwen3.5 is a HYBRID arch (its recurrent /
    // linear-attention layers keep per-token state snapshots bounded by n_rs_seq).
    // n_rs_seq must cover BOTH the KV rollback of rejected drafts AND the verify
    // batch's output positions: the verify batch is [seed + up to n_draft drafts]
    // with an output row on every position, and the recurrent decode needs a state
    // snapshot per output row (a token-only prompt decode needs just one, which is
    // why prefill works but the multi-output verify decode fails when n_rs_seq is
    // exactly n_draft). Size it to n_draft + margin. Only when speculative is
    // requested, so a normal session is unchanged (n_rs_seq stays 0).
    if (params.speculative_enabled) {
        ctx_params.n_rs_seq = (params.spec_n_draft > 0 ? params.spec_n_draft : 3) + 1;
    }

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx && kv_type != GGML_TYPE_F16) {
        // Some model/backend combinations on this device can't do Flash
        // Attention + quantized KV. Retry once with full-precision F16 KV
        // (AUTO flash-attn) so the session still loads instead of failing.
        LOGe("%s: quantized KV (type %d) init failed; falling back to F16 KV",
             __func__, params.kv_cache_type);
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        ctx = llama_init_from_model(model, ctx_params);
    }
    if (!ctx) {
        LOGe("%s: error: failed to create the llama_context\n" , __func__);
        return;
    }

    // Experimental self-MTP speculative decoding: build a second "draft" context
    // of type MTP on the SAME model. llama_init_from_model returns null when the
    // model has no MTP head (nextn tensors) — only Qwen3.5 / 3.5-MoE execute it in
    // this llama.cpp — so we detect capability by trying, and fall back to normal
    // decode (ctx_dft == null) otherwise. Increment 1: build + log only; the
    // draft->verify->accept loop lands in a later step.
    speculative_requested = params.speculative_enabled;
    if (params.speculative_enabled) {
        // Match the server's self-MTP draft-context setup (server-context.cpp):
        // MTP context type on the SAME model, forced F16 KV for the MTP head's
        // attention cache (partial seq_rm rollback needs it), no recurrent-state
        // rollback snapshots, a single output row per decode.
        llama_context_params mtp_params = ctx_params;
        mtp_params.ctx_type        = LLAMA_CONTEXT_TYPE_MTP;
        mtp_params.type_k          = GGML_TYPE_F16;
        mtp_params.type_v          = GGML_TYPE_F16;
        mtp_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        mtp_params.n_rs_seq        = 0;
        mtp_params.n_outputs_max   = 1;
        ctx_dft = llama_init_from_model(model, mtp_params);
        if (ctx_dft) {
            LOGi("MTP: draft context created (model has an MTP head); n_draft=%d",
                 params.spec_n_draft);
        } else {
            LOGe("MTP: requested but this model has no MTP head; using normal decode");
        }
    }

    // Abort callback: llama_decode polls this between compute steps, so setting
    // abort_requested lets Stop interrupt the prompt-eval phase (a single big
    // decode), not just the between-token loop. Returns true to abort.
    llama_set_abort_callback(
        ctx,
        [](void *data) -> bool {
            return static_cast<LlamaGenerationSession *>(data)->abort_requested.load();
        },
        this);

    auto smplParams = llama_sampler_chain_default_params();
    smplParams.no_perf = false;

    smpl = llama_sampler_chain_init(smplParams);

    sampler_params = params;

    if (!params.system_prompt.empty()) {
        common_chat_msg system_msg;
        system_msg.role = "system";
        system_msg.content = params.system_prompt;
        messages.push_back(system_msg);
    }

    // Repetition penalty (only if > 1.0)
    if (params.repetition_penalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, params.repetition_penalty, 0.0f, 0.0f));
    }

    // Top-K (only if > 0)
    if (params.top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(params.top_k));
    }

    // Top-P (only if < 1.0)
    if (params.top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(params.top_p, 1));
    }

    // Min-P (only if > 0.0)
    if (params.min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(params.min_p, 1));
    }

    // Temperature: greedy if 0, otherwise temp + dist
    if (params.temperature == 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(params.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(params.seed));
    }

    prev_len = 0;

    // Increment 2: if the MTP draft context built, wire the speculative framework
    // and confirm BOTH contexts can roll back up to n_draft rejected draft tokens.
    // A context qualifies if it does unbounded partial removal (PART) OR bounded
    // recurrent-state rollback (RS) deep enough for n_draft (Qwen3.5's hybrid
    // target reports RS thanks to the n_rs_seq set above; the attention-only MTP
    // context reports PART). common_context_can_seq_rm is destructive (clears the
    // context memory + evals two dummy tokens), so it must run here, before any
    // real prompt is decoded. FULL/NO disables speculation cleanly (no mid-run abort).
    // Enabled on both CPU and GPU: the probe below + the runtime seq_rm return
    // checks disable speculation cleanly if the OpenCL backend can't do the
    // recurrent rollback, and a verify decode that the GPU can't run just returns
    // an error we handle (no crash). GPU is where a batched verify can actually pay
    // off (weights loaded once for the whole batch), so it is worth measuring.
    if (ctx_dft != nullptr) {
        const int n_draft = params.spec_n_draft > 0 ? params.spec_n_draft : 3;
        LOGi("MTP: setting up speculation (gpu_enabled=%d)", (int) gpu_enabled);
        common_context_seq_rm_type tgt_rm = common_context_can_seq_rm(ctx);
        common_context_seq_rm_type dft_rm = common_context_can_seq_rm(ctx_dft);
        auto can_rollback = [&](common_context_seq_rm_type t, llama_context * c) {
            return t == COMMON_CONTEXT_SEQ_RM_TYPE_PART ||
                   (t == COMMON_CONTEXT_SEQ_RM_TYPE_RS && (int) llama_n_rs_seq(c) >= n_draft);
        };
        if (!can_rollback(tgt_rm, ctx) || !can_rollback(dft_rm, ctx_dft)) {
            LOGe("MTP: KV rollback insufficient (tgt=%d/%d dft=%d/%d, need>=%d); speculation disabled",
                 (int) tgt_rm, (int) llama_n_rs_seq(ctx),
                 (int) dft_rm, (int) llama_n_rs_seq(ctx_dft), n_draft);
        } else {
            spec_params.types         = { COMMON_SPECULATIVE_TYPE_DRAFT_MTP };
            spec_params.draft.ctx_tgt = ctx;
            spec_params.draft.ctx_dft = ctx_dft;
            spec_params.draft.n_max   = params.spec_n_draft > 0 ? params.spec_n_draft : 3;
            spec_params.draft.n_min   = 0;
            spec_params.draft.p_min   = 0.0f;
            spec = common_speculative_init(spec_params, /*n_seq=*/1);
            if (spec == nullptr) {
                LOGe("MTP: common_speculative_init failed; speculation disabled");
            } else {
                spec_supported = true;
                LOGi("MTP: speculative decoding ready (n_draft=%d)", spec_params.draft.n_max);
            }
        }
    }
}

void LlamaGenerationSession::requestAbort() {
    abort_requested.store(true);
}

void LlamaGenerationSession::setProjector(mtmd_context *mmctx) {
    mctx = mmctx;
}

void LlamaGenerationSession::setImageData(const uint8_t *data, size_t len) {
    if (data == nullptr || len == 0) {
        pending_image_data.clear();
        return;
    }
    pending_image_data.assign(data, data + len);
}

int LlamaGenerationSession::addImageMessage(const char *text, bool enableThinking) {
    if (chat_tmpls == nullptr || ctx == nullptr || mctx == nullptr) {
        LOGe("addImageMessage called on uninitialized/text-only session");
        pending_image_data.clear();
        return 1;
    }

    // Images don't reuse the incremental KV cache — start this turn from a clean
    // context and re-evaluate the full prompt through mtmd.
    llama_memory_clear(llama_get_memory(ctx), true);
    prev_len = 0;
    last_full_prompt.clear();
    last_prompt_end_pos = 0;

    // mtmd_tokenize is handed exactly ONE bitmap, so the rendered prompt must
    // contain exactly ONE media marker. Earlier image turns left theirs in
    // [messages]; their embeddings are gone anyway (the KV was just cleared),
    // so demote old markers to a plain-text placeholder before rendering.
    {
        const std::string marker = mtmd_default_marker();
        for (auto &msg : messages) {
            size_t pos;
            while ((pos = msg.content.find(marker)) != std::string::npos) {
                msg.content.replace(pos, marker.size(), "[image]");
            }
        }
    }

    // Compose the user turn with exactly one media marker so the chat template
    // renders it inside the user message; mtmd_tokenize splits the rendered
    // prompt on this marker and substitutes the image chunk.
    common_chat_msg user_msg;
    user_msg.role = "user";
    user_msg.content = std::string(mtmd_default_marker()) + "\n" + std::string(text);
    messages.push_back(user_msg);

    prev_enable_thinking = enableThinking;

    common_chat_params result;
    try {
        result = renderTemplate(enableThinking);
    } catch (const std::exception &e) {
        LOGe("Failed to render chat template (image): %s", e.what());
        messages.pop_back();
        pending_image_data.clear();
        return 1;
    } catch (...) {
        LOGe("Failed to render chat template (image): unknown error");
        messages.pop_back();
        pending_image_data.clear();
        return 1;
    }
    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;

    // Parser init (mirror addMessage) so finalizeResponse can extract reasoning.
    // Tools are never active on an image turn.
    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        parser_params.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        parser_params.parse_tool_calls = false;
        parser_params.parser.load(result.parser);
        parser_initialized = true;
    } else {
        parser_initialized = false;
    }
    destroyToolSampler();

    // Decode the staged encoded image (jpg/png/…) into an mtmd bitmap.
    mtmd_bitmap *bmp = mtmd_helper_bitmap_init_from_buf(
        mctx, pending_image_data.data(), pending_image_data.size());
    pending_image_data.clear();
    if (bmp == nullptr) {
        LOGe("failed to decode staged image");
        messages.pop_back();
        return 1;
    }

    // Tokenize the rendered prompt + image, then evaluate all chunks. The helper
    // runs llama_decode on text chunks and mtmd_encode + llama_decode on the
    // image chunk, advancing n_past and (logits_last=true) leaving logits on the
    // last token — non-causal mask and M-RoPE positions are handled internally.
    mtmd_input_text itext;
    itext.text = full_prompt.c_str();
    itext.add_special = true;   // fresh KV — add BOS like a first turn
    itext.parse_special = true;
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bmps[1] = { bmp };
    int32_t tk = mtmd_tokenize(mctx, chunks, &itext, bmps, 1);
    if (tk != 0) {
        LOGe("mtmd_tokenize failed: %d", tk);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        messages.pop_back();
        return 1;
    }

    // Image token weight (sum over image chunks) for the UI. Reflects the
    // resolution the projector chose for this image (see image_max_tokens).
    last_image_tokens = 0;
    for (size_t i = 0; i < mtmd_input_chunks_size(chunks); ++i) {
        const mtmd_input_chunk *chunk = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
            last_image_tokens += (int) mtmd_input_chunk_get_n_tokens(chunk);
        }
    }

    llama_pos new_n_past = 0;
    int32_t ev = mtmd_helper_eval_chunks(mctx, ctx, chunks, /*n_past=*/0,
                                         /*seq_id=*/0, llama_n_batch(ctx),
                                         /*logits_last=*/true, &new_n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bmp);
    if (ev != 0) {
        LOGe("mtmd_helper_eval_chunks failed: %d", ev);
        // The KV may hold a partial eval of this turn; wipe it so the pop'd
        // message can't leave stale tokens behind (next turn re-evals fresh).
        llama_memory_clear(llama_get_memory(ctx), true);
        messages.pop_back();
        return 1;
    }

    // State so generate() samples from the last token's logits and a following
    // text turn can prefix-match against this rendered prompt.
    response.clear();
    prompt_tokens.clear();
    last_full_prompt = full_prompt;
    prev_rendered_prompt = full_prompt;
    prev_len = (int) full_prompt.size();
    last_prompt_end_pos = new_n_past;
    skip_first_decode = true;
    // Image turns prime the KV via mtmd (bypassing our decode + MTP process()),
    // so the MTP head is never primed: never speculate on them.
    spec_disabled_this_turn = true;
    skip_next_decode = false;
    return 0;
}

int LlamaGenerationSession::addMessage(const char *string, bool enableThinking) {
    if (chat_tmpls == nullptr || ctx == nullptr) {
        LOGe("addMessage called on uninitialized session");
        return 1;
    }
    // Fresh turn — clear any abort left set by a previous cancel.
    abort_requested.store(false);
    // Fresh text turn: allow speculation (the per-turn gate still applies) and
    // clear any stale speculative-continuation flag from a previous turn.
    spec_disabled_this_turn = false;
    skip_next_decode = false;
    // Reset the per-turn decode measurement (real streamed-token count + window).
    emitted_tokens = 0;
    decode_first_us = 0;
    decode_last_us = 0;

    // Multimodal turn: if an image was staged (setImageData) and a projector is
    // loaded, take the mtmd path (fresh KV + image encode) instead of the
    // text-only incremental KV/prefix path.
    if (!pending_image_data.empty() && mctx != nullptr) {
        return addImageMessage(string, enableThinking);
    }

    // Lazy preamble KV-cache: on the first addMessage of a session, try
    // to load (or just-save) the static system+tools prefix so the user
    // delta can re-use the prefix-match fast path. Subsequent calls skip
    // this — preamble_attempted is set after one go so we don't repeat
    // the work after compaction-induced KV clears.
    if (!preamble_attempted && prev_len == 0 && !preamble_cache_path.empty()) {
        tryPreambleCache(enableThinking);
    }

    common_chat_msg user_msg;
    user_msg.role = "user";
    user_msg.content = string;
    messages.push_back(user_msg);

    auto renderPrompt = [&](bool enableThinking) -> common_chat_params {
        return renderTemplate(enableThinking);
    };

    prev_enable_thinking = enableThinking;

    common_chat_params result;
    try {
        result = renderPrompt(enableThinking);
    } catch (const std::exception &e) {
        LOGe("Failed to render chat template: %s", e.what());
        messages.pop_back();
        return 1;
    } catch (...) {
        LOGe("Failed to render chat template: unknown error");
        messages.pop_back();
        return 1;
    }
    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;

    // Initialize the PEG parser from the freshly-rendered template so
    // `common_chat_parse` is usable for the rest of this turn — including
    // the strip-thinking pass below. The parser is template-derived, so
    // re-rendering after compaction yields the same parser; no need to
    // redo this setup later in the function.
    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        // Always extract reasoning into reasoning_content, regardless of the
        // thinking toggle. Passing NONE makes the gpt-oss/harmony parser leave
        // the raw "<|channel|>analysis<|message|>...<|end|>" markup in the
        // content — gpt-oss emits an analysis channel even at minimal reasoning
        // effort, so "thinking off" must still parse the channels, not dump
        // them. The UI already detects <think> blocks even when the toggle is
        // off (some models always think) and renders them as a collapsible
        // section, so extracting always keeps content clean without losing the
        // reasoning. Models that emit no reasoning when thinking is off yield an
        // empty reasoning_content here, so their output is unchanged.
        parser_params.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        // Tool-call parsing follows the session's tools state (set via
        // setTools / a non-empty tools_enabled). When tools are disabled the
        // parser ignores tool_call grammars in the model output.
        parser_params.parse_tool_calls = tools_enabled;
        parser_params.parser.load(result.parser);
        parser_initialized = true;
    } else {
        parser_initialized = false;
    }

    // Build a fresh tool-calling sampler (grammar + budget) for this turn when
    // tools are active; otherwise tear it down so normal chat uses [smpl].
    if (tools_enabled && !result.grammar.empty()) {
        recreateToolSampler(result);
    } else {
        destroyToolSampler();
    }

    // Apply (or refresh) the lazy tool-call grammar for this turn. Rebuilding
    // each tools-enabled turn gives the lazy grammar a fresh, armed state so
    // the model is constrained to valid tool calls once a trigger fires.
    // Without this, weaker models emit prose describing the tool instead of
    // calling it.

    // Check if the rendered prompt prefix matches what finalizeResponse computed.
    // The Jinja template may render assistant content differently depending on
    // position (e.g. Qwen3 adds <think></think> prefill to the last assistant
    // message but strips it from earlier ones). A mismatch means the KV cache
    // doesn't correspond to the current render, so we must clear and reprocess.
    if (prev_len > 0) {
        bool prefix_match = (int)full_prompt.size() >= prev_len &&
                            full_prompt.compare(0, prev_len, prev_rendered_prompt) == 0;
        if (!prefix_match) {
            LOGi("Prompt prefix mismatch, clearing KV cache");
            llama_memory_clear(llama_get_memory(ctx), true);
            prev_len = 0;
            last_full_prompt.clear();
            last_prompt_end_pos = 0;
        }
    }

    std::string prompt = full_prompt.substr(prev_len);
    response.clear();

    bool is_first = (prev_len == 0);
    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = is_first ? 0 : (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);

    int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);

    bool compacted = false;

    // Stage 1: strip thinking content from older assistant messages
    if (n_ctx_used + n_prompt_tokens > n_ctx) {
        LOGi("Context would overflow (%d + %d > %d), stripping thinking from older turns",
             n_ctx_used, n_prompt_tokens, n_ctx);

        bool stripped_any = false;
        // If the template has no parser, there's no thinking format to
        // strip — the loop is a no-op and we fall through to Stage 2.
        if (parser_initialized) {
            for (size_t i = 0; i + 1 < messages.size(); i++) {
                if (messages[i].role != "assistant") continue;
                try {
                    auto parsed = common_chat_parse(messages[i].content, false, parser_params);
                    if (!parsed.reasoning_content.empty()) {
                        messages[i].content = parsed.content;
                        messages[i].reasoning_content.clear();
                        stripped_any = true;
                    }
                } catch (const std::exception &e) {
                    LOGe("PEG parse failed while stripping older turn: %s", e.what());
                } catch (...) {
                    LOGe("PEG parse failed while stripping older turn: unknown");
                }
            }
        }

        if (stripped_any) {
            try {
                result = renderPrompt(enableThinking);
            } catch (const std::exception &e) {
                LOGe("Failed to render chat template after stripping: %s", e.what());
                messages.pop_back();
                return 1;
            } catch (...) {
                LOGe("Failed to render chat template after stripping: unknown error");
                messages.pop_back();
                return 1;
            }
            full_prompt = result.prompt;
            additional_stops = result.additional_stops;
            prompt = full_prompt;
            is_first = true;
            n_ctx_used = 0;
            n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, true, true);
            compacted = true;
        }
    }

    // Stage 2: drop oldest user+assistant pairs
    while (n_ctx_used + n_prompt_tokens > n_ctx && messages.size() > 1) {
        LOGi("Still overflowing (%d + %d > %d), dropping oldest turn (%zu messages remain)",
             n_ctx_used, n_prompt_tokens, n_ctx, messages.size());

        auto it = messages.begin();
        if (it->role == "system") ++it;
        if (it == messages.end()) break;
        messages.erase(it);

        it = messages.begin();
        if (it->role == "system") ++it;
        if (it != messages.end() && it->role == "assistant") {
            messages.erase(it);
        }

        try {
            result = renderPrompt(enableThinking);
        } catch (const std::exception &e) {
            LOGe("Failed to render chat template after dropping turns: %s", e.what());
            messages.pop_back();
            return 1;
        } catch (...) {
            LOGe("Failed to render chat template after dropping turns: unknown error");
            messages.pop_back();
            return 1;
        }
        full_prompt = result.prompt;
        additional_stops = result.additional_stops;
        prompt = full_prompt;
        is_first = true;
        n_ctx_used = 0;
        n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, true, true);
        compacted = true;
    }

    if (compacted) {
        LOGi("Context compacted, clearing KV cache and reprocessing (%d tokens)", n_prompt_tokens);
        llama_memory_clear(llama_get_memory(ctx), true);
        prev_len = 0;
        last_full_prompt.clear();
        last_prompt_end_pos = 0;
        is_first = true;
    }

    prompt_tokens.resize(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), is_first, true) < 0) {
        LOGe("failed to tokenize the prompt");
        return 1;
    }

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    // Save the rendered string we're about to feed so the tool-call
    // path in generate() can roll back to this point on rc=2 and
    // submitToolResults can string-prefix-match against it. Token
    // count gets captured at the end of the first generate() call,
    // after llama_decode has actually committed these tokens to the
    // KV cache.
    last_full_prompt = full_prompt;


    // Add reasoning budget sampler on first thinking-enabled turn, using
    // the model's actual thinking tags from the template (not hardcoded).
    // Must be first in chain (before top-k/top-p/temp) so it can override logits,
    // so we rebuild the entire sampler chain.
    if (sampler_params.thinking_budget >= 0 && enableThinking && !budget_sampler_added && result.supports_thinking && gsmpl == nullptr) {
        auto tokenize_str = [&](const std::string &text) -> std::vector<llama_token> {
            int n = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, false, true);
            std::vector<llama_token> tokens(n);
            llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), false, true);
            return tokens;
        };

        std::string start_tag = result.thinking_start_tag;
        std::string end_tag = result.thinking_end_tag;

        // For gpt-oss (Gemma 4) and similar models that use channel-based thinking,
        // thinking_start_tag/end_tag may be empty — detect from preserved tokens
        if (start_tag.empty() && !result.preserved_tokens.empty()) {
            for (const auto &tok : result.preserved_tokens) {
                if (tok.find("channel") != std::string::npos) {
                    start_tag = "<|channel|>analysis<|message|>";
                    end_tag = "<|end|>";
                    break;
                }
            }
        }

        if (!start_tag.empty() && !end_tag.empty()) {
            // Rebuild sampler chain with budget sampler first
            llama_sampler_free(smpl);
            auto smplParams = llama_sampler_chain_default_params();
            smplParams.no_perf = false;
            smpl = llama_sampler_chain_init(smplParams);

            // Budget sampler first (must override logits before other samplers filter)
            auto start_tokens  = tokenize_str(start_tag);
            auto end_tokens    = tokenize_str(end_tag);
            auto forced_tokens = end_tokens;
            llama_sampler_chain_add(smpl, common_reasoning_budget_init(
                    vocab, start_tokens, end_tokens, forced_tokens, sampler_params.thinking_budget));

            // Re-add other samplers in original order
            if (sampler_params.repetition_penalty > 1.0f)
                llama_sampler_chain_add(smpl, llama_sampler_init_penalties(256, sampler_params.repetition_penalty, 0.0f, 0.0f));
            if (sampler_params.top_k > 0)
                llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampler_params.top_k));
            if (sampler_params.top_p < 1.0f)
                llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sampler_params.top_p, 1));
            if (sampler_params.min_p > 0.0f)
                llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sampler_params.min_p, 1));
            if (sampler_params.temperature == 0.0f) {
                llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
            } else {
                llama_sampler_chain_add(smpl, llama_sampler_init_temp(sampler_params.temperature));
                llama_sampler_chain_add(smpl, llama_sampler_init_dist(sampler_params.seed));
            }

            budget_sampler_added = true;
            LOGi("Reasoning budget sampler added: budget=%d, start='%s', end='%s'",
                 sampler_params.thinking_budget, start_tag.c_str(), end_tag.c_str());
        }
    }

    return 0;
}

void LlamaGenerationSession::finalizeResponse() {
    common_chat_msg assistant_msg;
    assistant_msg.role = "assistant";
    assistant_msg.content = response;
    messages.push_back(assistant_msg);

    if (parser_initialized) {
        try {
            auto parsed = common_chat_parse(response, /*is_partial=*/false, parser_params);
            prev_had_thinking = !parsed.reasoning_content.empty();
        } catch (const std::exception &e) {
            LOGe("PEG parse failed in finalizeResponse: %s", e.what());
            prev_had_thinking = response.find("</think>") != std::string::npos;
        } catch (...) {
            LOGe("PEG parse failed in finalizeResponse: unknown");
            prev_had_thinking = response.find("</think>") != std::string::npos;
        }
    } else {
        prev_had_thinking = response.find("</think>") != std::string::npos;
    }

    try {
        auto result = renderTemplate(prev_enable_thinking, /*addGenerationPrompt=*/false);
        prev_rendered_prompt = result.prompt;
        prev_len = (int)prev_rendered_prompt.size();
    } catch (const std::exception &e) {
        LOGe("Failed to render chat template in finalizeResponse: %s", e.what());
        prev_rendered_prompt.clear();
        prev_len = 0;
    } catch (...) {
        LOGe("Failed to render chat template in finalizeResponse: unknown error");
        prev_rendered_prompt.clear();
        prev_len = 0;
    }
}

int LlamaGenerationSession::generate(const ResponseCallback& callback) {
    if (ctx == nullptr || smpl == nullptr) {
        LOGe("generate called on uninitialized session");
        return 1;
    }

    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(ctx), 0);

    // Increment 2 per-turn gate: speculate only on plain greedy chat/benchmark.
    // Excludes tool grammar (gsmpl), the reasoning-budget sampler, temp>0 (the
    // greedy verify assumes an exact argmax match), and image turns (whose prompt
    // decode bypasses our llama_decode + the MTP process() calls).
    spec_active = spec_supported
        && !spec_disabled_this_turn
        && (gsmpl == nullptr)
        && !budget_sampler_added
        && !tools_enabled
        && (sampler_params.temperature == 0.0f);

    if (skip_next_decode) {
        // Speculative continuation: last_token is the pending verify seed, not in
        // the KV yet. Skip the normal prompt decode (and its process()); the seed
        // is decoded as element 0 of the verify batch inside the step.
        skip_next_decode = false;
        return generateSpeculativeStep(callback);
    }

    if (skip_first_decode) {
        // Image turn: mtmd_helper_eval_chunks already ran llama_decode on all
        // text + image chunks (logits on the last token), so skip the prompt
        // decode entirely. The prompt-token priming block below is bypassed
        // (prompt_tokens is empty for an image turn), so reset the sampler here
        // for a clean turn.
        skip_first_decode = false;
        llama_sampler_reset(smpl);
    } else {
        if (n_ctx_used + batch.n_tokens > n_ctx) {
            LOGe("context size exceeded: n_ctx_used = %d, batch.n_tokens = %d, n_ctx = %d", n_ctx_used, batch.n_tokens, n_ctx);
            finalizeResponse();
            return 1;
        }

        // Process prompt in chunks of n_batch to avoid exceeding the batch limit.
        // After replayHistory or context compaction the prompt can be much larger
        // than n_batch since the entire conversation is re-tokenized.
        int n_batch_limit = llama_n_batch(ctx);
        if (spec_active) {
            // Self-MTP: decode the prompt with an output row on EVERY position so
            // the target's per-token nextn hidden states exist, and feed each chunk
            // to the MTP head (its recurrent state must track the whole prompt
            // before it can draft). Chunked to stay within n_batch.
            llama_token * toks = batch.token;
            int remaining = batch.n_tokens;
            while (remaining > 0) {
                int chunk = std::min(remaining, n_batch_limit);
                if (decodeSpecPromptChunk(toks, chunk)) {
                    finalizeResponse();
                    return 1;
                }
                toks += chunk;
                remaining -= chunk;
            }
        } else {
            // Normal path (unchanged): only the last token needs an output row.
            while (batch.n_tokens > n_batch_limit) {
                llama_batch chunk = llama_batch_get_one(batch.token, n_batch_limit);
                if (llama_decode(ctx, chunk)) {
                    LOGe("failed to decode prompt chunk");
                    finalizeResponse();
                    return 1;
                }
                batch = llama_batch_get_one(batch.token + n_batch_limit, batch.n_tokens - n_batch_limit);
            }
            if (llama_decode(ctx, batch)) {
                LOGe("failed to decode the batch");
                finalizeResponse();
                return 1;
            }
        }
    }

    // Reset sampler and feed prompt tokens so the reasoning budget sampler
    // can detect <think> prefill from chat templates (e.g. Qwen3).
    // Only on the first call per turn (prompt_tokens is non-empty).
    if (!prompt_tokens.empty()) {
        if (gsmpl != nullptr) {
            // Tool path: feed prompt tokens as non-generated (penalty/history
            // context only). The grammar + reasoning-budget prefill is handled
            // at sampler-init time from the template's generation_prompt.
            common_sampler_reset(gsmpl);
            for (const auto &token : prompt_tokens) {
                common_sampler_accept(gsmpl, token, /*is_generated=*/false);
            }
        } else {
            llama_sampler_reset(smpl);
            for (const auto &token : prompt_tokens) {
                llama_sampler_accept(smpl, token);
            }
        }
        if (spec_active) {
            // Prime the MTP head for this turn. begin() wants the prompt token
            // vector (MTP ignores its content, but the API stores the pointer, so
            // spec_prompt must outlive the draft calls that reference it).
            spec_prompt = prompt_tokens;
            common_speculative_begin(spec, /*seq_id=*/0, spec_prompt);
        }
        prompt_tokens.clear();
        // KV cache now contains the full prompt (everything llama_decode
        // just committed). Snapshot this position — if the model emits
        // a tool_call, we'll discard everything past it and feed only
        // the tool-result delta instead of re-tokenizing the world.
        last_prompt_end_pos = llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
    }

    if (gsmpl != nullptr) {
        // Mac-parity tool sampling: grammar-constrained sample-then-validate,
        // with controlled accept (common_sampler_sample does not auto-accept).
        last_token = common_sampler_sample(gsmpl, ctx, -1);
        common_sampler_accept(gsmpl, last_token, /*is_generated=*/true);
    } else {
        last_token = llama_sampler_sample(smpl, ctx, -1);
    }

    EmitResult er = emitToken(last_token, callback);
    if (er == EMIT_ERROR) {
        finalizeResponse();
        return 1;
    }
    if (er == EMIT_CONTINUE) {
        if (spec_active) {
            // First token of a speculative turn: sampled normally (no id_last to
            // draft from yet). Hand off to the speculative step on the next call
            // by leaving last_token as the pending, uncommitted verify seed.
            skip_next_decode = true;
        } else {
            batch = llama_batch_get_one(&last_token, 1);
        }
        return 0;
    }
    // er == EMIT_STOP: fall through to the tool-call check + finalize below.

    // Check for tool calls before finalizing
    if (tools_enabled && parser_initialized) {
        common_chat_msg parsed;
        bool parsed_ok = false;
        try {
            parsed = common_chat_parse(response, /*is_partial=*/false, parser_params);
            parsed_ok = true;
        } catch (const std::exception &e) {
            // Some templates' tool-call parsers throw on their own model's
            // output (e.g. LFM2.5 350M's "<|tool_call_end|>" format). A parse
            // failure must never crash the inference process — fall back to
            // treating the output as a plain-text response.
            LOGe("Tool-call parse failed, treating as plain response: %s", e.what());
        } catch (...) {
            LOGe("Tool-call parse failed (unknown), treating as plain response");
        }
        if (parsed_ok && !parsed.tool_calls.empty()) {
            std::vector<std::string> ids_cache;
            auto gen_id = [this]() -> std::string {
                return "call_" + std::to_string(tool_call_counter++);
            };

            common_chat_msg assistant_msg;
            assistant_msg.role = "assistant";
            assistant_msg.content = parsed.content;
            assistant_msg.reasoning_content = parsed.reasoning_content;
            assistant_msg.tool_calls = parsed.tool_calls;
            assistant_msg.set_tool_call_ids(ids_cache, gen_id);
            pending_tool_calls = assistant_msg.tool_calls;
            messages.push_back(assistant_msg);

            // KV cache reuse for the upcoming submitToolResults: the
            // cache currently contains [last_full_prompt tokens] +
            // [model's tool_call output tokens]. The model's output
            // tokens will be re-rendered differently by the chat
            // template in round 2 (template-specific tool_call
            // formatting != raw bytes the model emitted), so they're
            // not safe to keep. But the prompt prefix IS safe — roll
            // back to that position so submitToolResults can feed only
            // the tool-result delta. Saves a full prompt-eval pass on
            // every tool round (huge on phone CPU where prompt-eval
            // dominates wall time).
            if (last_prompt_end_pos > 0 && !last_full_prompt.empty()) {
                llama_memory_seq_rm(
                    llama_get_memory(ctx), 0, last_prompt_end_pos, -1);
                prev_rendered_prompt = last_full_prompt;
                prev_len = (int)last_full_prompt.size();
            }

            LOGi("Tool calls detected: %zu calls (KV preserved at pos %d)",
                 pending_tool_calls.size(), last_prompt_end_pos);
            response.clear();
            return 2;
        }
    }

    finalizeResponse();
    return 1;
}

// Convert one sampled token to text and stream it. Returns EMIT_STOP on EOG or a
// stop-string (response already trimmed), EMIT_ERROR on a token_to_piece failure,
// EMIT_CONTINUE otherwise. Shared by the normal decode path and the speculative
// step so both stream identically (same PEG normalization, same monotonic
// append-only contract the service relies on).
LlamaGenerationSession::EmitResult
LlamaGenerationSession::emitToken(llama_token tok, const ResponseCallback& callback) {
    if (llama_vocab_is_eog(vocab, tok)) {
        return EMIT_STOP;
    }

    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) {
        LOGe("failed to convert token to piece");
        return EMIT_ERROR;
    }
    std::string piece(buf, n);
    response += piece;

    for (const auto& stop : additional_stops) {
        if (response.size() >= stop.size() &&
            response.compare(response.size() - stop.size(), stop.size(), stop) == 0) {
            response.erase(response.size() - stop.size());
            return EMIT_STOP;
        }
    }

    // Use PEG parser to normalize thinking format for the UI. Emitting only the
    // parsed content keeps the streamed string monotonic ("" -> "<think>..." ->
    // "<think>...</think>content"), which the service's append-only delta
    // accumulation (response.substring(sentLength)) depends on.
    if (parser_initialized) {
        try {
            auto parsed = common_chat_parse(response, /*is_partial=*/true, parser_params);
            std::string normalized;
            if (!parsed.reasoning_content.empty()) {
                normalized = "<think>" + parsed.reasoning_content;
                if (!parsed.content.empty()) {
                    normalized += "</think>" + parsed.content;
                }
            } else {
                normalized = parsed.content;
            }
            callback(normalized);
        } catch (const std::exception &e) {
            LOGe("PEG parse failed in generate (partial): %s", e.what());
            callback(response);
        } catch (...) {
            LOGe("PEG parse failed in generate (partial): unknown");
            callback(response);
        }
    } else {
        callback(response);
    }
    // Count the streamed token and stamp the decode window (native, so MTP bursts
    // are measured accurately regardless of stream-callback coalescing).
    const int64_t now_us = ggml_time_us();
    if (emitted_tokens == 0) decode_first_us = now_us;
    decode_last_us = now_us;
    emitted_tokens++;
    return EMIT_CONTINUE;
}

// Decode one run of prompt tokens for self-MTP: every position gets an output row
// (logits=1) so the target produces a nextn hidden state per token, then hand the
// batch to the MTP head. Positions continue from the current KV, matching what
// llama_batch_get_one would assign. Returns 0 ok, 1 on decode failure.
int LlamaGenerationSession::decodeSpecPromptChunk(llama_token * tokens, int n_tokens) {
    // A full batch is REQUIRED here: common_speculative_process reads batch.pos[k]
    // and batch.seq_id[k][0] for every token, but llama_batch_get_one leaves those
    // null (it relies on the context to fill positions), so feeding a get_one
    // batch to process() null-derefs. We also only need an output row on the last
    // token (for sampling); the MTP head reads ALL tokens' nextn hidden states in
    // unmasked mode regardless of the logits flags (qwen35.cpp sets t_h_nextn
    // before reducing to output rows), so we don't pay for full per-token logits.
    const llama_pos pos0 = llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
    llama_batch b = llama_batch_init(n_tokens, /*embd=*/0, /*n_seq_max=*/1);
    for (int i = 0; i < n_tokens; i++) {
        b.token[i]     = tokens[i];
        b.pos[i]       = pos0 + i;
        b.n_seq_id[i]  = 1;
        b.seq_id[i][0] = 0;
        b.logits[i]    = (i == n_tokens - 1) ? 1 : 0;
        b.n_tokens++;
    }
    const int rc = llama_decode(ctx, b);
    if (rc == 0) {
        common_speculative_process(spec, b);
    } else {
        LOGe("MTP: prompt chunk decode failed rc=%d", rc);
    }
    llama_batch_free(b);
    return rc ? 1 : 0;
}

// One self-MTP speculative decode step. Invariant on entry: last_token is the
// pending verify seed (already sampled + emitted last step, NOT yet in the KV),
// and spec/ctx_dft are valid. Draft K tokens from the MTP head, verify them in a
// single target decode, accept the greedy-matching prefix (+1 free token), roll
// back the rejected drafts, and emit every accepted token. On continue, the last
// accepted token becomes the next pending seed (skip_next_decode stays the
// driver). All KV positions are read fresh from the memory, never counted by hand.
int LlamaGenerationSession::generateSpeculativeStep(const ResponseCallback& callback) {
    if (spec == nullptr || ctx_dft == nullptr) {
        // Defensive: never expected (spec_active implies both). Decode the pending
        // seed and sample once the normal way, then hand back to the normal path.
        llama_batch sb = llama_batch_get_one(&last_token, 1);
        if (llama_decode(ctx, sb)) { finalizeResponse(); return 1; }
        last_token = llama_sampler_sample(smpl, ctx, -1);
        EmitResult er = emitToken(last_token, callback);
        if (er != EMIT_CONTINUE) { finalizeResponse(); return 1; }
        batch = llama_batch_get_one(&last_token, 1);
        return 0;
    }

    const int n_ctx  = llama_n_ctx(ctx);
    const llama_pos n_past = llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;

    // Context full: the pending seed no longer fits. This branch replaces the
    // normal path's n_ctx_used overflow guard, which spec continuations skip.
    if ((int) n_past >= n_ctx) {
        LOGe("MTP: context full (n_past=%d, n_ctx=%d); stopping", (int) n_past, n_ctx);
        finalizeResponse();
        return 1;
    }

    // Cap the draft so [seed + drafts] fits the context with a 1-token margin.
    int n_draft_max = std::min(spec_params.draft.n_max, n_ctx - (int) n_past - 1);

    // Helper: no speculation this step. Decode the pending seed alone (via the
    // full-batch path so process() gets valid pos/seq_id), feed the MTP head,
    // sample one token the normal way, keep the speculative contract.
    auto decode_seed_and_sample = [&]() -> int {
        if (decodeSpecPromptChunk(&last_token, 1)) { finalizeResponse(); return 1; }
        last_token = llama_sampler_sample(smpl, ctx, -1);
        EmitResult er = emitToken(last_token, callback);
        if (er != EMIT_CONTINUE) { finalizeResponse(); return 1; }
        skip_next_decode = true;
        return 0;
    };

    if (n_draft_max < 1) {
        return decode_seed_and_sample();   // no room to speculate
    }

    // 1) Draft K tokens from the MTP head. The result buffer MUST be empty first.
    spec_draft.clear();
    common_speculative_draft_params & dp = common_speculative_get_draft_params(spec, /*seq_id=*/0);
    dp.drafting = true;
    dp.n_max    = n_draft_max;
    dp.n_past   = n_past;
    dp.id_last  = last_token;
    dp.prompt   = &spec_prompt;
    dp.result   = &spec_draft;
    common_speculative_draft(spec);

    // common_speculative_draft ran the MTP head autoregressively, pre-advancing
    // ctx_dft's KV to positions n_past..n_past+K-1. Undo that now (exactly as the
    // server does between draft and process, server-context.cpp): the verify
    // process() below re-decodes those same positions into ctx_dft, and llama.cpp's
    // KV cache APPENDS new cells rather than overwriting same-position ones, so
    // without this rollback ctx_dft accumulates duplicate cells, the draft state
    // corrupts, and acceptance collapses toward zero (no speedup). Runs before the
    // K<1 / decode_seed_and_sample branch, which also re-decodes ctx_dft at n_past.
    if (!llama_memory_seq_rm(llama_get_memory(ctx_dft), 0, n_past, -1)) {
        LOGe("MTP: ctx_dft rollback after draft failed; disabling speculation");
        spec_supported = false;
        finalizeResponse();
        return 1;
    }

    const int K = (int) spec_draft.size();
    if (K < 1) {
        return decode_seed_and_sample();   // the head declined to draft
    }

    // 2) Build the target verify batch [seed, draft0..draftK-1] at consecutive
    //    positions from n_past, logits on every row (output row i == batch index i).
    llama_batch vb = llama_batch_init(K + 1, /*embd=*/0, /*n_seq_max=*/1);
    auto vb_add = [&](llama_token t, llama_pos p) {
        const int i = vb.n_tokens;
        vb.token[i]     = t;
        vb.pos[i]       = p;
        vb.n_seq_id[i]  = 1;
        vb.seq_id[i][0] = 0;
        vb.logits[i]    = 1;
        vb.n_tokens++;
    };
    llama_pos p = n_past;
    vb_add(last_token, p++);
    for (llama_token d : spec_draft) vb_add(d, p++);

    // 3) One target decode, then feed the same batch to the MTP head.
    if (llama_decode(ctx, vb)) {
        LOGe("MTP: verify decode failed (K=%d n_past=%d n_outputs=%d)", K, (int) n_past, K + 1);
        llama_batch_free(vb);
        finalizeResponse();
        return 1;
    }
    common_speculative_process(spec, vb);
    llama_batch_free(vb);

    // 4) Greedy verify+accept against the raw smpl chain. llama_sampler_sample
    //    ALREADY calls llama_sampler_accept internally, so we must not accept
    //    again. accepted[i] is the target's own token at row i; accept while it
    //    equals the drafted token, stop at the first mismatch (the mismatch token
    //    is the target's correction and is kept). If all K match, take one free
    //    bonus token from row K.
    std::vector<llama_token> accepted;
    accepted.reserve(K + 1);
    int i = 0;
    for (; i < K; i++) {
        llama_token id = llama_sampler_sample(smpl, ctx, i);
        accepted.push_back(id);
        if (spec_draft[i] != id) break;
    }
    if (i == K) {
        accepted.push_back(llama_sampler_sample(smpl, ctx, K));
    }
    const int n_accepted_drafts = (int) accepted.size() - 1;   // >= 0

    // 5) Tell the MTP head how many drafts matched (updates its hidden-state
    //    carry-over), then roll back the rejected drafts from both KV caches. We
    //    keep [seed .. seed + n_accepted_drafts]; the last accepted token is the
    //    next seed and is intentionally NOT committed (it re-enters as verify
    //    element 0 next step). Positions are half-open [keep_end, -1).
    common_speculative_accept(spec, /*seq_id=*/0, (uint16_t) n_accepted_drafts);
    spec_draft_total  += K;
    spec_accept_total += n_accepted_drafts;
    spec_steps        += 1;
    const llama_pos keep_end = n_past + (llama_pos) accepted.size();
    bool rm_ok = llama_memory_seq_rm(llama_get_memory(ctx), 0, keep_end, -1);
    rm_ok = llama_memory_seq_rm(llama_get_memory(ctx_dft), 0, keep_end, -1) && rm_ok;
    if (!rm_ok) {
        // Partial removal failed at runtime (the init probe should have caught
        // this). Disable speculation for the rest of the session and stop this
        // turn rather than continue from an inconsistent KV.
        LOGe("MTP: seq_rm failed mid-generation; disabling speculation");
        spec_supported = false;
        finalizeResponse();
        return 1;
    }

    // 6) Emit every accepted token in order; stop at the first EOG / stop-string.
    for (size_t j = 0; j < accepted.size(); j++) {
        last_token = accepted[j];
        EmitResult er = emitToken(last_token, callback);
        if (er == EMIT_ERROR || er == EMIT_STOP) {
            // Keep only [seed .. accepted[j-1]] (positions n_past .. n_past+j) so a
            // reused KV stays clean; drop this token and everything after it.
            llama_memory_seq_rm(llama_get_memory(ctx),     0, n_past + (llama_pos) j + 1, -1);
            llama_memory_seq_rm(llama_get_memory(ctx_dft), 0, n_past + (llama_pos) j + 1, -1);
            finalizeResponse();
            return 1;
        }
    }

    // Continue: the last accepted token is the pending seed for the next step.
    skip_next_decode = true;
    return 0;
}

void LlamaGenerationSession::printReport() {
    llama_perf_context_print(ctx);
}

void LlamaGenerationSession::replayHistory(const std::vector<std::pair<std::string, std::string>>& history) {
    messages.clear();
    if (!sampler_params.system_prompt.empty()) {
        common_chat_msg system_msg;
        system_msg.role = "system";
        system_msg.content = sampler_params.system_prompt;
        messages.push_back(system_msg);
    }
    for (const auto& pair : history) {
        common_chat_msg user_msg;
        user_msg.role = "user";
        user_msg.content = pair.first;
        messages.push_back(user_msg);

        common_chat_msg assistant_msg;
        assistant_msg.role = "assistant";
        assistant_msg.content = pair.second;
        messages.push_back(assistant_msg);
    }
    prev_len = 0;
    prev_rendered_prompt.clear();
    prev_had_thinking = false;
    prev_enable_thinking = false;
    response.clear();
    last_full_prompt.clear();
    last_prompt_end_pos = 0;
    if (ctx != nullptr) {
        llama_memory_clear(llama_get_memory(ctx), true);
    }
    LOGi("Replayed %zu turns of history", history.size());
}

common_chat_params LlamaGenerationSession::renderTemplate(bool enableThinking, bool addGenerationPrompt) {
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = addGenerationPrompt;
    inputs.use_jinja = true;
    inputs.enable_thinking = enableThinking;
    // Always extract reasoning. For channel-based formats (gpt-oss / harmony)
    // the extract-vs-raw decision is baked into the parser grammar at apply
    // time from this field. Leaving it NONE when thinking is off would make
    // the parser dump literal "<|channel|>analysis<|message|>...<|end|>"
    // markup into content — gpt-oss emits the analysis channel even at
    // minimal effort. enable_thinking (above) independently drives the
    // prompt's reasoning effort, so this doesn't force the model to think.
    inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    if (tools_enabled) {
        inputs.tools = tools;
        inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
    }
    return common_chat_templates_apply(chat_tmpls, inputs);
}

std::string LlamaGenerationSession::renderPreambleString(bool enableThinking) {
    if (chat_tmpls == nullptr) return "";

    // Swap messages aside so we can render in isolation, then restore.
    auto saved = std::move(messages);
    messages.clear();

    // Strategy A: render with [system] alone, no generation prompt. Works
    // for Gemma, Qwen3, LFM2.5. Some templates (e.g. Qwen3.5 multimodal)
    // throw because they require at least one user turn — fall back to B.
    std::string preamble;
    if (!sampler_params.system_prompt.empty()) {
        common_chat_msg sys;
        sys.role = "system";
        sys.content = sampler_params.system_prompt;
        messages.push_back(sys);
    }
    try {
        auto result = renderTemplate(enableThinking, /*addGenerationPrompt=*/false);
        preamble = result.prompt;
    } catch (const std::exception &e) {
        LOGi("renderPreambleString A failed (%s), trying fallback", e.what());
    } catch (...) {
        LOGi("renderPreambleString A failed (unknown), trying fallback");
    }

    if (preamble.empty()) {
        // Strategy B: render with [system?, user="<<__PREAMBLE_PROBE__>>"],
        // find the unique probe in the rendered output, return everything
        // up to and including the user-marker prefix that precedes it.
        // We isolate the byte offset of the probe content; the preamble is
        // [0, probe_start_in_output), but we want to KEEP the user-role
        // marker that opens the user turn since it's part of the static
        // structure that follows the preamble. Actually no — we DON'T want
        // the user-marker, because the user turn marker varies (or could
        // be reused). So preamble = [0, probe_start) — everything BEFORE
        // the user-marker content. This gives us the static prefix.
        //
        // The fallback only succeeds if the probe sentinel ends up in the
        // rendered output verbatim (it should, since templates pass user
        // content through unchanged). If not, return empty.
        messages.clear();
        if (!sampler_params.system_prompt.empty()) {
            common_chat_msg sys;
            sys.role = "system";
            sys.content = sampler_params.system_prompt;
            messages.push_back(sys);
        }
        const std::string probe = "__PREAMBLE_PROBE_5MhEU3xQ__";
        common_chat_msg user;
        user.role = "user";
        user.content = probe;
        messages.push_back(user);
        try {
            auto result = renderTemplate(enableThinking, /*addGenerationPrompt=*/false);
            const auto &rendered = result.prompt;
            auto pos = rendered.find(probe);
            if (pos == std::string::npos) {
                LOGi("renderPreambleString fallback: probe not found in rendered output");
            } else {
                // Walk back through any user-role markers immediately
                // preceding the probe so the preamble doesn't include the
                // open-user-turn marker. Those markers are template-
                // specific (e.g. "<|im_start|>user\n", "<start_of_turn>user\n"),
                // so we just trim trailing newlines + a small heuristic
                // window. Simpler & safe: report position right BEFORE
                // the user marker (find the last "<" before pos).
                auto marker_start = rendered.rfind('<', pos);
                if (marker_start == std::string::npos || marker_start < pos - 80) {
                    // Couldn't locate — fall back to "everything before the probe content".
                    preamble = rendered.substr(0, pos);
                } else {
                    preamble = rendered.substr(0, marker_start);
                }
                LOGi("renderPreambleString fallback B succeeded: %zu bytes", preamble.size());
            }
        } catch (const std::exception &e) {
            LOGe("renderPreambleString fallback failed: %s", e.what());
        } catch (...) {
            LOGe("renderPreambleString fallback failed: unknown");
        }
    }

    messages = std::move(saved);
    return preamble;
}

void LlamaGenerationSession::setPreambleCachePath(const char* path, const char* fingerprint) {
    preamble_cache_path = (path != nullptr) ? path : "";
    preamble_cache_fingerprint = (fingerprint != nullptr) ? fingerprint : "";
    preamble_attempted = false;
}

bool LlamaGenerationSession::tryPreambleCache(bool enableThinking) {
    preamble_attempted = true; // never retry per session, success or failure

    if (ctx == nullptr || preamble_cache_path.empty()) {
        return false;
    }

    // 1. Derive the preamble string. Returns "" if the template can't render in isolation.
    const std::string preamble = renderPreambleString(enableThinking);
    if (preamble.size() < 32) {
        // Tiny preamble (probably system-only with no tools) — saving is more
        // overhead than benefit. Disk write of an empty cache also burns one
        // LRU slot for nothing. Skip.
        LOGi("Preamble cache: preamble %zu B — too small, skipping", preamble.size());
        return false;
    }

    const std::string bin_path  = preamble_cache_path + ".bin";
    const std::string json_path = preamble_cache_path + ".json";

    auto file_readable = [](const std::string &p) -> bool {
        std::ifstream f(p);
        return f.good();
    };

    // 2. Try cache hit
    if (file_readable(bin_path) && file_readable(json_path)) {
        try {
            std::ifstream jf(json_path);
            nlohmann::json manifest;
            jf >> manifest;

            const bool fp_ok      = manifest.value("fingerprint", "") == preamble_cache_fingerprint;
            const bool ver_ok     = manifest.value("version", 0) == 1;
            const bool ctx_ok     = manifest.value("n_ctx", -1) == llama_n_ctx(ctx);
            const bool prelude_ok = manifest.value("preamble", "") == preamble;

            if (fp_ok && ver_ok && ctx_ok && prelude_ok) {
                std::vector<llama_token> tokens(llama_n_ctx(ctx));
                size_t n_loaded = 0;
                bool ok = llama_state_seq_load_file(ctx, bin_path.c_str(),
                                                    /*dest_seq_id=*/0,
                                                    tokens.data(), tokens.size(),
                                                    &n_loaded);
                if (ok && n_loaded > 0) {
                    prev_rendered_prompt = preamble;
                    prev_len = (int)preamble.size();
                    LOGi("Preamble cache HIT: loaded %zu tokens from %s",
                         n_loaded, bin_path.c_str());
                    return true;
                }
                LOGi("Preamble cache: load returned ok=%d n_loaded=%zu, falling through to miss path",
                     (int)ok, n_loaded);
            } else {
                LOGi("Preamble cache: manifest mismatch (fp=%d ver=%d ctx=%d preamble=%d), regenerating",
                     (int)fp_ok, (int)ver_ok, (int)ctx_ok, (int)prelude_ok);
            }
        } catch (const std::exception &e) {
            LOGi("Preamble cache: manifest read failed (%s), regenerating", e.what());
        } catch (...) {
            LOGi("Preamble cache: manifest read failed, regenerating");
        }
        // Stale or unreadable: clean up so we don't keep retrying.
        unlink(bin_path.c_str());
        unlink(json_path.c_str());
    }

    // 3. Cache miss: prefill the preamble alone, then save.
    int n_ctx = llama_n_ctx(ctx);
    int n_prompt_tokens = -llama_tokenize(vocab, preamble.c_str(), preamble.size(),
                                           nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n_prompt_tokens <= 0 || n_prompt_tokens >= n_ctx) {
        LOGi("Preamble cache: %d tokens too large for n_ctx=%d, skipping", n_prompt_tokens, n_ctx);
        return false;
    }

    std::vector<llama_token> tokens(n_prompt_tokens);
    if (llama_tokenize(vocab, preamble.c_str(), preamble.size(),
                       tokens.data(), tokens.size(),
                       /*add_special=*/true, /*parse_special=*/true) < 0) {
        LOGi("Preamble cache: tokenize failed");
        return false;
    }

    // Decode in batches matching n_batch from init().
    const int n_batch = std::min(n_ctx, 512);
    for (int i = 0; i < (int)tokens.size(); i += n_batch) {
        int chunk = std::min(n_batch, (int)tokens.size() - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(ctx, b) != 0) {
            LOGi("Preamble cache: decode failed at chunk %d, clearing KV", i);
            llama_memory_clear(llama_get_memory(ctx), true);
            return false;
        }
    }

    // KV is now populated. Wire prefix-match state so addMessage's existing
    // logic feeds only the user-message delta.
    prev_rendered_prompt = preamble;
    prev_len = (int)preamble.size();

    // Save to disk. Best-effort: a save failure means no cache for next time
    // but the in-memory state is correct, so the current turn benefits anyway.
    if (!llama_state_seq_save_file(ctx, bin_path.c_str(),
                                    /*seq_id=*/0,
                                    tokens.data(), tokens.size())) {
        LOGi("Preamble cache: save_file failed (continuing without cache)");
        unlink(bin_path.c_str());
        return true;
    }

    try {
        nlohmann::ordered_json manifest;
        manifest["version"]     = 1;
        manifest["fingerprint"] = preamble_cache_fingerprint;
        manifest["n_ctx"]       = n_ctx;
        manifest["preamble"]    = preamble;
        manifest["n_tokens"]    = (int)tokens.size();
        std::ofstream out(json_path);
        out << manifest.dump();
        if (!out) {
            LOGi("Preamble cache: manifest write failed, removing .bin to keep state consistent");
            unlink(bin_path.c_str());
        } else {
            LOGi("Preamble cache MISS: prefilled & saved %zu tokens to %s",
                 tokens.size(), bin_path.c_str());
        }
    } catch (const std::exception &e) {
        LOGi("Preamble cache: manifest serialize failed (%s)", e.what());
        unlink(bin_path.c_str());
    }

    return true;
}

void LlamaGenerationSession::setTools(const char *toolsJson) {
    pending_tool_calls.clear();
    if (toolsJson == nullptr || strlen(toolsJson) == 0 || strcmp(toolsJson, "[]") == 0) {
        tools.clear();
        tools_enabled = false;
        return;
    }
    try {
        auto j = nlohmann::ordered_json::parse(toolsJson);
        tools = common_chat_tools_parse_oaicompat(j);
        tools_enabled = !tools.empty();
        LOGi("Tools set: %zu tools, enabled: %d", tools.size(), (int)tools_enabled);
    } catch (const std::exception &e) {
        LOGe("Failed to parse tools JSON: %s", e.what());
        tools.clear();
        tools_enabled = false;
    }
}

std::string LlamaGenerationSession::getToolCallsJson() {
    nlohmann::ordered_json arr = nlohmann::ordered_json::array();
    for (const auto &tc : pending_tool_calls) {
        nlohmann::ordered_json obj;
        obj["id"] = tc.id;
        obj["name"] = tc.name;
        obj["arguments"] = tc.arguments;
        arr.push_back(obj);
    }
    return arr.dump();
}

int LlamaGenerationSession::submitToolResults(const char *resultsJson, bool enableThinking) {
    if (chat_tmpls == nullptr || ctx == nullptr) {
        LOGe("submitToolResults called on uninitialized session");
        return 1;
    }
    // New decode phase — clear any abort left set by a previous cancel.
    abort_requested.store(false);
    // Tool-result turns run with tools enabled, so the per-turn gate already
    // excludes speculation; clear the continuation flag defensively regardless.
    skip_next_decode = false;
    spec_disabled_this_turn = false;

    try {
        auto results = nlohmann::ordered_json::parse(resultsJson);
        for (const auto &result : results) {
            common_chat_msg tool_msg;
            tool_msg.role = "tool";
            tool_msg.content = result["content"].get<std::string>();
            tool_msg.tool_call_id = result["id"].get<std::string>();
            tool_msg.tool_name = result["name"].get<std::string>();
            messages.push_back(tool_msg);
        }
    } catch (const std::exception &e) {
        LOGe("Failed to parse tool results JSON: %s", e.what());
        return 1;
    }

    pending_tool_calls.clear();
    prev_enable_thinking = enableThinking;

    common_chat_params result;
    try {
        result = renderTemplate(enableThinking);
    } catch (const std::exception &e) {
        LOGe("Failed to render template after tool results: %s", e.what());
        return 1;
    } catch (...) {
        LOGe("Failed to render template after tool results: unknown error");
        return 1;
    }

    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;
    response.clear();

    // Same prefix-reuse strategy as addMessage: if the new full prompt
    // starts with [prev_rendered_prompt] (which generate()'s tool-call
    // path set to last_full_prompt and rolled the KV cache back to),
    // we feed only the delta and skip re-tokenizing the conversation.
    // Mismatch can happen when the chat template re-renders earlier
    // turns differently for round 2 (e.g. enableThinking flipped, or
    // the template inlines reasoning content into the assistant turn).
    // Fall back to clearing the cache in that case — correctness over
    // speed.
    bool prefix_match = false;
    if (prev_len > 0) {
        prefix_match = (int)full_prompt.size() >= prev_len &&
                       full_prompt.compare(0, prev_len, prev_rendered_prompt) == 0;
        if (!prefix_match) {
            LOGi("submitToolResults: prefix mismatch, clearing KV cache");
            llama_memory_clear(llama_get_memory(ctx), true);
            prev_len = 0;
            last_full_prompt.clear();
            last_prompt_end_pos = 0;
        }
    } else {
        // No prefix to reuse (e.g. tool_call detection rolled back
        // before any prompt was fed, or the session was truncated).
        // Clear to be safe and feed everything from scratch.
        llama_memory_clear(llama_get_memory(ctx), true);
        last_full_prompt.clear();
        last_prompt_end_pos = 0;
    }

    std::string prompt = full_prompt.substr(prev_len);
    bool is_first = (prev_len == 0);

    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = is_first
        ? 0
        : (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
    int n_prompt_tokens = -llama_tokenize(
        vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);

    if (n_ctx_used + n_prompt_tokens > n_ctx) {
        LOGe("Context overflow in submitToolResults: %d + %d > %d",
             n_ctx_used, n_prompt_tokens, n_ctx);
        return 1;
    }

    prompt_tokens.resize(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                       prompt_tokens.data(), prompt_tokens.size(),
                       is_first, true) < 0) {
        LOGe("failed to tokenize prompt after tool results");
        return 1;
    }

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    // Track the new full prompt for the next round's tool-call rollback.
    last_full_prompt = full_prompt;

    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        // Same rationale as in addMessage: always extract reasoning so
        // channel markers from gpt-oss/harmony parsers don't leak into
        // content even when thinking is off.
        parser_params.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        parser_params.parse_tool_calls = tools_enabled;
        parser_params.parser.load(result.parser);
        parser_initialized = true;
    }

    // Refresh the tool sampler for the response phase: a fresh lazy grammar
    // that allows free text and re-arms for any follow-up tool call.
    if (tools_enabled && !result.grammar.empty()) {
        recreateToolSampler(result);
    } else {
        destroyToolSampler();
    }

    LOGi("Tool results submitted: prefix_match=%d, fed %d delta tokens "
         "(prompt now %zu bytes, %d tokens already in KV)",
         prefix_match ? 1 : 0, n_prompt_tokens, full_prompt.size(), n_ctx_used);
    return 0;
}

std::string LlamaGenerationSession::getReport() {
    auto timings = llama_perf_context(ctx);
    auto sampler_timings = llama_perf_sampler(smpl);

    int n_ctx_total = llama_n_ctx(ctx);
    int n_ctx_used = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);

    std::ostringstream report;

    report << "Session\n";
    report << "  Context: " << n_ctx_used << " / " << n_ctx_total << " tokens\n";
    report << "  Prompt tokens: " << timings.n_p_eval << "\n";
    report << "  Generated tokens: " << timings.n_eval << "\n";
    report << "\n";

    report << "Performance\n";
    report << "  Load time: " << std::fixed << std::setprecision(0) << timings.t_load_ms << " ms\n";
    if (timings.n_p_eval > 0 && timings.t_p_eval_ms > 0) {
        report << "  Prompt eval: " << timings.n_p_eval << " tokens, "
               << std::setprecision(1) << (1e3 / timings.t_p_eval_ms * timings.n_p_eval) << " t/s\n";
    }
    if (timings.n_eval > 0 && timings.t_eval_ms > 0) {
        report << "  Generation: " << timings.n_eval << " tokens, "
               << std::setprecision(1) << (1e3 / timings.t_eval_ms * timings.n_eval) << " t/s\n";
    }
    if (sampler_timings.n_sample > 0 && sampler_timings.t_sample_ms > 0) {
        report << "  Sampling: " << sampler_timings.n_sample << " tokens, "
               << std::setprecision(1) << (1e3 / sampler_timings.t_sample_ms * sampler_timings.n_sample) << " t/s\n";
    }

    // Authoritative decode measurement: real streamed-token count + the wall-clock
    // window they spanned (first to last emit). The benchmark computes decode t/s
    // from this so MTP's burst emission is measured correctly.
    if (emitted_tokens > 0) {
        const double dec_ms = (double) (decode_last_us - decode_first_us) / 1000.0;
        report << "  Decode window: " << emitted_tokens << " tokens, "
               << std::fixed << std::setprecision(0) << dec_ms << " ms\n";
    }

    // Experimental MTP status, so the benchmark can surface whether the model's
    // MTP head was actually built (active) or absent (unsupported) without adb,
    // plus the draft acceptance rate once the loop has run (higher = more speedup).
    if (speculative_requested) {
        report << "  MTP: " << (spec_supported ? "active" : "unsupported") << "\n";
        if (spec_steps > 0) {
            double rate = spec_draft_total > 0
                ? (100.0 * (double) spec_accept_total / (double) spec_draft_total) : 0.0;
            report << "  MTP accept: " << spec_accept_total << " / " << spec_draft_total
                   << " drafts (" << std::fixed << std::setprecision(0) << rate << "%)\n";
        }
    }

    return report.str();
}
