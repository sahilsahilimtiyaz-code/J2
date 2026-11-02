package com.jarvis.assistant.brain

class ProviderRegistry(
    client: okhttp3.OkHttpClient,
    localModelManager: LocalModelDownloadManager? = null,
) {

    private val providers: Map<ProviderKind, LlmProvider> = mapOf(
        ProviderKind.OPENAI_COMPATIBLE to OpenAiCompatibleProvider(client),
        ProviderKind.GEMINI to GeminiProvider(client),
        ProviderKind.ANTHROPIC to AnthropicProvider(client),
        ProviderKind.LOCAL_ON_DEVICE to LocalLlmProvider(client, localModelManager),
    )

    fun forKind(kind: ProviderKind): LlmProvider =
        providers[kind] ?: error("Unsupported provider kind: $kind")

    fun stream(request: ChatRequest, config: ProviderConfig) =
        forKind(config.kind).stream(request, config)
}
