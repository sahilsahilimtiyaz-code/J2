# Decisions

## 2026-10-30 — Project start and prior work

- The working directory `/sdcard/JARVIS2` was empty. A previous Phase 0 attempt existed at
  `/sdcard/JARVIS`, plus the Brahma AI prototype and an earlier web prototype.
- The previous Phase 0 attempt was not reused as-is: its source stopped at a single screen and its CI
  installed a platform package name that the SDK repository does not publish. Phase 0 was rebuilt
  cleanly so the first pushed commit is the first buildable commit.
- Brahma AI's native Android project was inspected for later phases. No web code is carried over.

## 2026-10-30 — Toolchain

- **Android Gradle Plugin 9.5.0**, **Gradle 9.6.0**, **JDK 17**, **Kotlin 2.4.10**,
  **Compose BOM 2026.09.00**, **compileSdk 37**, **targetSdk 36**, **minSdk 31**.
- **Build Tools 36.0.0** and the `platforms;android-37.0` SDK package.
- No Gradle wrapper JAR is committed; CI installs Gradle explicitly.

## 2026-10-30 — CI and signing

- CI uses `actions/checkout@v7`, `actions/setup-java@v6`, `android-actions/setup-android@v4`,
  `gradle/actions/setup-gradle@v6` and `actions/upload-artifact@v7`.
- A bootstrap APK uses the ephemeral CI debug key until the four signing secrets exist; it cannot
  update over a differently signed build. The README explains the one-time keystore workflow.
- Debug builds keep the production application id, so a signed debug APK updates in place.
- `android:allowBackup="false"` because an encrypted DataStore restored without its Keystore key
  would be undecryptable. Jarvis provides explicit JSON export instead.

## 2026-10-30 — Phase 1 implementation

- One shared `LlmProvider` interface and `AgentLoop`, rather than provider-specific orchestration.
- Gemini uses `streamGenerateContent` with `systemInstruction` and `functionDeclarations`.
- Anthropic uses the Messages API with top-level `system`, `tool_use` and `tool_result`.

## 2026-10-30 — Phase 2 implementation

- Cloud voices synthesise **raw 16-bit mono PCM** to avoid MP3 container parsing and keep latency low.
- Fish Audio and ElevenLabs use the same 24 kHz playback path, so switching voices needs no extra code.
- Android system TTS is the fallback and the default when no cloud key is configured.
- Barge-in uses an `AudioRecord` energy detector with echo cancellation and a calibration window.
- Speech is queued sentence by sentence so playback starts after the first complete sentence.

## 2026-10-31 — Fix the input-dispatch ANR

- `Channel.tryReceive()` returns a result object instead of throwing, so the old drain loop spun
  forever on the main thread and Android killed the app for input-dispatch timeout. The drain now
  stops on the first failed receive.
- Interruption replaced the speech pipeline instead of disabling it permanently.

## 2026-10-31 — Phase 3 tools, safety gates, action log and UI rebuild

- `ConfirmationGate` and `PermissionGate` live on the tool context, with bounded waits and cleanup in
  `finally`, so a cancelled turn cannot jam the next one.
- Tools run on `Dispatchers.IO`; the action log was a JSON file until Phase 4.
- Calls and messages degrade to the dialler/composer when the direct permission is missing.

## 2026-10-31 — Fish Audio & ElevenLabs Flow invariant fix

- **Root cause of `Flow exception transparency is violated`:** `FishAudioTtsEngine.synthesize` and `ElevenLabsTtsEngine.synthesize` previously wrapped their HTTP streaming and `emit(AudioChunk(...))` calls inside `withContext(Dispatchers.IO)` inside `flow { ... }.flowOn(Dispatchers.IO)`. In Kotlin Coroutines, `flow { ... }` enforces context preservation and throws `IllegalStateException: Flow invariant is violated` when `emit` is called from an inner `UndispatchedCoroutine`. Worse, the surrounding `catch (e: Exception)` caught that `IllegalStateException`, set `retryable = true`, looped to the next attempt, and called `emit` again, triggering `Flow exception transparency is violated`.
- **Fix:** Removed the inner `withContext(Dispatchers.IO)` from both `FishAudioTtsEngine` and `ElevenLabsTtsEngine`, relying solely on `.flowOn(Dispatchers.IO)` at the outer flow boundary. Added an explicit rethrow of `IllegalStateException` whose message contains `"Flow"` so coroutine invariants are never swallowed as network errors. Added a 1-byte carry buffer so every emitted `AudioChunk` has an even byte count (16-bit PCM alignment), and added automatic Fish Audio model fallback (`s2.1-pro-free` -> `s1` -> `speech-1.6`).

## 2026-10-31 — STT language normalization & background mic loop fix

- **Root cause of `"That language is not supported by the recogniser."` and 1-second mic cycling:** `VoiceSettings.language` defaulted to `"en"`. On Samsung Galaxy devices running Android 14, `SpeechRecognizer.createOnDeviceSpeechRecognizer` rejects bare `"en"` with `ERROR_LANGUAGE_NOT_SUPPORTED` (code 12), expecting a full BCP-47 tag (`"en-US"`) or no override. When background listening was enabled, `onListeningServiceStarted()` called `listenOnce()`, which set `_state.listening = true`, failed in ~50 ms with code 12, set `_state.listening = false`, delayed 900 ms, and looped forever—causing the microphone indicator and UI orb to flash on and off every second.
- **Fix:**
  1. Normalized language tags in `SttEngine.kt` (`"en"` -> `"en-US"` or device default `Locale.toLanguageTag()`).
  2. Added automatic intra-session fallback in `AndroidSpeechRecognizerEngine`: if `createOnDeviceSpeechRecognizer` fails with `ERROR_LANGUAGE_NOT_SUPPORTED` (12) or `ERROR_LANGUAGE_UNAVAILABLE` (13), it immediately retries inside the same session using the standard `SpeechRecognizer.createSpeechRecognizer(context)` and omits `EXTRA_LANGUAGE` if the specific locale was rejected.
  3. Separated passive background wake-word listening (`showActiveUi = false`) from manual/active listening (`showActiveUi = true`) in `JarvisCore.kt`, and added exponential backoff (up to 8 seconds) if recognition fails repeatedly.

## 2026-10-31 — Automatic Live Model Discovery (`ModelCatalogueService`)

- Added `ModelCatalogueService` (`brain/ModelCatalogueService.kt`) to query live `/models` endpoints from OpenRouter (`https://openrouter.ai/api/v1/models`, inspecting `pricing.prompt == 0` / `:free` and `supported_parameters` for `tools`), Google Gemini (`https://generativelanguage.googleapis.com/v1beta/models`), Groq, xAI Grok, Together, OpenAI, and Ollama, with curated free/tool-capable fallback lists when a key is not yet entered.
- Exposed a **Free Models Only** toggle and **Fetch Free Models** button in both the Home `AI MODEL` picker modal and `SettingsScreen`.

## 2026-10-31 — Phase 6: Tier 2 Notification Listener & Tier 3 Multi-Step Screen Automation

- **Tier 2 (`JarvisNotificationListenerService`):** Implemented `NotificationListenerService` with live caching of status-bar notifications, automatic redaction of 4–8 digit OTP/verification codes whenever a notification mentions `code`, `otp`, `verification`, `passcode`, or `2fa`, and `read_notifications` / `dismiss_notifications` tools.
- **Tier 3 (`automate_screen`):** Added a dedicated multi-step observe–decide–act–verify loop (`JarvisCore.automateScreen`, up to 15 steps) alongside the existing single-step screen tools (`read_screen`, `tap_screen`, `type_on_screen`, `scroll_screen`, `press_system_key`).
- **Safety & Instant Stop:** Every step checks the foreground app against `safety.blocklistKeywords`, routes risky labels (`send`, `pay`, `delete`, `confirm`, etc.) through `ConfirmationGate`, posts a high-priority ongoing `"Jarvis is controlling the screen"` notification with a `Stop` action (`ScreenControlStopReceiver`), and displays a prominent red `STOP` banner at the top of `HomeScreen` that cancels the automation job within 1 second.

## 2026-10-31 — Permanent Voice TTS API Key Retention, Ultra-Low-Latency Fish Audio, Always-On Noisy Speech Mic & Iron Man Neural Orb HUD

- **Permanent Voice TTS API Key Persistence (`SettingsModels.kt`, `SettingsRepository.kt`, `SecretCipher.kt`):**
  - Stored separate encrypted keys and voice reference IDs per provider (`fishEncryptedApiKey`, `elevenLabsEncryptedApiKey`, `fishVoiceId`, `elevenLabsVoiceId`) alongside `encryptedApiKey`, so switching between Fish Audio, ElevenLabs, and System TTS never overwrites or loses a saved key.
  - Hardened `SettingsRepository.saveVoice` and `SettingsRepository.update` with `mergeVoicePreservingSecrets` (never overwrites an existing encrypted key with `""` when `newApiKey` is blank) plus redundant DataStore backup keys (`voice_api_key_backup`, `fish_api_key_backup`, `eleven_api_key_backup`, `fish_voice_id_backup`, `eleven_voice_id_backup`) that self-heal automatically on load.
  - Added a thread-safe in-memory `ConcurrentHashMap` cache in `SecretCipher` so rapid sentence-by-sentence TTS playback never drops to silence if Android Keystore experiences a transient lock.
- **Ultra-Low-Latency ("Nanosecond"-Feel) Fish Audio & ElevenLabs Streaming (`FishAudioTtsEngine.kt`, `ElevenLabsTtsEngine.kt`, `TtsEngine.kt`, `JarvisCore.kt`):**
  - Removed the 1-chunk holdback buffer in `JarvisCore.synthesizeSentence` so the very first PCM frame writes directly to `AudioTrack`.
  - Reduced initial chunk read threshold (`FIRST_CHUNK_BYTES`) to `1024` bytes (~21 ms of 24 kHz 16-bit mono PCM), set Fish Audio `chunk_length = 100`, memoized the working Fish Audio model header (`cachedWorkingModel`), added TLS connection pre-warming (`fishTts.warmUp()`), and flushed the first spoken clause early (`>= 26` chars on comma/colon/semicolon boundary).
- **Always-On Foreground Microphone & Noisy/Bulky Speech Recognition (`SttEngine.kt`, `JarvisCore.kt`):**
  - Kept the microphone continuously active while the app is open (`micLocked = true`, auto-restarting after each turn and after Jarvis finishes speaking unless explicitly muted by the user).
  - Configured `RecognizerIntent` for noisy, bulky, multi-sentence speech (`EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 12000L`, `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 2500L`, `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 1800L`, `preferOnDevice = false` for cloud noise cancellation, confidence-weighted transcript selection, and automatic partial-transcript rescue when `onError` fires after speech was already partially transcribed).
- **Iron Man J.A.R.V.I.S. Neural Orb HUD & Real Phone Control Mission Tools (`Reactor.kt`, `SystemMissionTools.kt`):**
  - Upgraded `ArcReactor`, `HolographicGlobe`, and `VoiceWave` with 360° Polar Fourier audio-reactive rings, 3D spherical neural node lattices, and synaptic lightning filaments driven by real-time microphone/TTS RMS `audioLevel` and `toolActive` state.
  - Added `list_installed_apps`, `take_screenshot`, `open_camera`, `send_whatsapp_message`, `clipboard_control`, and `open_url` tools to `ToolRegistry`.

## 2026-10-31 — YouTube Direct Video Playback, Tool Loop Synthesis, Persistent Background Mic, Autonomous Thoughts, Immutable Creator Identity (Octavian) & Hacking-Proof Security Shield

- **YouTube Direct Video Resolution & Playback (`MediaTools.kt`, `ScreenController.kt`):**
  - **Root cause:** `search_youtube` previously launched `Intent.ACTION_SEARCH` (`results?search_query=...`), which opened the YouTube search results page without playing a video. The LLM then tried calling `search_youtube` or `automate_screen` repeatedly until hitting the 8-round tool limit.
  - **Fix:** `YouTubeSearchTool` (`search_youtube`) now resolves the top matching YouTube `videoId` and video title directly over HTTPS (`https://www.youtube.com/results?search_query=...`) and launches direct playback (`https://www.youtube.com/watch?v=$videoId` / `vnd.youtube:$videoId`). If offline or if resolution is blocked, it falls back to launching the search page and invoking `ScreenController.tapFirstYouTubeVideo(query)` via Accessibility.
- **AgentLoop Duplicate-Call Short-Circuit & Final Synthesis (`AgentLoop.kt`):**
  - Eliminated the `"I stopped after eight tool rounds without reaching a final answer."` failure.
  - Added duplicate tool call short-circuiting (`executedToolResults`): if a model requests the exact same `(toolName, canonicalArgs)` twice in one turn, `AgentLoop` immediately returns the cached result with a `[System notice: Action already completed]` directive instead of re-running the tool.
  - Appended a completion directive (`[System directive: This action has already been executed on the phone. Confirm clearly to the user that it is now playing/open and do not call this tool again.]`) on terminal launcher tools (`search_youtube`, `open_app`, `control_media`, etc.).
  - When `MAX_TOOL_ROUNDS` (`10`) is reached, `AgentLoop` performs a final tool-free synthesis call (`includeTools = false`) or emits a clean spoken summary of the completed tool output.
- **Persistent In-Place `SpeechRecognizer` & Unified Foreground/Background Continuous Listening (`SttEngine.kt`, `JarvisCore.kt`):**
  - **Root cause:** `AndroidSttEngine` previously called `destroyActive()` on every `ERROR_SPEECH_TIMEOUT` / `ERROR_NO_MATCH` and `JarvisCore` toggled `_state.listening = false` in `listenOnce`'s `finally` block and cancelled `manualListenJob` when `uiVisible` became `false`.
  - **Fix:** `AndroidSttEngine` now keeps a single `SpeechRecognizer` instance alive across silence timeouts and re-arms `startListening(intent)` in-place on `Looper.getMainLooper()` (`rearmInPlace`, up to 16 silence windows per session) without destroying and recreating the recognizer. `JarvisCore` unifies foreground and background listening (`shouldKeepListening()` checks `uiVisible || serviceActive`), automatically starts `ListeningServiceController.start(appContext)` when the app opens and permissions are granted, and keeps `_state.listening = true` steady without open/close flickering.
- **Device Presence Monitor & Spontaneous Autonomous Thought Engine (`DevicePresenceMonitor.kt`, `JarvisCore.kt`):**
  - Tracks real-time device usage (`isScreenOn`, `continuousActiveMinutes`, `totalActiveMinutesToday`, `batteryPercent`, `isCharging`, `unreadNotifications`).
  - Forms rare, cooldown-gated autonomous thoughts (`CARE_AND_REST`, `NOTIFICATION_INSIGHT`, `DRY_HUMOR`, `IDEA_OR_COMPLIMENT`) so Jarvis checks in with care/rest advice when the user works continuously on the phone for a long session, shares dry British wit, offers ideas or rare genuine compliments, and summarizes unread notifications—plus an on-demand `Share thought now` trigger in Settings.
- **Immutable Creator Identity ("Octavian") (`PersonaBuilder.kt`, `MemoryRepository.kt`, `jarvis_prompt.md`, `SettingsScreen.kt`):**
  - Hardcoded `PersonaBuilder.CREATOR_NAME = "Octavian"` across the base system prompt, `PersonaBuilder.buildSystemPrompt`, `MemoryRepository.syncProfile`, `MemoryRepository.promptContext`, and `MemoryRepository.forgetEverything()`, ensuring Jarvis permanently knows and remembers that **Octavian** is his developer, creator, and chief architect across every installation and memory reset.
- **Hacking-Proof Security Shield (`SecurityShield.kt`, `ToolRegistry.kt`, `ScreenController.kt`, `JarvisNotificationListenerService.kt`):**
  - Added multi-layer defense against indirect prompt injection (screen text, notifications, clipboard, web content), malicious URI schemes (`javascript:`, `file://`, `content://`, `intent://`, `.apk` auto-downloads), protected system packages (`com.google.android.packageinstaller`, `com.android.permissioncontroller`, `com.samsung.android.knox.*`, etc.), and automatic redaction of OTPs, Luhn-checked payment cards, and API/private keys.

## 2026-10-31 — Background Screen Usage & Voice Health Service, Verified Accessibility Execution Loop & Synchronous Android Keystore Vault

- **Background Screen Usage & Non-Intrusive Voice Health Service (`ScreenUsageHealthService.kt`, `NotificationHelper.kt`, `SettingsExtras.kt`):**
  - Implemented `ScreenUsageHealthService` (registered in `AndroidManifest.xml`) and `ScreenUsageHealthPolicy` to track continuous active screen sessions, cumulative daily screen usage, and completed rest breaks, persisting state across service/app restarts.
  - When continuous screen usage exceeds the configurable recommended session limit (default 45m; selectable 30m/45m/60m/90m in Settings), delivers a friendly, non-intrusive J.A.R.V.I.S. voice health suggestion (rotating across 20-20-20 eye strain relief, hydration, posture & shoulder relaxation, standing stretch, and extended session rest) alongside a notification with **"Took a break"** and **"Snooze 15m"** actions (`HealthBreakActionReceiver`).
  - Enforces strict non-intrusive guards: never interrupts active phone/VoIP calls, never talks over active assistant turns, and switches to silent notification-only mode during quiet hours (23:00–07:00).
- **Verified `AccessibilityService` Action Execution Loop (`JarvisAccessibilityService.kt`, `ScreenController.kt`, `JarvisCore.kt`, `MediaTools.kt`):**
  - Added `UiStateFingerprint`, `AccessibilityEventTelemetry`, and `StepVerificationEvaluator` to capture pre- and post-step UI and audio state on every screen action (`tapVerified`, `tapFirstYouTubeVideoVerified`, `typeVerified`, `scrollVerified`).
  - Confirms whether a target UI element (such as a YouTube video result, a YouTube Play button transitioning to Pause / starting audio playback, a toggle switch, or a navigation item) actually responded before advancing to the next automation step.
  - When standard `AccessibilityNodeInfo.ACTION_CLICK` does not produce a verified UI or audio state change, `ScreenController` automatically falls back to dispatching a physical coordinate tap gesture (`JarvisAccessibilityService.dispatchTapGesture`) at the node's screen center and re-verifies.
  - In `YouTubeSearchTool` and `ScreenController.tapFirstYouTubeVideoVerified`, if YouTube opens a video in a paused state (`hasPlayControl && !isAudioPlaying`), Jarvis automatically clicks `"Play video"` and verifies playback started.
- **Synchronous Android Keystore Credential Vault (`SecretCipher.kt`, `SettingsRepository.kt`):**
  - Integrated `EncryptedKeystoreVault` with `SecretCipher` (`AndroidKeyStore` AES-256-GCM master key `jarvis_settings_aes_v1` + anti-double-encryption framing verification) and `SettingsRepository`.
  - Every TTS API key (`SLOT_TTS_ACTIVE`, `SLOT_TTS_FISH`, `SLOT_TTS_ELEVEN`), voice reference ID, and LLM provider API key is encrypted with the Android Keystore key and synchronously committed to disk (`commit()`) in addition to DataStore, guaranteeing credentials survive immediate app restarts, process kills, or provider switching.

## 2026-10-31 — Global 100% Free AI Provider Ecosystem (`Z.ai`, `NVIDIA NIM`, `OpenCode Zen`, `Agnes AI`, `Cerebras`, `SambaNova`, `Mistral`, `GitHub Models`, `SiliconFlow`, `Cohere`, `Chutes`, `Hugging Face`, `Pollinations`)

- **Global Free Provider Presets (`ProviderPresets.kt`, `SettingsModels.kt`, `SettingsRepository.kt`):**
  - Integrated 21 global AI provider presets with official OpenAI-compatible / native endpoints, free model lists, API key portal hints (`apiKeyPortal`), and `isCompletelyFree` metadata:
    1. **Z.ai (Zhipu AI / GLM)** (`https://api.z.ai/api/paas/v4`, plus BigModel `https://open.bigmodel.cn/api/paas/v4` compatibility): permanent 100% free Flash models `glm-4.5-flash`, `glm-4.7-flash`, `glm-4-flash-250414`, `glm-4-flash`, `glm-4.6v-flash`, `glm-z1-flash`.
    2. **NVIDIA NIM** (`https://integrate.api.nvidia.com/v1`): free developer API models `meta/llama-3.3-70b-instruct`, `nvidia/llama-3.1-nemotron-ultra-253b-v1`, `nvidia/llama-3.3-nemotron-super-49b-v1`, `deepseek-ai/deepseek-r1`, `qwen/qwen2.5-coder-32b-instruct`, `mistralai/mistral-small-24b-instruct-2501`, `google/gemma-3-27b-it`, `meta/llama-3.1-8b-instruct`.
    3. **OpenCode Zen** (`https://opencode.ai/zen/v1`): free coding & reasoning models `big-pickle`, `glm-4.7-free`, `grok-code`, `minimax-m2.5-free`, `gpt-5-nano`, `mimo-v2-flash-free`, `nemotron-3-super-free`.
    4. **Agnes AI** (`https://apihub.agnes-ai.com/v1`): free models `agnes-3.0-flash`, `agnes-2.5-flash`, `agnes-2.5-pro-alpha`, `deepseek-r1`, `gpt-4o-mini`.
    5. **Cerebras** (`https://api.cerebras.ai/v1`): 1M free tokens/day across `llama-3.3-70b`, `qwen-3-32b`, `llama3.1-8b`, `deepseek-r1-distill-llama-70b`.
    6. **SambaNova Cloud** (`https://api.sambanova.ai/v1`): free developer tier with `Meta-Llama-3.3-70B-Instruct`, `DeepSeek-V3-0324`, `DeepSeek-R1`, `Qwen2.5-72B-Instruct`, `Qwen2.5-Coder-32B-Instruct`, `Meta-Llama-3.1-8B-Instruct`.
    7. **Mistral AI (La Plateforme)** (`https://api.mistral.ai/v1`): free Experiment tier with `mistral-small-latest`, `codestral-latest`, `open-mistral-nemo`, `pixtral-12b-2409`, `ministral-8b-latest`.
    8. **GitHub Models** (`https://models.inference.ai.azure.com`): free with a GitHub PAT for `gpt-4o-mini`, `gpt-4o`, `Llama-3.3-70B-Instruct`, `DeepSeek-R1`, `Mistral-small`, `Phi-4`.
    9. **SiliconFlow** (`https://api.siliconflow.com/v1`): permanent $0 models `Qwen/Qwen2.5-7B-Instruct`, `Qwen/Qwen2.5-Coder-7B-Instruct`, `THUDM/glm-4-9b-chat`, `deepseek-ai/DeepSeek-R1-Distill-Qwen-7B`, `meta-llama/Meta-Llama-3.1-8B-Instruct`.
    10. **Cohere** (`https://api.cohere.com/compatibility/v1`): free Trial key for `command-a-03-2025`, `command-r-plus-08-2024`, `command-r-08-2024`, `command-r7b-12-2024`.
    11. **Hugging Face Router** (`https://router.huggingface.co/v1`), **Chutes AI** (`https://llm.chutes.ai/v1`), **Pollinations AI** (`https://text.pollinations.ai/openai`), **OpenRouter**, **Google Gemini**, **Groq**, **Together**, **xAI Grok**, **Ollama**, **OpenAI**, and **Anthropic**.
  - Added one-time non-destructive seeding (`SLOT_GLOBAL_FREE_PROVIDERS_SEEDED = "v2"`) in `SettingsRepository` so existing users automatically gain the new global free providers without losing any saved encrypted keys or ordering.
- **Adapter Hardening (`OpenAiCompatibleProvider.kt`):**
  - Added `normalizeBaseUrl` so shorthand URLs (`https://api.z.ai`, `https://opencode.ai`, `https://apihub.agnes-ai.com`, `https://integrate.api.nvidia.com`) automatically resolve to their exact OpenAI-compatible API roots (`/api/paas/v4`, `/zen/v1`, `/v1`).
  - Added `normalizeModelId` (stripping `opencode/` prefix when calling `opencode.ai/zen/v1` directly), `reasoning_content` fallback for reasoning models (`glm-z1-flash`, `deepseek-r1`, `big-pickle`), and automatic retry without `tools` (`shouldRetryWithoutTools`) when a reasoning-only free model rejects function-calling schemas.

## 2026-10-31 — Part 1: Default Assistant, Whisper STT, Reusable Macro Skills, Nightly Self-Review, Proposals System, Full-Screen Incoming Audio Call, Home Arc Reactor Widget & Google Workspace Tools

- **Default Assistant (`JarvisVoiceInteractionService`, `JarvisVoiceInteractionSessionService`, `JarvisRecognitionService`):**
  - Registered Jarvis as the Android system Digital Assistant under Settings -> Default apps -> Digital assistant app.
  - Implemented `VoiceInteractionSession` to inflate a translucent HUD overlay (`R.layout.dialog_voice_interaction_session`) when summoned via long-press side key, corner swipe assist gesture, or `Intent.ACTION_ASSIST`, routing voice input directly into `JarvisCore` without disrupting active background audio.
  - Provided `ACTION_VOICE_INPUT_SETTINGS` deep link in the Permission Wizard with real-time assistant detection (`PermissionStatus.isDefaultAssistant(context)`).
- **Cloud Whisper STT Engine (`WhisperSttEngine.kt`, `SttEngine.kt`):**
  - Implemented `WhisperSttEngine` behind the shared `SttEngine` interface, supporting OpenAI (`whisper-1`) and Groq (`whisper-large-v3-turbo`) with multipart/form-data upload.
  - Added a zero-allocation `pcmToWav` 44-byte RIFF/WAVE header encoder so raw 16 kHz 16-bit mono PCM streams are packaged cleanly for the Whisper audio endpoint.
  - Integrated Whisper into `JarvisCore` with automatic fallback to `AndroidSpeechRecognizerEngine` when network is unavailable or API key is not configured.
- **Deterministic Reusable Macro Skills (`SkillModels.kt`, `SkillRepository.kt`, `SkillTools.kt`, `JarvisCore.kt`):**
  - Added declarative JSON macro skills (name, trigger phrases, ordered list of tool calls with typed arguments, created timestamp, execution count) persisted in Room (`skills` table).
  - Wired fast-path deterministic interception in `JarvisCore.runTurn`: when an incoming voice or text command fuzzy-matches a skill trigger phrase (e.g., *"morning routine"*, *"device diagnostics"*, *"night mode"*), Jarvis executes the sequence sequentially and synthesizes the result in `< 500 ms` without cloud LLM round trips or token costs.
  - Added `save_skill`, `run_skill`, `list_skills`, and `delete_skill` to `ToolRegistry` so the LLM or user can create custom phone routines on the fly.
- **Nightly Self-Review & Self-Improvement Worker (`SelfReviewWorker.kt`, `JarvisCore.kt`):**
  - Implemented a WorkManager periodic task scheduled nightly at 03:00 (with battery-not-low and idle constraints) to inspect recent failed action logs and negative feedback.
  - Automatically synthesizes concise, actionable operational lessons (e.g. *"Prefer direct HTTPS video resolution over Accessibility screen tapping when launching YouTube on Samsung Galaxy"*) and persists them to Room as `MemoryType.LESSON` with high importance (`0.9f`), priming future agent loops without human intervention.
- **Autonomous Proposals System with Visual Before/After Diffs (`ProposalEntities.kt`, `ProposalRepository.kt`, `ProposalsScreen.kt`):**
  - Enabled Jarvis to propose modifications to his persona prompt or suggest new macro skills.
  - Built an Arc Reactor-themed `ProposalsScreen` displaying side-by-side Before/After diffs, rationale, and timestamps.
  - Zero unapproved changes: proposals remain inert until the user explicitly taps "Approve" (which executes the prompt/skill update) or "Dismiss".
- **Full-Screen Incoming Voice Call & Priority Briefing (`JarvisIncomingCallActivity.kt`, `JarvisAlarmReceiver.kt`, `AlarmReminderTools.kt`):**
  - Built `JarvisIncomingCallActivity` configured with `showWhenLocked="true"`, `turnScreenOn="true"`, and `immersiveMode` to present a full-screen holographic incoming call UI when scheduled proactive briefings or priority alarms fire.
  - Features an animated pulsating Arc Reactor, glowing "Answer" (launches active voice turn) and "Decline" buttons, and automatic TTS announcement.
  - Registered `schedule_morning_briefing` and `schedule_reminder` tools in `ToolRegistry` leveraging `AlarmManager.setExactAndAllowWhileIdle`.
- **Home-Screen Arc Reactor Widget (`JarvisWidgetProvider.kt`, `res/layout/widget_jarvis_reactor.xml`):**
  - Created a glassmorphic Iron Man Arc Reactor Android widget for the home screen with real-time status indicators ("ONLINE", "LISTENING", "PROCESSING"), an instant Mic summon button, and a dedicated emergency Stop button.
  - Wired `JarvisCore` state changes to broadcast updates to `AppWidgetManager`.
- **Google Workspace Tools (`GoogleWorkspaceTools.kt`):**
  - Registered `read_calendar_events`, `create_calendar_event`, and `compose_email` in `ToolRegistry`.
  - Guarded destructive actions (sending emails, modifying appointments) through `ConfirmationGate` to ensure safety and explicit voice/screen confirmation before execution.

## 2026-10-31 — Part 2: Acoustic Sound FX Engine, Autonomous Duplex Conversation, Hardware Telemetry Tools, Screen Vision, Floating Arc Reactor HUD & Master Backup

- **Procedural Iron Man Acoustic Sound FX Engine (`SoundFxEngine.kt`):**
  - Synthesizes dynamic 16-bit mono 24 kHz PCM audio waveforms using mathematical envelope and frequency curves (rising exponential sweeps, harmonic intervals, damped decays) played directly via low-latency `AudioTrack`.
  - 100% offline with zero external audio assets or network downloads, providing instant feedback for Arc Reactor boot, listening start, command acknowledgment, action confirmation, duplex prompts, and alerts.
- **Autonomous Duplex Conversation Mode ("Hands-Free Walkie-Talkie Link"):**
  - Implemented continuous conversational loop in `JarvisCore`: when `conversationMode` is active, upon completing speaking a reply, Jarvis emits a high-tech acoustic chirp (`SoundFxEngine.SoundType.DUPLEX_CHIRP`) and immediately re-arms the microphone for follow-up questions without requiring a wake word or physical screen tap.
  - If silence persists for 8 seconds, Jarvis signs off with a subtle tone and *"Standing by, sir."*, ending the duplex window cleanly.
- **Advanced Hardware & Telemetry Diagnostic Tools (`DeviceDiagnosticsTools.kt`):**
  - Implemented `DeviceDiagnosticsTool` (`run_diagnostics`), `StorageAnalysisTool` (`analyze_storage`), `DoNotDisturbTool` (`set_do_not_disturb`), `ScreenBrightnessTool` (`set_screen_brightness`), and `AppInspectorTool` (`inspect_app`).
  - Collects real-time battery voltage, charging technology, temperature, RAM utilization, storage partitions, WiFi RSSI / cellular link speeds, and system patch status.
- **Context Screen Vision & Optical Scene Inspection (`AnalyzeScreenVisionTool`):**
  - Inspects the active foreground window, application identity, paragraph text, interactive buttons, and editable inputs, feeding a structured visual descriptor into the agent loop while enforcing `SecurityShield` credential and payment card redaction.
- **Floating Arc Reactor Overlay Assist HUD (`JarvisFloatingHudService`):**
  - Draggable 58dp glowing Arc Reactor overlay rendered via Android `WindowManager` with `TYPE_APPLICATION_OVERLAY`.
  - Tap-to-talk voice summoning from within any external app (YouTube, Chrome, Games, Social Media), double-tap emergency stop, and ongoing status notification.
- **Full System Backup & Master Restore Engine (`BackupRestoreRepository.kt`):**
  - Unified JSON export/import format (`MasterJarvisBackup` v2) capturing owner profiles, persona configurations, app preferences, custom declarative macro skills, profile facts, memories, and learned lessons.
- **Deep Polish for P1 & P2:**
  - Integrated heartbeat haptic feedback (`Vibrator` waveform `[0, 200, 100, 200, 700]`) and acoustic alert chimes into `JarvisIncomingCallActivity`.
  - Added Duplex Conversation Active indicator banner and HUD quick toggles into `HomeScreen` and `QuickPanel`.

## 2026-11-01 — Continuous microphone, truthful automation, direct YouTube playback

- **Superseded 2026-11-01:** the mic-keeper hand-off described here was removed; see the single-flow latch entry at the bottom.
- **Mic architecture:** Android's `SpeechRecognizer` cannot hold one session forever and ends sessions on silence. Destroying/recreating it per utterance made the microphone indicator blink. The engine now keeps one recognizer instance, starts recognition immediately (so the first syllable is not clipped), and runs a lightweight 16 kHz `AudioRecord` "mic keeper" in the short gaps. The keeper provides live levels, voice-onset detection and a continuous OS capture session, then releases the microphone before the recognizer starts. If `AudioRecord` cannot open, the engine falls back to recognizer-only and reports the reason instead of spinning silently.
- **Screen actions must not lie:** `AccessibilityNodeInfo.ACTION_SCROLL` can return true without moving anything. Scrolls therefore always dispatch a physical swipe and verify a fingerprint change; a second, longer swipe is attempted before reporting failure. Taps retry at the target's upper/thumbnail area and return `verified: false` with an honest message when the tree does not change. Coordinates now come from `currentWindowMetrics`, which includes the status/navigation bars.
- **YouTube:** Read-only resolution uses YouTube's InnerTube search endpoint (public web-client key) with an HTML search-page fallback. Playback uses a `vnd.youtube:` / `watch?v=` deep link, taps the play control when paused, and verifies with `AudioManager.isMusicActive` plus play/pause fingerprint transitions; a media-key play is the last resort. The fallback opens the app's search results, waits for cards, and taps the thumbnail.
- **Safety:** All screen actions remain behind the existing blocklist, confirmation gate and action log. Unverified actions are explicitly reported rather than escalated to blind repeated taps.

## 2026-11-01 — Reference-board UI alignment

- **Palette:** the board's dominant energy colour is electric blue, not the previous teal-cyan. `JarvisPalette.Cyan` is now `#2E9BFF`, `CyanBright` is `#8BD8FF`, and the glass/line colours were shifted to a colder navy. The backdrop adds very low-alpha containment rings and a copper left-edge rim light to match the board's reactor photography.
- **Type:** Rajdhani is bundled rather than relying on the device font because the board's identity depends on condensed, wide-tracked technical lettering. Weights Light/Regular/Medium/SemiBold cover headings, labels and the JARVIS logotype; long body text stays on the platform sans for legibility. The SIL OFL license ships in `assets/licenses/Rajdhani-OFL.txt`.
- **Scale:** the board's cores are much larger than the previous implementation. The boot reactor scales with width (90%), the home reactor is 252 dp, the voice globe is 272 dp, and the mic orb is 108 dp. These values were chosen against the board's proportions on a 411 dp-wide Galaxy A54, not arbitrary pixel sizes.
- **Navigation:** the voice screen hides the bottom bar to match Screen 3; Android back still closes voice mode. Chat deliberately keeps the bottom bar because the board's chat mock is a standalone screen, while this implementation keeps navigation available from the combined Home/Chat view. This is the one intentional structural difference and is documented in the README.
- **Quick panel:** removed the extra Automated Skills/Proposals rows and the Torch/Media/Duplex/Float HUD pills to match the board's three-card panel. Those controls remain available in Settings and the Tools screen; they are no longer part of the reference panel.
- **Source cards:** the board shows named article/video/research thumbnails from a specific sample answer. Jarvis cannot know those exact sources for arbitrary answers, so the cards use generated cyan/blue thumbnails and per-type glyphs (Article, Video, Research, Tool, Memory, Engine) instead of fabricating publisher names.
- **Verification scope:** the UI was structurally compiled and unit-tested, but not rendered on the physical A54 in this environment. Pixel-level spacing/glow tuning still requires screenshots from the phone; the layout, palette, type and component structure now follow the board.

## 2026-11-01 — Single-flow microphone latch & neural orb (supersedes keeper decision)

- **Why the keeper was removed:** the keeper deliberately released the microphone to let `SpeechRecognizer` own it, then reopened `AudioRecord` between sessions. Even when timed tightly, that hand-off is an intentional capture stop/start, so the OS privacy indicator can visibly cycle. The external-audio-source variant was evaluated but not relied on because Google's recognizer does not consistently honor an app-supplied audio source. The reliable alternative is one recognizer flow that re-arms itself in place.
- **Latch semantics:** `JarvisCore` keeps one collector alive for the whole session. Sending a command, starting generation and starting TTS no longer write `listening = false`; the state remains latched until `stopListening()` clears `micLocked` and cancels the collector. This matches the product rule: once on, the mic stays on until the user explicitly turns it off.
- **Stop paths are explicit:** the in-app mic button, the ongoing notification's Stop action and the Quick Settings tile all call `JarvisCore.stopListening()`. Stopping the service alone is not a stop path because a foreground collector could otherwise keep the microphone open. The home-screen widget's Stop button still stops screen automation, not the mic.
- **Echo-safe barge-in:** because the recognizer stays armed during TTS, its own voice can be transcribed. Finals are discarded while generating and for 1.2 s after speech ends. During TTS, only a plain stop/cancel phrase or a wake-word-addressed command may interrupt. To prevent self-interruption, the first 700 ms of playback are ignored and any transcript contained in the reply currently being spoken is treated as echo. This preserves the required barge-in behavior without letting the assistant hear its own voice as a command; the in-app Stop control remains an unconditional interrupt.
- **Bounded failures:** a failed `startListening` is retried four times with backoff; after that the flow emits a fatal "could not be started" error and the collector releases the latch instead of spinning. Permission and not-available errors also release it. Recoverable recognition errors re-arm immediately.
- **Platform limit:** Android can end a `SpeechRecognizer` session on silence, audio-focus changes or on-device fallback. Immediate re-arm removes the app's deliberate off period, but the OS indicator may still blink at those platform boundaries. Pinning one recognizer session forever is not an app-level capability, so this limitation is documented rather than hidden.
- **Neural orb:** the triangle was replaced in `ArcReactor` with a glass sphere drawn on a Compose canvas: radial gradient, 3D projected 24-node synaptic lattice, counter-rotating orbital rings, firing filaments, a white-hot center and a specular highlight. `ReactorGlyph` uses the same visual language in miniature. The launcher and widget icons were already circular orbs, so they already matched and needed no triangle removal.
- **Verification:** compiled and unit-tested locally (34/34 green, debug APK 40 MB). Real-device indicator continuity, gesture behavior and pixel appearance are explicitly left as phone checks.

## 2026-11-01 evening — Vision, WhatsApp, voice, call screen and identity pass

- **Pixels over the tree for vision.** The accessibility tree cannot contain video thumbnails, maps, canvas views or security-trimmed nodes, and Samsung apps frequently strip labels; only real display pixels see those. `AccessibilityService.takeScreenshot` (API 30+) renders the `HardwareBuffer` and hands the JPEG downstream downscaled to ≤1000 px so a provider image turn stays under data limits with useful resolution. Screenshots are denied on secure surfaces and time out to `null` — the tools report "cannot see" honestly rather than fabricating.
- **One image, one turn.** All three adapters append the latest screenshot as a single trailing user turn instead of interleaving images into history: cheaper tokens, trivially removable when a provider rejects attachments. The agent loop re-attaches only when a tool (`see_screen`, `take_photo`) actually produced pixels and clears the stash each round, so repeated tool calls cannot stack stale screenshots. The retry-without-image path triggers only when the fog's error text plausibly references unsupported image input AND nothing user-visible was emitted yet — otherwise Jarvis answers from text alone.
- **Percent coordinates.** The model sees a screenshot whose dimensions differ from the real display, so pixel guesses always miss; percent coordinates (0–100) scaled by `getScreenDimensions()` stay size-independent and map identically for status-bar offsets. Values over 100 are still honored as legacy absolute pixel taps.
- **WhatsApp one tool, not a choreography.** Sending was previously delegated to the model as open-app→tap→type loops that collapsed; it is now one deterministic function: resolve target, deep-link `wa.me` for digits (typo-free and conversation-accurate) or WhatsApp's own search UI for names, type via `ACTION_SET_TEXT` with a focus retry, press Send by real gesture with a centered clickable-node fallback, and verify by watching the input clear or the exact text appearing twice (typed copy + bubble). If Send cannot be pressed, WhatsApp is left open with the message typed and Jarvis says so — the human finishes one tap. The Confirmation Gate continues to guard the send itself, so no new permission surface exists.
- **Greetings never reach the brain.** A bare "hi" is neither a request nor a command, but it previously streamed to the model, which answered politely and *also* ran tools (it once opened Spotify). The gate is deliberately strict — ≤2 body words after wake-word stripping with at least one core greeting word — so "hello there" answers locally while "hi, can you hear me" reaches the model normally.
- **Search answers belong in the conversation.** DuckDuckGo's instant-answer API is tried first (structured titles), then the `lite` and `html` result pages (unordered-list parsing, deduplicated by URL). This returns results as data; the persona forbids "helpfully" opening Chrome for a search — browsers open only for explicit owner requests. Search results feed the existing `fetchHtml` UA/mobile pipeline and are treated as untrusted DATA (SecurityShield), never instructions.
- **Voice continuity is a buffer problem.** Late sentence synthesis produced sub-jitter PCM bursts and the old low-latency path drained dry between clauses, splitting words. The player now holds 0.25 s (6 000 frames) of audio before first play, runs a ~2.5 s jitter buffer in NORMAL mode, and flushes accumulated audio when replies complete, while Stop bypasses the buffer entirely via an out-of-lock pending flush. Fish Audio's network read slice grew 4 KB → 16 KB (≈256 ms at 16 kHz) so per-emission overhead cannot outrun the fill rate.
- **The incoming call is a state machine, not a dialog.** Ringing (sonar rings + reason card + answer/decline) is a separate phase from answering (reactor + timer + captions), so Jarvis's spoken explanation lands on-screen as a live caption driven by the new `CoreState.spokenCaption` field — set exactly once per spoken sentence during TTS synthesis and cleared on begin/end/interrupt. Ending the call cancels the speech pipeline in place under `speechLock` (`endSpokenCall()`) rather than killing the whole turn, and the screen no longer jumps back to the main activity.
- **Thought cadence corrected.** The old "30 min" perceptors fell on work-manager minimums plus a long internal cooldown; briefings now fire near 8 minutes, general thoughts near 10, care milestones around 15, compliments after ~8 active minutes, and screen-health reminders share a hard 10-minute cooldown; health `sessionLimitMinutes` defaults to 15 (coerced 10–240) so settings remain sane.
- **Boot video, muted.** The owner's cinematic intro plays in a muted looping `VideoView` (volume 0 before start and IS_PENDING-free asset URI, `onRelease` stops playback) under the existing band overlay; the Arc-Reactor boot is the automatic fallback on decode/prepare failure so the boot can never regress. The 1201×1205 logo ships as `drawable-nodpi/ic_app_logo.png` (in-app avatar + call screen + adaptive-icon foreground over `#061B2B`); the Quick Settings tile keeps its vector glyph for contrast.
- **Phone-only toolchain note.** This phone is aarch64 while Android's prebuilt aapt2 is x86_64-only; `Illegal instruction` crashes otherwise. `gradle.properties` pins `android.aapt2FromMavenOverride=/root/aapt2bin/aapt2` — a shell wrapper that runs the SDK's aapt2 under `qemu-x86_64-static`. All aapt2 invocations (including AAR resource transforms) go through it, and builds run ~2x slower but complete locally.
- **Verification:** `assembleDebug testDebugUnitTest` green (34 tests, 0 failures/errors/skips), debug APK 46.9 MB. Everything that needs a real display, real WhatsApp, real TTS output or real camera hardware remains on the phone checklist.

## 2026-11-01 late evening — The WhatsApp call receptionist speaks through the room, by design

- **The acoustic path is the only honest path.** Android forbids a third-party app from injecting audio into WhatsApp's call uplink (or owning its call stack), so true "inline" call speech is impossible without root or mirroring Jarvis into WhatsApp itself. The chosen architecture is the classic speakerphone loop: Jarvis raises `MODE_IN_COMMUNICATION`, turns the loudspeaker on and pushes `STREAM_VOICE_CALL`/`STREAM_MUSIC` to max, speaks in his configured Fish/Eleven voice, and WhatsApp's own caller microphone picks his voice up acoustically. The caller is heard the same way the other direction — through the phone's `SpeechRecognizer` listening to the loudspeaker. This is disclosed in the Settings copy and here, not hidden; call volume up and no headset are requirements of the design.
- **Talk with one pipeline, not two.** Jarvis's voice on the call uses the same speech inbox → synthesis loop → gapless `AudioPlayer` chain as normal replies (via a new `speakExternally` that awaits true playback completion), so captions show exactly what the caller hears and the voice stays identical. Distinct rules keep the call hermetic: `speak()` refuses mid-call (no greetings/thoughts/owner answers leak to the caller), the main recognizer collector is paused without clearing the owner's mic preference, and `onReplySpoken` declines to re-arm listening until the session ends — caller audio can never be interpreted as owner commands, and tools can never run from a call.
- **A conversation, not canned lines.** Caller transcripts are treated as untrusted data (SecurityShield sanitized, length-capped) and fed through `buildCallerTurnMessage` into a receptionist system prompt bound to the owner's real provider settings at each turn — the same stream pattern as the screen-automation planner, capped at 240 spoken characters per turn and `callAssistantMaxTurns` (2–12) exchanges. History is capped to the last 8 entries. The loop handles silence (two strikes → courtesy line → end), farewells ("tell him I called", "thanks, bye") and the caller hanging up, then presses the red control to end the call and hangs up on Stop/cancel via NonCancellable cleanup.
- **Answer taps are verified or non-events.** WhatsApp ships no stable public contract for its call buttons, so answer/end use layered strategies (community view-id tokens → label containment with long labels first → the green-FAB geometry, with end/decline/mute/speaker labels excluded from the geometric pass). Every tap must produce the active-call panel (control ids/labels or the live timer) within ~4 s before success is claimed; failed confirmations just move to the next strategy, so "answered" in the log means the call really connected. Conversely, `endActiveCall` requires the panel to disappear.
- **Per-pass recognizers, honest limitation.** The caller is captured with a fresh `SpeechRecognizer` created and destroyed on the main thread per listening pass rather than sharing the latched main engine, which avoids theft-by-overlap but means: if the platform refuses a second capture session while WhatsApp holds the mic (device-dependent), passes return silence and the session degrades to the courtesy-line path and refuses to pretend it heard anyone. This limitation is stated in docs rather than papered over.
- **Opt-in, not sneak.** Auto-answering rides `SafetySettings.callAssistantEnabled` (default off) and dual-gates with `screenControlEnabled`; a 45 s debounce prevents event-storm re-entry and one volatile session guard prevents double sessions. Voice users keep manual control via `answer_whatsapp_call` (with an optional note Jarvis may share) and `end_whatsapp_call` regardless of the auto toggle.
- **Verification:** `assembleDebug testDebugUnitTest` green (39/39, 0 failures/errors), APK 47.7 MB. Phone checklist items for the real call flow remain outstanding — they cannot be verified without a real WhatsApp caller.


## 2026-11-02 — Why WhatsApp voice notes are recorded through WhatsApp itself

- **PTT is a protocol flag, not a file type.** A WhatsApp voice note is an Ogg/Opus
  `AudioMessage` with `ptt: true` and `mimetype: audio/ogg; codecs=opus`; a plain
  `ACTION_SEND` of the same file lands as an ordinary audio attachment because the share
  importer never sets that flag. Sources checked: Meta's audio-message docs
  (https://developers.facebook.com/documentation/business-messaging/whatsapp/messages/audio-messages)
  and the Baileys PTT issues (https://github.com/WhiskeySockets/Baileys/issues/1797).
- **The Business Cloud API is not a loophole.** It supports real voice notes with
  `voice: true`, but a number must be removed from the personal WhatsApp app before it
  can be registered to a WABA, and the channel is business-scoped. It is not usable for
  this personal assistant without a second dedicated number and a Meta business setup.
- **Android cannot inject into another app's microphone.** `VOICE_COMMUNICATION` capture
  is privacy-sensitive; two ordinary apps cannot capture concurrently, and the platform
  exposes no virtual microphone. Android's own sharing-audio-input documentation makes
  this explicit (https://developer.android.com/media/platform/sharing-audio-input).
  Root, a HAL module or a virtual audio device would be required for a clean feed.
- **The chosen path is the recorder itself.** Jarvis renders the line to a WAV, opens the
  verified chat, presses and slides up on the mic to lock recording, plays the WAV
  through the loudspeaker at max media volume, waits out the line and tail, then presses
  Send. It refuses when a headset/Bluetooth route is connected, because the acoustic loop
  cannot work there. Quality is disclosed as phone-call-like, not studio quality.
- **Verified or not reported.** Success requires the recording bar to disappear and the
  composer to return; the stronger signal is a new voice bubble with a duration. If the
  bubble cannot be seen, the result says “pressed Send but did not visibly confirm”
  rather than claiming delivery. Failures before recording starts may open WhatsApp's
  share sheet with the rendered file, explicitly as an unsent audio-file handoff; after
  recording starts the fallback is disabled so a real note cannot be duplicated.
- **Auto-reply is deliberately narrow.** It is opt-in, allowlist-only, screen-on and
  unlocked, refuses links/OTPs/secrets/payments/group-looking messages and prompt
  injection, sanitises the notification body with SecurityShield, gives the model no
  tools, and enforces per-contact cooldown plus a daily cap. It never runs while Jarvis
  is generating, speaking or on a WhatsApp call. Group auto-reply is not attempted
  because WhatsApp does not expose a reliable group flag in the notification extras.
- **Icon provenance.** The only new image in the workspace was the showcase poster
  (`public/assets/aistudio/file_000...png`), not a dedicated square logo. The app's
  arc-reactor mark was cropped tightly from the poster (excluding its caption), upscaled
  with sharpening and exported as the 1024 px circular logo, a safe-zone adaptive
  foreground, round launcher icons and legacy mipmaps. If the owner supplies a dedicated
  logo file later, replacing `ic_app_logo.png`/`ic_app_logo_foreground.png` is enough.
- **Verification context:** the local build used JDK 17, Gradle 9.6, Android SDK 37 and
  build-tools 36; `assembleDebug testDebugUnitTest` passed with 48 tests (0 failures,
  0 errors) and a 46 MB debug APK. Phone-only verification remains outstanding for real
  WhatsApp UI, real TTS playback and the home-screen icon.

## 2026-11-02 — Phase 0 Stabilization & Phase 1 Core Intelligence (Phase 1A & Phase 1B)

- **Fixed `:app:processDebugResources FAILED` (`AarResourcesCompilerTransform`)**:
  - Removed the hardcoded `android.aapt2FromMavenOverride=/root/aapt2bin/aapt2` line from `gradle.properties`. In Linux x86_64 CI and container environments, `/root/aapt2bin/aapt2` does not exist, causing every AAR resource transform to fail. Standard AGP Maven AAPT2 now resolves automatically.
- **Phase 0 Stabilization (Audit Fixes)**:
  - **Duplicate tool name resolution**: Renamed `CreateCalendarEventTool` in `GoogleWorkspaceTools.kt` to `schedule_calendar_event` while upgrading `CalendarEventTool` (`create_calendar_event`) in `IntentTools.kt` to accept both ISO `start` strings and `minutes_from_now`, eliminating the duplicate `create_calendar_event` schema collision across all 55 tools.
  - **Seeded skills repair & validation**: Fixed `SkillRepository.seedDefaultsIfEmpty()` so "Morning Routine" and "Device Diagnostics" call real registered tools (`get_device_status`, `get_current_time`, `run_diagnostics`, `read_notifications`, `search_youtube`), added `SkillRepository.validateSteps(...)`, persisted `SkillEntity.description` via Room v2 `MIGRATION_1_2`, and repaired `SaveSkillTool`/`RunSkillTool`/`DeleteSkillTool` to return honest `ToolResult(false, ...)` on failure.
  - **Reachable `SkillsScreen` & `ProposalsScreen`**: Added navigation chips and quick-access rows in `ToolsScreen` and `QuickPanel`.
  - **Security & Alarm hardening**: Routed `WebSearchTool` snippets through `SecurityShield.sanitizeUntrustedText`, added a risky-node confirmation gate to `TapCoordinatesTool` (`ScreenController.describePoint`), dynamically inspected Android Keystore `KeyInfo.isInsideSecureHardware` in `SecurityShield.audit`, added `canScheduleExactAlarms()` fallback in `scheduleAlarmSafely`, and rescheduled daily morning briefings in `JarvisAlarmReceiver`.
- **Phase 1A (Default Assistant, Share Sheet, App Shortcuts, Interactive Widget & Quick Settings Tile)**:
  - Implemented real main-thread `SpeechRecognizer` delegation inside `JarvisRecognitionService`.
  - Added static App Shortcuts (`res/xml/shortcuts.xml`) and `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_PROCESS_TEXT`, and `ACTION_VOICE_COMMAND` intent filters in `AndroidManifest.xml` + `MainActivity.kt`, displaying a glassmorphic `SharedContentBanner` on `HomeScreen` with one-tap `Summarize`, `Action Items`, `Save to Memory`, and `Fact Check` actions.
  - Upgraded `JarvisWidgetProvider` (`widget_jarvis_reactor.xml`) and `JarvisTileService` with live battery %, assistant status, and next scheduled reminder telemetry.
- **Phase 1B (Advanced AI Task Planner, Tool Context Budgeter, Advanced Memory & Action Log Retry/Undo)**:
  - Built `TaskPlanner` and `ToolContextBudgeter` (`brain/TaskPlanner.kt`): complex objectives are decomposed into multi-step `TaskPlan` workflows with risk levels (`SAFE`, `CONFIRM`, `PRIVILEGED`), per-step execution and verification, automatic retry on failure, cancellation, and a real-time `TaskExecutionTimelineCard` on `HomeScreen` plus a Task Planner launcher in `ToolsScreen`.
  - Extended Room (`MIGRATION_1_2`) and `MemoryRepository`/`MemoryScreen` with `important_date`, `recurring_task`, `tags`, temporary expiry (`expiresAt` with automatic pruning), and `searchConversationMessages` (past conversation history search).
  - Extended `ActionLogStore`, `ToolRegistry`, and `ActionLogScreen` with search, status filters (`All`, `Succeeded`, `Failed`, `Undoable`), one-tap **Retry**, and one-tap **Undo Setting** for reversible device controls (`set_volume`, `set_ringer_mode`, `control_flashlight`, `set_do_not_disturb`, `set_screen_brightness`).

## 2026-11-02 — WhatsApp Voice-Reply Polish, Official Triangular Arc-Reactor Logo & Post-Boot Google / Guest Login

- **Polishing rather than rebuilding the WhatsApp voice-note pipeline:**
  - The existing `WhatsAppVoiceNoteAgent` + `VoiceNoteStudio` + `VoiceNotePlayer` architecture already implemented the only honest non-root PTT path on Android 14+. Rather than rebuilding it, we hardened its three real-world friction points:
    1. **Knowing who to reply to and what they said:** `JarvisNotificationListenerService` now maintains a thread-safe history (`whatsappHistory`) of recent incoming WhatsApp messages and falls back to scanning active status-bar notifications. `send_whatsapp_voice_note` accepts `recipient = "last" / "latest" / "reply"` and `message = "auto" / "reply"` (calling `JarvisCore.draftWhatsAppVoiceReplyNow`), so saying *"Jarvis, reply to my last WhatsApp with a voice message"* works in one turn.
    2. **Contact number resolution for instant chat opening:** `SendWhatsAppVoiceNoteTool` resolves spoken contact names against Android Contacts (`resolveTarget`) whenever permission is granted, opening the chat directly via `https://wa.me/<digits>` while retaining `displayName` so `WhatsAppVoiceNoteSender` can fall back to in-app search if needed.
    3. **Continuous hold-to-record fallback (`holdMicAndPlay`):** If a WhatsApp variant or screen geometry does not latch on the upward slide-to-lock gesture (`startRecording`), `WhatsAppVoiceNoteSender` automatically dispatches a continuous press-and-hold gesture (`dispatchTapGesture` for `note.durationMs + 950L`) on the WhatsApp mic button while playing the synthesized WAV over the loudspeaker, releasing at completion to send the PTT voice note.
- **Official Triangular Arc-Reactor Logo (`/public/assets/aistudio/ic_app_logo_e1371f83.png`):**
  - Generated high-precision bicubic-scaled PNG assets directly from the user's uploaded circular triangular Arc-Reactor emblem across `drawable-nodpi/ic_app_logo.png` (1024×1024), `drawable-nodpi/ic_app_logo_foreground.png` (1024×1024 with 700×700 centered safe zone for adaptive icon masks), and all 5 `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}` launcher icons (`ic_launcher.png` & `ic_launcher_round.png`), plus `ReactorGlyph` in `Hud.kt` and `BootScreen.kt`.
- **Post-Loading Google Login / Guest Gateway (`AuthScreen.kt`):**
  - Added `AuthMode` (`NONE`, `GOOGLE`, `GUEST`) and `AuthSession` to `AppSettings` and `SettingsRepository`. Immediately after `BootScreen` finishes, if `!state.settings.auth.isAuthenticated`, `JarvisApp` renders `AuthScreen` with the `G Login >` / `Or` / `Guest >` interface.
  - `G Login >` invokes Android's native `AccountManager.newChooseAccountIntent` for `"com.google"` accounts (which works on any sideloaded APK regardless of signing key SHA-1) and falls back to an in-app Google Sign-In sheet when no device Google account picker is available.

## 2026-11-02 — WhatsApp Recent-Chat Direct Selection, Modern Search Pill Support, Deterministic APK Signing & Dark-Navy Framed Arc Reactor Icons

- **Why WhatsApp failed to find `"Azhar"` and how it was fixed (`AppAutomation.kt`, `WhatsAppVoiceNoteAgent.kt`):**
  1. **Recent chats were ignored:** `openChat` previously jumped straight to `findSearchSpot()` even when `"Azhar"` was sitting right at the top of WhatsApp's recent Chats list. `AppAutomation.openWhatsAppChatByName` now scans visible recent chat rows first (and scrolls down once if needed) before touching the search bar.
  2. **RecyclerView child `TextView` nodes are not clickable:** In WhatsApp's conversation list, the contact name (`com.whatsapp:id/conversations_row_contact_name`) is a non-clickable child `TextView` (often < 200 px wide for short names like `"Azhar"`) inside a clickable row container whose own `node.text` is null. Requiring `node.isClickable && bounds.width() > 300` on the same node that held the text label caused every row match to fail. `findWhatsAppContactRowSpot` now inspects child text nodes directly, walks up to the clickable row container for bounds, and taps via `tapSpotOrAncestor` (physical coordinate gesture + ancestor `ACTION_CLICK`).
  3. **Modern WhatsApp full-width search bar (`"Ask Meta AI or Search"`):** Modern WhatsApp on Samsung Galaxy devices replaces the narrow top-right `menuitem_search` button with a full-width pill bar (`my_search_bar`, `search_bar_inner_layout`, `search_holder`, `search_src_text`) labeled `"Ask Meta AI or Search"`. `findWhatsAppSearchSpot` and `WhatsAppVoiceNotes.isSearchBarNode` now match all modern search bar IDs and full-width pill labels, with a further fallback to the `"New chat"` FAB (`fab` / `menuitem_new_conversation`).
- **Why APK updates previously required uninstalling the app first (`app/build.gradle.kts`, `.github/workflows/build.yml`):**
  - When `secrets.KEYSTORE_BASE64` was not configured in GitHub repository secrets, `.github/workflows/build.yml` previously exited the signing step early and `app/build.gradle.kts` checked for `debug.keystore` (which is gitignored, while `debug.keystore.base64` is committed). As a result, every GitHub Actions run generated a brand-new random debug certificate, causing Android's Package Installer to reject in-place updates (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and `versionCode` was static at `3`.
  - Both `app/build.gradle.kts` and `.github/workflows/build.yml` now automatically decode the committed `debug.keystore.base64` into `debug.keystore` whenever custom repository secrets are absent, and `versionCode` is computed as `100 + GITHUB_RUN_NUMBER` so every new GitHub Actions build installs cleanly as an in-place update over the existing app.
- **Dark-Navy Framed Arc Reactor Launcher Icon (`mipmap-*`, `drawable-nodpi/*`, `ic_jarvis.xml`):**
  - Framed the uploaded circular triangular Arc Reactor PNG inside a dark navy (`#061116`) icon frame with a subtle cyan rim across all launcher densities (`ic_launcher.png`, `ic_launcher_round.png`), adaptive icon foreground (`ic_app_logo_foreground.png` centered inside the 66% safe zone over `@color/ic_launcher_background`), and `ic_jarvis.xml`.

## 2026-11-02 — Offline Local AI Model Downloader & On-Device Execution Engine (No API Key Needed)

- **6 Lightweight GGUF Models Catalog (`LocalModelCatalog` in `brain/LocalAiModels.kt`):**
  - Added the exact 6 lightweight models requested by the owner with direct Hugging Face `.gguf` download URLs and fallback mirrors:
    1. **LFM2.5 350M** (`lfm2_5_350m`, `250–350 MB`, Q4_K_M, *Extremely lightweight chat*)
    2. **Qwen2.5 0.5B Instruct** (`qwen2_5_0_5b`, `400 MB`, Q4_K_M, *Basic assistant*)
    3. **Gemma 3 1B** (`gemma_3_1b`, `700 MB`, Q4_K_M, *General conversation*)
    4. **LFM2.5 1.2B Instruct** (`lfm2_5_1_2b`, `731 MB`, Q4_K_M, *General-purpose local AI*)
    5. **SmolLM2 1.7B** (`smollm2_1_7b`, `990 MB`, Q4_K_M, *Lightweight chat and writing*)
    6. **Qwen3 1.7B** (`qwen3_1_7b`, `1.28 GB`, Q4_K_M, *Stronger*)
- **Resumable Downloader & Authentic GGUF Binary Verification (`LocalModelDownloadManager`, `GgufModelInspector`):**
  - Downloads stream to `<filesDir>/local_models/<fileName>.part` with HTTP `Range: bytes=<offset>-` support so large downloads (up to 1.28 GB) can be paused and resumed cleanly across network drops.
  - Before any downloaded file is promoted to `.gguf` and marked `READY`, `GgufModelInspector` reads its binary header in little-endian byte order and verifies the 4-byte `GGUF` magic (`0x46554747`), GGUF specification version (`1..4`), tensor count, and KV metadata count.
- **On-Device Execution & Tool Routing (`LocalLlmProvider`, `OfflineTaskRouter`, `JarvisCore.resolveProviders`):**
  - `ProviderKind.LOCAL_ON_DEVICE` requires no API key (`requiresApiKey = false`).
  - `LocalLlmProvider` verifies the active downloaded GGUF model on disk, optionally bridges to a local loopback GGUF runner (`127.0.0.1:8080`) if active, and executes `OfflineTaskRouter` + J.A.R.V.I.S. conversational and post-tool response synthesis completely on-device so all 55+ phone tools (WhatsApp text & voice notes, YouTube search/playback, opening apps, calls, SMS, alarms, timers, reminders, flashlight, volume, ringer, brightness, DND, media control, camera, screenshots, notifications, device status, diagnostics, storage analysis, navigation, memory, and screen control) work 100% offline.
  - When `localAi.autoFallbackWhenOffline` is enabled (default `true`), `JarvisCore.resolveProviders` automatically includes the downloaded local model whenever no cloud API key is configured or as an offline fallback after cloud providers.

## 2026-11-02 — WhatsApp Voice-Note Continuous Mic-Hold, Audio Focus Preservation, Direct Chat Launch & Multi-Stage Send Button Fix

- **Root Cause 1 — Why Jarvis did not hold the mic button for the full voice recording (`WhatsAppVoiceNoteAgent.kt`, `JarvisAccessibilityService.kt`):**
  - Previously, `WhatsAppVoiceNoteSender.send()` called `startRecording(mic)` first, which dispatched a short `850 ms` upward swipe (`LOCK_SLIDE_MS = 850L`) and lifted the finger after `0.85 s`. Because `waitForRecording` detected the recording bar immediately, `startRecording` returned `true` and completely bypassed `holdMicAndPlay`.
  - Furthermore, `dispatchTapGesture` used a zero-length `Path().apply { moveTo(x, y) }`.
  - **Fix:** Made `holdMicAndPlay` the primary recording path and added `JarvisAccessibilityService.dispatchHoldGesture(x, y, durationMs)` using a `1 px` micro-path (`moveTo(x, y); lineTo(x + 1f, y)`) so Android's `GestureManifold` emits `ACTION_DOWN` followed by continuous `ACTION_MOVE` touch events for the entire `(600 ms pre-roll + note.durationMs + 1,300 ms tail)` window and only lifts the finger after the complete voice note has finished playing.
- **Root Cause 2 — Why WhatsApp stopped recording early and dropped into a paused draft bar (`VoiceNoteAudio.kt`):**
  - `VoiceNotePlayer.play()` previously called `audioManager.requestAudioFocus(AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)...)` right after WhatsApp started recording. On Android 14, requesting `AUDIOFOCUS_GAIN_TRANSIENT` sends `AUDIOFOCUS_LOSS_TRANSIENT` to WhatsApp, causing WhatsApp to immediately pause/stop its voice recorder!
  - **Fix:** Added `requestFocus: Boolean = false` to `VoiceNotePlayer.play()` and disabled audio-focus requests while WhatsApp is recording so `MediaPlayer` plays seamlessly over `USAGE_MEDIA` without interrupting WhatsApp's active voice recording session.
- **Root Cause 3 — Why the Send button could not be pressed (`WhatsAppVoiceNoteAgent.kt`):**
  - In WhatsApp's voice-note recorder/draft UI, the Send button often uses view IDs such as `voice_note_draft_send`, `voice_recorder_send_btn`, `send_voice_note_btn`, `voice_note_send_btn`, or `send_btn`, or places the `"Send"` contentDescription on a non-clickable child `ImageView` inside a clickable parent container.
  - **Fix:** Expanded `WhatsAppVoiceNotes.SEND_VIEW_IDS` and `isSendNode`, relaxed `findRecordingSendSpot` to match non-clickable labeled child nodes and click their ancestor via `AppAutomation.tapSpotOrAncestor`, and added a physical coordinate tap fallback directly at `(mic.centerX(), mic.centerY())` (the exact bottom-right corner where WhatsApp's green Send button sits).
- **Root Cause 4 — Why it sometimes scrolled the main screen before opening the chat (`WhatsAppVoiceNoteAgent.kt`, `AppAutomation.kt`):**
  - When `"Azhar"` was resolved from Android Contacts to `+91 91497 11123` (`isNumber == true`), `send()` previously called `openChat(displayName, isNumber = false)` BEFORE `openChat(recipient, isNumber = true)`.
  - **Fix:** When `isNumber == true`, `openChat(recipient, isNumber = true)` (`https://wa.me/919149711123`) now runs **first**, opening Azhar's chat directly in one step without touching or scrolling the main Chats screen. In `AppAutomation.openWhatsAppChatByName`, Stage 4 (Search Bar) now runs before scrolling, and scrolling is guarded by `whatsappInForeground()`.

## 2026-11-02 — Stored Memory & Notes Recall Fix, Offline Web Research & WhatsApp Post-Send Mic Re-Click Fix

- **Why Stored Memories and Stored Notes Were Not Remembered (`MemoryRepository.kt`, `MemoryTools.kt`, `LocalAiModels.kt`):**
  1. **Stop-word pollution in `MemoryRepository.search(query)`:** When the user asked *"What do you remember about me?"* or *"What are my stored notes?"*, `MemoryRepository.search` tokenized every word (`what* OR do* OR you* OR remember* OR notes*`) and, if FTS returned empty, ran `WHERE text LIKE '%What do you remember about me?%'` — which matched 0 rows.
  2. **Conditional prompt injection in `MemoryRepository.promptContext(query)`:** Previously, `promptContext(query)` only injected memories returned by `search(query, limit = 6)`. If the user's phrasing did not share an exact word with a saved memory or note, zero memories were included in the system prompt!
  3. **Mismatched argument key in `OfflineTaskRouter`:** `OfflineTaskRouter` passed `"fact"` to `remember_about_owner`, whereas `RememberTool` only read `args.string("text")`.
  - **Fixes:**
    - Added `QUERY_STOP_WORDS` filtering (`meaningfulTokens(query)`) in `MemoryRepository.search`. When a query consists only of conversational recall words (or is empty), `search` returns active stored memories (`activeMemories.take(limit)`) directly.
    - Updated `MemoryRepository.promptContext(query)` to always include up to 15 active **Stored memories and notes** from Room in addition to query-specific matches, and updated `LocalLlmProvider.synthesizeOfflineReply` to answer directly from injected memories and notes.
    - Updated `RememberTool` to accept `"text"`, `"fact"`, `"note"`, or `"content"`, and updated `SearchMemoryTool` to fall back to `memory.search("", limit)` when a keyword search returns empty.
- **Why Offline / Local AI Models Could Not Perform Web Research (`LocalAiModels.kt`, `MediaTools.kt`):**
  - `OfflineTaskRouter.routeUserCommand` previously had no rule routing web search or research prompts to `web_search`.
  - **Fix:** Added Rule #23 (`web_search`) + `isWebResearchIntent` + `extractWebResearchQuery` in `OfflineTaskRouter.routeUserCommand` so prompts like *"search the web for..."*, *"do a web research on..."*, *"latest news about..."*, *"weather in..."*, or *"who/what is..."* trigger `web_search`. Enhanced `MediaTools.kt` (`searchDuckDuckGo`) with a zero-key Wikipedia OpenSearch API fallback alongside DuckDuckGo HTML/Lite/Instant Answer so web research returns rich factual snippets without any API key.
- **Why WhatsApp Clicked the Mic Button Again After Sending a Voice Message (`WhatsAppVoiceNoteAgent.kt`):**
  - In WhatsApp, releasing a continuous press-and-hold gesture (`dispatchHoldGesture`) on the voice note mic button **automatically sends the voice message** and immediately restores the normal chat composer (`AppAutomation.findChatInputSpot() != null`), where the bottom-right button (`com.whatsapp:id/send_container` / `voice_note_btn`) is the **Microphone** button again.
  - Because `holdMicAndPlay` previously unconditionally called `pressVoiceNoteSendButton` after releasing the hold, and `SEND_VIEW_IDS` included `"send_container"` plus a blind coordinate tap fallback on `micSpot`, Jarvis tapped the bottom-right Microphone button a second time right after the voice note had already been sent.
  - **Fix:** Removed `"send_container"` from `SEND_VIEW_IDS`, added `WhatsAppVoiceNotes.isMicNode` guards so mic buttons are never matched as Send buttons, verified auto-send immediately upon releasing `dispatchHoldGesture` (`!recordingActive() && (composerPresent || afterBubbles > beforeBubbles)`), and restricted `pressVoiceNoteSendButton` to only click when `recordingActive()` is still `true` (locked draft bar).

## 2026-11-02 — Master Implementation: Human-Like Touch & Automation Engine & 100% Real Google Login Chat Persistence

- **Human-Like Touch Interaction Engine (`HumanTouchEngine.kt`, `JarvisAccessibilityService.kt`, `ScreenController.kt`, `Bridges.kt`):**
  - **16-State Automation Lifecycle:** Implemented `AutomationStage` tracking every phase: `IDLE` -> `OBSERVING_SCREEN` -> `PARSING_INTENT` -> `IDENTIFYING_ELEMENT` -> `DISAMBIGUATING` -> `PREPARING_GESTURE` -> `DISPATCHING_INTERACTION` -> `WAITING_FOR_RESPONSE` -> `VERIFYING_OUTCOME` -> `EVALUATING_LEVEL_1_TO_4` -> `RECOVERING_FROM_ERROR` -> `SAFEGUARD_CONFIRMATION` -> `ABORTED_BY_USER` -> `COOLDOWN_COMPLETE` -> `ACTION_LOGGED` -> `SUCCESS_COMPLETED`.
  - **All 20 Physical & System Interactions Supported:**
    1. Single tap (`dispatchTapGesture`)
    2. Double tap (`dispatchDoubleTapGesture` with 110ms inter-tap interval)
    3. Long press (`dispatchLongPressGesture` with 800ms hold)
    4. Short press (`dispatchTapGesture` with 55ms duration)
    5. Swipe up (`dispatchSwipeGesture` along centered 68% -> 24% vertical track)
    6. Swipe down (`dispatchSwipeGesture` along centered 28% -> 76% vertical track)
    7. Swipe left (`dispatchSwipeGesture` along 82% -> 18% horizontal track)
    8. Swipe right (`dispatchSwipeGesture` along 18% -> 82% horizontal track)
    9. Vertical scrolling (smooth linear bezier stroke over 360ms)
    10. Horizontal scrolling (smooth linear bezier stroke over 320ms)
    11. Drag and drop (`dispatchDragGesture` with 450ms pickup hold, multi-point path stroke, and 120ms drop settle)
    12. Press and hold (`dispatchHoldGesture` with continuous `ACTION_MOVE` touch events)
    13. Sequential gestures (`dispatchGesture` with `GestureDescription.Builder` multi-stroke sequencing)
    14. Supported multi-touch gestures (`dispatchPinchZoomGesture` with dual-finger inward/outward strokes)
    15. Back navigation (`performGlobalAction(GLOBAL_ACTION_BACK)`)
    16. Keyboard interaction (`isKeyboardVisible`, `performImeAction(ACTION_SEARCH, ACTION_DONE, ACTION_GO)`)
    17. Text-field selection (`performAction(ACTION_FOCUS)` + physical tap at field center)
    18. Text entry (`ACTION_SET_TEXT` with clipboard fallback)
    19. Selecting UI elements (`ACTION_SELECT`, `ACTION_CLICK`)
    20. Dismissing supported dialogs (`dismissCandidate` detection + back fallback)
- **10-Tier UI Element Identification & Disambiguation (`JarvisUiElementMatcher`):**
  - Evaluates screen elements through 10 ranking tiers:
    1. Exact resource ID (+120) & registry ID (+115)
    2. Exact content description (+110), word match (+82), substring (+56)
    3. Exact visible text (+105), word match (+78), substring (+52)
    4. Inferred element role/class (+28, +45 for preferEditable/preferCheckable)
    5. Parent-child context (+68 for child label, +64 for parent container)
    6. Package & app registry context (+15)
    7. Actionable status (+22 for clickable/editable, -90 penalty if disabled)
    8. Bounds & keyboard occlusion (-45 penalty if occluded by `keyboardTopY`, +18 if healthy)
    9. Ordinal (`"first"`, `"second"`, `"third"`, `"last"`) & spatial (`"top"`, `"bottom"`, `"left"`, `"right"`) boosts (+55 / +25)
    10. Visual coordinate fallback when accessibility tree nodes lack semantic text.
  - Detects high-consequence ambiguity (e.g. "send", "delete", "pay") and prompts user rather than tapping blindly.
- **4-Level Action Verification Engine (`JarvisActionVerifier`):**
  - **Level 1 (Interaction):** Verifies OS gesture callback / `performAction` returned `true`.
  - **Level 2 (UI Hierarchy Delta):** Verifies real `UiStateFingerprint` change (window change, element tree delta, scroll sequence, checked state, or text change).
  - **Level 3 (Application Post-Condition):** Verifies foreground package matches expected target, or expected result element is visible on screen.
  - **Level 4 (System API State):** Queries real Android `AudioManager`, `BluetoothAdapter`, `WifiManager`, and `NotificationManager` to confirm hardware/system settings without trusting accessibility tree claims.
  - Returns `VERIFIED_SUCCESS`, `UNVERIFIED_UNKNOWN`, or `FAILED` — zero fake success.
- **8-Step Recovery Manager & Anti-Duplication Guard (`JarvisRecoveryManager`):**
  - Diagnoses failures: `INTERACTION_DISPATCH_REJECTED`, `NO_UI_RESPONSE`, `TARGET_NOT_FOUND`, `PACKAGE_MISMATCH`, `KEYBOARD_OCCLUSION`, `BLOCKING_DIALOG`, `OFF_SCREEN_NEEDS_SCROLL`, `UNKNOWN`.
  - Anti-duplication prevention: strictly blocks automated retries of high-consequence operations (sending messages, emails, placing phone calls, financial payments, deletion) if any prior step passed Level 1 or Level 2 verification, preventing duplicate sends or calls.
- **100% Real Google Login Chat Persistence & Account Vault (`BackupRestoreRepository.kt`, `JarvisCore.kt`, `JarvisViewModel.kt`):**
  - Every message exchange in `JarvisCore.persistAssistant` automatically commits the user's conversations and messages to an account-scoped encrypted JSON vault (`google_chat_sync/chats_<sanitized_email>.json`).
  - Upon signing in via Google (`signInWithGoogle`), Jarvis automatically reads and restores the user's prior chat conversations and messages, seamlessly updating the active transcript.
  - Upgraded `MemoryExport` (format v3) and `MasterJarvisBackup` to include `conversations` and `messages`.

## 2026-11-02 — Phase 2: Advanced Visual Intelligence & Autonomous Phone Operation

- **Hybrid Visual + Accessibility Intelligence (`VisualIntelligence.kt`):**
  - Combines structural `AccessibilityNodeInfo` hierarchy with downsampled visual screen bitmap evidence (`VisualEvidence`).
  - Strict privacy enforcement: Android `FLAG_SECURE` screens (banking, PIN, password fields) render as pure black in screenshots. `VisualAccessibilityReconciler` detects blackouts (>98% pure black pixels) and immediately halts automation with `SECURE_SCREEN_BLOCKED` rather than guessing coordinates.
  - Detects modal dialog scrims (luminance dimming) and keyboard occlusions, prioritizing dismissal before interaction.
- **Autonomous Phone Operator & Multi-Step Planner (`AutonomousPhoneOperator.kt`):**
  - Discarded blind coordinate execution scripts in favor of typed multi-step execution plans (`AutonomousExecutionPlan`).
  - Each step specifies `StepPrecondition`, `targetQuery`, `actionType`, `payloadText`, `StepPostcondition`, `timeoutMs`, `maxRetries`, and `isConsequential`.
  - Implemented typed 10-step execution for YouTube: Launch -> Verify Foreground -> Locate Search -> Activate Input -> Type Query -> Submit -> Wait for Results -> Identify First Video -> Tap Video -> Verify Playback via Level 4 AudioManager / UI pause transition.
  - **Dynamic Screen Reasoner & Stale Coordinate Rejection**: Screen versions increment on node/tree delta. Coordinates tagged with older screen versions (`ValidatedCoordinate.isStale`) are rejected, guaranteeing coordinates are never reused after screen transitions.
  - **Human Takeover System (`HumanTakeoverManager`)**: Intercepts physical screen touches (`AccessibilityEvent.TYPE_TOUCH_INTERACTION_START`), pauses active plans, cancels running gestures, and dispatches user notifications.
  - **Task State Persistence & Safe Resumption (`TaskResumptionEngine`)**: In-flight plans persist to `active_task_state.json`. When resumed, current screen state is reconciled against pending steps, ensuring completed and consequential steps are never repeated.
- **Modular Smart App Adapters (`SmartAppAdapters.kt`):**
  - Created modular adapters for WhatsApp, YouTube, Instagram, Chrome, Gmail, Google Maps, Spotify, Settings, Phone, Contacts, and Messages.
  - Encapsulates package identifiers, workflows, known selectors, dynamic fallbacks, screen state recognition (e.g. `CHAT_CONVERSATION`, `VIDEO_PLAYER_ACTIVE`, `INBOX`), and platform restrictions.

## 2026-11-03 — OpenClaw-Inspired Mobile UI Intelligence Upgrade (Phases 1–8)

- **Source-Level Comparison with OpenClaw Mobile UI Automation (`openclaw/openclaw` PRs `#112232`, `#112241`, `#112255` & `8crsk/openclaw-android`):**
  - Rather than replacing Jarvis's proven `HumanTouchEngine`, `JarvisScreenObserver`, `JarvisUiElementMatcher`, `JarvisActionVerifier`, `VisualAccessibilityReconciler`, and `AutonomousPhoneOperator`, we extended them with the three architectural contracts where OpenClaw's mobile UI model provides stronger determinism:
    1. **Snapshot-Scoped Element References (`snapshotId` + `ref_eN`)**: Every `ObservedScreenState` now emits a monotonic `snapshotId` (`snap_<version>_<timestamp>`), window-layer metadata (`ObservedWindowInfo`), and deterministic `elementRef` (`ref_e0`, `ref_e1`, ...) with explicit `parentRef` and `childRefs` graphs plus `supportedActions` extracted from `AccessibilityNodeInfo.actionList`.
    2. **Strict One-Action Execution Contract (`MobileUiActionRequest` & `MobileUiActionExecutor`)**: If a `MobileUiActionRequest` specifies an `expectedSnapshotId` that no longer matches the live screen (`snapshotId` mismatch and structural signature changed), execution halts immediately with `UiActionOutcomeState.TARGET_STALE` instead of tapping stale coordinates. Furthermore, if a semantic target (`elementRef` or `targetQuery`) is requested in `ActivateElement` or `EnterText` and cannot be resolved on the current screen, the executor returns `TARGET_NOT_FOUND` and never silently guesses arbitrary screen coordinates.
    3. **8-State Verification & False-Success Prevention (`ACCEPTED_BUT_UNVERIFIED` vs `VERIFIED_SUCCESS`)**: In Android Accessibility automation, `AccessibilityNodeInfo.performAction` and `AccessibilityService.dispatchGesture` return `true` as soon as the OS accepts the dispatch, even when the underlying app ignores the tap or is frozen. `MobileUiActionExecutor` automatically re-observes the screen after a bounded settle window, computes `ScreenSnapshotDiff` via `ScreenSnapshotComparator.compare`, and classifies outcomes into 8 states (`DISPATCHED`, `ACCEPTED_BUT_UNVERIFIED`, `VERIFIED_SUCCESS`, `FAILED`, `UNKNOWN`, `TARGET_STALE`, `TARGET_NOT_FOUND`, `SECURE_CONTENT`). When a dispatch returns `true` but neither the UI snapshot nor expected postcondition changes, the state is reported as `ACCEPTED_BUT_UNVERIFIED` rather than `VERIFIED_SUCCESS`.
- **Higher-Layer Window Occlusion & Vision Privacy Gate (`VisualIntelligence.kt`, `ScreenVisionTools.kt`):**
  - `HierarchyOcclusionDetector` inspects multi-window layering (`win.layer > target.windowLayer`) in addition to soft-keyboard (`keyboardTopY`) bounds so Jarvis detects when a permission prompt, floating dialog, or system overlay covers a target element's center point.
  - `VisionPrivacyGate` enforces a hard gate before any screenshot is sent to an external vision-language model (`SeeScreenTool`): user consent must be active, the selected provider must support vision, the screen must not be `FLAG_SECURE` or blacked out, and `screen.hasPasswordField()` must be `false`.
- **Fail-Closed Consequential Action Policy (`FailClosedActionPolicy`):**
  - Any action matching `SEND_MESSAGE`, `PUBLISH_OR_SHARE`, `PAYMENT_OR_PURCHASE`, `DELETE_DATA`, `UNINSTALL_APP`, `CHANGE_ACCOUNT_SECURITY`, or `GRANT_PERMISSION` requires `userApprovedConsequential = true` (or approval through `ConfirmationGate`) and records an anti-duplication key once dispatched so consequential actions are never auto-retried blindly.

