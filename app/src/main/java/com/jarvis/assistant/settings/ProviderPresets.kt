package com.jarvis.assistant.settings

import com.jarvis.assistant.brain.ProviderKind

/**
 * Convenience presets for quick provider onboarding, covering all major global
 * providers that offer completely free AI models by API key:
 * - Z.ai (Zhipu AI GLM-4.5-Flash / GLM-4.7-Flash — 100% free unlimited flash models)
 * - NVIDIA NIM (build.nvidia.com — free developer API credits & 40 RPM free tier)
 * - OpenCode Zen (opencode.ai/zen/v1 — free coding/agent models like big-pickle & glm-4.7-free)
 * - Agnes AI (apihub.agnes-ai.com/v1 — Agnes 3.0 Flash & 2.5 Flash free tier)
 * - OpenRouter (openrouter.ai — 30+ :free models)
 * - Google Gemini (AI Studio — free tier Gemini 2.5 Flash & Pro)
 * - Groq Cloud (console.groq.com — ultra-fast LPU free tier)
 * - Cerebras (cloud.cerebras.ai — 1M free tokens/day)
 * - SambaNova Cloud (cloud.sambanova.ai — free developer tier for Llama 3.3 70B & DeepSeek R1)
 * - Mistral AI La Plateforme (console.mistral.ai — free Experiment tier)
 * - GitHub Models (models.inference.ai.azure.com — free with GitHub PAT)
 * - SiliconFlow (api.siliconflow.com — permanent free open-weight models)
 * - Cohere (api.cohere.com — free Trial API key)
 * - Hugging Face Router (router.huggingface.co — free serverless inference)
 * - Chutes AI (llm.chutes.ai — free serverless open-source LLMs)
 * - Pollinations AI (text.pollinations.ai/openai — free OpenAI-compatible gateway)
 * - Together AI, xAI Grok, Ollama (Local), OpenAI, and Anthropic.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String,
    val model: String,
    val note: String = "",
    val isCompletelyFree: Boolean = true,
    val apiKeyPortal: String = "",
)

val providerPresets: List<ProviderPreset> = listOf(
    ProviderPreset(
        id = "local_ai",
        label = "Offline Local AI (No Key)",
        kind = ProviderKind.LOCAL_ON_DEVICE,
        name = "Offline Local AI",
        baseUrl = "http://127.0.0.1:8080/v1",
        model = "lfm2_5_350m",
        note = "100% offline on-device execution without API keys: LFM2.5 350M, Qwen2.5 0.5B, Gemma 3 1B, LFM2.5 1.2B, SmolLM2 1.7B & Qwen3 1.7B.",
        isCompletelyFree = true,
        apiKeyPortal = "No API key needed (Download in Settings → AI Model)",
    ),
    ProviderPreset(
        id = "zai",
        label = "Z.ai (100% Free GLM)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Z.ai",
        baseUrl = "https://api.z.ai/api/paas/v4",
        model = "glm-4.5-flash",
        note = "100% free GLM-4.5-Flash, GLM-4.7-Flash & GLM-4-Flash models with full tool calling.",
        isCompletelyFree = true,
        apiKeyPortal = "chat.z.ai / open.bigmodel.cn",
    ),
    ProviderPreset(
        id = "nvidia_nim",
        label = "NVIDIA NIM (Free API)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        model = "meta/llama-3.3-70b-instruct",
        note = "Free NVIDIA developer API key (nvapi-...) with Llama 3.3 70B, DeepSeek R1 & Nemotron Ultra.",
        isCompletelyFree = true,
        apiKeyPortal = "build.nvidia.com",
    ),
    ProviderPreset(
        id = "opencode_zen",
        label = "OpenCode Zen (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "OpenCode Zen",
        baseUrl = "https://opencode.ai/zen/v1",
        model = "big-pickle",
        note = "100% free OpenCode Zen models: big-pickle, glm-4.7-free, grok-code, minimax-m2.5-free & gpt-5-nano.",
        isCompletelyFree = true,
        apiKeyPortal = "opencode.ai/auth",
    ),
    ProviderPreset(
        id = "agnes_ai",
        label = "Agnes AI (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Agnes AI",
        baseUrl = "https://apihub.agnes-ai.com/v1",
        model = "agnes-3.0-flash",
        note = "Free Agnes AI API Hub models: agnes-3.0-flash, agnes-2.5-flash & agnes-2.5-pro-alpha.",
        isCompletelyFree = true,
        apiKeyPortal = "platform.agnes-ai.com",
    ),
    ProviderPreset(
        id = "openrouter",
        label = "OpenRouter (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "meta-llama/llama-3.3-70b-instruct:free",
        note = "Auto-fetches 30+ live :free models with tool-calling support.",
        isCompletelyFree = true,
        apiKeyPortal = "openrouter.ai/keys",
    ),
    ProviderPreset(
        id = "groq",
        label = "Groq (Free Tier)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama-3.3-70b-versatile",
        note = "Blazing-fast free LPU tier with Llama 3.3 70B, Llama 4 Scout & Qwen QwQ.",
        isCompletelyFree = true,
        apiKeyPortal = "console.groq.com/keys",
    ),
    ProviderPreset(
        id = "cerebras",
        label = "Cerebras (Free 1M/day)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Cerebras",
        baseUrl = "https://api.cerebras.ai/v1",
        model = "llama-3.3-70b",
        note = "1,000,000 free tokens/day at 2,000+ tok/s with Llama 3.3 70B & Qwen 3 32B.",
        isCompletelyFree = true,
        apiKeyPortal = "cloud.cerebras.ai",
    ),
    ProviderPreset(
        id = "sambanova",
        label = "SambaNova (Free Tier)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "SambaNova",
        baseUrl = "https://api.sambanova.ai/v1",
        model = "Meta-Llama-3.3-70B-Instruct",
        note = "Free developer API key for Llama 3.3 70B, DeepSeek-R1, DeepSeek-V3 & Qwen 2.5 72B.",
        isCompletelyFree = true,
        apiKeyPortal = "cloud.sambanova.ai",
    ),
    ProviderPreset(
        id = "mistral",
        label = "Mistral AI (Free Tier)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Mistral AI",
        baseUrl = "https://api.mistral.ai/v1",
        model = "mistral-small-latest",
        note = "La Plateforme free Experiment tier with Mistral Small, Codestral & Pixtral.",
        isCompletelyFree = true,
        apiKeyPortal = "console.mistral.ai",
    ),
    ProviderPreset(
        id = "github_models",
        label = "GitHub Models (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "GitHub Models",
        baseUrl = "https://models.inference.ai.azure.com",
        model = "gpt-4o-mini",
        note = "100% free GPT-4o, GPT-4o-mini, Llama 3.3 70B & DeepSeek R1 using your GitHub Personal Access Token.",
        isCompletelyFree = true,
        apiKeyPortal = "github.com/settings/tokens",
    ),
    ProviderPreset(
        id = "siliconflow",
        label = "SiliconFlow (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "SiliconFlow",
        baseUrl = "https://api.siliconflow.com/v1",
        model = "Qwen/Qwen2.5-7B-Instruct",
        note = "Permanent $0 free open-weight models: Qwen 2.5 7B, GLM-4-9B & DeepSeek R1 Distill.",
        isCompletelyFree = true,
        apiKeyPortal = "cloud.siliconflow.com",
    ),
    ProviderPreset(
        id = "cohere",
        label = "Cohere (Free Trial Key)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Cohere",
        baseUrl = "https://api.cohere.com/compatibility/v1",
        model = "command-a-03-2025",
        note = "Free Trial API key (1,000 calls/month) for Command A & Command R+ with tool use.",
        isCompletelyFree = true,
        apiKeyPortal = "dashboard.cohere.com/api-keys",
    ),
    ProviderPreset(
        id = "huggingface",
        label = "Hugging Face (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Hugging Face",
        baseUrl = "https://router.huggingface.co/v1",
        model = "Qwen/Qwen2.5-72B-Instruct",
        note = "Free Hugging Face access token (hf_...) with Qwen 2.5 72B & Llama 3.3 70B.",
        isCompletelyFree = true,
        apiKeyPortal = "huggingface.co/settings/tokens",
    ),
    ProviderPreset(
        id = "chutes",
        label = "Chutes AI (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Chutes AI",
        baseUrl = "https://llm.chutes.ai/v1",
        model = "deepseek-ai/DeepSeek-V3-0324",
        note = "Free decentralized serverless inference for DeepSeek V3, DeepSeek R1 & Llama 3.3 70B.",
        isCompletelyFree = true,
        apiKeyPortal = "chutes.ai",
    ),
    ProviderPreset(
        id = "pollinations",
        label = "Pollinations AI (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Pollinations AI",
        baseUrl = "https://text.pollinations.ai/openai",
        model = "openai",
        note = "100% free OpenAI-compatible gateway (works immediately with or without an API key).",
        isCompletelyFree = true,
        apiKeyPortal = "auth.pollinations.ai (optional)",
    ),
    ProviderPreset(
        id = "together",
        label = "Together (Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Together",
        baseUrl = "https://api.together.xyz/v1",
        model = "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free",
        note = "Includes Together's free Llama 3.3 70B & DeepSeek R1 Distill endpoints.",
        isCompletelyFree = true,
        apiKeyPortal = "api.together.ai",
    ),
    ProviderPreset(
        id = "xai_grok",
        label = "xAI Grok",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "xAI Grok",
        baseUrl = "https://api.x.ai/v1",
        model = "grok-3-mini-beta",
        note = "xAI Grok endpoint with fast reasoning and function calling.",
        isCompletelyFree = false,
        apiKeyPortal = "console.x.ai",
    ),
    ProviderPreset(
        id = "ollama",
        label = "Ollama (Local Free)",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "Ollama",
        baseUrl = "http://127.0.0.1:11434/v1",
        model = "llama3.3",
        note = "100% local & free. Plain HTTP is allowed only for localhost.",
        isCompletelyFree = true,
        apiKeyPortal = "Local Ollama server",
    ),
    ProviderPreset(
        id = "openai",
        label = "OpenAI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        note = "Official OpenAI API endpoint.",
        isCompletelyFree = false,
        apiKeyPortal = "platform.openai.com",
    ),
    ProviderPreset(
        id = "gemini",
        label = "Gemini 2.5 Flash (Free)",
        kind = ProviderKind.GEMINI,
        name = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        model = "gemini-2.5-flash",
        note = "100% free Google AI Studio key with Gemini 2.5 Flash, Flash-Lite & Pro + tool calling.",
        isCompletelyFree = true,
        apiKeyPortal = "aistudio.google.com/apikey",
    ),
    ProviderPreset(
        id = "gemini_pro",
        label = "Gemini 2.5 Pro (Free Tier)",
        kind = ProviderKind.GEMINI,
        name = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        model = "gemini-2.5-pro",
        note = "Google AI Studio free tier deep reasoning model with full tool calling.",
        isCompletelyFree = true,
        apiKeyPortal = "aistudio.google.com/apikey",
    ),
    ProviderPreset(
        id = "anthropic",
        label = "Claude Sonnet",
        kind = ProviderKind.ANTHROPIC,
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
        isCompletelyFree = false,
        apiKeyPortal = "console.anthropic.com",
    ),
    ProviderPreset(
        id = "anthropic_haiku",
        label = "Claude Haiku",
        kind = ProviderKind.ANTHROPIC,
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-haiku-4-5",
        isCompletelyFree = false,
        apiKeyPortal = "console.anthropic.com",
    ),
)

fun presetsFor(kind: ProviderKind): List<ProviderPreset> =
    providerPresets.filter { it.kind == kind }

fun defaultPresetFor(kind: ProviderKind): ProviderPreset =
    presetsFor(kind).first()

fun findPresetForProvider(nameOrLabel: String, baseUrl: String = ""): ProviderPreset? {
    val cleanName = nameOrLabel.trim().lowercase()
    val cleanUrl = baseUrl.trim().lowercase()
    return providerPresets.firstOrNull { preset ->
        preset.id.equals(cleanName, ignoreCase = true) ||
            preset.name.equals(cleanName, ignoreCase = true) ||
            (cleanUrl.isNotBlank() && cleanUrl.contains(preset.baseUrl.lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')))
    }
}

