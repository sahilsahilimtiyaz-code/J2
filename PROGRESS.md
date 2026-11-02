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

