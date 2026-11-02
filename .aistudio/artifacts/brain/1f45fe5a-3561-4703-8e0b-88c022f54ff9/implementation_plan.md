# J.A.R.V.I.S. for Android — Phase 6 Completion, Voice & Mic Repairs, Live Model Discovery & Holographic HUD Overhaul

This plan completes **Phase 6 (Tier 2 Notification Listener & Tier 3 Multi-Step Screen Automation with immediate Stop controls)**, resolves the **Fish Audio / ElevenLabs Coroutine Flow crash**, fixes the **SpeechRecognizer language-not-supported error and 1-second background mic loop**, introduces **live AI model auto-discovery with a Free Models filter across providers**, and upgrades all **8 screens and animations** to match the uploaded J.A.R.V.I.S. holographic reference design 100%.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural and product choices have been confirmed from your answers and will govern this implementation pass:

- **Confirmed Scope**: Complete **Phase 6** end-to-end (Tier 2 Notification Reading/Dismissing + Tier 3 Multi-Step Screen Automation with visible Stop controls in both the notification shade and the in-app HUD), fix all reported voice, microphone, and provider issues, and upgrade the 8-screen UI/UX and animations to match the reference image.
- **Confirmed Screen Automation Architecture**: Keep existing single-step screen tools (`read_screen`, `tap_screen`, `type_on_screen`, `scroll_screen`, `press_system_key`) for quick atomic actions AND add a dedicated autonomous multi-step screen automation tool (`automate_screen`, capped at 15 observe–decide–act–verify steps) with a persistent `"Jarvis is controlling the screen"` notification, a notification `Stop` action, and a prominent in-app `Stop Screen Control` banner/button that halts execution within 1 second.
- **Confirmed Live AI Model Discovery**: Auto-fetch live model catalogues directly from provider APIs (OpenRouter, Google Gemini, Groq, OpenAI, Together, xAI Grok, Ollama, and curated Anthropic/fallback catalogues) with an interactive **Free Models** filter toggle, tool-calling support badges, and quick model switching from both the Home screen header and Settings.

---

## 1. Overview & Core Concept

- **What It Does**: Transforms Jarvis into a fully functional, always-capable voice-first personal AI assistant on Android 14+ (Samsung Galaxy A54) that speaks reliably through Fish Audio, ElevenLabs, or Android System TTS, listens without language-tag crashes or 1-second mic cycling, reads/summarizes/dismisses notifications, executes multi-step UI automation tasks safely with instant stop controls, automatically discovers free and tool-capable AI models, and presents a cinematic 8-screen holographic interface.
- **Target Audience / Persona**: The phone owner ("sir"), running Jarvis as a private sideloaded Android assistant built via GitHub Actions.
- **Key Value**: Eliminates all voice/recognition runtime failures, unlocks hands-free notification management and multi-step screen control, removes manual model ID guesswork by fetching live free models, and delivers the exact futuristic J.A.R.V.I.S. visual and motion experience shown in the reference sheet.

---

## 2. User Experience & Visual Design

### Visual Identity & Holographic Theme
- **Aesthetic Direction**: Deep-space cybernetic HUD inspired by J.A.R.V.I.S., featuring multi-layered glassmorphism, concentric counter-rotating arc-reactor geometry, 3D perspective particle globes, warm amber accent flares on outer mechanical rings, and luminous cyan telemetry.
- **Color Palette & Mood**:
  - `--background` / Void: `#02070D` to `#051322` deep radial abyss
  - `--surface` / Glass Panel: `#081B2E` at 88–94% opacity with top-lit cyan specular borders (`#00E5FF` at 35% alpha)
  - `--primary` / Arc Cyan: `#00E5FF` & Bright Core `#76F7FF`
  - `--accent` / Mechanical Amber: `#FF9E3D` subtle outer ring ticks & corner lens flares
  - `--status-online`: `#19F0A6` emerald telemetry dot
  - `--status-danger`: `#FF4D6A` critical stop / alert highlight
- **Typography & Hierarchy**:
  - Wide-tracked uppercase geometric headers (`J . A . R . V . I . S .` with `6.sp` letter spacing)
  - Tabular monospace numerals for live CPU, GPU, Memory, Storage, Battery, and Temperature readouts
  - Crisp sans-serif body copy inside frosted glass cards

### Step-by-Step 8-Screen Experience (Matching Reference 100%)
1. **Screen 1 — Boot Animation**:
   - Full-bleed concentric mechanical & holographic Arc Reactor with glowing triangular core and subtle warm amber outer light streaks.
   - `J . A . R . V . I . S .` title above `INITIALIZING SYSTEMS...` and a glowing horizontal cyan progress bar with a bright leading flare.
   - Five sequential checklist rows with glowing cyan ring indicators: `LOADING AI CORE`, `CONNECTING MODULES`, `CALIBRATING INTERFACE`, `INITIALIZING VOICE`, and `SYSTEMS ONLINE`.
2. **Screen 2 — Home Screen**:
   - Top bar with circular Reactor emblem, `J.A.R.V.I.S.` title, green `● ONLINE` status, and quick-action icon button.
   - Interactive **AI Model Selector Bar** showing rocket icon, `AI MODEL` label, active model name (e.g., `Claude 3.5 Sonnet` / `Gemini 2.5 Flash` / free OpenRouter model), inline waveform icon, and gear button—tapping opens a quick model/provider switcher with live Free Models filter.
   - Left & Right floating HUD telemetry overlays flanking the central Arc Reactor:
     - Left: `SYSTEM STABLE`, `CPU 12%`, `GPU 8%` with mini progress bars.
     - Right: `MEMORY 47%`, `STORAGE 87%` with mini progress bars.
   - Centerpiece animated **Arc Reactor** with 3D perspective holographic HUD cards below left and right (`Good morning, sir. All systems are online and at your service.`).
   - Four square glassmorphic quick-action tiles: `Web Search`, `Generate`, `Tools`, `Memory`.
   - Floating 5-slot bottom dock (`Home`, `Tools`, central glowing `Arc Reactor` button, `Memory`, `Settings`).
3. **Screen 3 — Voice Assistant Overlay / Mode**:
   - Activated when tapping the central Reactor or microphone button (or speaking the wake word).
   - Displays `Listening...` in cyan, live upper frequency bar spectrum (`VoiceWave`), a **3D Rotating Particle Globe with Orbital Rings**, live prompt/transcript (`"How can I help you, sir?"`), quick command pills (`Web Search`, `YouTube`, `Summarize`, `Analyze`), and a large concentric glowing Microphone orb button above the bottom navigation bar.
4. **Screen 4 — AI Response / Chat View**:
   - Right-aligned user query bubble (`Explain quantum computing`) and full-width frosted `J.A.R.V.I.S.` response card with Reactor badge.
   - Displays structured tool/source chips (`Sources: Article / Video / Research` or executed phone tools), an embedded **Dynamic Particle Wave Visualization** card when speaking/analyzing, and bottom action pills (`Regenerate`, `Copy`, `+ More`).
   - Bottom glassmorphic input bar (`Ask anything...`) with inline voice-wave trigger and send button.
   - **Visible Screen Automation Stop Control**: Whenever the multi-step screen controller is active, a high-visibility glowing red/cyan HUD control bar (`Jarvis is controlling the screen · Step N/15` + `[STOP]`) pins above the composer and in the system notification shade.
5. **Screen 5 — Tools Section**:
   - Header `J.A.R.V.I.S. / Tools`, category pills (`All`, `Productivity`, `Media`, `Analysis`, `Device`, `Notifications`), and `Search tools...` bar.
   - Glassmorphic tool cards with glowing icon wells, title, description, status badge (`● Online`, `● Local`, `● Connected`), and right chevron; tapping a tool lets you trigger or prefill it immediately.
6. **Screen 6 — Memory Visualization**:
   - Header `J.A.R.V.I.S. / Memory`, `Search your memory...` bar, and filter tabs (`All`, `Conversations`, `Files`, `Images` + Add/Export/Import/Forget actions).
   - Interactive **3D Holographic Memory Globe** with floating glassmorphic cluster nodes (`Projects`, `Ideas`, `Research`, `Personal`, `Media`) showing live item counts, tethered by glowing leader lines to the rotating wireframe sphere.
   - Bottom `Total Memories` summary card with total count (`153 items` / live count), mini bar-chart sparkline, and expandable list of individual editable memories below.
7. **Screen 7 — Settings**:
   - Clean modern J.A.R.V.I.S. Settings hub matching Screen 7 of the reference: `AI Model` (with live **Fetch Models** & **Free Models Only** toggle), `Voice Settings` (Fish Audio, ElevenLabs, System TTS), `Appearance` (Themes & UI), `Connections` (API Keys & Services), `System` (Performance, Battery & Background Listening), `Security` (Privacy, Permissions Wizard, Notification Access & Screen Control Blocklist), and `About`.
8. **Screen 8 — Quick Panel**:
   - Swipe-in right HUD drawer with `QUICK ACCESS` rows (`Camera Vision`, `Screenshot`, `YouTube`, `Summarize`, `Translate`), `SYSTEM` card (`Network Connected`, `Battery %`, `Temperature °C`, `Performance Optimal`), and `NOW PLAYING` card with live cyan waveform and `JARVIS Voice Active` status.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Fixing Fish Audio & ElevenLabs `Flow invariant is violated` / `Flow exception transparency is violated`**
  - *Root Cause*: Both `FishAudioTtsEngine` and `ElevenLabsTtsEngine` wrap their HTTP response stream loop inside `withContext(Dispatchers.IO) { ... emit(AudioChunk(...)) }` inside a `flow { ... }.flowOn(Dispatchers.IO)` builder. In Kotlin Coroutines, calling `emit` from inside `withContext` changes the coroutine context (`UndispatchedCoroutine`), throwing `IllegalStateException: Flow invariant is violated`. The surrounding `try / catch (e: Exception)` then catches that `IllegalStateException`, sets `retryable = true`, loops around, and calls `emit` a second time—which throws `Flow exception transparency is violated`!
  - *Chosen Approach*: Remove the inner `withContext(Dispatchers.IO)` block (since `.flowOn(Dispatchers.IO)` already dispatches the entire flow producer on `Dispatchers.IO`), perform the HTTP call and stream reading directly on the flow's dispatcher, and emit chunks outside of any broad `try/catch` that could swallow downstream collector exceptions. Also support both `s2.1-pro-free` / `s1` model headers and clean MP3/PCM handling so Fish Audio works reliably on all accounts.
- **Decision 2: Fixing SpeechRecognizer `"That language is not supported by the recogniser"` & 1-Second Background Mic Flicker**
  - *Root Cause*:
    1. `settings.voice.language` defaults to `"en"`, and `Locale.forLanguageTag("en")` produces `"en"` without a region code. Furthermore, `AndroidSttEngine` prefers `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` with `EXTRA_PREFER_OFFLINE = true` on Android 13+. On Samsung Galaxy devices (One UI / Android 14), if the offline language pack for bare `"en"` or the selected locale is not installed in the on-device recognizer, Android immediately emits `ERROR_LANGUAGE_NOT_SUPPORTED` (code 12) or `ERROR_LANGUAGE_UNAVAILABLE` (code 13).
    2. In `JarvisCore.onListeningServiceStarted()`, the `while (isActive)` loop calls `listenOnce(..., reportErrors = false)`. Every call sets `_state.update { it.copy(listening = true) }`, fails immediately with code 12, sets `listening = false`, waits `900ms`, and repeats infinitely—causing the microphone indicator to turn on and off every second!
  - *Chosen Approach*:
    1. In `AndroidSttEngine`, normalize bare language tags (`"en"` -> device default English tag such as `"en-US"` or `"en-GB"`, or `Locale.getDefault().toLanguageTag()`). If the on-device recognizer emits `ERROR_LANGUAGE_NOT_SUPPORTED` (12), `ERROR_LANGUAGE_UNAVAILABLE` (13), or `ERROR_CLIENT` (5), transparently fall back within the same session to the standard `SpeechRecognizer.createSpeechRecognizer(context)` with `EXTRA_PREFER_OFFLINE = false`, and if a custom language tag still fails with 12/13, retry once without `EXTRA_LANGUAGE` so the device's default recognizer language always succeeds.
    2. Separate background passive wake-word listening state from foreground active user-listening state so passive background cycles don't flash the UI mic state every second, and add adaptive exponential backoff if the recognizer reports repeated errors.
- **Decision 3: Live AI Model Auto-Discovery with Free Models Filter**
  - *Chosen Approach*: Build `ModelCatalogueService` using OkHttp to query live `/models` endpoints:
    - **OpenRouter** (`GET https://openrouter.ai/api/v1/models`): Parses `pricing.prompt == "0"` and `pricing.completion == "0"` (or `:free` suffix) and `supported_parameters` containing `"tools"` to identify 100% free, tool-capable models automatically.
    - **Google Gemini** (`GET https://generativelanguage.googleapis.com/v1beta/models?key=...`): Fetches all available `generateContent`-capable Gemini models (e.g., `gemini-2.5-flash`, `gemini-2.5-pro`, `gemini-2.0-flash`) and marks free-tier eligible models.
    - **Groq / OpenAI / Together / xAI Grok / Ollama** (`GET {baseUrl}/models`): Fetches live model lists with a built-in curated fallback catalogue so models are always selectable even before entering an API key.
- **Decision 4: Tier 2 NotificationListenerService & Tier 3 Multi-Step Screen Automation**
  - *Chosen Approach*:
    - Add `JarvisNotificationListenerService` bound in `AndroidManifest.xml`, maintaining an in-memory + Room-logged ring buffer of active notifications with tools `read_notifications`, `reply_or_open_notification`, and `dismiss_notifications`.
    - Add `ScreenAutomationRunner` and `AutomateScreenTool` (`automate_screen`) that runs an observe -> decide -> act -> verify loop up to 15 steps. While active, `NotificationHelper` posts a high-priority ongoing notification `"Jarvis is controlling the screen"` with a `Stop` action broadcast/service intent, and the UI displays a persistent red/cyan `Stop Screen Control` bar. Tapping either `Stop` cancels the automation `Job` immediately (<100ms).

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose Holographic UI                       │
│ ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌────────┐ ┌────────┐ ┌───────────┐ │
│ │1.BootHUD │ │2.HomeHUD │ │3.VoiceOrb │ │4.Chat  │ │5.Tools │ │6.3DMemory │ │
│ └────┬─────┘ └────┬─────┘ └─────┬─────┘ └───┬────┘ └───┬────┘ └─────┬─────┘ │
│      └────────────┴─────────────┴───────────┼──────────┴────────────┘       │
│                                 ┌───────────┴───────────┐                   │
│                                 │7.Settings & 8.QuickPan│                   │
│                                 └───────────┬───────────┘                   │
└─────────────────────────────────────────────┼───────────────────────────────┘
                                              │ StateFlow<JarvisUiState>
┌─────────────────────────────────────────────▼───────────────────────────────┐
│                          JarvisCore (App Singleton)                         │
│  ┌────────────────────────┐  ┌───────────────────────┐  ┌─────────────────┐ │
│  │   Brain & AgentLoop    │  │  Voice & STT/TTS Pipe │  │ Safety & Gates  │ │
│  │ • OpenAI/OpenRouter    │  │ • AndroidStt (Auto-   │  │ • ConfirmSheet  │ │
│  │ • Google Gemini        │  │   Fallback & Locale)  │  │ • Blocklist     │ │
│  │ • Anthropic Messages   │  │ • FishAudio (No Inner │  │ • ScreenStop    │ │
│  │ • ModelCatalogueFetcher│  │   withContext Crash)  │  │   Cancellation  │ │
│  │   (Live + Free Filter) │  │ • ElevenLabs & System │  └────────┬────────┘ │
│  └───────────┬────────────┘  └───────────┬───────────┘           │          │
└──────────────┼───────────────────────────┼───────────────────────┼──────────┘
               │                           │                       │
┌──────────────▼───────────────────────────▼───────────────────────▼──────────┐
│                     Android System & Hardware Services                      │
│  ┌────────────────────────┐  ┌───────────────────────┐  ┌─────────────────┐ │
│  │Tier 2: Notification    │  │Tier 3: Accessibility  │  │Always-On Mic    │ │
│  │JarvisNotificationSvc   │  │JarvisAccessibilitySvc │  │JarvisListening  │ │
│  │• read_notifications    │  │• Single-step tools    │  │Service          │ │
│  │• dismiss_notifications │  │• automate_screen loop │  │• Backoff &      │ │
│  │• summarize unread      │  │  (<=15 steps + Stop)  │  │  WakeWord parse │ │
│  └────────────────────────┘  └───────────────────────┘  └─────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Interactive Component & State Mapping
- **Voice Synthesis Pipeline (`FishAudioTtsEngine` & `ElevenLabsTtsEngine`)**:
  - Executes HTTP request directly inside `flow { ... }.flowOn(Dispatchers.IO)` without nested `withContext(Dispatchers.IO)`.
  - Reads 8 KiB PCM chunks into a local buffer before calling `emit(AudioChunk(...))` outside any exception-swallowing block, preventing `Flow invariant is violated` and `Flow exception transparency is violated`.
- **Speech Recognition & Background Loop (`AndroidSttEngine` & `JarvisCore`)**:
  - Resolves language tag safely (mapping `"en"` to a full regional tag like `"en-US"` or device default).
  - Automatically falls back from `OnDeviceSpeechRecognizer` to standard network/system `SpeechRecognizer` on codes `12` (`ERROR_LANGUAGE_NOT_SUPPORTED`), `13` (`ERROR_LANGUAGE_UNAVAILABLE`), or `5` (`ERROR_CLIENT`).
  - Separates `backgroundListening` status from `manualListening` UI state and applies backoff when no speech is detected so the mic never flickers on/off every second.
- **Multi-Step Screen Automation Loop (`ScreenAutomationRunner` & `AutomateScreenTool`)**:
  - Exposes `screenAutomationActive: Boolean` and `screenAutomationStatus: String?` on `CoreState`.
  - Posts ongoing notification `SCREEN_CONTROL_NOTIFICATION_ID` (`"Jarvis is controlling the screen"`) with a `PendingIntent` broadcast/service action `ACTION_STOP_SCREEN_CONTROL`.
  - Executes up to 15 steps of `(snapshot screen -> check blocklist -> ask LLM for next atomic action [tap / type / scroll / press / done] -> confirm if risky label -> perform action -> wait 650ms for UI settle -> verify)`.
  - Clicking `Stop` in the notification or in the Compose HUD immediately cancels `automationJob`, dismisses the notification, and logs the interruption.
- **Notification Listener (`JarvisNotificationListenerService` & `NotificationTools`)**:
  - Captures active `StatusBarNotification` entries (excluding ongoing foreground service cards and sensitive OTP/password texts), exposing `read_notifications` and `dismiss_notifications` to the agent loop and showing live unread notification counts in the greeting and Quick Panel.
- **Live Model Discovery (`ModelCatalogueService`)**:
  - Fetches live models from the configured provider's API, caches results in memory, and exposes `fetchedModels`, `fetchingModels`, and `freeModelsOnly` in `JarvisUiState` so the user can filter and pick working free models with one tap in Settings or the Home AI Model bar.
