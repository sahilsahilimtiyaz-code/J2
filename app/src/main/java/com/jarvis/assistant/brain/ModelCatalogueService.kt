package com.jarvis.assistant.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveredModel(
    val id: String,
    val name: String,
    val providerName: String,
    val isFree: Boolean,
    val supportsTools: Boolean = true,
    val contextWindow: Int = 0,
) {
    val displayName: String get() = name
    val providerLabel: String get() = providerName
}

/**
 * Fetches live models from any configured global AI provider:
 * - Z.ai (Zhipu AI GLM-4.5-Flash, GLM-4.7-Flash, GLM-4-Flash, GLM-Z1-Flash)
 * - NVIDIA NIM (build.nvidia.com free developer tier)
 * - OpenCode Zen (opencode.ai/zen/v1 free agent/coding models)
 * - Agnes AI (apihub.agnes-ai.com/v1 free flash & pro-alpha models)
 * - OpenRouter (openrouter.ai :free models)
 * - Google Gemini (AI Studio free tier)
 * - Groq Cloud (console.groq.com free LPU tier)
 * - Cerebras (cloud.cerebras.ai 1M tokens/day free tier)
 * - SambaNova Cloud (cloud.sambanova.ai free developer tier)
 * - Mistral AI La Plateforme (console.mistral.ai free Experiment tier)
 * - GitHub Models (models.inference.ai.azure.com free PAT tier)
 * - SiliconFlow (api.siliconflow.com permanent $0 models)
 * - Cohere (api.cohere.com free Trial key)
 * - Hugging Face Router (router.huggingface.co free tier)
 * - Chutes AI (llm.chutes.ai free serverless models)
 * - Pollinations AI (text.pollinations.ai/openai free gateway)
 * - Together AI, xAI Grok, OpenAI, Anthropic, and local Ollama.
 */
class ModelCatalogueService(private val client: OkHttpClient) {

    fun curatedModelsFor(kind: ProviderKind, baseUrl: String): List<DiscoveredModel> =
        curatedCatalogFor(kind, OpenAiCompatibleProvider.normalizeBaseUrl(baseUrl))

    /**
     * Returns the complete curated catalog of free models across all major global
     * free-by-API-key providers so users can browse and switch to any free model immediately.
     */
    fun allGlobalFreeModelsCatalog(): List<DiscoveredModel> = buildList {
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.z.ai/api/paas/v4"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://integrate.api.nvidia.com/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://opencode.ai/zen/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://apihub.agnes-ai.com/v1"))
        addAll(curatedCatalogFor(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.cerebras.ai/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.sambanova.ai/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://models.inference.ai.azure.com"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.siliconflow.com/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.cohere.com/compatibility/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://router.huggingface.co/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://llm.chutes.ai/v1"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://text.pollinations.ai/openai"))
        addAll(curatedCatalogFor(ProviderKind.OPENAI_COMPATIBLE, "https://api.together.xyz/v1"))
    }

    suspend fun fetchModels(
        kind: ProviderKind,
        baseUrl: String,
        apiKey: String,
    ): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        val cleanUrl = OpenAiCompatibleProvider.normalizeBaseUrl(baseUrl)
        val cleanKey = apiKey.trim()

        val live = runCatching {
            when {
                kind == ProviderKind.GEMINI || cleanUrl.contains("generativelanguage.googleapis.com") ->
                    fetchGeminiModels(cleanUrl, cleanKey)

                cleanUrl.contains("openrouter.ai", ignoreCase = true) ->
                    fetchOpenRouterModels(cleanUrl, cleanKey)

                kind == ProviderKind.OPENAI_COMPATIBLE ->
                    fetchOpenAiCompatibleModels(cleanUrl, cleanKey)

                else -> emptyList()
            }
        }.getOrDefault(emptyList())

        val fallback = curatedCatalogFor(kind, cleanUrl)
        if (live.isEmpty()) {
            fallback
        } else {
            // Merge live models with curated top picks, keeping free & tool-capable models first
            val seen = HashSet<String>()
            val merged = mutableListOf<DiscoveredModel>()
            for (model in fallback + live) {
                if (seen.add(model.id)) {
                    merged += model
                }
            }
            merged.sortedWith(
                compareByDescending<DiscoveredModel> { it.isFree }
                    .thenByDescending { it.supportsTools }
                    .thenBy { it.name.lowercase() },
            )
        }
    }

    private fun fetchOpenRouterModels(baseUrl: String, apiKey: String): List<DiscoveredModel> {
        val url = if (baseUrl.endsWith("/models")) baseUrl else "$baseUrl/models"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val root = JSONObject(response.body.string())
            val data = root.optJSONArray("data") ?: return emptyList()
            val list = ArrayList<DiscoveredModel>(data.length())
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                val name = item.optString("name", id).trim()
                val pricing = item.optJSONObject("pricing")
                val promptPrice = pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0
                val completionPrice = pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0
                val isFree = id.endsWith(":free") || (promptPrice == 0.0 && completionPrice == 0.0)
                val params = item.optJSONArray("supported_parameters")
                val hasTools = params?.containsString("tools") ?: looksLikeToolModel(id)
                val contextLen = item.optInt("context_length", 0)
                list += DiscoveredModel(
                    id = id,
                    name = name,
                    providerName = "OpenRouter",
                    isFree = isFree,
                    supportsTools = hasTools,
                    contextWindow = contextLen,
                )
            }
            return list
        }
    }

    private fun fetchGeminiModels(baseUrl: String, apiKey: String): List<DiscoveredModel> {
        if (apiKey.isBlank()) return emptyList()
        val url = "$baseUrl/models?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val root = JSONObject(response.body.string())
            val models = root.optJSONArray("models") ?: return emptyList()
            val list = ArrayList<DiscoveredModel>()
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                val methods = item.optJSONArray("supportedGenerationMethods")
                if (methods != null && !methods.containsString("generateContent")) continue
                val rawName = item.optString("name").removePrefix("models/").trim()
                if (rawName.isBlank() || rawName.contains("embedding") || rawName.contains("aqa")) continue
                val displayName = item.optString("displayName", rawName)
                val isFreeTier = rawName.contains("flash") || rawName.contains("pro") || rawName.contains("gemma")
                list += DiscoveredModel(
                    id = rawName,
                    name = displayName,
                    providerName = "Google Gemini",
                    isFree = isFreeTier,
                    supportsTools = !rawName.contains("gemma"),
                    contextWindow = item.optInt("inputTokenLimit", 0),
                )
            }
            return list
        }
    }

    private fun fetchOpenAiCompatibleModels(baseUrl: String, apiKey: String): List<DiscoveredModel> {
        val lowerUrl = baseUrl.lowercase()
        val supportsPublicModelsEndpoint = lowerUrl.contains("127.0.0.1") ||
            lowerUrl.contains("localhost") ||
            lowerUrl.contains("opencode.ai") ||
            lowerUrl.contains("integrate.api.nvidia.com") ||
            lowerUrl.contains("llm.chutes.ai") ||
            lowerUrl.contains("pollinations.ai")

        if (apiKey.isBlank() && !supportsPublicModelsEndpoint) return emptyList()

        val url = if (baseUrl.endsWith("/models")) baseUrl else "$baseUrl/models"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }

        val providerLabel = resolveProviderLabel(lowerUrl)

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val bodyText = response.body.string().trim()
            val data = if (bodyText.startsWith("[")) {
                JSONArray(bodyText)
            } else {
                val root = JSONObject(bodyText)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
            }
            val list = ArrayList<DiscoveredModel>(data.length())
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val rawId = item.optString("id").ifBlank { item.optString("name") }.trim()
                if (rawId.isBlank()) continue
                val id = OpenAiCompatibleProvider.normalizeModelId(baseUrl, rawId)
                val lowerId = id.lowercase()
                if (lowerId.contains("whisper") ||
                    lowerId.contains("tts") ||
                    lowerId.contains("embedding") ||
                    lowerId.contains("rerank") ||
                    lowerId.contains("moderation") ||
                    lowerId.contains("guard")
                ) {
                    continue
                }
                val isFree = isModelFreeOnProvider(lowerUrl, lowerId, item)
                val displayName = item.optString("name").takeIf { it.isNotBlank() && it != id } ?: id
                list += DiscoveredModel(
                    id = id,
                    name = displayName,
                    providerName = providerLabel,
                    isFree = isFree,
                    supportsTools = looksLikeToolModel(id),
                    contextWindow = item.optInt("context_window", item.optInt("context_length", 0)),
                )
            }
            return list
        }
    }

    fun resolveProviderLabel(lowerUrl: String): String = when {
        lowerUrl.contains("api.z.ai") || lowerUrl.contains("bigmodel.cn") -> "Z.ai"
        lowerUrl.contains("integrate.api.nvidia.com") || lowerUrl.contains("nvidia.com") -> "NVIDIA NIM"
        lowerUrl.contains("opencode.ai") -> "OpenCode Zen"
        lowerUrl.contains("agnes-ai.com") -> "Agnes AI"
        lowerUrl.contains("openrouter.ai") -> "OpenRouter"
        lowerUrl.contains("googleapis.com") -> "Google Gemini"
        lowerUrl.contains("groq.com") -> "Groq"
        lowerUrl.contains("cerebras.ai") -> "Cerebras"
        lowerUrl.contains("sambanova.ai") -> "SambaNova"
        lowerUrl.contains("mistral.ai") -> "Mistral AI"
        lowerUrl.contains("inference.ai.azure.com") || lowerUrl.contains("models.github.ai") -> "GitHub Models"
        lowerUrl.contains("siliconflow.") -> "SiliconFlow"
        lowerUrl.contains("cohere.com") -> "Cohere"
        lowerUrl.contains("huggingface.co") -> "Hugging Face"
        lowerUrl.contains("chutes.ai") -> "Chutes AI"
        lowerUrl.contains("pollinations.ai") -> "Pollinations AI"
        lowerUrl.contains("together.xyz") || lowerUrl.contains("together.ai") -> "Together"
        lowerUrl.contains("x.ai") -> "xAI Grok"
        lowerUrl.contains("127.0.0.1") || lowerUrl.contains("localhost") -> "Ollama"
        lowerUrl.contains("anthropic.com") -> "Anthropic"
        lowerUrl.contains("openai.com") -> "OpenAI"
        else -> "OpenAI Compatible"
    }

    private fun isModelFreeOnProvider(lowerUrl: String, lowerId: String, item: JSONObject): Boolean {
        val pricing = item.optJSONObject("pricing")
        if (pricing != null) {
            val p = pricing.optString("prompt", "1").toDoubleOrNull() ?: 1.0
            val c = pricing.optString("completion", "1").toDoubleOrNull() ?: 1.0
            if (p == 0.0 && c == 0.0) return true
        }
        if (lowerId.endsWith(":free") || lowerId.endsWith("-free") || lowerId.contains("free")) {
            return true
        }
        return when {
            lowerUrl.contains("api.z.ai") || lowerUrl.contains("bigmodel.cn") ->
                lowerId.contains("flash")
            lowerUrl.contains("opencode.ai") ->
                lowerId in OPENCODE_ZEN_FREE_MODELS || lowerId.contains("free") || lowerId.contains("pickle")
            lowerUrl.contains("agnes-ai.com") ->
                lowerId.contains("agnes") || lowerId.contains("flash") || lowerId.contains("mini") || lowerId.contains("r1")
            lowerUrl.contains("integrate.api.nvidia.com") ||
                lowerUrl.contains("groq.com") ||
                lowerUrl.contains("cerebras.ai") ||
                lowerUrl.contains("sambanova.ai") ||
                lowerUrl.contains("mistral.ai") ||
                lowerUrl.contains("inference.ai.azure.com") ||
                lowerUrl.contains("models.github.ai") ||
                lowerUrl.contains("cohere.com") ||
                lowerUrl.contains("huggingface.co") ||
                lowerUrl.contains("chutes.ai") ||
                lowerUrl.contains("pollinations.ai") ||
                lowerUrl.contains("127.0.0.1") ||
                lowerUrl.contains("localhost") -> true
            lowerUrl.contains("siliconflow.") ->
                lowerId.contains("7b") || lowerId.contains("8b") || lowerId.contains("9b")
            else -> false
        }
    }

    fun curatedCatalogFor(kind: ProviderKind, baseUrl: String): List<DiscoveredModel> {
        val lower = baseUrl.lowercase()
        return when {
            lower.contains("api.z.ai") || lower.contains("bigmodel.cn") -> listOf(
                DiscoveredModel("glm-4.5-flash", "GLM-4.5 Flash (100% Free · 128K + Tools)", "Z.ai", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("glm-4.7-flash", "GLM-4.7 Flash (100% Free · Agentic + Tools)", "Z.ai", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("glm-4-flash-250414", "GLM-4 Flash 250414 (100% Free · Ultra Fast + Tools)", "Z.ai", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("glm-4-flash", "GLM-4 Flash (100% Free · Fast + Tools)", "Z.ai", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("glm-4.6v-flash", "GLM-4.6V Flash (100% Free · Vision + Tools)", "Z.ai", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("glm-z1-flash", "GLM-Z1 Flash (100% Free · Deep Reasoning)", "Z.ai", isFree = true, supportsTools = false, contextWindow = 131072),
            )

            lower.contains("integrate.api.nvidia.com") || lower.contains("nvidia.com") -> listOf(
                DiscoveredModel("meta/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct (Free NIM + Tools)", "NVIDIA NIM", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("nvidia/llama-3.1-nemotron-ultra-253b-v1", "Nemotron Ultra 253B (Free NIM · Flagship + Tools)", "NVIDIA NIM", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("nvidia/llama-3.3-nemotron-super-49b-v1", "Nemotron Super 49B (Free NIM + Tools)", "NVIDIA NIM", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("deepseek-ai/deepseek-r1", "DeepSeek R1 671B (Free NIM · Reasoning)", "NVIDIA NIM", isFree = true, supportsTools = false, contextWindow = 131072),
                DiscoveredModel("qwen/qwen2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B (Free NIM + Tools)", "NVIDIA NIM", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("mistralai/mistral-small-24b-instruct-2501", "Mistral Small 24B (Free NIM + Tools)", "NVIDIA NIM", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("google/gemma-3-27b-it", "Gemma 3 27B IT (Free NIM)", "NVIDIA NIM", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("meta/llama-3.1-8b-instruct", "Llama 3.1 8B Instruct (Free NIM · Instant + Tools)", "NVIDIA NIM", isFree = true, supportsTools = true, contextWindow = 131072),
            )

            lower.contains("opencode.ai") -> listOf(
                DiscoveredModel("big-pickle", "Big Pickle (100% Free · 200K Agent + Tools)", "OpenCode Zen", isFree = true, supportsTools = true, contextWindow = 200000),
                DiscoveredModel("glm-4.7-free", "GLM-4.7 Free (100% Free · Coding + Tools)", "OpenCode Zen", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("grok-code", "Grok Code Fast 1 (100% Free · Agentic + Tools)", "OpenCode Zen", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("minimax-m2.5-free", "MiniMax M2.5 Free (100% Free + Tools)", "OpenCode Zen", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("gpt-5-nano", "GPT-5 Nano Free (100% Free · Fast + Tools)", "OpenCode Zen", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("mimo-v2-flash-free", "MiMo V2 Flash Free (100% Free + Tools)", "OpenCode Zen", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("nemotron-3-super-free", "Nemotron 3 Super Free (100% Free + Tools)", "OpenCode Zen", isFree = true, supportsTools = true, contextWindow = 131072),
            )

            lower.contains("agnes-ai.com") -> listOf(
                DiscoveredModel("agnes-3.0-flash", "Agnes 3.0 Flash (Free API + Tools)", "Agnes AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("agnes-2.5-flash", "Agnes 2.5 Flash (Free API · Ultra Fast + Tools)", "Agnes AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("agnes-2.5-pro-alpha", "Agnes 2.5 Pro Alpha (Free API · Deep Reasoning + Tools)", "Agnes AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("deepseek-r1", "DeepSeek R1 (Agnes AI Free Hub)", "Agnes AI", isFree = true, supportsTools = false, contextWindow = 65536),
                DiscoveredModel("gpt-4o-mini", "GPT-4o Mini (Agnes AI Hub + Tools)", "Agnes AI", isFree = true, supportsTools = true, contextWindow = 128000),
            )

            kind == ProviderKind.GEMINI || lower.contains("googleapis.com") -> listOf(
                DiscoveredModel("gemini-2.5-flash", "Gemini 2.5 Flash (Free Tier · Fast + Tools)", "Google Gemini", isFree = true, supportsTools = true, contextWindow = 1048576),
                DiscoveredModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite (Free Tier · Ultra Fast)", "Google Gemini", isFree = true, supportsTools = true, contextWindow = 1048576),
                DiscoveredModel("gemini-2.5-pro", "Gemini 2.5 Pro (Free Tier · Deep Reasoning + Tools)", "Google Gemini", isFree = true, supportsTools = true, contextWindow = 1048576),
                DiscoveredModel("gemini-2.0-flash", "Gemini 2.0 Flash (Free Tier + Tools)", "Google Gemini", isFree = true, supportsTools = true, contextWindow = 1048576),
            )

            lower.contains("openrouter.ai") -> listOf(
                DiscoveredModel("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B Instruct (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash Exp (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 1048576),
                DiscoveredModel("deepseek/deepseek-chat-v3-0324:free", "DeepSeek V3 0324 (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("deepseek/deepseek-r1:free", "DeepSeek R1 (Free · Reasoning)", "OpenRouter", isFree = true, supportsTools = false, contextWindow = 131072),
                DiscoveredModel("qwen/qwen-2.5-72b-instruct:free", "Qwen 2.5 72B Instruct (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("mistralai/mistral-small-3.1-24b-instruct:free", "Mistral Small 3.1 24B (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 96000),
                DiscoveredModel("nvidia/llama-3.1-nemotron-ultra-253b-v1:free", "Nemotron Ultra 253B (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("nousresearch/hermes-3-llama-3.1-405b:free", "Hermes 3 405B (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("openrouter/free", "OpenRouter Auto-Free Router (Free + Tools)", "OpenRouter", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("openai/gpt-4o-mini", "GPT-4o Mini (Paid · Reliable Tools)", "OpenRouter", isFree = false, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet (Paid · Elite Tools)", "OpenRouter", isFree = false, supportsTools = true, contextWindow = 200000),
            )

            lower.contains("groq.com") -> listOf(
                DiscoveredModel("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile (Free Tier + Tools)", "Groq", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("meta-llama/llama-4-scout-17b-16e-instruct", "Llama 4 Scout 17B (Free Tier + Tools)", "Groq", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("llama-3.1-8b-instant", "Llama 3.1 8B Instant (Free Tier · Ultra Fast + Tools)", "Groq", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("qwen-qwq-32b", "Qwen QwQ 32B (Free Tier + Tools)", "Groq", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("deepseek-r1-distill-llama-70b", "DeepSeek R1 Distill 70B (Free Tier)", "Groq", isFree = true, supportsTools = false, contextWindow = 128000),
                DiscoveredModel("gemma2-9b-it", "Gemma 2 9B IT (Free Tier)", "Groq", isFree = true, supportsTools = true, contextWindow = 8192),
            )

            lower.contains("cerebras.ai") -> listOf(
                DiscoveredModel("llama-3.3-70b", "Llama 3.3 70B (Free 1M/day · 2000 tok/s + Tools)", "Cerebras", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("qwen-3-32b", "Qwen 3 32B (Free 1M/day + Tools)", "Cerebras", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("llama3.1-8b", "Llama 3.1 8B (Free 1M/day · Instant + Tools)", "Cerebras", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("deepseek-r1-distill-llama-70b", "DeepSeek R1 Distill 70B (Free 1M/day)", "Cerebras", isFree = true, supportsTools = false, contextWindow = 65536),
            )

            lower.contains("sambanova.ai") -> listOf(
                DiscoveredModel("Meta-Llama-3.3-70B-Instruct", "Llama 3.3 70B Instruct (Free Tier + Tools)", "SambaNova", isFree = true, supportsTools = true, contextWindow = 131072),
                DiscoveredModel("DeepSeek-V3-0324", "DeepSeek V3 0324 (Free Tier + Tools)", "SambaNova", isFree = true, supportsTools = true, contextWindow = 65536),
                DiscoveredModel("DeepSeek-R1", "DeepSeek R1 671B (Free Tier · Reasoning)", "SambaNova", isFree = true, supportsTools = false, contextWindow = 65536),
                DiscoveredModel("Qwen2.5-72B-Instruct", "Qwen 2.5 72B Instruct (Free Tier + Tools)", "SambaNova", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("Qwen2.5-Coder-32B-Instruct", "Qwen 2.5 Coder 32B (Free Tier + Tools)", "SambaNova", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("Meta-Llama-3.1-8B-Instruct", "Llama 3.1 8B Instruct (Free Tier + Tools)", "SambaNova", isFree = true, supportsTools = true, contextWindow = 131072),
            )

            lower.contains("mistral.ai") -> listOf(
                DiscoveredModel("mistral-small-latest", "Mistral Small 3.1 (Free Experiment Tier + Tools)", "Mistral AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("codestral-latest", "Codestral Latest (Free Experiment Tier + Tools)", "Mistral AI", isFree = true, supportsTools = true, contextWindow = 256000),
                DiscoveredModel("open-mistral-nemo", "Mistral Nemo 12B (Free Experiment Tier + Tools)", "Mistral AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("pixtral-12b-2409", "Pixtral 12B Vision (Free Experiment Tier + Tools)", "Mistral AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("ministral-8b-latest", "Ministral 8B (Free Experiment Tier + Tools)", "Mistral AI", isFree = true, supportsTools = true, contextWindow = 128000),
            )

            lower.contains("inference.ai.azure.com") || lower.contains("models.github.ai") -> listOf(
                DiscoveredModel("gpt-4o-mini", "GPT-4o Mini (GitHub Models Free PAT + Tools)", "GitHub Models", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("gpt-4o", "GPT-4o (GitHub Models Free PAT + Tools)", "GitHub Models", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("Llama-3.3-70B-Instruct", "Llama 3.3 70B Instruct (GitHub Free + Tools)", "GitHub Models", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("DeepSeek-R1", "DeepSeek R1 (GitHub Free · Reasoning)", "GitHub Models", isFree = true, supportsTools = false, contextWindow = 65536),
                DiscoveredModel("Mistral-small", "Mistral Small (GitHub Free + Tools)", "GitHub Models", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("Phi-4", "Microsoft Phi-4 (GitHub Free)", "GitHub Models", isFree = true, supportsTools = false, contextWindow = 16384),
            )

            lower.contains("siliconflow.") -> listOf(
                DiscoveredModel("Qwen/Qwen2.5-7B-Instruct", "Qwen 2.5 7B Instruct (100% Free + Tools)", "SiliconFlow", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("Qwen/Qwen2.5-Coder-7B-Instruct", "Qwen 2.5 Coder 7B (100% Free + Tools)", "SiliconFlow", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("THUDM/glm-4-9b-chat", "GLM-4 9B Chat (100% Free + Tools)", "SiliconFlow", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("deepseek-ai/DeepSeek-R1-Distill-Qwen-7B", "DeepSeek R1 Distill Qwen 7B (100% Free)", "SiliconFlow", isFree = true, supportsTools = false, contextWindow = 32768),
                DiscoveredModel("meta-llama/Meta-Llama-3.1-8B-Instruct", "Llama 3.1 8B Instruct (100% Free + Tools)", "SiliconFlow", isFree = true, supportsTools = true, contextWindow = 32768),
            )

            lower.contains("cohere.com") -> listOf(
                DiscoveredModel("command-a-03-2025", "Command A (Free Trial Key + Tools)", "Cohere", isFree = true, supportsTools = true, contextWindow = 256000),
                DiscoveredModel("command-r-plus-08-2024", "Command R+ (Free Trial Key + Tools)", "Cohere", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("command-r-08-2024", "Command R (Free Trial Key + Tools)", "Cohere", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("command-r7b-12-2024", "Command R7B (Free Trial Key · Fast + Tools)", "Cohere", isFree = true, supportsTools = true, contextWindow = 128000),
            )

            lower.contains("huggingface.co") -> listOf(
                DiscoveredModel("Qwen/Qwen2.5-72B-Instruct", "Qwen 2.5 72B Instruct (HF Free + Tools)", "Hugging Face", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B Instruct (HF Free + Tools)", "Hugging Face", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("Qwen/Qwen2.5-Coder-32B-Instruct", "Qwen 2.5 Coder 32B (HF Free + Tools)", "Hugging Face", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("deepseek-ai/DeepSeek-R1", "DeepSeek R1 (HF Free · Reasoning)", "Hugging Face", isFree = true, supportsTools = false, contextWindow = 65536),
            )

            lower.contains("chutes.ai") -> listOf(
                DiscoveredModel("deepseek-ai/DeepSeek-V3-0324", "DeepSeek V3 0324 (Chutes Free + Tools)", "Chutes AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("deepseek-ai/DeepSeek-R1", "DeepSeek R1 (Chutes Free · Reasoning)", "Chutes AI", isFree = true, supportsTools = false, contextWindow = 128000),
                DiscoveredModel("Qwen/Qwen2.5-72B-Instruct", "Qwen 2.5 72B Instruct (Chutes Free + Tools)", "Chutes AI", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B Instruct (Chutes Free + Tools)", "Chutes AI", isFree = true, supportsTools = true, contextWindow = 128000),
            )

            lower.contains("pollinations.ai") -> listOf(
                DiscoveredModel("openai", "GPT-4o Mini Gateway (100% Free · No Key Needed + Tools)", "Pollinations AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("openai-large", "GPT-4o Gateway (100% Free + Tools)", "Pollinations AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("qwen-coder", "Qwen 2.5 Coder 32B (100% Free + Tools)", "Pollinations AI", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("llama", "Llama 3.3 70B (100% Free + Tools)", "Pollinations AI", isFree = true, supportsTools = true, contextWindow = 128000),
                DiscoveredModel("mistral", "Mistral Small (100% Free + Tools)", "Pollinations AI", isFree = true, supportsTools = true, contextWindow = 32768),
                DiscoveredModel("deepseek-r1", "DeepSeek R1 (100% Free · Reasoning)", "Pollinations AI", isFree = true, supportsTools = false, contextWindow = 65536),
            )

            lower.contains("x.ai") -> listOf(
                DiscoveredModel("grok-3-mini-beta", "Grok 3 Mini (Fast + Tools)", "xAI Grok", isFree = true, supportsTools = true),
                DiscoveredModel("grok-3-beta", "Grok 3 (Flagship + Tools)", "xAI Grok", isFree = false, supportsTools = true),
                DiscoveredModel("grok-2-latest", "Grok 2 Latest (Tools)", "xAI Grok", isFree = true, supportsTools = true),
            )

            lower.contains("together.xyz") || lower.contains("together.ai") -> listOf(
                DiscoveredModel("meta-llama/Llama-3.3-70B-Instruct-Turbo-Free", "Llama 3.3 70B Free (Free + Tools)", "Together", isFree = true, supportsTools = true),
                DiscoveredModel("deepseek-ai/DeepSeek-R1-Distill-Llama-70B-free", "DeepSeek R1 Distill 70B (Free)", "Together", isFree = true, supportsTools = false),
                DiscoveredModel("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B Turbo (Tools)", "Together", isFree = false, supportsTools = true),
            )

            lower.contains("127.0.0.1") || lower.contains("localhost") -> listOf(
                DiscoveredModel("llama3.3", "Llama 3.3 (Local Free + Tools)", "Ollama", isFree = true, supportsTools = true),
                DiscoveredModel("qwen2.5:14b", "Qwen 2.5 14B (Local Free + Tools)", "Ollama", isFree = true, supportsTools = true),
                DiscoveredModel("mistral", "Mistral 7B (Local Free + Tools)", "Ollama", isFree = true, supportsTools = true),
            )

            kind == ProviderKind.ANTHROPIC -> listOf(
                DiscoveredModel("claude-sonnet-4-5", "Claude Sonnet 4.5 (Tools)", "Anthropic", isFree = false, supportsTools = true),
                DiscoveredModel("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet (Tools)", "Anthropic", isFree = false, supportsTools = true),
                DiscoveredModel("claude-3-5-haiku-latest", "Claude 3.5 Haiku (Fast + Tools)", "Anthropic", isFree = false, supportsTools = true),
            )

            else -> listOf(
                DiscoveredModel("gpt-4o-mini", "GPT-4o Mini (Fast + Tools)", "OpenAI", isFree = false, supportsTools = true),
                DiscoveredModel("gpt-4o", "GPT-4o (Flagship + Tools)", "OpenAI", isFree = false, supportsTools = true),
            )
        }
    }

    private fun JSONArray.containsString(target: String): Boolean {
        for (i in 0 until length()) {
            if (optString(i).equals(target, ignoreCase = true)) return true
        }
        return false
    }

    private fun looksLikeToolModel(id: String): Boolean {
        val lower = id.lowercase()
        if (lower.contains("r1") || lower.contains("z1") || lower.contains("gemma-2") || lower.contains("phi-4")) {
            return false
        }
        return lower.contains("gpt") ||
            lower.contains("openai") ||
            lower.contains("gemini") ||
            lower.contains("claude") ||
            lower.contains("glm") ||
            lower.contains("pickle") ||
            lower.contains("agnes") ||
            lower.contains("minimax") ||
            lower.contains("mimo") ||
            lower.contains("nemotron") ||
            lower.contains("command") ||
            lower.contains("codestral") ||
            lower.contains("ministral") ||
            lower.contains("pixtral") ||
            lower.contains("llama-3.1") ||
            lower.contains("llama3.1") ||
            lower.contains("llama-3.3") ||
            lower.contains("llama-4") ||
            lower.contains("llama") ||
            lower.contains("qwen") ||
            lower.contains("mistral") ||
            lower.contains("hermes") ||
            lower.contains("grok") ||
            lower.contains("deepseek-chat") ||
            lower.contains("deepseek-v3")
    }

    private companion object {
        val OPENCODE_ZEN_FREE_MODELS = setOf(
            "big-pickle",
            "opencode/big-pickle",
            "glm-4.7-free",
            "opencode/glm-4.7-free",
            "grok-code",
            "opencode/grok-code",
            "minimax-m2.5-free",
            "opencode/minimax-m2.5-free",
            "gpt-5-nano",
            "opencode/gpt-5-nano",
            "mimo-v2-flash-free",
            "opencode/mimo-v2-flash-free",
            "nemotron-3-super-free",
            "opencode/nemotron-3-super-free",
            "beam-free",
            "opencode/beam-free",
        )
    }
}

