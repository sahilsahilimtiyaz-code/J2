# Jarvis for Android

Jarvis is a private, native Kotlin personal assistant for Android 14+ (minSdk 31), built for one
owner's Samsung Galaxy A54. It is sideloaded, never published on Google Play.

**Current status: Phase 9 complete plus two 2026-11-01 passes: (A) a latched continuous microphone, glass neural-orb core, verified screen taps/scrolls, direct YouTube playback, live free-model discovery and the 8-screen holographic HUD; (B) true pixel vision for the model, one-shot verified WhatsApp sending, real web-search answers, a premium two-phase incoming-call screen with live captions, deeply buffered gapless TTS, autonomous thoughts on a 10-minute cadence, a cinematic boot video, a new logo/adaptive launcher icon and a professional neural orb.**

Local `assembleDebug` and `testDebugUnitTest` are green (JDK 17, Gradle 9.6.0, AGP 9.5.0, SDK 37.0; this project tree lives in `/sdcard/JAR2/J2-main`, where the aarch64 phone routes aapt2 through `/root/aapt2bin/aapt2`).

## What works now

- **Brain & Live Free Model Discovery:** OpenRouter, Google Gemini, Groq, xAI Grok, Together, OpenAI, Ollama, and Anthropic, with streaming, fallback order, tool calling, and automatic **Free Models** discovery (`Fetch Free Models` button + `Free models only` toggle).
- **Voice (Fixed & Hardened):** Fish Audio and ElevenLabs streaming 24 kHz 16-bit PCM into one gapless player (with `Flow invariant` crash fixed and automatic Fish model fallback `s2.1-pro-free` -> `s1` -> `speech-1.6`), Android system voice fallback, echo-safe wake-word barge-in and manual interruption, and BCP-47 speech recognition with automatic fallback from on-device to standard `SpeechRecognizer` so Samsung devices never fail with `"That language is not supported by the recogniser."`
- **Phone tools (Tier 1):** Open any app, YouTube search, web search, media, volume, ringer, torch, timers, alarms, calls, SMS, navigation, calendar, share, settings screens, battery and network status.
- **Notifications (Tier 2):** Read recent status-bar notifications, summarise them, and dismiss them by app, keyword, or all (`read_notifications`, `dismiss_notifications`), with automatic OTP/security-code redaction.
- **Screen control (Tier 3):** Both single-step tools (`read_screen`, `tap_screen`, `type_on_screen`, `scroll_screen`, `press_system_key`) and the multi-step **`automate_screen`** observe–decide–act–verify loop (up to 15 steps) with a persistent **"Jarvis is controlling the screen"** notification (`Stop` button) and an in-app **STOP** banner that halts control within 1 second.
- **Memory:** Local Room database with FTS4 search, 3D Holographic Memory Globe visualization, automatic background fact extraction, JSON export/import, and "forget everything".
- **Always-on:** Microphone foreground service with wake-word detection and one long-lived recognizer flow. Listening stays latched through replies and TTS until you stop it from the mic button, the notification, or the Quick Settings tile; the app no longer hands the mic between a keeper and the recognizer.

## Latched Microphone & Neural-Orb Core (2026-11-01)

- **One capture flow:** `SttEngine` no longer starts an `AudioRecord` "mic keeper" or releases the microphone between turns. It keeps one recognizer flow armed and re-arms in place after every final result or recoverable error, so there is no deliberate stop/start gap.
- **Latched state:** `JarvisCore` no longer clears `listening` when you send a command, when it starts generating, or when TTS begins. The only in-app way to clear the latch is an explicit stop: the mic button, the ongoing notification's Stop action, or the Quick Settings tile. All three call `stopListening()`.
- **Echo-safe barge-in:** finals received while Jarvis is generating, and for 1.2 s after speech ends, are discarded. During TTS the recognizer stays armed, but only a plain stop/cancel phrase or a wake-word-addressed command can interrupt; transcripts that match the reply being spoken are treated as echo. The first 700 ms of playback are ignored to avoid the onset, and the in-app Stop control always interrupts immediately.
- **Platform boundary:** Android may still end a `SpeechRecognizer` session on silence, audio-focus changes or on-device fallback. The app re-arms immediately and never intentionally cycles the mic, but the OS privacy indicator can blink at those platform boundaries; an app cannot pin one `SpeechRecognizer` session forever.
- **Neural orb:** the home and boot core (`ArcReactor`) is now a glass sphere with a radial gradient, a 3D projected synaptic lattice, two counter-rotating orbital rings, firing filaments, a white-hot center and a specular highlight. `ReactorGlyph` uses a mini orb with four synaptic nodes in the header and bottom navigation. No triangle remains in draw code, the launcher icon or the widget.
- **Verified here:** `assembleDebug testDebugUnitTest` is green (34 tests, 0 failures/errors/skips); the debug APK is 40 MB. Physical-device appearance and the OS privacy indicator still need the phone check below.

## Vision, WhatsApp, Gapless Voice & Call Experience (2026-11-01 evening pass)

- **Jarvis can literally see:** `JarvisAccessibilityService` declares `canTakeScreenshot` and captures real pixels (`takeScreenshot` → `HardwareBuffer` → downscaled JPEG). The capture is carried to the LLM as an image attachment (`AttachedImage`), appended as a final user turn by the OpenAI-compatible, Gemini and Anthropic adapters, with an automatic retry without the image if a provider rejects it.
- **New vision tools:** `see_screen` returns the screenshot plus a sanitized text-label merge, and `analyze_screen` is the structured no-image variant. `tap_screen_coordinates` takes **percent** coordinates (x/y 0–100) scaled by the real display size.
- **The automation planner sees too:** every step of `automate_screen` attaches a fresh screenshot and reasons in percent coordinates off the pixels, so videos, maps and pop-ups that never appear as nodes can be tapped.
- **One-shot WhatsApp:** `send_whatsapp_message` resolves the contact, opens the exact chat through `wa.me` (name-only fallback opens WhatsApp's own search), types the message, presses Send with a real gesture plus a verified node-click fallback, and confirms the send by watching the input clear. It never asks the model to drive UI itself; the Confirmation Gate still guards the actual send.
- **Greetings are answered locally:** a deterministic gate in `JarvisCore.send` intercepts bare greetings (hi / hello / good morning..., up to about two words plus a wake word) with a fresh Persona greeting and never hands them to the model — a bare "hi" can no longer open Spotify.
- **Web search answers in the conversation:** `web_search` fetches live DuckDuckGo results (instant-answer API, then the `lite`/`html` result page) and returns real titles, snippets and URLs; the persona forbids cheapening out by casually opening a browser — the browser is only for explicit "open ..." requests.
- **Premium incoming-call screen:** ringing phase wraps the app logo in staggered sonar rings over a reason card with wide tactile Decline/Answer pills; the in-call phase shows the audio-reactive reactor, a live duration timer and a live caption bubble fed by `CoreState.spokenCaption` so the owner can read everything Jarvis says while he says it.
- **Gapless voice:** `AudioPlayer` keeps a 0.25 s pre-roll (6 000 frames) before `track.play()`, a deep ~2.5 s jitter buffer, normal-priority playback instead of low-latency, and a fast non-blocking Stop. Fish Audio's stream slice grew 4 KB → 16 KB (≈256 ms of audio at 16 kHz) so each write feeds the buffer comfortably; the "voice splits mid-word" stutter is addressed at both ends.
- **Autonomous thoughts arrive on time:** notification briefings ~8 min, spontaneous thoughts ~10 min, wellbeing-care milestones ~15 min, compliments after ~8 active minutes, and screen-health reminders share a 10-minute minimum cooldown instead of 30.
- **Cinematic boot + new identity:** the boot screen plays the owner's muted, looping intro video full-screen (Arc-Reactor fallback if the video ever fails), the app runs the new logo as `ic_app_logo`, and the launcher uses an adaptive icon (`#061B2B` navy backdrop) so the app tiles match the mark.
- **Real photo capture:** `open_camera` honours the requested lens (joint AOSP/CameraX/Samsung extras so "back camera" opens the rear lens), and `take_photo` runs an `ACTION_IMAGE_CAPTURE` session, presses the shutter itself and verifies the file in `Pictures/Jarvis/`.

## The 8-Screen Holographic Interface

1. **Boot Animation:** The owner's cinematic intro video plays full-screen, muted and looping, with the `J.A.R.V.I.S.` logotype, glowing progress bar and 5-step startup checklist (`LOADING AI CORE`, `CONNECTING MODULES`, `CALIBRATING INTERFACE`, `INITIALIZING VOICE`, `SYSTEMS ONLINE`). If the asset ever fails, the glass Arc Reactor boot screen takes over.
2. **Home Screen:** Interactive `AI MODEL` selector pill bar, live `SYSTEM STABLE / CPU / GPU` and `MEMORY / STORAGE` HUD telemetry, central glass neural-orb Arc Reactor with 3D perspective holographic side panels, time-of-day greeting card, and 4 quick action tiles (`Web Search`, `Generate`, `Tools`, `Memory`).
3. **Voice Assistant Mode:** `"Listening..."` header, live voice spectrum waveform, 3D rotating particle/wireframe `HolographicGlobe`, spoken prompt, quick suggestion pills (`Web Search`, `YouTube`, `Summarize`, `Analyze`), and glowing circular microphone orb.
4. **AI Response / Chat View:** Glassmorphic `J.A.R.V.I.S.` card, Sources & Executed Tools badges, embedded 3D `ParticleWaveCanvas` visualization, `Regenerate` / `Copy` / `+ More` actions, and `Ask anything...` composer bar.
5. **Tools Section:** `All`, `Productivity`, `Media`, `Analysis` category tabs, `Search tools...` bar, and glassmorphic tool cards with `● Online` / `● Local` / `● Connected` status badges.
6. **Memory Visualization:** Interactive 3D `HolographicGlobe` with floating cluster cards (`Projects`, `Ideas`, `Research`, `Personal`, `Media`), `Total Memories` summary card with mini bar chart, and full note editor.
7. **Settings Hub:** 7 glassmorphic rows (`AI Model`, `Voice Settings`, `Appearance`, `Connections`, `System`, `Security`, `About`) with live model fetching, encrypted API key storage, and the permission wizard.
8. **Quick Panel:** Swipe from the right edge for `QUICK ACCESS` (`Camera Vision`, `Screenshot`, `YouTube`, `Summarize`, `Translate`), `SYSTEM` (`Network`, `Battery`, `Temperature`, `Performance`), and `NOW PLAYING` (`JARVIS Voice Active` waveform).

## Reference-Board Visual Alignment (2026-11-01)

- **Palette:** switched from teal-cyan to the board's electric blue (`#2E9BFF` core, `#8BD8FF` highlights), deeper navy glass, and copper rim light on the backdrop.
- **Type:** bundled **Rajdhani** (SIL OFL) for the J.A.R.V.I.S. logotype, large screen headings, HUD labels and numerals. Body copy stays on the platform sans for readability.
- **Proportions:** boot reactor now fills 90% of the content width, the home reactor is 252 dp inside a 286 dp stage, the voice globe is 272 dp, and the microphone orb is 108 dp inside 148 dp pulse rings.
- **Screen structure:** Tools, Memory and Settings now show the reference's large heading below the JARVIS header instead of a small cyan subtitle. The voice screen no longer shows the bottom bar, matching the board.
- **Details:** colored tool icon wells and the board's labels (`Video Analysis`, `File Manager`, `Camera Vision`, `Terminal`), circular response avatar, compact user bubble, thumbnail source cards, and a streamlined three-card quick panel.
- **Known, intentional differences:** chat keeps the bottom navigation for usability (the board's chat mock has no nav), Android's own status/navigation bars are outside the app's drawing surface, and source thumbnails are generated locally rather than using the board's IBM/Veritasium/Nature images.

## Test This on Your Phone (2026-11-01 Evening Batch)

1. **Greeting gate:** Launch the app with a model configured and say only "hi". Jarvis must answer with a fresh spoken greeting and open no app, start no search and run no tool. Then say "Jarvis, good morning" — same result. Then say "hi, what's the weather" — that must go to the model normally.
2. ** Seeing the screen:** Open any visual app (Maps, YouTube, Gallery) and say "Jarvis, look at my screen" or "what's on my screen right now?". Jarvis must capture real pixels and describe actual visible content (video titles, buttons, imagery), not just the text list. While he does, say "tap the <thing you can see>" and watch the pixel-guided percent tap land.
3. **WhatsApp end-to-end:** Say "Jarvis, send hi to <a contact> on WhatsApp". Jarvis must open the exact chat, type the greeting, press the green Send arrow himself and confirm "Message sent to ..." only after the input clears. Watch WhatsApp: the message must actually leave. Then try a phone-number send and a name-only send.
4. **Web search:** Say "Jarvis, search who won today's football match". He must answer from fetched search results without opening Chrome or Google. Then explicitly say "open YouTube" — only then is the browser/app route allowed.
5. **Gapless voice:** Ask a long five-sentence question. Listen for word splits or double-speaking at sentence seams; long paragraphs should run gapless now. Tap Stop mid-reply — it should cut off instantly.
6. **Incoming call:** In Settings, schedule a reminder ("Jarvis, remind me in 2 minutes to stretch"). Verify the new call UI: expanding sonar rings around the logo, a reason card with the reminder text, wide pills. Answer — Jarvis speaks the reminder and the caption bubble shows exactly what he is saying; the timer runs; End Call silences him instantly.
7. **Thought cadence:** Keep the screen on and Jarvis idle. A thought or information briefing should arrive near the 8–10 minute mark, not 30 minutes late.
8. **Camera lenses & photo:** Say "Jarvis, open my back camera" — the rear lens must be the live one. Then say "Jarvis, take a photo" — the shutter gets pressed automatically and Pictures/Jarvis/<name>.jpg lands in the gallery.
9. **Boot video & logo:** Reboot/relaunch the app: the intro video plays muted (no sound) and looping; if it ever breaks, Jarvis still boots through the reactor fallback. Check the launcher icon — the new logo on the deep-navy adaptive background.

## Test This on Your Phone (2026-11-01 Reliability Pass)

1. **Microphone latch:** Open the app, turn on listening, and stay quiet for 30 seconds. Say "Jarvis, what's my battery at?" and let the full reply finish. The mic must stay on through the reply and switch off only when you tap the mic button, the notification Stop, or the Quick Settings tile. Repeat once with the screen locked and confirm the command is captured from the first word.
2. **Play a song:** Say "Jarvis, play Starboy" (or any song). The YouTube player must open on the resolved video and audio must start. If it opens paused, Jarvis taps Play and confirms audio.
3. **Open YouTube and tap a video:** Say "Jarvis, open YouTube", then "scroll down" twice, then "tap the first video". The feed must scroll and the tapped video must open; the tool result must say verified only when the player actually changed.
4. **Screen control in other apps:** In Chrome or Settings, say "scroll down" and "tap <visible label>". If Android does not visibly respond, Jarvis must say the action was unverified instead of claiming success.
5. **Animations:** Open the voice screen, watch the layered spectrum react to your voice, confirm the holographic side-panel scanlines and bottom-nav indicator animate, and confirm the boot progress bar fills smoothly.
6. **Neural orb:** Check Home, the voice screen, the small header mark, the launcher icon and the widget. The core must read as a glass sphere with a lattice, orbital rings and a bright center; no triangle should appear anywhere.

## Test This on Your Phone (Phase 6 & Bug-Fix Checklist)

1. **Boot & Home HUD:** Launch **Jarvis**. Verify the 5-step boot animation completes smoothly and opens the Home screen with the spinning Arc Reactor, 3D side panels, and live telemetry.
2. **Live Free Model Discovery:** Tap the **AI MODEL** bar at the top of the Home screen (or open **Settings -> AI Model -> Fetch Models**). Toggle **Free models only** on, tap any free model (e.g., `Gemini 2.5 Flash` or an OpenRouter `:free` model), and send a message.
3. **Fish Audio / ElevenLabs Voice:** Open **Settings -> Voice Settings**, select **Fish Audio**, save your API key and voice reference ID (leave model as `s2.1-pro-free` or blank), and tap **Test voice**. Verify Jarvis speaks clearly without any `Flow exception transparency` or `Flow invariant` error.
4. **Microphone & Voice Assistant Screen:** Tap the central neural orb or the bottom-center Reactor button. Verify the **Voice Assistant** screen (Screen 3) opens with the 3D rotating particle globe, listens without any `"language not supported"` error, and transcribes your speech.
5. **Background Listening (No 1-Second Flicker):** Open **Settings -> Security -> Open permission wizard** (or **Settings -> Voice Settings**), turn on **Background listening**, and watch the microphone indicator. Verify it stays calm and does not toggle on/off every second. Say `"Jarvis, what's my battery at?"`
6. **Tier 2 Notification Reading:** In **Settings -> Security -> Open permission wizard**, tap **Notification access (Tier 2)** and enable **Jarvis**. Ask: `"Jarvis, read and summarize my notifications"` and `"Dismiss my notifications"`.
7. **Tier 3 Multi-Step Screen Automation & Stop Control:** In the permission wizard, enable **Accessibility: screen control (Tier 3)** (using *App info -> ⋮ -> Allow restricted settings* if greyed out). Ask: `"Jarvis, open Settings and turn on dark mode"`. Verify:
   - The persistent **"Jarvis is controlling the screen"** notification and the red in-app **STOP** banner appear showing `STEP N/15`.
   - Tapping **STOP** halts automation within 1 second.
   - Banking and password apps are refused automatically.
8. **Swipe Quick Panel:** Swipe inward from the right edge of the screen to open the **Quick Panel** (Screen 8) and test `Summarize`, `YouTube`, `Torch`, and live system readings.

## WhatsApp Voice-Call Receptionist (2026-11-01, late evening)

Jarvis can now answer WhatsApp voice calls for you:

1. **He detects the ringing call** through the accessibility service (WhatsApp's incoming-call window class) when you turn on **Settings → Security → "Answer WhatsApp calls for sir"** (off by default).
2. **He presses the green answer button himself** — by view id, label, or the classic bottom-right green FAB geometry — and only claims success after the active-call controls actually appear.
3. **He opens the call in his own voice**: "Hello, this is Jarvis, speaking for sir. Sir is not available at the moment…" using your configured Fish Audio / ElevenLabs / system voice.
4. **He engages with what the caller says**: the caller's words are transcribed from the loudspeaker path, sanitized (codes and card numbers redacted), and answered by the model in short spoken sentences — acknowledging news, asking a follow-up, or taking a message — for a bounded number of exchanges (2–12, default 6). Farewells end the call politely.
5. **He can do it on demand**: say "answer the call" (tool `answer_whatsapp_call`, optionally with a note) or "end the call" (`end_whatsapp_call`) once a call is active.
6. **He informs you and logs everything**: action-log records per turn, in-app status, and graceful Stop handling (Stop banner or `stopAll` hangs up for you).

**How the caller hears him (please read):** Android does not allow one app to pump audio into another app's phone call, so Jarvis speaks through the loudspeaker at max call volume and WhatsApp's own microphone carries his voice to the caller; the caller is heard by Jarvis the same acoustic way, through the phone's speech recognition. Keep the phone off your ear (speaker, volume up, no headset) during a handled call. If AI/provider/recognizer access fails mid-call, Jarvis degrades gracefully — a courtesy line ends the call rather than pretending he heard something.

### Test this on your phone (WhatsApp call feature)

1. **Turn it on:** Settings → Security → enable **Answer WhatsApp calls for sir** (also keep **Allow screen control** on) and tap **Save safety settings**.
2. **Have someone call your WhatsApp number.** Jarvis should tap Answer within ~2 s of the call popping; verify the call screen shows Jarvis's active call and the action log records `answer_whatsapp_call`.
3. **The intro:** the caller must hear, through the speaker, "this is Jarvis… sir is not available at the moment…" in your configured voice (not a robot system voice).
4. **The engagement:** have the caller answer a question ("I'm calling about dinner on Friday") and verify Jarvis acknowledges it and either asks one short follow-up or offers to take a message.
5. **A message:** have the caller say "please tell him I called tomorrow" — Jarvis should confirm the message and say goodbye, then hang up by itself.
6. **Farewell:** caller says "thanks, bye" — Jarvis ends the call.
7. **Stop control:** while Jarvis is on a call, press the in-app **STOP** (banner or notification). The call must hang up.
8. **Manual path:** leave auto-answer OFF; when a call rings, say "answer the WhatsApp call" in Jarvis's app, or open the tool `answer_whatsapp_call`. Then say "end the call".
9. **Fail-safe:** with the toggle on, call yourself with the phone's call volume at zero. Jarvis should still answer, but you should notice the poor pickup — this validates the acoustic path note above (not a bug).
10. **Settings persists:** after a full app restart, the toggle and "Max caller exchanges" stay where you left them.


## WhatsApp Voice Messages (2026-11-02)

Jarvis can now send a **real WhatsApp voice note** in his own configured voice — the
waveform/PTT bubble WhatsApp records itself, not an audio-file attachment.

### Use it

- **By voice or chat:** “Jarvis, send a voice message to Rahul saying I will call him
  tonight.” The `send_whatsapp_voice_note` tool finds the chat, asks for your explicit
  confirmation, speaks the line in your Fish Audio, ElevenLabs or system voice, records
  it through WhatsApp's own microphone and presses Send.
- **Tools screen:** `Send WhatsApp voice note` is listed under *Calls & SMS*.

### Auto-replies (opt-in)

In **Settings → Security → WhatsApp voice messages** you can allow a small list of
contacts to receive an automatic spoken reply when they message you. The policy:

- only listed contacts, only 1:1 text notifications, only while the screen is on,
  unlocked and screen control is enabled;
- refuses links, OTP/verification codes, passwords, payment or money requests, group
  messages, media placeholders and anything that looks like a prompt injection;
- enforces a per-contact cooldown (default 10 minutes) and a daily cap (default 12);
- drafts a 1–2 sentence reply through your configured AI provider and sends it as a
  voice note. Group auto-reply is intentionally not supported.

### How it works, honestly

Android gives no third-party app a way to write into WhatsApp's microphone or to set the
PTT flag on a shared file. The Business Cloud API can send true voice notes but cannot
use a personal WhatsApp number. So Jarvis uses WhatsApp's own recorder: it opens the
exact chat, holds the mic and slides up to lock, plays the rendered WAV through the
loudspeaker at full media volume, waits for the sentence to finish, then presses Send.
This is the same acoustic limitation as the call receptionist: the phone must be
**unlocked, off any headset/Bluetooth output, with media volume up**. No note is claimed as delivered unless the recording bar disappears and the composer
returns; a new voice bubble with a duration upgrades that to a verified delivery. If
WhatsApp's UI cannot be driven, Jarvis says so and offers an audio-file share handoff
only when no recording has started (so it can never duplicate a note).

The renderer uses the configured cloud voice and falls back to the Android system voice
when `Fall back to system voice` is on. Notes are capped at 420 characters by default
(80–700), and anything over 60 seconds is refused.

### Test this on your phone

1. Enable **Settings → Security → Allow screen control**, then **Allow WhatsApp voice
   notes**. Disconnect Bluetooth/headphones and raise media volume.
2. Say: “Jarvis, send a voice message to <a contact> saying hello, I will call you
   shortly.” Approve the confirmation dialog.
3. WhatsApp must open the right chat, show the recording bar, play Jarvis's voice, and
   send a voice bubble. Check the action log for `send_whatsapp_voice_note`.
4. Try a saved contact by name and an unsaved number with country code.
5. For auto-reply, add that contact to the allowlist, enable it, and send yourself a
   message from the contact's phone while this phone is unlocked. Verify one reply, then
   verify the cooldown blocks a second immediate message.
6. Negative checks: a link, an OTP, a group message and a locked/off screen must all be
   refused or skipped.

**Known limits:** WhatsApp's private UI can change with an update; the mic and send
detection uses layered resource-id, label and geometric strategies and refuses rather
than guessing. The acoustic recording sounds like a phone call, not studio audio.
Auto-reply needs notification access and is deliberately limited to allowlisted 1:1
contacts.

### Launcher icon

The only new image added to the workspace was the app showcase poster. Its arc-reactor
mark was cropped cleanly (no caption) into the 1024 px app logo, an adaptive-icon
foreground, round icons and legacy mipmap sizes; `ic_launcher.xml` and the manifest now
use it.

---

## Proactive Chief-of-Staff Call Engine ("Jarvis Calls You With a Problem")

Jarvis includes an autonomous **Chief-of-Staff Proactive Incident Call Engine** (`ProactiveIncidentCallEngine.kt` + `assets/persona/jarvis_chief_of_staff.md`) that watches your phone, catches urgent problems, executes safe pre-call mitigations (`GREEN` / `AMBER`), and calls you with a crisp, spoken executive briefing before asking permission for any high-impact (`RED`) action.

### 1. How to Add an `IncidentWatcher`

All incident sources implement the pluggable `IncidentWatcher` interface in `com.jarvis.assistant.service.ProactiveIncidentCallEngine`:

```kotlin
interface IncidentWatcher {
    val sourceId: String
    suspend fun poll(): List<ProactiveIncidentEvent>
}
```

Three production watchers are registered out of the box:
1. **`NotificationIncidentWatcher`** — scans live status-bar notifications for urgent payment failures, fraud alerts, shipping holds, security lockouts, or VIP messages.
2. **`CalendarDeadlineWatcher`** — detects imminent conflicting meetings, unconfirmed travel departures, or critical deadlines within the next 45 minutes.
3. **`DeviceOperationsHealthWatcher`** — monitors critical battery depletion before an upcoming calendar event or connectivity failures during active tasks.

To register a custom watcher (for example, a Stripe webhook watcher or CI build failure watcher), instantiate it and call:

```kotlin
proactiveCallEngine.registerWatcher(MyCustomWatcher())
```

### 2. Permission Tiers (`GREEN`, `AMBER`, `RED`) & Global Kill Switch

Every tool action is governed by a strict `PermissionTier` (`ProactiveIncidentCallEngine.defaultToolTiers`):

- **`GREEN` (Silent & Pre-Call Safe):** Read-only diagnostics, checking logs, pulling invoice/calendar details, opening an app, reading notifications (`check_status`, `get_notifications`, `search_memory`, `get_calendar_events`). Jarvis runs these automatically before calling you so he has the facts ready.
- **`AMBER` (Reversible Mitigation — Logged & Reported):** Reversible containment actions such as pausing a noisy campaign, drafting (not sending) a reply, holding a queue, or setting a reminder (`pause_campaign`, `draft_message`, `create_reminder`, `hold_shipment`). Jarvis executes these before placing the call, records them in the `ActionLedger`, and reports them in the opening briefing.
- **`RED` (Irreversible / External / Financial — Owner Approval Required):** Spending money, charging a card, sending an external message/email/voice note, placing a phone call, submitting a legal appeal, or deleting data (`charge_backup_card`, `submit_appeal`, `send_whatsapp_message`, `send_whatsapp_voice_note`, `make_phone_call`, `delete_data`).
  - **Unknown tools automatically default to `RED`.**
  - `RED` actions **never** run before the call and **never** run unless you explicitly approve them during the call (`"Yes, do both"`, `"Use the Visa"`, `"Send it"`).

You can override any tool's tier at runtime:

```kotlin
proactiveCallEngine.setToolPermissionTier("pause_campaign", PermissionTier.RED)
```

Or disable all autonomous pre-call mitigations and proactive calls immediately via:
- **`proactiveCallEngine.setGlobalKillSwitch(true)`**, or
- **Settings → Master Power Card → "Shut Off J.A.R.V.I.S."**

### 3. Honesty Guardrails (`HonestyGuardrail`)

Before any line is spoken by TTS during a proactive call, `HonestyGuardrail.sanitizeSpokenTurn(...)` enforces:
1. **No Phantom Actions:** Every sentence claiming a completed action (`"I've already..."`, `"I paused..."`, `"I sent..."`, `"I appealed..."`, `"I moved..."`) is verified against the cryptographic `ActionLedger` for that incident. If no matching `ExecutedLedgerEntry` (`succeeded = true`) exists, the claim is stripped before reaching TTS.
2. **No Invented Figures:** Any spoken number (`4,200`, `18`, `40`, etc.) is cross-checked against the event's verified `facts`, `rawNumbers`, and tool results. If a number is not backed by data, Jarvis replaces it with `"I don't have that figure yet."`
3. **Brevity & Cadence:** Strips markdown, emojis, exclamation marks, and robotic filler (`"Great question"`, `"As an AI"`), enforces `<= 35` words per turn (unless you ask for detail), and allows at most **one** `"One more thing"` pivot per call.

### 4. Running the Three Reference Simulations (`simulate A`, `simulate B`, `simulate C`)

You can trigger all three end-to-end reference scenarios directly from the J.A.R.V.I.S. chat box or by voice at any time:

- **Type or say `simulate A` (Scenario A — Ad Campaign Policy Flag & Spend Spike):**
  - Pre-call: Jarvis checks the ad account (`GREEN`) and pauses the flagged campaign (`AMBER`).
  - Call opens: *"Sir."* → *"Meta flagged the summer reel for policy and ad spend jumped forty percent. I paused the campaign. Want me to appeal and shift budget to search?"*
  - Follow-ups supported: *"How much did it burn?"* → *"Fourteen hundred over cap in two hours."* → *"Appeal it, hold the budget move."* → Executes `submit_appeal` (`RED`), confirms *"Done."*, and pivots once: *"One more thing. Stripe webhook timed out twice. Already retrying."*
- **Type or say `simulate B` (Scenario B — Supplier Payment Declined on Primary Card):**
  - Pre-call: Jarvis verifies the freight invoice (`GREEN`) and holds the dispatch slot (`AMBER`).
  - Call opens: *"Sir."* → *"Amex declined the four-thousand-two-hundred-dollar freight charge and the shipment locks in twenty minutes. I held the slot. Should I put it on the Chase Visa?"*
  - Follow-ups supported: *"Use the Visa, and set a four grand alert next time."* → Executes `charge_backup_card` (`RED`) and saves the `"Four grand"` threshold to persistent memory (`"Done. Paid on the Visa, and I'll flag anything over four grand."`).
- **Type or say `simulate C` (Scenario C — Production Crash Loop While Driving):**
  - Pre-call: Jarvis detects driving mode, rolls back the staging worker (`AMBER`), and pins traffic to the stable build (`AMBER`).
  - Call opens: *"Sir. Are you driving?"* → If you say *"Yeah, keep it quick"*, Jarvis switches to ultra-brief mode (`<= 18` words) and defers non-critical details until you park.

---

## Human-Like Touch Automation Engine (15-State Machine)

Screen control is powered by `HumanTouchEngine.kt`, `ScreenController.kt`, and `AppDestinations.kt`:

- **15-State Execution Machine (`AutomationExecutionState`):** `IDLE` → `LISTENING` → `UNDERSTANDING` → `PLANNING` → `PERMISSION_CHECK` → `OBSERVING_SCREEN` → `LOCATING_TARGET` → `EXECUTING_ACTION` → `WAITING_FOR_UI` → `VERIFYING_ACTION` → `RECOVERING` → `WAITING_FOR_USER` → `TASK_COMPLETED` / `TASK_FAILED` / `CANCELLED`.
- **Bottom-Tab & YouTube Profile Precision:** Rejects full-width bottom navigation containers (`pivot_bar` spanning `0..1080`, whose geometric center `(540, Y)` previously tapped Shorts/Create), penalizes `"Shorts"` when not requested, and distinguishes the genuine bottom-right `"You"` profile tab (`(972, 2260)`) from mid-screen `"For you"` feed headers.
- **WhatsApp Voice Note Workflows (`WhatsAppVoiceWorkflowEngine`):**
  - **Mode A (Native Mic Recording):** Opens the target chat, locates the bottom-right voice note mic button (`voice_note_btn`), slides up to lock recording, speaks the message acoustically, and taps Send only after owner confirmation.
  - **Mode B (Generated Audio File Attachment):** Generates a high-clarity motivational WAV file via TTS and attaches it to the chat with transparent disclosure that it is sent as an audio file rather than a live mic recording.

---

## Phase 0 Feasibility Probes, Call & Message Receptionist, Multi-App Social Voice Notes & 4-Rung Perception Ladder

### 1. How to Open & Run Phase 0 Feasibility Probes (`P1`–`P6`) on Your Phone

Android restricts microphone capture during active cellular/VoIP calls (`isClientSilenced`), with a narrow compatibility exception for `AccessibilityService` using `AudioSource.VOICE_RECOGNITION`, and call echo cancellation (AEC) may suppress loudspeaker TTS. **Test on your phone first — never assume.**

1. **Open the Phase 0 Feasibility Probe Lab:**
   - Open **Settings → System** (or **Settings → Security**) and tap **`PHASE 0 PROBES & CALL/MESSAGE RECEPTIONIST (OPEN LAB)`**, **OR**
   - Tap the **`J.A.R.V.I.S.`** title in the top header of the Settings screen **5 times**, **OR**
   - Type/say **`run phase 0 probes`** in the Home chat.
2. **Run each probe step-by-step while placing test calls/messages from a second phone:**
   - **P1 Device (`RUN AUTOMATED PROBE`):** Logs Android SDK/release, OEM, `AdvancedProtectionManager` state, Accessibility Service connection, NotificationListener status, and sideloaded Restricted Settings status.
   - **P2 Notifications (`RUN AUTOMATED PROBE`):** Send a real WhatsApp message and place a WhatsApp call from another phone; P2 logs `package`, `category`, `actionTitles`, and `RemoteInput` keys (`inline_reply`), plus lets you test firing inline Reply / Answer / Decline notification actions.
   - **P3 Screens (`RUN AUTOMATED PROBE`):** Open WhatsApp incoming-call, in-call, chat, and contact-search screens and tap Run; P3 dumps the accessibility node tree and tests `AccessibilityService.takeScreenshot()` to check if any screen comes back blank or blocked (`FLAG_SECURE`).
   - **P4 Audio In (`RUN AUDIO IN PROBE`):** While on an active WhatsApp or cellular call (test with speakerphone ON and OFF), tap Run. From inside `JarvisAccessibilityService`, P4 captures audio across `VOICE_RECOGNITION`, `MIC`, `VOICE_COMMUNICATION`, and `UNPROCESSED`, registers `AudioRecordingCallback`, logs `isClientSilenced`, peak, and RMS levels, and asks you what you observed.
   - **P5 Audio Out (`RUN AUDIO OUT PROBE`):** During the same call, P5 plays a 660 Hz test tone + TTS sentence across `USAGE_MEDIA`, `USAGE_VOICE_COMMUNICATION`, and `USAGE_ASSISTANCE_ACCESSIBILITY`, logs the active output route, and asks **"Did the caller on the other phone clearly hear Jarvis?"** (`Caller Heard Clearly (PASS)` / `Faint / Clipped (PARTIAL)` / `Caller Heard Nothing (FAIL)`).
   - **P6 Control (`RUN AUTOMATED PROBE`):** Verifies answering/ending WhatsApp calls, speakerphone toggling (`isSpeakerphoneOn`), and cellular `TelecomManager` (`ANSWER_PHONE_CALLS`).
3. **Export & Share the Evidence Report:**
   - Tap **`SHARE REPORT`** or **`COPY REPORT`** at the top of the Diagnostics screen to export `/files/probe_reports/jarvis_phase0_probe_report.md` (and `.json`).

### 2. Which Probes Unlock or Disable Path A, Path B, and Path C

| Path | Description | Required Probes | When Unlocked / Disabled |
| :--- | :--- | :--- | :--- |
| **PATH A** | **WhatsApp Business Cloud API Calling** (Webhook + WebRTC/SIP media bridge → STT → LLM → TTS on a business number) | Server-side (independent of phone mic/AEC) | Always available if you have a WhatsApp Business Cloud API number & backend. Note: Meta restricts general-purpose AI assistants on WhatsApp Business API. |
| **PATH B** | **On-Device Consumer App Call Audio** (Accessibility UI/Notification Answer + Speakerphone + `VOICE_RECOGNITION` Mic + Loudspeaker TTS) | **P1 + P3 + P4 + P5 + P6 = `PASS`** | **Locked by default.** Unlocks **only** after P4 (`isClientSilenced == false` with audible caller RMS) and P5 (you confirm the caller heard Jarvis over loudspeaker) both record `PASS` on your device. Carries an experimental Meta ToS warning badge. |
| **PATH C** | **Safe Fallback: Call Screening, Decline-and-Message & Cellular Forwarding** | **P1 + P2 (`PASS` or `PARTIAL`)** | **Default safe path** whenever P4 or P5 is untested, `PARTIAL`, or `FAIL`. Declines or screens the call when busy and immediately texts the caller via Notification `RemoteInput` or SMS: *"Jarvis here, [Name]'s assistant. What do you need?"* Also supports carrier conditional call forwarding (`*61*` / `*67*` on Jio/Airtel/Vi) to an Indian cloud telephony number (Exotel / Ozonetel / Twilio; ~₹1,500–₹4,500/mo rental + ~₹0.75–₹1.50/min). |

### 3. How to Add a `SocialAppProfile` (Data-Driven Social Apps)

All social app selectors live in `SocialAppProfileRegistry` (`service/CallReceptionistAndMessagingEngine.kt`). No app-specific `if/else` branches are needed in the core engine. Built-in profiles:
- **WhatsApp** (`com.whatsapp`)
- **WhatsApp Business** (`com.whatsapp.w4b`)
- **Instagram** (`com.instagram.android`)
- **Telegram** (`org.telegram.messenger`)
- **Facebook Messenger** (`com.facebook.orca`)
- **SMS / Google Messages** (`com.google.android.apps.messaging`)

To add a new app (e.g., Signal or Discord), call `SocialAppProfileRegistry.register(...)`:

```kotlin
SocialAppProfileRegistry.register(
    SocialAppProfile(
        id = "signal",
        displayName = "Signal",
        packageName = "org.thoughtcrime.securesms",
        aliases = setOf("signal", "signal messenger"),
        searchTriggerIds = listOf("menu_search"),
        searchInputIds = listOf("search_view"),
        composerInputIds = listOf("embedded_text_editor"),
        sendButtonIds = listOf("send_button"),
        voiceNoteMicViewIds = listOf("recorder_view", "mic_button"),
        voiceNoteMicLabels = listOf("Voice message", "Record"),
        voiceNoteSendViewIds = listOf("send_button"),
        voiceNoteSendLabels = listOf("Send"),
    )
)
```

### 4. How to Send Voice Messages Across Social Media Apps (WhatsApp, Instagram, Telegram, Messenger)

Use the **`send_social_voice_note`** tool (or ask Jarvis by voice):
- *"Jarvis, send a voice message on Instagram to Rahul saying I'll review the design tonight."*
- *"Jarvis, send a WhatsApp voice note to Azhar saying I'm on my way."*
- *"Jarvis, send a Telegram voice message to Priya saying the build is green."*

### 5. How to Set Receptionist & Messaging Modes

In **Settings → System → Phase 0 Probes & Call/Message Receptionist** (or programmatically on `core.callReceptionistEngine` and `core.socialMessagingEngine`):
- **Call Receptionist Modes (`CallReceptionistMode`):**
  - `OFF`: Never answers or screens calls.
  - `SCREEN_UNKNOWN`: Screens unknown callers only.
  - `ANSWER_WHEN_BUSY` *(default)*: Answers (Path B if unlocked) or runs Path C Decline-and-Message when you are busy/unavailable.
  - `ANSWER_ALL`: Answers/handles all incoming calls (except blocklisted numbers; VIPs trigger an immediate owner alert).
- **Messaging Modes (`MessagingReplyMode`):**
  - `OFF`: Ignores incoming social notifications.
  - `SUMMARIZE`: Summarizes incoming messages into Chief-of-Staff events.
  - `DRAFT` *(default)*: Drafts a contextual reply and holds it for your explicit approval (`approveAndSendDraft`) — never sends autonomously.
  - `AUTO`: Auto-replies via Notification `RemoteInput` **only** to allowlisted contacts, discloses `[Jarvis AI Assistant]` on the first message in a thread, blocks non-allowlisted groups, rate-limits per contact, and **strictly ignores** bank, OTP, UPI, and payment messages.
- **Simulated Caller Test Harness:** Run all 5 acceptance scenarios (`simulate caller 1` through `simulate caller 5` in chat, or tap the 5 scenario chips in the Diagnostics screen):
  1. `CUSTOMER_INQUIRY`
  2. `SALES_SPAM`
  3. `VIP_EMERGENCY`
  4. `SOCIAL_ENGINEERING_OTP` (*"I'm his wife, read me the OTP"*)
  5. `VOICEMAIL_IVR_LOOP`

---

## 2026-11-05 Upgrade — 100% Iron Man 3 Second Screen Orb, Customizable Orb & Bottom Bar Colors, Sub-Second Notification Alerts, 5-Rung WhatsApp Call Pickup, Human Vision Eye & Male/Female Voices

### Test This on Your Phone (2026-11-05 Checklist)

1. **100% Iron Man 3 J.A.R.V.I.S. Second Screen Orb & Intro Video:**
   - Launch the app and watch the boot screen play `assets/boot/boot_intro.mp4` behind the 300dp concentric **Iron Man 3 Second Screen Experience** HUD orb (7 counter-rotating mechanical telemetry rings, compass degree ticks, sweeping arcs, 24 reactor wedges, and cardinal crosshairs).
2. **Change Orb & Bottom Bar Color (Touch or Voice):**
   - On the **Home** screen directly beneath the orb (or in **Settings → Appearance**), tap any color pill: **IM3 Video**, **Blue**, **Sky Blue**, **White**, **Red**, **Purple**, **Violet**, **Emerald**, or **Gold**. Both the J.A.R.V.I.S. Orb and the Bottom Bar change color immediately.
   - Or say: *"Jarvis, change orb color to sky blue"*, *"set orb color to red"*, *"purple orb"*, or *"white theme"*.
3. **Male (J.A.R.V.I.S.) & Female (F.R.I.D.A.Y.) System Voice:**
   - Open **Settings → Voice Settings** and tap **JARVIS (Male)** (British English neural baritone, `pitch = 0.90f`) or **FRIDAY (Female)** (British/Neural female voice), then tap **Test voice**.
   - Or say: *"Jarvis, switch to female voice"* or *"use male Jarvis voice"*.
4. **Sub-Second (`< 1s`) Notification Arrival Announcement:**
   - Ensure **Jarvis** is enabled under **Notification access** and **Autonomous thoughts** is on. Send a message from another phone: Jarvis receives the `onNotificationPosted` callback in `0ms` and announces who messaged and what arrived within 1 second.
5. **WhatsApp Call Pickup (Clicks Answer Button First & Talks to Caller):**
   - Say *"Jarvis, if anyone called from WhatsApp pick that up"* (or verify **Settings → Security → Answer WhatsApp calls for sir** is ON by default).
   - Place a WhatsApp voice call from a second phone: Jarvis detects the call at `0ms`, clicks the **Answer** button first (via Notification PendingIntent, View ID, Label, or `HumanVisionEyeEngine` green button pixel detection), switches on speakerphone, greets the caller, and converses with them.
6. **WhatsApp Message by Contact Name (Never Requires Phone Number):**
   - Say *"Jarvis, send a WhatsApp message to <Contact Name> saying I will be there in 10 minutes"*. Jarvis resolves the contact by name (including phonetic/accented matches, WhatsApp JID rows, inline notification reply, or opening WhatsApp Search and typing their name) without ever asking for their phone number.

## Octa-Jarvis download site and app updates

The download page lives in [`site/`](site/index.html) and reads
[`site/version.json`](site/version.json). Every installed build checks that file (and
the raw repository copy as a fallback) on launch and every 12 hours; when
`versionCode` is newer than the installed build, the app shows the update sheet and a
notification that opens the download.

Publishing a release (GitHub → Actions → **Publish Octa-Jarvis release** → Run workflow):

1. Builds and signs the APK.
2. Publishes a GitHub Release with `app-debug.apk`.
3. Rewrites `site/version.json` with the new `versionCode`, `versionName`, notes and
   SHA-256.
4. Deploys `site/` to GitHub Pages.

Enable Pages once under **Settings → Pages → Build and deployment → Source: GitHub Actions**.

The app icon set is generated, never hand-edited:

```bash
python3 tools/generate_app_icons.py                    # built-in Octa-Jarvis orb
python3 tools/generate_app_icons.py design/app-icon.png  # your own 1024x1024 source
```
