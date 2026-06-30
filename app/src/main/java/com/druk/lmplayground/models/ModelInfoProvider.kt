package com.druk.lmplayground.models

import android.net.Uri
import com.druk.lmplayground.R
import java.time.LocalDate

object ModelInfoProvider {

    // Officially declared language support per model family (ISO 639-1 codes),
    // sourced from each model's HuggingFace card / publisher blog.
    private val MULTILINGUAL_BROAD = listOf(
        "en", "ar", "bg", "cs", "da", "de", "el", "es", "fi", "fr",
        "hi", "hu", "id", "it", "ja", "ko", "ms", "nl", "no", "pl",
        "pt", "ro", "ru", "sv", "th", "tr", "uk", "vi", "zh"
    )
    private val QWEN25_LANGS = listOf(
        "en", "zh", "fr", "es", "pt", "de", "it", "ru",
        "ja", "ko", "vi", "th", "ar"
    )
    private val LLAMA_LANGS = listOf("en", "de", "fr", "it", "pt", "hi", "es", "th")
    private val PHI_LANGS = listOf(
        "ar", "zh", "cs", "da", "nl", "en", "fi", "fr", "de", "he",
        "hu", "it", "ja", "ko", "no", "pl", "pt", "ru", "es", "sv",
        "th", "tr", "uk"
    )
    private val DEEPSEEK_LANGS = listOf("en", "zh")
    private val LFM_LANGS = listOf("en", "ar", "zh", "fr", "de", "ja", "ko", "es")
    private val MISTRAL_LANGS = listOf(
        "en", "fr", "de", "es", "it", "pt", "nl", "zh", "ja", "ko", "ar"
    )
    private val GRANITE_LANGS = listOf(
        "en", "de", "es", "fr", "ja", "pt", "ar", "cs", "it", "ko", "nl", "zh"
    )
    private val ENGLISH_ONLY = listOf("en")

    /**
     * Static list of all available models
     */
    private val rawModels: List<ModelInfo> = listOf(
        ModelInfo(
            name = "Qwen 3 0.6B",
            filename = "Qwen3-0.6B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 Lightweight chat model \u00B7 484Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3 1.7B",
            filename = "Qwen3-1.7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 1.28Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3 4B",
            filename = "Qwen3-4B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 2.5Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3.5 0.8B",
            filename = "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 Lightweight chat model \u00B7 466Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3.5 2B",
            filename = "Qwen_Qwen3.5-2B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 1.07Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3.5 4B",
            filename = "Qwen_Qwen3.5-4B-Q3_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/Qwen_Qwen3.5-4B-Q3_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 2.25Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3 1B",
            filename = "gemma-3-1b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 Lightweight chat model \u00B7 806Mb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3 4B",
            filename = "gemma-3-4b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 General-purpose chat model \u00B7 2.49Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Llama 3.2 1B",
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 Lightweight chat model \u00B7 808Mb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Llama 3.2 3B",
            filename = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 2.02Gb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Phi-4 mini",
            filename = "Phi-4-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-15"),
            description = "Microsoft \u00B7 Compact reasoning model \u00B7 2.49Gb",
            logoRes = R.drawable.logo_microsoft,
            supportedLanguages = PHI_LANGS
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 1.5B",
            filename = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Compact reasoning model \u00B7 1.12Gb",
            logoRes = R.drawable.logo_deepseek,
            supportedLanguages = DEEPSEEK_LANGS
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 7B",
            filename = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Advanced reasoning model \u00B7 4.68Gb",
            logoRes = R.drawable.logo_deepseek,
            supportedLanguages = DEEPSEEK_LANGS
        ),
        ModelInfo(
            name = "LFM2.5 350M",
            filename = "LFM2.5-350M-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Liquid AI \u00B7 Ultra-lightweight chat model \u00B7 267Mb",
            logoRes = R.drawable.logo_liquid,
            supportedLanguages = LFM_LANGS
        ),
        ModelInfo(
            name = "LFM2.5 1.2B Thinking",
            filename = "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-09"),
            description = "Liquid AI \u00B7 Thinking model \u00B7 731Mb",
            logoRes = R.drawable.logo_liquid,
            supportedLanguages = LFM_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 3B Instruct",
            filename = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight chat model \u00B7 2.15Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 3B Reasoning",
            filename = "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Reasoning-2512-GGUF/resolve/main/Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight reasoning model \u00B7 2.15Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 8B Instruct",
            filename = "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/Ministral-3-8B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 General-purpose chat model \u00B7 5.2Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 8B Reasoning",
            filename = "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Reasoning-2512-GGUF/resolve/main/Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Advanced reasoning model \u00B7 5.2Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Granite 4.0 Micro",
            filename = "granite-4.0-micro-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-micro-GGUF/resolve/main/granite-4.0-micro-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Enterprise chat model \u00B7 2.1Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Granite 4.0 H-Tiny",
            filename = "granite-4.0-h-tiny-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-h-tiny-GGUF/resolve/main/granite-4.0-h-tiny-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Hybrid enterprise model \u00B7 4.23Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Granite 4.1 3B",
            filename = "granite-4.1-3b-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.1-3b-GGUF/resolve/main/granite-4.1-3b-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-05-01"),
            description = "IBM \u00B7 Enterprise chat model \u00B7 2.10Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Granite 4.1 8B",
            filename = "granite-4.1-8b-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.1-8b-GGUF/resolve/main/granite-4.1-8b-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-05-01"),
            description = "IBM \u00B7 Advanced enterprise model \u00B7 5.35Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Nemotron 3 Nano 4B",
            filename = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF/resolve/main/NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-12-15"),
            description = "NVIDIA \u00B7 Hybrid reasoning model \u00B7 2.84Gb",
            logoRes = R.drawable.logo_nvidia,
            supportedLanguages = ENGLISH_ONLY
        ),
        ModelInfo(
            name = "Gemma 3n E2B",
            filename = "gemma-3n-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E2B-it-text-GGUF/resolve/main/gemma-3n-E2B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 2.79Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3n E4B",
            filename = "gemma-3n-E4B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E4B-it-text-GGUF/resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 4.24Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 4 E2B",
            filename = "gemma-4-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 Efficient on-device model \u00B7 3.11Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 4 E4B",
            filename = "gemma-4-E4B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 Efficient on-device model \u00B7 4.98Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 4 12B",
            filename = "gemma-4-12B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/ggml-org/gemma-4-12B-it-GGUF/resolve/main/gemma-4-12B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-06-03"),
            description = "Google \u00B7 Advanced chat model \u00B7 7.38Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen2.5 0.5B",
            filename = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            description = "Alibaba \u00B7 Ultra-lightweight chat model \u00B7 398Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = QWEN25_LANGS
        ),
        ModelInfo(
            name = "Qwen2.5 1.5B",
            filename = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            description = "Alibaba \u00B7 Compact chat model \u00B7 986Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = QWEN25_LANGS
        ),
        ModelInfo(
            name = "Phi3.5 mini",
            filename = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-08-20"),
            description = "Microsoft \u00B7 Compact chat model \u00B7 2.2Gb",
            logoRes = R.drawable.logo_microsoft,
            supportedLanguages = PHI_LANGS
        ),
        ModelInfo(
            name = "Mistral 7B",
            filename = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-05-22"),
            description = "Mistral \u00B7 General-purpose chat model \u00B7 4.37Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Llama 3.1 8B",
            filename = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-07-23"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 4.92Gb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Gemma2 9B",
            filename = "gemma-2-9b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-06-27"),
            description = "Google \u00B7 Advanced chat model \u00B7 5.44Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = ENGLISH_ONLY
        ),
        ModelInfo(
            name = "GPT-OSS 20B",
            filename = "gpt-oss-20b-mxfp4.gguf",
            remoteUri = Uri.parse("https://huggingface.co/ggml-org/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-mxfp4.gguf"),
            releaseDate = LocalDate.parse("2025-08-05"),
            description = "OpenAI \u00B7 Large reasoning MoE model \u00B7 12.11Gb",
            logoRes = R.drawable.logo_openai,
            supportedLanguages = MULTILINGUAL_BROAD
        )
    )

    // Best-effort capability flags by filename, assigned by model family. The
    // authoritative source is each GGUF's embedded chat template, which is only
    // readable after the model loads; once loaded, the detected capabilities are
    // cached and override these (see ModelInfo.resolveCapabilities). A wrong flag
    // here only mis-paints a list badge \u2014 it never affects whether tools actually
    // run, which is gated separately on the loaded model's real capability.
    private val TOOL_CAPABLE = setOf(
        "Qwen3-0.6B-Q4_K_M.gguf",
        "Qwen3-1.7B-Q4_K_M.gguf",
        "Qwen3-4B-Q4_K_M.gguf",
        "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
        "Qwen_Qwen3.5-2B-Q3_K_M.gguf",
        "Qwen_Qwen3.5-4B-Q3_K_M.gguf",
        "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        "Phi-4-mini-instruct-Q4_K_M.gguf",
        "LFM2.5-350M-Q4_K_M.gguf",
        "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
        "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
        "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
        "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
        "granite-4.0-micro-Q4_K_M.gguf",
        "granite-4.0-h-tiny-Q4_K_M.gguf",
        "granite-4.1-3b-Q4_K_M.gguf",
        "granite-4.1-8b-Q4_K_M.gguf",
        "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
        "gemma-4-E2B-it-Q4_K_M.gguf",
        "gemma-4-E4B-it-Q4_K_M.gguf",
        "gemma-4-12B-it-Q4_K_M.gguf",
        "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
        "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        "gpt-oss-20b-mxfp4.gguf",
    )
    private val THINKING_CAPABLE = setOf(
        "Qwen3-0.6B-Q4_K_M.gguf",
        "Qwen3-1.7B-Q4_K_M.gguf",
        "Qwen3-4B-Q4_K_M.gguf",
        "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
        "Qwen_Qwen3.5-2B-Q3_K_M.gguf",
        "Qwen_Qwen3.5-4B-Q3_K_M.gguf",
        "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
        "LFM2.5-350M-Q4_K_M.gguf",
        "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
        "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
        "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
        "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
        "gemma-4-E2B-it-Q4_K_M.gguf",
        "gemma-4-E4B-it-Q4_K_M.gguf",
        "gemma-4-12B-it-Q4_K_M.gguf",
        "gpt-oss-20b-mxfp4.gguf",
    )

    val allModels: List<ModelInfo> = rawModels.map { model ->
        model.copy(
            supportsTools = model.filename in TOOL_CAPABLE,
            supportsThinking = model.filename in THINKING_CAPABLE,
        )
    }

    /**
     * Get all known model filenames
     */
    val knownFilenames: Set<String> = allModels.map { it.filename }.toSet()
    
    /**
     * Get model by filename
     */
    fun getByFilename(filename: String): ModelInfo? = allModels.find { it.filename == filename }
    
    /**
     * Get display name for a filename
     */
    fun getDisplayName(filename: String): String = getByFilename(filename)?.name ?: filename.removeSuffix(".gguf")
    
    private fun formatFileSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1.0) "%.2fGb".format(gb) else "%dMb".format(bytes / 1_000_000)
    }

    /**
     * Create a ModelInfo for a custom (user-provided) GGUF file.
     */
    fun createCustomModelInfo(filename: String, name: String, sizeBytes: Long): ModelInfo {
        val sizeLabel = formatFileSize(sizeBytes)
        return ModelInfo(
            name = sanitizeCustomModelName(name, filename),
            filename = filename,
            remoteUri = null,
            releaseDate = null,
            description = "Custom model \u00B7 $sizeLabel",
            logoRes = R.drawable.penrose_triangle
        )
    }

    private val HEX_HASH = Regex("[0-9a-fA-F]{12,}")
    private val QUANT_SUFFIX = Regex("[._-]?(IQ|Q)\\d+(_[A-Za-z0-9]+)*$", RegexOption.IGNORE_CASE)
    private val GGUF_SUFFIX = Regex("[._-]?GGUF$", RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("[-_ ]")

    /**
     * Pick a sensible display name for a custom GGUF. The GGUF `general.name`
     * metadata is usually good, but some files ship a blank or junk value \u2014 e.g.
     * a bare hex hash like "feb5e04da4910dd56d3" \u2014 which surfaced in the model
     * list as a random string. When the probed name looks like that, fall back
     * to the filename with the ".gguf" extension and the quantization suffix
     * (e.g. "-Q4_K_M") stripped, then prettified ("LFM2.5-8B-A1B-Q4_K_M.gguf" \u2192
     * "LFM 2.5 8B A1B"). A real, human-readable name is always kept as-is. Runs
     * on every load, so already-cached junk names get fixed too.
     */
    private fun sanitizeCustomModelName(rawName: String, filename: String): String {
        val name = rawName.trim()
        val condensed = name.replace(SEPARATORS, "")
        val looksJunk = name.isEmpty() || (!name.contains(' ') && HEX_HASH.matches(condensed))
        if (!looksJunk) return name
        val cleaned = filename
            .removeSuffix(".gguf").removeSuffix(".GGUF")
            .let { QUANT_SUFFIX.replace(it, "") }
            .let { GGUF_SUFFIX.replace(it, "") }
            .trim('-', '_', '.', ' ')
        return prettifyModelId(cleaned).ifBlank { filename.removeSuffix(".gguf") }
    }

    /**
     * Best-effort provider logo for an arbitrary model id (e.g. a remote
     * server's model name) by matching provider keywords in the id. Order
     * matters: "deepseek" before "qwen" (DeepSeek-R1-Distill-Qwen ids contain
     * both). Falls back to the app's penrose logo.
     */
    fun logoForModelId(modelId: String): Int {
        val id = modelId.lowercase()
        val rules = listOf(
            "bonsai" to R.drawable.logo_bonsai,
            "prism" to R.drawable.logo_bonsai,
            "deepseek" to R.drawable.logo_deepseek,
            "minicpm" to R.drawable.logo_minicpm,
            "qwen" to R.drawable.logo_qwen,
            "gemma" to R.drawable.logo_google,
            "gemini" to R.drawable.logo_google,
            "google" to R.drawable.logo_google,
            "llama" to R.drawable.logo_meta,
            "meta" to R.drawable.logo_meta,
            "phi" to R.drawable.logo_microsoft,
            "microsoft" to R.drawable.logo_microsoft,
            "lfm" to R.drawable.logo_liquid,
            "liquid" to R.drawable.logo_liquid,
            "ministral" to R.drawable.logo_mistral,
            "mistral" to R.drawable.logo_mistral,
            "mixtral" to R.drawable.logo_mistral,
            "magistral" to R.drawable.logo_mistral,
            "granite" to R.drawable.logo_ibm,
            "ibm" to R.drawable.logo_ibm,
            "nemotron" to R.drawable.logo_nvidia,
            "nvidia" to R.drawable.logo_nvidia,
            "gpt-oss" to R.drawable.logo_openai,
            "openai" to R.drawable.logo_openai,
            "ernie" to R.drawable.logo_ernie,
            "baidu" to R.drawable.logo_ernie,
            "ornith" to R.drawable.logo_ornith,
            "glm" to R.drawable.logo_glm,
            "command" to R.drawable.logo_command,
            "exaone" to R.drawable.logo_exaone,
            "kimi" to R.drawable.logo_kimi,
            "hunyuan" to R.drawable.logo_hunyuan,
            "stablelm" to R.drawable.logo_stablelm,
        )
        return rules.firstOrNull { (kw, _) -> id.contains(kw) }?.second
            ?: R.drawable.penrose_triangle
    }

    // Tokens shown fully upper-cased in a prettified name (acronyms / formats).
    private val NAME_ACRONYMS = setOf(
        "mtp", "qat", "moe", "oss", "gpt", "glm", "lfm", "qwq", "fp8", "fp16",
        "bf16", "awq", "gptq", "gguf", "mlx", "it", "rl", "sft", "dpo", "ocr",
        "vl", "rag",
    )
    // Canonical capitalisation for known model families / publishers.
    private val NAME_FAMILY_CASING = mapOf(
        "qwen" to "Qwen", "gemma" to "Gemma", "glm" to "GLM", "lfm" to "LFM",
        "llama" to "Llama", "mistral" to "Mistral", "ministral" to "Ministral",
        "mixtral" to "Mixtral", "phi" to "Phi", "granite" to "Granite",
        "nemotron" to "Nemotron", "bonsai" to "Bonsai", "deepseek" to "DeepSeek",
        "gpt" to "GPT", "gemini" to "Gemini", "openai" to "OpenAI", "yi" to "Yi",
        "ernie" to "ERNIE", "ornith" to "Ornith", "glm" to "GLM",
        "command" to "Command", "aya" to "Aya", "minicpm" to "MiniCPM",
        "olmo" to "OLMo", "smollm" to "SmolLM", "hunyuan" to "Hunyuan",
        "kimi" to "Kimi", "exaone" to "EXAONE", "stablelm" to "StableLM",
    )
    private val PARAM_SIZE = Regex("^\\d+(\\.\\d+)?[bmk]$")          // 4b, 0.5b, 350m
    private val ACTIVE_PARAMS = Regex("^a\\d+(\\.\\d+)?b$")          // a3b, a1b
    private val FAMILY_VERSION = Regex("^([a-z]+)(\\d[\\d.]*)$")     // qwen3.5, lfm2.5
    private val PLAIN_VERSION = Regex("^\\d[\\d.]*$")                // 3.5, 4

    /**
     * Turn a raw server model id into a friendly display name, e.g.
     * "qwen3.5-4b-mtp" → "Qwen 3.5 4B MTP", "google/gemma-4-12b-qat" →
     * "Gemma 4 12B QAT". Strips a leading "publisher/" segment, splits a family
     * from its glued version number, sentence-cases plain words, and keeps
     * parameter sizes (4B, A3B) and known acronyms (MTP, QAT, MoE…) upper-cased.
     * Purely cosmetic — the raw id is still used for every server call.
     */
    fun prettifyModelId(rawId: String): String {
        val core = rawId.substringAfterLast('/').trim()
        if (core.isEmpty()) return rawId
        // Keep dots (version numbers) but break on - and _.
        val tokens = core.split('-', '_').filter { it.isNotBlank() }
        val pretty = tokens.joinToString(" ") { prettifyToken(it) }.trim()
        return pretty.ifEmpty { rawId }
    }

    private fun prettifyToken(token: String): String {
        val t = token.lowercase()
        if (PARAM_SIZE.matches(t)) return t.uppercase()             // 4b → 4B
        if (ACTIVE_PARAMS.matches(t)) return t.uppercase()          // a3b → A3B
        if (t in NAME_ACRONYMS) return t.uppercase()                // mtp → MTP
        FAMILY_VERSION.matchEntire(t)?.let { m ->                   // qwen3.5 → Qwen 3.5
            NAME_FAMILY_CASING[m.groupValues[1]]?.let { fam ->
                return "$fam ${m.groupValues[2]}"
            }
        }
        NAME_FAMILY_CASING[t]?.let { return it }                    // gemma → Gemma
        if (PLAIN_VERSION.matches(t)) return t                      // 3.5 stays
        return t.replaceFirstChar { it.uppercase() }                // flash → Flash
    }

    /**
     * Brand/family group a model id belongs to, used to group the server model
     * list (e.g. "qwen3.5-4b" → "Qwen", "google/gemma-4-12b" → "Gemma"). Mirrors
     * [logoForModelId]'s keyword order (DeepSeek before Qwen, since
     * DeepSeek-R1-Distill-Qwen ids contain both). "Other" when nothing matches.
     */
    fun providerGroup(modelId: String): String {
        val id = modelId.lowercase()
        val rules = listOf(
            "bonsai" to "Bonsai",
            "prism" to "Bonsai",
            "deepseek" to "DeepSeek",
            "minicpm" to "MiniCPM",
            "qwq" to "Qwen",
            "qwen" to "Qwen",
            "gemma" to "Gemma",
            "gemini" to "Gemini",
            "llama" to "Llama",
            "phi" to "Phi",
            "lfm" to "LFM",
            "liquid" to "LFM",
            "ministral" to "Mistral",
            "mistral" to "Mistral",
            "mixtral" to "Mistral",
            "magistral" to "Mistral",
            "granite" to "Granite",
            "nemotron" to "Nemotron",
            "gpt-oss" to "GPT-OSS",
            "gpt" to "GPT-OSS",
            "glm" to "GLM",
            "ernie" to "ERNIE",
            "baidu" to "ERNIE",
            "ornith" to "Ornith",
            "command" to "Command",
            "aya" to "Aya",
            "olmo" to "OLMo",
            "smollm" to "SmolLM",
            "hunyuan" to "Hunyuan",
            "kimi" to "Kimi",
            "exaone" to "EXAONE",
            "stablelm" to "StableLM",
        )
        return rules.firstOrNull { (kw, _) -> id.contains(kw) }?.second ?: "Other"
    }

    /**
     * Get models with their download status.
     */
    fun getModelsWithStatus(
        downloadedFilenames: Set<String>,
        customModels: List<ModelInfo> = emptyList()
    ): List<ModelWithStatus> {
        val knownModels = allModels
            .sortedByDescending { it.releaseDate }
            .map { model ->
                ModelWithStatus(
                    model = model,
                    isDownloaded = model.filename in downloadedFilenames,
                )
            }
        val customWithStatus = customModels.map { model ->
            ModelWithStatus(model = model, isDownloaded = true)
        }
        return customWithStatus + knownModels
    }
}
