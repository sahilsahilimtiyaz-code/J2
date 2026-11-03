# Progress

## Phase 0 — Skeleton and CI

- [x] Create the native Kotlin / Jetpack Compose app module (`minSdk 31`).
- [x] Add a dark Compose launch screen that says **Jarvis online**.
- [x] Add the GitHub Actions debug-APK build and artifact upload workflow.
- [x] Add the manually triggered keystore-generation workflow.
- [x] Add phone-only signing setup and test instructions to `README.md`.
- [x] Configure CI signing from four repository secrets, with a secure fallback for the bootstrap build.
- [x] Owner: add the four signing secrets, download the signed APK and install it.

## Phase 1 — Brain, chat, persona (complete)

- [x] Shared LLM model types, `LlmProvider.stream`, event types and provider exceptions.
- [x] OpenAI-compatible streaming adapter with tool-call accumulation.
- [x] Gemini streaming adapter with function calling.
- [x] Anthropic Messages streaming adapter with tool use.
- [x] Provider registry, fallback order and connection tester.
- [x] Provider presets and Settings UI for add, edit, save, test, default, reorder, remove, plus one-tap **Load All Free Providers** (`21` global presets including **Z.ai (Zhipu GLM)**, **NVIDIA NIM**, **OpenCode Zen**, **Agnes AI**, **Google Gemini**, **OpenRouter**, **Groq**, **Cerebras**, **SambaNova Cloud**, **Mistral AI**, **GitHub Models**, **SiliconFlow**, **Cohere**, **Hugging Face Router**, **Chutes AI**, **Pollinations AI**, **Together**, **xAI Grok**, **Ollama**, **OpenAI**, and **Anthropic**).
- [x] Automatic live model discovery (`ModelCatalogueService`) plus preloaded global free model catalog across all 17+ free AI providers with provider filter chips (`All`, `Z.ai`, `NVIDIA NIM`, `OpenCode Zen`, `Agnes AI`, `Cerebras`, `SambaNova`, `Mistral AI`, etc.), `100% FREE API` badges, API key portal links, and a **Free Models Only** filter toggle.
- [x] AES-GCM Android Keystore storage for provider keys.
- [x] Persona prompt builder, owner onboarding and local greeting engine.
- [x] Compose chat with streamed replies, tool activity, provider status and retry.

## Phase 2 — Voice (complete)

- [x] `SttEngine` and Android speech recognition, with BCP-47 language normalization (`"en"` -> `"en-US"`), noisy/bulky speech tolerance (`2500ms` complete silence window, `12000ms` min speech window, partial transcript rescue on timeout), real-time RMS audio level emission, and continuous always-on foreground listening while the app is open.
- [x] `TtsEngine` as a streaming PCM flow; Fish Audio and ElevenLabs implemented with ultra-low-latency streaming (`FIRST_CHUNK_BYTES = 1024`, Fish `chunk_length = 100`, TLS connection pre-warming, working model memoization, zero holdback buffering, and early first-clause flush).
- [x] Permanent Voice TTS API key & Voice ID retention per provider (`fishEncryptedApiKey`, `elevenLabsEncryptedApiKey`) with redundant DataStore backup keys and in-memory `SecretCipher` caching so keys never disappear.
- [x] 16-bit PCM even-byte frame alignment and automatic Fish Audio model fallback (`s2.1-pro-free` -> `s1` -> `speech-1.6`).
- [x] Android system TTS fallback, with a working stop/cancel path.
- [x] AudioPlayer: low-latency `AudioTrack` per reply, real-time PCM RMS `audioLevel` StateFlow, no gap between sentences, bounded drain, safe stop.
- [x] Barge-in and audio focus, including a proper audio-focus release.
- [x] Voice settings screen with per-provider saved key badges, draft auto-save on `Test voice`, and error surfacing.

## Phase 3 — Tier 1 phone tools & Neural HUD (complete)

- [x] `ConfirmationGate` and `PermissionGate` on the tool context.
- [x] Tier 1 tools plus `list_installed_apps`, `take_screenshot`, `open_camera`, `send_whatsapp_message`, `clipboard_control`, `open_url`, fuzzy app matching, contact lookup and the action log.
- [x] Runtime permission requests driven by the tool that needs them.
- [x] Every tool runs off the main thread and is recorded in the action log; every card in `ToolsScreen` and `QuickPanel` triggers a concrete, real mission command.
- [x] Full 8-screen Holographic HUD UI with Iron Man J.A.R.V.I.S. Neural Orb (`ArcReactor` & `HolographicGlobe`) featuring 360° Polar Fourier audio-reactive rings, 3D spherical neural node lattice, and synaptic lightning filaments coupled to live `audioLevel` and `toolActive` states.

## Phase 4 — Memory (complete)

- [x] Room database (`jarvis.db`) with `profile_facts`, `memories`, `memories_fts`, `conversations`,
      `messages`, `summaries`, `skills` and `action_log`.
- [x] FTS4 (unicode61) mirror kept in sync by Room triggers, with a LIKE fallback.
- [x] `MemoryRepository`: remember with fingerprint de-duplication, retrieve, edit, delete, count,
      profile-fact sync, conversation persistence and summaries.
- [x] Retrieval on every turn: always-on profile facts plus the memories that match the request,
      injected into the system prompt.
- [x] `remember_about_owner`, `search_memory` and `forget_memory` tools, with a secret guard and a
      confirmation before deleting.
- [x] Conversations and messages persist across a full restart; the transcript is reloaded on launch.
- [x] Background extraction with a WorkManager job that runs a few minutes after the last turn,
      distils at most six durable facts, skips secrets and writes a short summary.
- [x] Memory screen: 3D Holographic Memory Globe with floating clusters (`Projects`, `Ideas`, `Research`, `Personal`, `Media`), `Total Memories` mini bar chart, search, add, edit, delete, profile facts, "extract now", JSON export and import, and "forget everything".
- [x] Action log moved into Room, with a one-time import of the old JSON log file.

## Phase 5 — Always-on background listening (complete)

- [x] App-scoped `JarvisCore` shared by the UI, the listening service and the workers.
- [x] `JarvisListeningService`: foreground service with `microphone` type, ongoing notification with
      Stop, partial wake lock, and `START_NOT_STICKY` so Android never rejects a background restart.
- [x] Fixed 1-second background mic loop flicker: passive wake-word listening uses `showActiveUi = false` and exponential backoff when recognition fails, keeping the UI orb calm until the wake word is heard.
- [x] Summons with a short "Yes, sir?" and a fresh time-aware greeting after a gap.
- [x] Quick Settings tile to start and stop listening.
- [x] Default assistant: implemented `VoiceInteractionService`, `VoiceInteractionSessionService`, and `JarvisRecognitionService` (`android.service.voice.VoiceInteractionService`), allowing Jarvis to be chosen as the Android Default Digital Assistant app with instant Side Key (Power button) or corner assist gesture summon.
- [x] Cloud Whisper STT Engine (`WhisperSttEngine`) with 44-byte RIFF WAVE header generation and low-latency transcription via Groq `whisper-large-v3-turbo` and OpenAI `whisper-1`.
- [x] Battery guidance and the "start it from the app" rule in the permission wizard, plus deep-linking directly to Android Default Assistant settings.

## Phase 6 — Notifications (Tier 2), Multi-Step Screen Control (Tier 3) & Security Shield (complete)

- [x] `JarvisNotificationListenerService`: Tier 2 `NotificationListenerService` that caches active status-bar notifications, redacts one-time verification codes (OTP/2FA), sanitizes untrusted text via `SecurityShield`, and supports dismissing by app, keyword, or all.
- [x] `read_notifications` and `dismiss_notifications` tools registered in `ToolRegistry`.
- [x] Accessibility service and single-step screen primitives: read, tap by label, type, scroll,
      back/home/recents/notifications/quick settings, plus `tapFirstYouTubeVideo` fallback.
- [x] Direct YouTube video resolution & playback (`YouTubeSearchTool` resolves top `videoId` and title and opens `watch?v=...` / `vnd.youtube:` directly instead of stopping on the search page).
- [x] `AgentLoop` duplicate tool call short-circuiting, terminal tool completion directives, and tool-free final synthesis pass (eliminating the `"I stopped after eight tool rounds"` error).
- [x] Persistent in-place `SpeechRecognizer` (`rearmInPlace` on silence timeouts without destroying/recreating the recognizer) and unified foreground + background continuous listening without open/close flickering.
- [x] Immutable Creator Identity (**Octavian**) permanently embedded across system prompt, `PersonaBuilder`, and `MemoryRepository` (survives "forget everything").
- [x] `SecurityShield`: hacking-proof multi-layer protection against indirect prompt injection, malicious URI schemes (`javascript:`, `file://`, `content://`, `intent://`, `.apk` auto-downloads), protected system packages (`packageinstaller`, `permissioncontroller`, `knox`), and OTP/card/API-key leakage.
- [x] `automate_screen` multi-step screen automation loop (up to 15 observe–decide–act–verify steps) with blocklist enforcement, risky-tap confirmation, and action logging.
- [x] Persistent high-priority `"Jarvis is controlling the screen"` notification with a `Stop` action (`ScreenControlStopReceiver`) plus a visible in-app `STOP` banner on `HomeScreen` that halts automation within 1 second.
- [x] Permission wizard updated with both Tier 2 Notification Access and Tier 3 Accessibility setup steps, including restricted-settings guidance for sideloaded APKs.

## Phase 7 — Autonomous Presence, Proactive Care & Self-Improvement (complete)

- [x] `DevicePresenceMonitor`: real-time tracking of screen-on state, continuous active session minutes, total daily screen time, battery/charging state, and unread notifications.
- [x] `ScreenUsageHealthService`: background service tracking continuous & daily screen usage across restarts and delivering periodic, non-intrusive voice health suggestions (20-20-20 eye rest, hydration, posture, standing stretch) when usage exceeds recommended limits, with `Took a break` and `Snooze 15m` notification controls.
- [x] Verified `AccessibilityService` Action Execution Loop (`StepVerificationEvaluator`, `ScreenController.tapVerified`, `tapFirstYouTubeVideoVerified`, `typeVerified`, `scrollVerified`): captures pre- and post-step UI/audio fingerprints, confirms UI elements (such as a YouTube video or play button) actually responded, and automatically retries via physical coordinate gesture taps (`dispatchTapGesture`) before proceeding to the next automation step.
- [x] Synchronous Android Keystore Credential Vault (`EncryptedKeystoreVault` + `SecretCipher` in `SettingsRepository`): hardware-backed `AndroidKeyStore` AES-256-GCM encryption with synchronous commit-verified persistence so TTS API keys (Fish Audio, ElevenLabs) and LLM provider keys are never lost across app restarts.
- [x] Autonomous Thought Engine (`JarvisCore.deliverAutonomousThought`): rare, cooldown-gated spontaneous thoughts covering wellbeing/rest reminders during long device sessions, dry British wit/jokes, creative ideas, rare genuine compliments, and notification briefings.
- [x] Declarative automated skills save/replay engine (`save_skill`, `run_skill`, `list_skills`, `delete_skill`, `SkillRepository`, fast-path deterministic execution in `runTurn` in <1s), `SkillsScreen` UI, nightly self-review worker (`SelfReviewWorker` writing durable `MemoryType.LESSON` memories), and `ProposalsScreen` with Before/After review and approval.
- [x] Incoming Full-Screen Priority Audio Call & Exact Reminder Scheduler (`JarvisIncomingCallActivity`, `JarvisAlarmReceiver`, `schedule_reminder`, `schedule_morning_briefing`) waking the screen over keyguard with pulsating Arc Reactor UI and Answer/Decline actions.

## Phase 8 — Polish (complete)

- [x] Quick Settings tile (`JarvisTileService`).
- [x] Home-screen Arc Reactor Widget (`JarvisWidgetProvider`, `widget_jarvis_reactor.xml`, `jarvis_widget_info.xml`) showing real-time J.A.R.V.I.S. online status with instant one-tap Mic summon and Stop actions.
- [x] Crash-safe runtime start (the application logs rather than kills the process).
- [x] Non-destructive setting upgrade migrations and JSON memory & skill persistence.

## Phase 9 — Google Services Integration (complete)

- [x] Google Calendar read (`read_calendar_events`) and event creation (`create_calendar_event`).
- [x] Gmail drafting & dispatch (`compose_email`) guarded by the mandatory Confirmation Gate.

## Part 2 & Polish — Ultimate Power Architecture (complete)

- [x] **Procedural Iron Man J.A.R.V.I.S. Acoustic SFX Engine (`SoundFxEngine.kt`)**: mathematical 16-bit PCM waveform synthesis (24 kHz) playing reactive acoustic cues for Arc Reactor boot, listening start, command acknowledgment, action confirmation, duplex chirps, and alerts without external MP3 files.
- [x] **Autonomous Duplex Conversation Mode ("Hands-Free Walkie-Talkie Link")**: continuous conversational loop in `JarvisCore` re-arming listening with a subtle chirp after replies speak, allowing fluid back-and-forth multi-turn voice interaction without saying wake words or tapping buttons.
- [x] **Advanced Hardware & Telemetry Diagnostic Tools (`DeviceDiagnosticsTools.kt`)**:
  - `run_diagnostics`: battery health, charging power source, temperature (°C), voltage, RAM load, storage GB, network bandwidth speed, and system uptime.
  - `analyze_storage`: deep internal and external MicroSD storage breakdown with usage percentages and health recommendations.
  - `set_do_not_disturb`: inspect or configure Do Not Disturb / Zen mode.
  - `set_screen_brightness`: inspect and adjust brightness percentage or auto-brightness mode.
  - `inspect_app`: query deep package metadata, install/update timestamps, target SDK, and permissions.
- [x] **Context Screen Vision & Optical Scene Inspection (`AnalyzeScreenVisionTool`)**: inspects visible UI hierarchy, window titles, paragraphs, buttons, and editable inputs for multimodal agent visual comprehension, protected by `SecurityShield` credential redaction.
- [x] **Floating Arc Reactor Overlay Assist HUD (`JarvisFloatingHudService`)**: draggable, glowing Arc Reactor floating badge over other apps (YouTube, Chrome, Games) with tap-to-talk, double-tap emergency stop, and long-press app launcher.
- [x] **Full System Backup & Master Restore Engine (`BackupRestoreRepository.kt`)**: comprehensive JSON state backup and restore spanning owner profiles, persona tones, app preferences, automated macro skills, facts, memories, and learned lessons.
- [x] **Deep Polish for P1 & P2**:
  - Rhythmic heartbeat haptic vibration (`VibrationEffect.createWaveform`) and acoustic alert pulse in `JarvisIncomingCallActivity`.
  - Duplex conversation toggle and Floating HUD controls in `QuickPanelOverlay` and `HomeScreen`.
  - Expanded unit test coverage in `SkillsAndToolsTest` covering diagnostics, screen vision, backup restore, and SoundFxEngine.

## 2026-11-01 — Continuous microphone, verified screen actions & real YouTube playback

- **Superseded 2026-11-01:** the keeper/hand-off design in this section was removed in favour of the single-flow latch; see the latest entry at the bottom.
- [x] Replaced per-utterance recognizer teardown with a persistent engine: one `SpeechRecognizer`, an always-on 16 kHz `AudioRecord` mic keeper, and voice-onset detection. Silence no longer closes the microphone; the keeper bridges recognizer sessions so the OS privacy indicator stays solid while idle in the background.
- [x] Recognition is armed continuously rather than VAD-gated, so the first syllable (for example "Jarvis") is not clipped. The keeper takes over only during the short gap between sessions, and a fallback path starts recognition if `AudioRecord` cannot open.
- [x] `scroll_screen` now always performs a physical swipe, even when `ACTION_SCROLL` claims success, and retries with a longer swipe when the fingerprint does not change.
- [x] Taps retry at the thumbnail/label offset and report `verified: false` (with a plain explanation) instead of claiming success when the screen did not change. Gesture coordinates use `currentWindowMetrics`, including system bars.
- [x] Video-card discovery climbs from title/thumbnail nodes to the real card bounds, making YouTube feed and search-result cards reliably tappable.
- [x] `search_youtube` resolves the top result through YouTube's InnerTube search with an HTML fallback, deep-links directly into the player, taps Play when paused, and verifies real audio. If playback cannot be confirmed it says so instead of inventing success.
- [x] Persona and prompt playbook: play requests call `search_youtube` once; screen actions must be retried or reported honestly when unverified.
- [x] Animation polish: layered mirrored voice spectrum with peak caps, reflection and shimmer; animated holographic panel scanlines; animated bottom-nav indicator; smooth boot progress; voice-screen entrance scale/fade.
- [x] Verification: `gradle assembleDebug testDebugUnitTest` green (34 tests), debug APK built; live InnerTube probe resolved a real video id.

## 2026-11-01 — Reference-board UI alignment

- [x] Replaced the teal-cyan palette with the reference board's electric blue and deeper navy glass, including a copper rim glow and faint containment rings in the shared backdrop.
- [x] Bundled Rajdhani Light/Regular/Medium/SemiBold (SIL OFL, license in `assets/licenses/`) and applied it to the J.A.R.V.I.S. logotype, headings, HUD labels and numeral styles; body copy remains the platform sans.
- [x] Enlarged the boot reactor to 90% of the content width, the home reactor to 252 dp in a 286 dp stage, the voice globe to 272 dp, and the microphone orb to 108 dp with 148 dp pulse rings.
- [x] Added a divider under the boot logotype and aligned the boot checklist/progress with the board's proportions.
- [x] Moved Tools, Memory and Settings to the board's structure: JARVIS header with only the trailing action, then a large Rajdhani screen heading.
- [x] Removed the bottom navigation from voice mode, matching Screen 3 of the board.
- [x] Matched the Tools list to the board's six showcase entries with distinct colored icon wells and labels (`Video Analysis`, `File Manager`, `Camera Vision`, `Terminal`), while keeping registry tools deduplicated below.
- [x] Reworked chat details: 28 dp circular response avatar, compact user bubble, and source cards with generated thumbnails and per-type glyphs.
- [x] Streamlined the quick panel to the board's three cards: five quick actions, four system metrics, and the now-playing waveform. Removed the extra skill/proposal rows and hardware toggle pills from the panel.
- [x] Verification: `gradle assembleDebug testDebugUnitTest` green (34 tests, 0 failures/errors/skips); debug APK 38 MB built.

## 2026-11-01 — Latched continuous microphone & neural-orb core

- [x] Removed the `AudioRecord` mic keeper and every keeper/recognizer hand-off. `AndroidSttEngine` now exposes one long-lived recognizer flow that re-arms immediately after finals and recoverable errors; the on-device fallback is tracked accurately, and a recognizer that cannot start four times reports a fatal error instead of spinning.
- [x] `JarvisCore` owns one continuous collector. `send`, `beginSpeaking` and TTS completion no longer clear `listening`; `micLocked` is cleared only by `stopListening()` (mic button, notification Stop, or Quick Settings tile).
- [x] TTS echo-safe barge-in: finals while generating and for 1.2 s after speech ends are ignored. During TTS a plain stop/cancel phrase or a wake-word-addressed command interrupts; echo matching against the current reply and a 700 ms onset window prevent self-interruption, and the mic remains armed throughout.
- [x] Unified the explicit stop paths: the ongoing notification and the Quick Settings tile now call `JarvisCore.stopListening()` instead of only stopping the service. The widget's Stop action remains screen-control stop.
- [x] Replaced the triangle core with a glass neural orb (radial gradient, 3D projected synaptic lattice, counter-rotating orbital rings, firing filaments, white-hot center, specular highlight) and the header/nav glyph with a mini orb.
- [x] Verification: `gradle assembleDebug testDebugUnitTest` green, 34 tests / 0 failures / 0 errors / 0 skipped; debug APK 40 MB.
- [ ] Phone verification: confirm the mic stays on through a reply and an idle period, explicit stop works from all three paths, and the orb renders without a triangle. OS privacy-indicator behavior at platform session boundaries cannot be verified in this VM.

## 2026-11-01 evening — Pixel vision, one-shot WhatsApp, real search, gapless voice, call screen, boot video

- [x] **Real screen vision:** `JarvisAccessibilityService` gained `canTakeScreenshot`, `captureScreenshotBitmap` and `captureScreenshotBytes` (API 30+ `takeScreenshot` → `HardwareBuffer` → bitmap → ≤1000 px JPEG q72 with true display dims). `ToolResult` now carries an optional image byte array.
- [x] **Images into the brain:** `ChatRequest.images` + `AttachedImage` added; OpenAI-compatible (`image_url` data URI), Gemini (`inline_data`) and Anthropic (base64 block) providers all append the screenshot as one final user turn; `AgentLoop` stashes the latest tool image, attaches it to the next round and retries without the image when a provider rejects attachments.
- [x] **Vision tools:** rewritten `see_screen` (JPEG + sanitized label merge), `tap_screen_coordinates` percent units (x/y 0–100 scaled by real device size), and the `automate_screen` planner now receives a fresh screenshot every step and plans percent-coordinate taps.
- [x] **WhatsApp automation engine (`AppAutomation.kt`):** node/spot finder with resource-id + label + bounds predicates, deep task loops built on `findSpot`/`findByViewId`, real touch gestures via `dispatchTapGesture` with verified node-click fallback (`tap_spot`/`tapNodeAction`), 16-bit-safe `typeIntoEditable` with focus fallback, and `verifyMessageSent` input-clear + duplicate-text confirmation.
- [x] **One-shot WhatsApp send:** `send_whatsapp_message` rewritten — phone digits go through `wa.me?text=`, name-only targets open WhatsApp's search, type the name, open the matching row, then type + press Send + verify inside the chat; the Confirmation Gate still guards every real send.
- [x] **Greeting gate:** deterministic `isGreetingOnly` in `JarvisCore.send` answers bare greetings ("hi", "hello" ...) with a fresh `GreetingEngine` line; they never reach the LLM or a tool.
- [x] **Real web search:** `web_search` rewritten to fetch live DuckDuckGo instant-answer / lite / html results with a mobile UA and clean URL decoding; persona rules forbid opening a browser on search requests.
- [x] **Two-phase incoming-call screen:** ringing phase (staggered sonar rings around the logo avatar, reason card, wide Decline/Answer pills with press animation) and in-call phase (audio-reactive reactor, live timer, live caption bubble from the new `CoreState.spokenCaption` field, End Call → `JarvisCore.endSpokenCall()`); the caption is set per spoken sentence in `synthesizeSentence` and cleared on speak start/end/interrupt.
- [x] **Gapless TTS:** `AudioPlayer` pre-rolls 0.25 s (6 000 frames) before `track.play()`, holds back at most 500 ms, runs a ~2.5 s deep jitter buffer (`sampleRate * 5` bytes), plays in NORMAL_MODE (LOW_LATENCY removed), drains pending audio outside the lock so Stop stays instant, and Fish Audio's stream slice grew 4 KB → 16 KB.
- [x] **Thought cadence:** notification briefings ~8 min, spontaneous thoughts ~10 min, wellbeing-care milestones every ~15 min, compliments after ~8 active minutes, screen-health reminder cooldown lowered 30 min → 10 min, and `sessionLimitMinutes` defaults to 15 (coerced 10–240).
- [x] **Professional neural orb:** `ArcReactor` rewritten as a copper/cyan rim-lit glass sphere with a depth-sorted 3D neural lattice, travelling synapse pulses, counter-rotating elliptical rings, a quiet 60-tick precision ring, a state-reactive arc and a specular highlight (all driven by `NeuralOrbPhysics`).
- [x] **Boot video + identity:** muted looping `assets/boot/boot_intro.mp4` plays behind the J.A.R.V.I.S. wordmark, progress bar and checklist with the reactor boot as fallback; logo PNG shipped as `drawable-nodpi/ic_app_logo.png`, the manifest uses an adaptive `@mipmap/ic_launcher` over `#061B2B`, and the legacy drawable icon remains for the tile/widget.
- [x] **Camera:** `open_camera` applies joint front/back lens extras; `take_photo` runs a real `ACTION_IMAGE_CAPTURE` session, presses the shutter, confirms the preview screen and verifies the file in `Pictures/Jarvis/`.
- [x] Verification: `gradle assembleDebug testDebugUnitTest` green (34 tests, 0 failures/errors/skips); debug APK 46.9 MB including the boot video; `android.aapt2FromMavenOverride=/root/aapt2bin/aapt2` re-added to `gradle.properties` so the aarch64 phone builds through the qemu x86 aapt2 wrapper.
- [ ] Phone verification: pixel-vision answers, WhatsApp sends by number and by name, ordinary greetings stay inert, search speaks results, call-screen captions, gapless long replies, thought cadence, shutter capture, boot video, launcher icon.

## 2026-11-01 late evening — WhatsApp voice-call receptionist ("answer for sir")

- [x] **Detection:** `JarvisAccessibilityService` gained a single `windowStateListener` hook fired on every `TYPE_WINDOW_STATE_CHANGED` with package + top-level class name. `WhatsAppCallAutomation.isIncomingCallWindow` matches WhatsApp's voip/incoming-call window classes; `JarvisCore.handleWhatsAppWindowEvent` filters by the new `SafetySettings.callAssistantEnabled` gate plus `screenControlEnabled`, debounces with a 45 s per-call window and funnels into one bounded session.
- [x] **Answering the call:** layered 3-strategy tap with mandatory verification: known WhatsApp view-id tokens (`answer_fab`, `accept_fab`, …) → label match ("Answer", "Accept call", …, long labels first) → geometric green-FAB fallback (square-ish 90–340 px clickable control, bottom-right quadrant, excluding end/decline/mute labels). Every tap is confirmed by the active-call panel appearing (mute/speaker controls, or the live call timer), otherwise the next strategy is tried — success is never claimed unverified. `endActiveCall` mirrors this with decline/hangup tokens and red-button labels.
- [x] **The conversation (engagement):** `WhatsAppCallConversation` holds a real back-and-forth loop: it opens with one line ("this is Jarvis, speaking for sir, he is not available"), then listens to the caller, runs the caller's sanitized words (SecurityShield, 600-char cap, codes redacted) through `WhatsAppCallAutomation.buildCallerTurnMessage` + the receptionist system prompt into the owner's configured provider (OpenAI-compatible/Gemini/Anthropic stream, replies capped at 240 chars), speaks the reply, and repeats for `callAssistantMaxTurns` (clamped 2–12, default 6). Callers are engaged about what they actually say — news acknowledged, one follow-up question, or a message taken for sir. Farewell phrases ("thanks, bye", "tell him I called", "I'll call later") close the call gracefully; two silent passes leave a courtesy line first; the caller hanging up is detected via the active-call panel disappearing.
- [x] **Jarvis's own voice during the call:** speech reuses the normal synthesis pipeline (`speakExternally` injects `SpeechItem.Sentence`/`EndOfReply` into the speech inbox and awaits playback), so Fish/Eleven/system voice, gapless AudioPlayer buffering and live `spokenCaption` all apply. Chat transcript, memory and greetings are untouched: `speak()` is gated off mid-call, never inserts messages, and the main mic collector is paused (not user-stopped) and `onReplySpoken` refuses to re-arm while a call session runs, so nothing owner-side can leak into the call or act on caller audio.
- [x] **Warm mic & safety:** each listening pass creates its own `SpeechRecognizer` (main thread, partials kept as fallback, 9.5 s window, per-pass destroy) so it never fights the latched main engine; `userPausedMic` is never set, and the owner's listening state is restored after the call. Ending the session (auto or Stop pressed) hangs up the call via NonCancellable cleanup. Every step is recorded to the action log (`answer_whatsapp_call`, `whatsapp_call_audio`, `whatsapp_call_turn`, `whatsapp_call_session`).
- [x] **Tools:** `answer_whatsapp_call` (optional `note` context for Jarvis) and `end_whatsapp_call` registered in `ToolRegistry` + `toolActivityLabel`, so the feature can be triggered by voice any time, not only automatically.
- [x] **Settings:** `SafetySettings.callAssistantEnabled` (default **off**) + `callAssistantMaxTurns` persisted; toggle row with the loudspeaker explanation and a "Max caller exchanges" field in Settings → Security → Safety card, saved via the enhanced `saveSafety`.
- [x] AppAutomation's node primitives (`findNode`, `findSpot`, `findByViewId`, `boundsOf`, `labelOf`, `normalized`, `tapSpot`, `tapNodeAction`) became `internal` so the call agent, message sender and camera share one automation engine.
- [x] Verification: `gradle assembleDebug testDebugUnitTest` green — **39 tests / 0 failures / 0 errors** (34 previous + 5 new `WhatsAppCallAgentTest` covering window detection, farewell phrases, sanitization cap, and the prompt/turn builders); debug APK 47.7 MB.
- [ ] Phone verification: real incoming WhatsApp call answered on-screen, Jarvis heard by the caller through the loudspeaker, engaged replies to real caller speech, farewell → hang-up, Stop banner + `end_whatsapp_call` abort properly.


## 2026-11-02 — WhatsApp voice messages, verified recorder automation, logo

- [x] **Research first:** confirmed a WhatsApp voice note is a PTT Opus message whose
  `ptt` flag is set by the client protocol, not by Android's `ACTION_SEND`; a personal
  WhatsApp number cannot use the Business Cloud API for this, and Android has no
  virtual-microphone API for unrooted apps. The only honest no-root path is WhatsApp's
  own recorder plus acoustic playback.
- [x] **`VoiceNoteAudio.kt`:** a RIFF/WAVE writer for 16-bit mono PCM, a
  `VoiceNoteStudio` that collects Fish Audio/ElevenLabs streamed PCM (or falls back to
  `TextToSpeech.synthesizeToFile`), a `VoiceNotePlayer` with transient audio focus and
  an external-route guard, and real duration tracking.
- [x] **`WhatsAppVoiceNoteAgent.kt`:** layered mic discovery (known view ids → label
  hints → rightmost square control, explicitly excluding camera/attach/emoji), a
  press-and-slide-up lock gesture with tap fallback, recording-bar verification, a
  bounded playback wait, verified Send, a best-effort cancel, and a share fallback that
  is only armed before recording starts.
- [x] **Chat identity checks:** deep links for numbers, WhatsApp search for names, top-bar
  title verification for name searches, and an abort if the composer/title never matches.
  Voice bubbles are counted with their duration so the mic's own “Voice message” label
  is never mistaken for a sent note.
- [x] **Auto-reply policy:** `WhatsAppReplyPolicy` gates on opt-in, allowlist, screen
  control, interactive/unlocked screen, safe body (no links, OTPs, secrets, payment,
  group hints or injection phrases), per-contact cooldown and a daily cap. The
  notification listener exposes a deduplicated WhatsApp text event, and JarvisCore
  drafts through the configured provider with SecurityShield sanitisation and no tools.
- [x] **Settings and tool:** `send_whatsapp_voice_note` registered and labelled; new
  safety fields (enabled, fallback, max chars, auto-reply, allowlist, cooldown, daily
  cap) persisted with a dedicated Settings card. `saveSafety` now copies the existing
  safety document so unrelated fields cannot be wiped.
- [x] **Launcher icon:** extracted the arc-reactor mark from the supplied showcase
  poster into the app logo, adaptive foreground, round/legacy mipmaps and manifest.
- [x] **Verification:** `gradle assembleDebug testDebugUnitTest` green — **48 tests / 0
  failures / 0 errors** (39 previous + 9 new `WhatsAppVoiceNoteTest` covering
  sanitisation, recording/bubble/invalid-number detection, allowlist and rate policy,
  prompt safety and WAV headers); debug APK 46 MB.
- [ ] **Phone verification:** real WhatsApp voice note by name and number, auto-reply on
  an unlocked phone, cooldown/daily cap, refusal of links/OTP/groups, share fallback,
  and the new launcher/round icon on the home screen.

## 2026-11-02 — Phase 0 Stabilization & Phase 1 Core Intelligence (Phase 1A & Phase 1B)

- [x] **Build Fix (`gradle.properties`, `.github/workflows/build.yml`):** Removed broken `android.aapt2FromMavenOverride=/root/aapt2bin/aapt2` override that caused `:app:processDebugResources FAILED`, and updated CI to run `gradle testDebugUnitTest assembleDebug --stacktrace`.
- [x] **Phase 0 Stabilization:**
  - Resolved duplicate `create_calendar_event` tool registration across all 55 tools (`create_calendar_event` + `schedule_calendar_event`).
  - Repaired seeded skills ("Morning Routine" & "Device Diagnostics") to invoke real registered tools (`get_device_status`, `get_current_time`, `run_diagnostics`, `read_notifications`, `search_youtube`), added `SkillRepository.validateSteps`, persisted `SkillEntity.description` in Room v2 (`MIGRATION_1_2`), and fixed `SkillTools` to propagate `ToolResult(false, ...)` on failure.
  - Made `SkillsScreen`, `ProposalsScreen`, and `ActionLogScreen` reachable from `ToolsScreen` and `QuickPanel`.
  - Sanitized `WebSearchTool` snippets via `SecurityShield`, added risky-target confirmation to `TapCoordinatesTool`, dynamically verified hardware-backed keystore status in `SecurityShield.audit`, added `canScheduleExactAlarms()` fallback in `scheduleAlarmSafely`, and added daily morning briefing rescheduling in `JarvisAlarmReceiver`.
- [x] **Phase 1A — Default Assistant, Share Sheet, App Shortcuts, Interactive Widget & Quick Settings:**
  - Implemented main-thread `SpeechRecognizer` delegation inside `JarvisRecognitionService`.
  - Added static App Shortcuts (`res/xml/shortcuts.xml`) for *Summon Voice*, *System Diagnostics*, and *AI Task Planner*.
  - Added Android Share Sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_PROCESS_TEXT`) and `ACTION_VOICE_COMMAND` support in `AndroidManifest.xml` and `MainActivity.kt`, plus `SharedContentBanner` on `HomeScreen`.
  - Upgraded `JarvisWidgetProvider` (`widget_jarvis_reactor.xml`) and `JarvisTileService` with live battery %, assistant status, and next reminder telemetry.
- [x] **Phase 1B — Advanced AI Task Planner, Context Budgeter, Advanced Memory & Action History Retry/Undo:**
  - Implemented `TaskPlanner` & `ToolContextBudgeter` (`brain/TaskPlanner.kt`) with multi-step plan generation, per-step tool execution & verification, automatic retry, cancellation, and interactive `TaskExecutionTimelineCard` on `HomeScreen` + Task Planner bar on `ToolsScreen`.
  - Added `important_date`, `recurring_task`, `tags`, temporary memory expiry (`expiresAt`), and past conversation history search (`searchConversationMessages`) in Room v2 and `MemoryScreen`.
  - Added search, filter chips (`All`, `Succeeded`, `Failed`, `Undoable`), **Retry**, and **Undo Setting** in `ActionLogScreen`.
  - Added unit tests in `Phase1IntelligenceTest.kt` (53 total unit tests passing).

## 2026-11-02 — WhatsApp Voice-Reply Polish, Official Triangular Arc-Reactor Logo & Post-Boot Google / Guest Login

- [x] **WhatsApp Voice Message Reply Polish (`WhatsAppVoiceNoteAgent.kt`, `JarvisNotificationListenerService.kt`, `JarvisCore.kt`, `SystemMissionTools.kt`, `SettingsExtras.kt`):**
  - Added incoming WhatsApp message history tracking (`JarvisNotificationListenerService.latestWhatsAppMessage(senderQuery)`, `recentWhatsAppMessages()`) with fallback to active status-bar notifications so Jarvis always knows who last messaged on WhatsApp and what they said.
  - Upgraded `send_whatsapp_voice_note` (`SendWhatsAppVoiceNoteTool`) to support `recipient = "last" / "latest" / "reply"` (automatic last-sender resolution), `message = "auto" / "reply"` (`JarvisCore.draftWhatsAppVoiceReplyNow` contextual reply drafting), and Android Contacts phone-number lookup (`resolveTarget` → `wa.me/<digits>` direct chat opening with automatic fallback to in-app name search).
  - Added `holdMicAndPlay` continuous press-and-hold gesture fallback in `WhatsAppVoiceNoteSender`: when a WhatsApp build does not latch on the upward slide-to-lock gesture, Jarvis holds the mic button continuously (`note.durationMs + 950L`) while playing the rendered voice note over the loudspeaker and releasing to send.
  - Added a live incoming WhatsApp message preview and **"Reply to latest WhatsApp with voice >"** action button in Settings → Security (`WhatsAppVoiceNoteCard`).
- [x] **Official Triangular Arc-Reactor App Logo (`ic_app_logo.png` & `mipmap-*`):**
  - Replaced `drawable-nodpi/ic_app_logo.png`, `drawable-nodpi/ic_app_logo_foreground.png` (safe-zone padded for Android adaptive icon masks), and all 5 `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` & `ic_launcher_round.png` densities with bicubic-scaled exports of the uploaded official logo (`/public/assets/aistudio/ic_app_logo_e1371f83.png`).
  - Updated `ReactorGlyph` (`Hud.kt`) and `BootScreen.kt` to render `R.drawable.ic_app_logo`.
- [x] **Post-Loading Google Login / Guest Gateway (`AuthScreen.kt`, `SettingsModels.kt`, `SettingsRepository.kt`, `JarvisApp.kt`, `SettingsScreen.kt`):**
  - Added `AuthScreen` immediately after the boot sequence when not yet authenticated, featuring the new Arc-Reactor logo and the exact `G Login >` / `Or` / `Guest >` layout.
  - Wired `G Login >` to Android's native `AccountManager.newChooseAccountIntent` for `"com.google"` device accounts with an in-app Google Sign-In fallback sheet, persisting `AuthSession` across launches and syncing the owner's display name.
  - Added an Account Session card at the top of Settings to view the active Google/Guest session or switch/sign out at any time.
- [x] **Verification:** `gradle testDebugUnitTest assembleDebug` green (55 unit tests passing, 0 failures/errors).

## 2026-11-02 — WhatsApp Recent-Chat & Modern Search Automation Fix, In-Place APK Updates & Dark-Navy Framed Arc Reactor Icons

- [x] **WhatsApp Contact Selection & Search Fix (`AppAutomation.kt`, `WhatsAppVoiceNoteAgent.kt`, `SystemMissionTools.kt`):**
  - Implemented `AppAutomation.openWhatsAppChatByName` with a 4-stage contact opening strategy shared by `send_whatsapp_voice_note` and `send_whatsapp_message`:
    1. **Keep active conversation** if already open to the target contact.
    2. **Direct Recent Chats List Scan**: scans visible conversation rows on the WhatsApp Chats screen (and scrolls down once if needed) to tap the contact (`"Azhar"`) directly without requiring the search bar when the contact is already in recent chats.
    3. **Modern Search Bar & Classic Search Icon (`findWhatsAppSearchSpot`, `isSearchBarNode`)**: matches modern full-width `"Ask Meta AI or Search"` / `"Search..."` pill bars (`my_search_bar`, `search_bar_inner_layout`, `search_holder`, `search_src_text`) as well as classic `menuitem_search` icons regardless of width.
    4. **"New chat" FAB Fallback (`findWhatsAppNewChatSpot`)**: taps WhatsApp's floating New Chat button (`fab` / `menuitem_new_conversation`) to search contacts when the top search bar is collapsed.
  - Fixed RecyclerView child `TextView` matching (`findWhatsAppContactRowSpot`, `tapSpotOrAncestor`): matches contact names rendered on non-clickable child `TextView` nodes (`conversations_row_contact_name`, `contactpicker_row_name`) and resolves the tap target via the clickable ancestor row container or physical coordinate gesture.
- [x] **Seamless In-Place APK Updates Without Uninstalling (`app/build.gradle.kts`, `.github/workflows/build.yml`, `AndroidManifest.xml`):**
  - Configured `app/build.gradle.kts` and `.github/workflows/build.yml` to automatically decode the committed `debug.keystore.base64` into `debug.keystore` whenever repository signing secrets are not set, ensuring every GitHub Actions artifact APK is signed with the exact same certificate.
  - Set `versionCode = 100 + GITHUB_RUN_NUMBER` in `app/build.gradle.kts` and `android:allowBackup="true"` in `AndroidManifest.xml` so every new GitHub Actions build installs cleanly over existing installations without deleting the app or losing data.
- [x] **Dark-Navy Framed Arc Reactor Launcher & System Icons (`mipmap-*`, `drawable-nodpi/*`, `drawable/ic_jarvis.xml`, `values/ic_launcher_background.xml`):**
  - Framed the uploaded circular triangular Arc Reactor emblem inside a dark navy (`#061116`) icon frame across all 5 `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}` launcher and round icon densities, adaptive icon foreground (`ic_app_logo_foreground.png`), in-app emblem (`ic_app_logo.png`), and vector icon (`ic_jarvis.xml`).
- [x] **Offline Local AI Model Downloader & On-Device Execution Engine (`LocalAiModels.kt`, `LlmModels.kt`, `ProviderRegistry.kt`, `JarvisCore.kt`, `SettingsScreen.kt`, `HomeScreen.kt`):**
  - Added full offline support (`ProviderKind.LOCAL_ON_DEVICE`) requiring **zero API keys**, with resumable HTTP `Range` downloads from Hugging Face, binary `GGUF` header verification (`0x46554747`), live progress bar (`MB / MB`), download speed (`MB/s`), Pause/Resume, Delete, and one-tap **Use Offline** activation across all 6 requested lightweight models:
    1. **LFM2.5 350M** (`250–350 MB` — *Extremely lightweight chat*)
    2. **Qwen2.5 0.5B Instruct** (`400 MB` — *Basic assistant*)
    3. **Gemma 3 1B** (`700 MB` — *General conversation*)
    4. **LFM2.5 1.2B Instruct** (`731 MB` — *General-purpose local AI*)
    5. **SmolLM2 1.7B** (`990 MB` — *Lightweight chat and writing*)
    6. **Qwen3 1.7B** (`1.28 GB` — *Stronger*)
  - Implemented `OfflineTaskRouter` + `LocalLlmProvider` so downloaded local models execute all phone tasks offline (WhatsApp text & voice notes, YouTube search/playback, opening apps, calls, SMS, alarms, timers, reminders, flashlight, volume, ringer, brightness, DND, media control, camera, screenshots, notifications, device status, diagnostics, storage analysis, navigation, memory, and screen control) and automatically act as a fallback when offline or when no API key is configured.
- [x] **WhatsApp Voice-Note Continuous Mic-Hold, Audio Focus Preservation & Send Button Fix (`WhatsAppVoiceNoteAgent.kt`, `VoiceNoteAudio.kt`, `JarvisAccessibilityService.kt`, `AppAutomation.kt`):**
  - Made continuous push-to-talk mic hold (`holdMicAndPlay` via `JarvisAccessibilityService.dispatchHoldGesture`) the primary recording strategy: presses and holds the WhatsApp microphone button starting `600 ms` before audio playback, keeps holding continuously with steady `ACTION_MOVE` touch events for the entire duration of the voice recording, and holds for `1,300 ms` after playback finishes before lifting.
  - Prevented `VoiceNotePlayer.play` from requesting `AUDIOFOCUS_GAIN_TRANSIENT` during WhatsApp voice recording (`requestFocus = false`), eliminating the Android 14 `AUDIOFOCUS_LOSS_TRANSIENT` interruption that previously caused WhatsApp to pause/stop recording mid-sentence.
  - Prioritized direct `https://wa.me/<digits>` chat opening whenever a contact's phone number is resolved from Android Contacts (`isNumber == true`), and reordered `AppAutomation.openWhatsAppChatByName` to try the top Search bar before scrolling so Jarvis never scrolls the main screen unnecessarily.
  - Upgraded `pressVoiceNoteSendButton` and `findRecordingSendSpot` to match all WhatsApp voice-note Send button view IDs (`send`, `voice_note_draft_send`, `voice_recorder_send_btn`, `send_voice_note_btn`, `voice_note_send_btn`, `send_btn`, `send_container`), click ancestor containers (`tapSpotOrAncestor`), and fall back to a physical coordinate tap directly at the bottom-right `(mic.centerX(), mic.centerY())` position where WhatsApp's green Send button sits.
- [x] **Verification:** `gradle testDebugUnitTest assembleDebug` green (62 unit tests passing, 0 failures/errors).

## 2026-11-02 — Stored Memory & Notes Recall Fix, Offline Web Research & WhatsApp Post-Send Mic Click Prevention

- [x] **Stored Memory & Stored Notes Recall Fix (`MemoryRepository.kt`, `MemoryEntities.kt`, `MemoryTools.kt`, `LocalAiModels.kt`):**
  - Added `MemoryType.NOTE = "note"` and conversational stop-word stripping (`meaningfulTokens`) in `MemoryRepository.search(query, limit)` so broad recall questions (*"What do you remember about me?"*, *"What are my stored notes?"*) return active stored memories and notes directly instead of failing FTS/LIKE matching on stop-words.
  - Upgraded `MemoryRepository.promptContext(query)` to always inject both query-relevant memories and up to 15 active **Stored memories and notes** into the system prompt on every turn so both cloud and offline models always know all saved facts and notes.
  - Updated `RememberTool` (`remember_about_owner`) to accept `"text"`, `"fact"`, `"note"`, or `"content"`, and updated `SearchMemoryTool` (`search_memory`) to fall back to returning all active stored memories/notes when a specific keyword match is empty.
- [x] **Offline / Local AI Web Research (`LocalAiModels.kt`, `MediaTools.kt`):**
  - Added Rule #23 (`web_search`) + `isWebResearchIntent` + `extractWebResearchQuery` in `OfflineTaskRouter.routeUserCommand` so downloaded local models route web research, search, news, weather, and general knowledge queries directly to `web_search`.
  - Added a zero-API-key **Wikipedia OpenSearch / Query API** fallback in `MediaTools.kt` (`searchDuckDuckGo`) alongside DuckDuckGo HTML, Lite, and Instant Answer endpoints, and formatted multi-source research summaries in `LocalLlmProvider`.
- [x] **WhatsApp Voice-Note Post-Send Mic Click Fix (`WhatsAppVoiceNoteAgent.kt`):**
  - Removed `"send_container"` from `WhatsAppVoiceNotes.SEND_VIEW_IDS` (which wraps the Microphone button when the text input is empty) and added `WhatsAppVoiceNotes.isMicNode` guards so microphone controls are never mistaken for a Send button.
  - Updated `WhatsAppVoiceNoteSender.holdMicAndPlay` and `pressVoiceNoteSendButton`: releasing the continuous hold gesture (`dispatchHoldGesture`) auto-sends the voice note in WhatsApp and restores the normal chat composer (`!recordingActive()`). Jarvis now verifies auto-send immediately on release and never taps the mic button a second time after the voice note has already been sent.

## 2026-11-02 — Master Implementation: Human-Like Touch & Automation Engine & 100% Real Google Login Chat Persistence

- [x] **Native Human-Like Touch & Automation Engine (`HumanTouchEngine.kt`, `JarvisAccessibilityService.kt`, `ScreenController.kt`, `Bridges.kt`, `JarvisCore.kt`):**
  - Supported all 20 human touch interactions: single tap, double tap (`dispatchDoubleTapGesture`), long press (`dispatchLongPressGesture`), short press, swipe up/down/left/right (`dispatchSwipeGesture`), vertical/horizontal scroll, drag and drop (`dispatchDragGesture`), press and hold (`dispatchHoldGesture`), sequential gestures, multi-touch pinch/zoom (`dispatchPinchZoomGesture`), back navigation, keyboard IME interaction (`performImeAction`, `isKeyboardVisible`), text-field selection, text entry, selecting UI elements, dismissing supported dialogs.
  - Built `JarvisScreenObserver` inspecting up to 450 UI nodes, active dialogs, input method window (`TYPE_INPUT_METHOD`) keyboard visibility detection, orientation/rotation, display metrics, and parent-child semantic lineage.
  - Implemented `JarvisUiElementMatcher` with 10-tier scoring: exact resource ID -> exact content description -> exact text -> element class/role -> parent-child -> package/app registry context -> enabled/clickable status -> bounds/keyboard occlusion check -> ordinal/spatial hint -> visual coordinate fallback.
  - Implemented `JarvisActionVerifier` with 4-level verification: Level 1 (Touch/Accessibility dispatch), Level 2 (UI hierarchy & fingerprint delta), Level 3 (Application post-condition), Level 4 (Android System API check for Bluetooth, Wi-Fi, Volume, Brightness, DND, Media) with `VERIFIED_SUCCESS`, `UNVERIFIED_UNKNOWN`, or `FAILED` — zero fake success.
  - Implemented `JarvisRecoveryManager` with 8-step diagnosis, keyboard hiding, dialog dismissal, scroll-to-reveal, and anti-duplication prevention blocking duplicate consequential actions (messages, calls, payments).
  - Implemented `JarvisAppRegistry` with deep links, package names, search IDs, input IDs, and send button IDs for 12 major apps (WhatsApp, YouTube, Instagram, Chrome, Google Maps, Spotify, Gmail, Settings, Phone, Contacts, Messages, Camera).
  - Implemented `InteractionContextMemory` resolving anaphoric references ("open that", "tap that", "click the second one", "go back", "do the same thing again").
- [x] **100% Real Google Login Chat Persistence & Cloud-Vault Sync (`BackupRestoreRepository.kt`, `MemoryDao.kt`, `MemoryRepository.kt`, `JarvisCore.kt`, `JarvisViewModel.kt`):**
  - Created `saveGoogleAccountChatBackup` and `restoreGoogleAccountChatBackup` in `BackupRestoreRepository.kt` saving and restoring all user and assistant conversations and messages to an account-scoped vault (`google_chat_sync/chats_<sanitized_email>.json`).
  - Auto-saves chat history to the Google account vault on every message turn in `JarvisCore.persistAssistant` when logged into Google.
  - Auto-restores saved chats on Google sign-in (`JarvisViewModel.signInWithGoogle`) and updates the chat transcript seamlessly.
  - Upgraded `MemoryExport` (format v3) and `MasterJarvisBackup` with `conversations` and `messages`.
- [x] **Verification:** `gradle testDebugUnitTest assembleDebug` green (68 unit tests passing, 0 failures/errors), `compile_applet` passed.

## 2026-11-02 — Phase 2: Advanced Visual Intelligence & Autonomous Phone Operation

- [x] **Hybrid Visual + Accessibility Intelligence Engine (`VisualIntelligence.kt`):**
  - Synthesizes structural `AccessibilityNodeInfo` hierarchy with downsampled visual screen bitmap evidence (`VisualEvidence`).
  - Detects `FLAG_SECURE` blacked-out screens and strictly halts automation to protect user security.
  - Detects modal dialog scrims and loading states.
  - Implemented `VisualAccessibilityReconciler`: reconciles structural and visual evidence; enriches missing accessibility labels from visual text regions, and prevents blind tapping when evidence diverges.
- [x] **Autonomous Phone Operator & Intelligent Task Planner (`AutonomousPhoneOperator.kt`):**
  - Generates verified, multi-step execution plans (`AutonomousExecutionPlan`) with typed preconditions (`StepPrecondition`), postconditions (`StepPostcondition`), timeouts, retry policies, and consequential action flags.
  - Dedicated multi-step plan synthesis for YouTube (10-step sequence: launch -> foreground check -> search -> activate -> type -> submit -> wait -> identify -> play -> verify playback), WhatsApp, and Settings.
  - **Dynamic Screen Reasoner & Stale Coordinate Rejection**: Tracks screen state versions (`screenVersion: Long`) and rejects stale coordinates tagged under older screen versions (`ValidatedCoordinate.isStale`).
  - **Human Takeover System**: Intercepts user manual touches (`TYPE_TOUCH_INTERACTION_START` in `JarvisAccessibilityService`), cancels synthetic gestures, pauses active plans, and provides `pause()`, `resume()`, and `stop()` controls.
  - **Task State Persistence & Resumption (`TaskResumptionEngine`)**: Persists in-flight tasks (`active_task_state.json`) and reconciles screen state before resuming without repeating completed or consequential operations.
- [x] **Smart App Adapters (`SmartAppAdapters.kt`):**
  - Built modular adapters for all 11 core applications: WhatsApp, YouTube, Instagram, Chrome, Gmail, Google Maps, Spotify, Settings, Phone, Contacts, and Messages.
  - Outlines supported workflows, launch intents, known view selectors, dynamic selector fallbacks, screen-state recognition, and known restrictions.
- [x] **Verification:** `gradle testDebugUnitTest` green across all 79 unit tests (including all 11 `VisualIntelligenceAndAutonomousPlanTest` tests), `assembleDebug` green (46 MB APK), and `compile_applet` passed.

## 2026-11-03 — Morning Routine Persona Synthesis & Unwanted YouTube Playback Fix

- [x] **Skill Persona Briefing Engine (`SkillPersonaBriefingEngine.kt`, `JarvisCore.kt`):**
  - Replaced the recitation of raw developer debug logs (`"Skill 'Morning Routine' completed all 3 steps successfully. Step 1 (get_device_status): Battery 76%... Inform the owner now."`) with a calm, dignified, natural J.A.R.V.I.S. spoken briefing.
  - Formats battery %, network connection, weather, unread notification highlights (e.g. Instagram, WhatsApp), and calendar schedule into 2–3 elegant spoken sentences addressing the owner as "sir" (or their configured title).
  - Strips all internal directives (`"Inform the owner now."`), tool names, step numbers, and hardware debug logs (`25°C`, memory/disk bytes) from spoken speech and transcripts.
- [x] **Morning Routine YouTube Hijack Elimination (`SkillRepository.kt`):**
  - Removed intrusive `search_youtube` from the seeded `"Morning Routine"` skill (`"start my day"` trigger) and replaced it with `read_calendar_events` and `get_current_time`.
  - Added an in-place migration in `seedDefaultsIfEmpty()` that automatically repairs existing database entries, preventing `"start my day"` from opening YouTube or blasting music unexpectedly.
  - Seeded a separate, dedicated `"Morning Music"` routine for users who explicitly ask to play morning lo-fi beats.
- [x] **Verification:** `gradle testDebugUnitTest` green (82 unit tests passing, including 3 new `SkillPersonaBriefingEngineTest` tests), `assembleDebug` green (46 MB APK), and `compile_applet` passed.

## 2026-11-03 — OpenClaw-Inspired Mobile UI Intelligence Upgrade (Phases 1–8)

- [x] **Phase 1 — Architectural Audit & Comparison:**
  - Inspected `JarvisAccessibilityService`, `HumanTouchEngine`, `JarvisScreenObserver`, `JarvisUiElementMatcher`, `JarvisActionVerifier`, `JarvisRecoveryManager`, `VisualIntelligence`, `AutonomousPhoneOperator`, `SmartAppAdapters`, `ScreenTools`, `ScreenVisionTools`, `SecurityShield`, and existing tests against OpenClaw's mobile UI automation architecture (`openclaw/openclaw` PRs `#112232`, `#112241`, `#112255`) and `8crsk/openclaw-android`.
  - Preserved all existing Jarvis capabilities without duplication.
- [x] **Phase 2 — Snapshot-Based Screen Observation (`HumanTouchEngine.kt`):**
  - Added `ObservedWindowInfo` (`windowId`, `windowType`, `title`, `packageName`, bounds, `isActive`, `isFocused`, `layer`).
  - Upgraded `ObservedUiElement` with deterministic `elementRef` (`ref_e0`, `ref_e1`, ...), `parentRef`, `childRefs`, `windowId`, `windowLayer`, `password` flag (redacting visible password strings as `[REDACTED_PASSWORD_FIELD]`), and `supportedActions` (`CLICK`, `LONG_CLICK`, `SET_TEXT`, `SCROLL_FORWARD`, `SCROLL_BACKWARD`, `FOCUS`, `SELECT`, `IME_ENTER`).
  - Upgraded `ObservedScreenState` with unique `snapshotId` (`snap_<version>_<timestamp>`), `screenVersion`, `windows`, `isSecureOrProtected`, `findByRef`, `hasPasswordField`, `structuralSignature`, and compact agent formatting (`formatSnapshotForAgent`).
  - Upgraded `JarvisUiElementMatcher` to resolve `ref_eN` references directly and enforce disabled penalties.
- [x] **Phase 3 — One-Action Execution Contract (`OpenClawMobileUiEngine.kt`, `ScreenTools.kt`, `ToolRegistry.kt`):**
  - Implemented `MobileUiActionRequest` sealed hierarchy covering all 13 typed operations (`ActivateElement`, `EnterText`, `Scroll`, `Tap`, `Swipe`, `LongPress`, `DoubleTap`, `Drag`, `PinchAndZoom`, `Back`, `Home`, `Recents`, `Wait`).
  - Implemented pre-execution validation in `MobileUiActionExecutor`: human takeover check, secure screen check (`SECURE_CONTENT`), foreground package check, snapshot freshness check (`TARGET_STALE`), semantic target resolution without silent arbitrary coordinate fallback (`TARGET_NOT_FOUND`), disabled check, bounds check, and higher-layer window/keyboard occlusion check.
  - Registered `capture_screen_snapshot` (`CaptureScreenSnapshotTool`) and `execute_mobile_ui_action` (`ExecuteMobileUiActionTool`) in `ToolRegistry.kt`.
- [x] **Phase 4 — Automatic Re-Observation & 8-State Verification (`OpenClawMobileUiEngine.kt`):**
  - Implemented `UiActionOutcomeState` (`DISPATCHED`, `ACCEPTED_BUT_UNVERIFIED`, `VERIFIED_SUCCESS`, `FAILED`, `UNKNOWN`, `TARGET_STALE`, `TARGET_NOT_FOUND`, `SECURE_CONTENT`).
  - Implemented `ScreenSnapshotComparator.compare(before, after, visualDiffRatio)` producing `ScreenSnapshotDiff`.
  - Enforced false-success prevention: a gesture or accessibility action callback returning `true` when the post-action snapshot is unchanged returns `ACCEPTED_BUT_UNVERIFIED`, never `VERIFIED_SUCCESS`.
- [x] **Phase 5 — Visual Intelligence Fallback (`VisualIntelligence.kt`, `ScreenVisionTools.kt`):**
  - Added `ScreenCoordinateMapper` (`mapScreenshotPointToDisplay` and `mapPercentToDisplay`), `HierarchyOcclusionDetector` (higher-layer window and IME occlusion detection), `VisualAccessibilityReconciler.computeBitmapDiff`, `matchVisualRegionToAccessibility`, and `VisionPrivacyGate` (blocking screenshot transmission to external VLMs unless user consent is granted, model supports vision, and zero password/secure fields are present).
- [x] **Phase 6 — Fail-Closed Safety (`OpenClawMobileUiEngine.kt`, `SecurityShield.kt`):**
  - Implemented `FailClosedActionPolicy.classifyConsequentialAction` across 7 categories (`SEND_MESSAGE`, `PUBLISH_OR_SHARE`, `PAYMENT_OR_PURCHASE`, `DELETE_DATA`, `UNINSTALL_APP`, `CHANGE_ACCOUNT_SECURITY`, `GRANT_PERMISSION`), requiring explicit human confirmation and blocking duplicate consequential actions once dispatched.
- [x] **Phase 7 — Galaxy A54 Performance Guards (`HumanTouchEngine.kt`):**
  - Enforced `MAX_OBSERVED_NODES = 450`, `MAX_DEPTH = 18`, `MAX_TEXT_LENGTH = 140`, `OBSERVE_TIME_BUDGET_MS = 260L`, 90ms snapshot caching (`SNAPSHOT_CACHE_TTL_MS = 90L`), and zero retained `AccessibilityNodeInfo` objects.
- [x] **Phase 8 — Automated Verification (`OpenClawMobileUiEngineTest.kt`):**
  - Added 14 new JVM unit tests covering all required scenarios (96 total unit tests passing).

## 2026-11-03 — Z.ai `glm-4.5-flash` Text-Only Model Fix & End-to-End YouTube Typing

- [x] **Z.ai / OpenAI-Compatible Text-Only Model Compatibility (`OpenAiCompatibleProvider.kt`, `AgentLoop.kt`):**
  - Fixed the `"Z.ai: The provider rejected the request: messages.content.type is invalid, allowed values: ['text']"` error when using `glm-4.5-flash` (or any other text-only model) on screen commands like `"type justin on YouTube"`.
  - Added `OpenAiCompatibleProvider.modelSupportsVision(baseUrl, model)` so non-vision models (`glm-4.5-flash`, `glm-4-flash`, `glm-4.5`, `glm-z1*`, DeepSeek text models, Llama 3.1/3.3 text models, Qwen text models, etc.) never receive `{"type": "image_url"}` content array blocks and always keep `messages[].content` as plain `"text"` strings.
  - Added automatic image-stripping retry in `OpenAiCompatibleProvider.stream()` (`shouldRetryWithoutImages`) and `AgentLoop.looksLikeImageRejection` whenever any provider returns HTTP 400/422 mentioning `messages.content`, `content.type`, `allowed values`, `['text']`, or `image_url`, preserving all tool declarations on retry.
- [x] **End-to-End YouTube & App Typing (`ScreenTools.kt`, `ScreenController.kt`, `AppTools.kt`, `MediaTools.kt`):**
  - Enhanced `type_on_screen` (`TypeOnScreenTool`) with optional `app` (e.g. `"YouTube"`), `target` (e.g. `"Search"`), and `submit` parameters so Jarvis automatically opens YouTube if not already in the foreground before typing.
  - Enhanced `ScreenController.typeVerified` with `activateSearchOrInputTrigger`: when no `EditText` is open yet on YouTube's Home screen, Jarvis automatically finds and taps the YouTube Search button (`menu_item_search` / `Search`), waits for the search input field to open, types the text, and optionally submits it.
  - Guarded `ScreenController.type` so Jarvis never accidentally types into its own foreground chat box while a target app is still launching, and updated `OpenAppTool` to wait for the launched app to reach the foreground.
  - Enhanced `search_youtube` with `play_immediately: Boolean` (default `true`; when `false`, opens YouTube search results for the query without auto-playing a video).
- [x] **Verification:** `gradle testDebugUnitTest assembleDebug` green (98 unit tests passing, including 2 new tests in `ProviderStreamingTest`), and `compile_applet` passed.

