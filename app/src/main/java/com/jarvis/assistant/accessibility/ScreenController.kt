package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.security.SecurityShield
import java.util.Locale
import com.jarvis.assistant.tools.ScreenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Snapshot of the active UI and audio state before or after an accessibility step,
 * used by [StepVerificationEvaluator] to confirm that an action (such as clicking
 * a YouTube play button or video card) actually took effect.
 */
data class UiStateFingerprint(
    val packageName: String,
    val nodeCount: Int,
    val treeSignatureHash: Int,
    val checkedStatesHash: Int,
    val editableTexts: List<String> = emptyList(),
    val isAudioPlaying: Boolean = false,
    val hasYouTubePlayer: Boolean = false,
    val hasPlayControl: Boolean = false,
    val hasPauseControl: Boolean = false,
    val telemetry: AccessibilityEventTelemetry = AccessibilityEventTelemetry(),
)

data class VerificationOutcome(
    val verified: Boolean,
    val signal: String,
)

/**
 * Pure verification evaluator that compares pre-action and post-action
 * [UiStateFingerprint] states to confirm whether a click, text entry, or scroll
 * succeeded on screen.
 */
object StepVerificationEvaluator {

    fun evaluateClick(
        before: UiStateFingerprint,
        after: UiStateFingerprint,
        targetLabel: String,
    ): VerificationOutcome {
        val normTarget = targetLabel.lowercase().trim()
        val isMediaPlayIntent = normTarget.contains("play") ||
            normTarget.contains("video") ||
            normTarget.contains("song") ||
            normTarget.contains("watch")

        // 1. Audio playback started (strongest signal for media / YouTube play actions)
        if (!before.isAudioPlaying && after.isAudioPlaying) {
            return VerificationOutcome(true, "audio_playback_started")
        }

        // 2. Play button transitioned to Pause control (e.g. YouTube player play -> pause)
        if ((before.hasPlayControl || isMediaPlayIntent) && !before.hasPauseControl && after.hasPauseControl) {
            return VerificationOutcome(true, "play_transitioned_to_pause")
        }

        // 3. Transitioned into YouTube video player surface
        if (!before.hasYouTubePlayer && after.hasYouTubePlayer) {
            return VerificationOutcome(true, "youtube_player_opened")
        }

        // 4. Foreground app or window state changed
        if (before.packageName != after.packageName && after.packageName.isNotBlank()) {
            return VerificationOutcome(true, "package_changed:${after.packageName}")
        }
        if (after.telemetry.lastWindowChangeSeq > before.telemetry.lastWindowChangeSeq) {
            return VerificationOutcome(true, "window_state_changed")
        }

        // 5. Toggle / checkbox / switch state changed
        if (before.checkedStatesHash != after.checkedStatesHash) {
            return VerificationOutcome(true, "toggle_state_changed")
        }

        // 6. Accessibility tree structure changed (new screen, dialog, or updated list)
        if (before.treeSignatureHash != after.treeSignatureHash || before.nodeCount != after.nodeCount) {
            return VerificationOutcome(true, "ui_tree_updated")
        }

        // 7. System accessibility click + content change event confirmed by target view
        if (after.telemetry.lastClickSeq > before.telemetry.lastClickSeq &&
            after.telemetry.lastContentChangeSeq > before.telemetry.lastContentChangeSeq
        ) {
            return VerificationOutcome(true, "click_and_content_event_confirmed")
        }

        return VerificationOutcome(false, "no_ui_change_detected")
    }

    fun evaluateTextEntry(
        before: UiStateFingerprint,
        after: UiStateFingerprint,
        expectedText: String,
    ): VerificationOutcome {
        val cleanExpected = expectedText.trim().lowercase()
        if (cleanExpected.isNotEmpty() &&
            after.editableTexts.any { it.lowercase().contains(cleanExpected) }
        ) {
            return VerificationOutcome(true, "editable_text_matches")
        }
        if (before.editableTexts != after.editableTexts) {
            return VerificationOutcome(true, "editable_field_updated")
        }
        if (after.telemetry.lastContentChangeSeq > before.telemetry.lastContentChangeSeq) {
            return VerificationOutcome(true, "content_change_event")
        }
        return VerificationOutcome(false, "text_not_reflected_in_field")
    }

    fun evaluateScroll(
        before: UiStateFingerprint,
        after: UiStateFingerprint,
    ): VerificationOutcome {
        if (after.telemetry.lastScrollSeq > before.telemetry.lastScrollSeq) {
            return VerificationOutcome(true, "scroll_event_confirmed")
        }
        if (before.treeSignatureHash != after.treeSignatureHash) {
            return VerificationOutcome(true, "visible_nodes_shifted")
        }
        return VerificationOutcome(false, "scroll_position_unchanged")
    }
}

/**
 * A deliberate, verified screen-control toolkit.
 *
 * Every action (`tapVerified`, `tapFirstYouTubeVideoVerified`, `typeVerified`,
 * `scrollVerified`) captures a pre-action [UiStateFingerprint], executes the
 * action, and verifies that the UI or audio state actually changed before
 * proceeding to the next automation step—automatically retrying with physical
 * coordinate gestures when a custom view ignores standard accessibility actions.
 */
class ScreenController(private val service: AccessibilityService) {

    data class ScreenSnapshot(
        val packageName: String,
        val appLabel: String,
        val text: String,
        val nodeCount: Int,
    )

    data class CandidateTarget(
        val score: Int,
        val label: String,
        val clickableNode: AccessibilityNodeInfo?,
        val bounds: Rect,
    )

    private var walked = 0

    private val jarvisService: JarvisAccessibilityService?
        get() = service as? JarvisAccessibilityService ?: JarvisAccessibilityService.serviceInstance()

    private val audioManager: AudioManager?
        get() = runCatching { service.getSystemService(AudioManager::class.java) }.getOrNull()

    fun foregroundPackage(): String =
        service.rootInActiveWindow?.packageName?.toString().orEmpty()

    fun appLabel(packageName: String): String = runCatching {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * Captures a structural and media fingerprint of the current screen so that
     * post-step verification can confirm whether an action took effect.
     */
    fun captureFingerprint(): UiStateFingerprint {
        val telemetry = jarvisService?.eventTelemetry() ?: AccessibilityEventTelemetry()
        val playing = runCatching { audioManager?.isMusicActive == true }.getOrDefault(false)
        val root = service.rootInActiveWindow
            ?: return UiStateFingerprint(
                packageName = "",
                nodeCount = 0,
                treeSignatureHash = 0,
                checkedStatesHash = 0,
                isAudioPlaying = playing,
                telemetry = telemetry,
            )

        val packageName = root.packageName?.toString().orEmpty()
        var count = 0
        var treeHash = 17
        var checkedHash = 31
        val editableValues = mutableListOf<String>()
        var hasPlayer = false
        var hasPlay = false
        var hasPause = false

        walked = 0
        walk(root) { node ->
            count++
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val combined = "$text|$desc|$viewId"
            treeHash = 31 * treeHash + combined.hashCode()
            if (node.isCheckable || node.isChecked || node.isSelected) {
                checkedHash = 31 * checkedHash + (combined.hashCode() xor (if (node.isChecked) 1 else 0) xor (if (node.isSelected) 2 else 0))
            }
            if (node.isEditable && text.isNotBlank() && !node.isPassword) {
                editableValues += text
            }
            val normDesc = normalize("$text $desc $viewId")
            if (viewId.contains("player_view") ||
                viewId.contains("watch_while") ||
                normDesc.contains("video player") ||
                normDesc.contains("minimize player") ||
                normDesc.contains("enter fullscreen")
            ) {
                hasPlayer = true
            }
            val isPlayPauseControl = viewId.contains("play_pause") ||
                viewId.contains("player_control") ||
                normDesc.contains("play video") ||
                normDesc.contains("pause video")
            if (isPlayPauseControl && normDesc.contains("pause")) {
                hasPause = true
            }
            if (isPlayPauseControl && normDesc.contains("play") && !normDesc.contains("pause")) {
                hasPlay = true
            }
            true
        }

        return UiStateFingerprint(
            packageName = packageName,
            nodeCount = count,
            treeSignatureHash = treeHash,
            checkedStatesHash = checkedHash,
            editableTexts = editableValues,
            isAudioPlaying = playing,
            hasYouTubePlayer = hasPlayer,
            hasPlayControl = hasPlay,
            hasPauseControl = hasPause,
            telemetry = telemetry,
        )
    }

    fun snapshot(): ScreenSnapshot {
        val root = service.rootInActiveWindow
            ?: return ScreenSnapshot("", "", "", 0)
        val packageName = root.packageName?.toString().orEmpty()
        if (SecurityShield.isProtectedSystemPackage(packageName)) {
            return ScreenSnapshot(
                packageName = packageName,
                appLabel = appLabel(packageName),
                text = "[Protected system security dialog — hidden by Jarvis Security Shield]",
                nodeCount = 0,
            )
        }
        val builder = StringBuilder()
        var count = 0
        var interactiveIdx = 0
        walked = 0
        walk(root) { node ->
            count++
            val isInteractive = node.isClickable || node.isEditable || isVideoCardNode(node)
            val idx = if (isInteractive) ++interactiveIdx else 0
            val line = describe(node, idx)
            if (line.isNotBlank()) builder.append(line).append('\n')
            true
        }
        val safeText = SecurityShield.sanitizeUntrustedText(
            raw = builder.toString().trim(),
            source = "screen:$packageName",
        )
        return ScreenSnapshot(
            packageName = packageName,
            appLabel = if (packageName.isBlank()) "" else appLabel(packageName),
            text = safeText,
            nodeCount = count,
        )
    }

    /**
     * When YouTube lands on a search results page, finds and taps the first playable
     * video item, then verifies that the video player opened and playback is active
     * (clicking the Play button if the player opened in a paused state).
     */
    suspend fun tapFirstYouTubeVideoVerified(query: String): ScreenResult =
        tapVideoItemVerified(index = 1, query = query)

    /** Synchronous wrapper preserved for callers that are not in a suspend context. */
    fun tapFirstYouTubeVideo(query: String): ScreenResult {
        val candidate = findBestYouTubeCandidate(query)
            ?: return ScreenResult(false, "No video item found to tap yet.", verified = false)
        val clicked = runCatching {
            candidate.clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }.getOrDefault(false)
        return if (clicked) {
            ScreenResult(true, "Tapped the first YouTube video result.")
        } else {
            ScreenResult(false, "Could not tap the YouTube video result.", verified = false)
        }
    }

    private fun findBestYouTubeCandidate(query: String): CandidateTarget? {
        val root = service.rootInActiveWindow ?: return null
        val queryWords = normalize(query).split(' ').filter { it.length >= 3 }
        val ignoredLabels = setOf(
            "search", "navigate up", "back", "cast", "notifications", "account",
            "all", "shorts", "videos", "unwatched", "watched", "recently uploaded",
            "live", "playlists", "channels", "filters", "more", "home", "subscriptions",
            "voice search", "clear",
        )

        val candidates = mutableListOf<CandidateTarget>()
        walked = 0
        walk(root) { node ->
            if (node.isEditable) return@walk true
            val rawLabel = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()).trim()
            val norm = normalize(rawLabel)
            if (norm.length < 3 || norm in ignoredLabels) return@walk true
            if (ignoredLabels.any { norm == it || norm.startsWith("$it ") && norm.length < 15 }) return@walk true

            val clickable = clickableTarget(node) ?: node
            var score = 20
            if (norm.contains("minute") || norm.contains("second") || norm.contains("views") || norm.contains("play video")) {
                score += 50
            }
            val matchedWords = queryWords.count { norm.contains(it) }
            score += matchedWords * 25
            if (rawLabel.length >= 25) score += 15
            val bounds = Rect()
            runCatching { clickable.getBoundsInScreen(bounds) }
            candidates += CandidateTarget(
                score = score,
                label = rawLabel.take(80),
                clickableNode = clickable,
                bounds = bounds,
            )
            candidates.size < MAX_CANDIDATES * 2
        }
        return candidates.maxByOrNull { it.score }
    }

    /**
     * Taps the best match for [text] and verifies that the UI or audio state responded.
     * If [text] is a coordinate pair ("x,y"), an element index ("#1", "item 1"),
     * or a semantic media reference ("video", "first video", "second video"),
     * it automatically resolves the target node or physical touch gesture coordinates.
     * Standard accessibility `ACTION_CLICK` is attempted first, and physical coordinate
     * touch gestures (`dispatchTapGesture`) are automatically used as a guaranteed fallback.
     */
    suspend fun tapVerified(text: String): ScreenResult {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return ScreenResult(false, "Tell me what to tap.", verified = false)
        }

        // Direct coordinate tap pattern e.g. "540, 800" or "tap 540, 800"
        val coordMatch = Regex("""(?:tap\s*)?(\d{2,4})\s*[,x\s]\s*(\d{2,4})""").find(trimmed)
        if (coordMatch != null && (trimmed.startsWith("tap ") || trimmed.contains(","))) {
            val x = coordMatch.groupValues[1].toIntOrNull() ?: 0
            val y = coordMatch.groupValues[2].toIntOrNull() ?: 0
            if (x > 0 && y > 0) {
                return tapCoordinatesVerified(x, y)
            }
        }

        val wanted = normalize(trimmed)

        // "Play video"/"Pause video" is the player control, not a request to
        // choose a result. Handle it directly so a paused player is resumed.
        if (wanted == "play video" || wanted == "pause video" || wanted == "play" || wanted == "pause") {
            val control = withContext(Dispatchers.Main) { findPlayerControl(wanted) }
            if (control != null) {
                val controlBefore = withContext(Dispatchers.Main) { captureFingerprint() }
                val clicked = withContext(Dispatchers.Main) {
                    runCatching {
                        control.clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                    }.getOrDefault(false)
                }
                if (!clicked && control.bounds.width() > 0) {
                    jarvisService?.dispatchTapGesture(
                        control.bounds.exactCenterX(),
                        control.bounds.exactCenterY(),
                    )
                }
                val controlOutcome = pollForClickVerification(controlBefore, wanted)
                return ScreenResult(
                    success = clicked || controlOutcome.verified,
                    message = if (controlOutcome.verified) {
                        "Tapped \"$wanted\" and confirmed the player responded."
                    } else {
                        "Tapped \"$wanted\", but the player did not respond. I can read the screen and try again."
                    },
                    verified = controlOutcome.verified,
                    verificationSignal = controlOutcome.signal,
                )
            }
        }

        // Video / media intent: e.g. "video", "first video", "second video", "watch video"
        val isVideoIntent = wanted == "video" || wanted == "first video" ||
            wanted == "second video" || wanted == "third video" ||
            wanted.startsWith("watch video") || wanted.contains("first video")
        if (isVideoIntent) {
            val idx = when {
                wanted.contains("second") || wanted.contains(" 2") -> 2
                wanted.contains("third") || wanted.contains(" 3") -> 3
                wanted.contains("fourth") || wanted.contains(" 4") -> 4
                else -> 1
            }
            val filterQuery = wanted
                .replace("first video", "")
                .replace("second video", "")
                .replace("third video", "")
                .replace("video", "")
                .replace("play", "")
                .replace("watch", "")
                .trim()
            return tapVideoItemVerified(index = idx, query = filterQuery)
        }

        val before = withContext(Dispatchers.Main) { captureFingerprint() }
        val candidates = withContext(Dispatchers.Main) { findTapCandidates(wanted) }
        if (candidates.isEmpty()) {
            // If on YouTube and search failed, try video item targeting
            val fg = foregroundPackage()
            if (fg.contains("youtube") || fg.contains("browser") || fg.contains("chrome")) {
                val videoRes = tapVideoItemVerified(1, wanted)
                if (videoRes.success) return videoRes
            }
            return ScreenResult(
                success = false,
                message = "I could not find \"$text\" on this screen.",
                verified = false,
                verificationSignal = "target_not_found",
            )
        }

        var attempts = 0
        var anyClicked = false
        var bestLabel = text

        for (candidate in candidates.take(3)) {
            bestLabel = candidate.label.ifBlank { text }
            attempts++

            // Attempt A: AccessibilityNodeInfo.ACTION_CLICK if node is clickable
            val clicked = withContext(Dispatchers.Main) {
                runCatching {
                    candidate.clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                }.getOrDefault(false)
            }
            if (clicked) anyClicked = true

            val checkA = pollForClickVerification(before, bestLabel)
            if (checkA.verified) {
                return ScreenResult(
                    success = true,
                    message = "Tapped \"$bestLabel\" (verified: ${checkA.signal}).",
                    verified = true,
                    verificationSignal = checkA.signal,
                    attempts = attempts,
                )
            }

            // Attempt B: Guaranteed physical touch coordinate gesture at candidate's center
            if (candidate.bounds.width() > 0 && candidate.bounds.height() > 0) {
                attempts++
                val gestureSent = jarvisService?.dispatchTapGesture(
                    x = candidate.bounds.exactCenterX(),
                    y = candidate.bounds.exactCenterY(),
                ) ?: false
                if (gestureSent) anyClicked = true

                val checkB = pollForClickVerification(before, bestLabel)
                if (checkB.verified) {
                    return ScreenResult(
                        success = true,
                        message = "Tapped \"$bestLabel\" via physical touch (verified: ${checkB.signal}).",
                        verified = true,
                        verificationSignal = checkB.signal,
                        attempts = attempts,
                    )
                }

                // Second physical touch, offset towards the upper part of the
                // target. YouTube and similar feeds often put a non-interactive
                // title node on top of the real thumbnail hit area.
                if (gestureSent && candidate.bounds.height() > 24) {
                    attempts++
                    jarvisService?.dispatchTapGesture(
                        x = candidate.bounds.exactCenterX(),
                        y = candidate.bounds.top + candidate.bounds.height() * 0.30f,
                    )
                    val checkC = pollForClickVerification(before, bestLabel)
                    if (checkC.verified) {
                        return ScreenResult(
                            success = true,
                            message = "Tapped \"$bestLabel\" via physical touch (verified: ${checkC.signal}).",
                            verified = true,
                            verificationSignal = checkC.signal,
                            attempts = attempts,
                        )
                    }
                }
            }
        }

        return if (anyClicked) {
            ScreenResult(
                success = true,
                message = "I tapped \"$bestLabel\", but the screen did not visibly change. I can read the screen and try again.",
                verified = false,
                verificationSignal = "touch_dispatched_unverified",
                attempts = attempts,
            )
        } else {
            ScreenResult(
                success = false,
                message = "I found \"$text\", but it did not respond to clicking or tapping.",
                verified = false,
                verificationSignal = "click_refused",
                attempts = attempts,
            )
        }
    }

    /**
     * Directly taps exact screen pixel coordinates ([x], [y]) via physical touch gesture.
     */
    suspend fun tapCoordinatesVerified(x: Int, y: Int): ScreenResult {
        if (x <= 0 || y <= 0) {
            return ScreenResult(false, "Invalid coordinates ($x, $y).", verified = false)
        }
        val before = withContext(Dispatchers.Main) { captureFingerprint() }
        val sent = jarvisService?.dispatchTapGesture(x.toFloat(), y.toFloat()) ?: false
        if (!sent) {
            return ScreenResult(false, "Could not send touch gesture to coordinates ($x, $y).", verified = false)
        }
        val outcome = pollForClickVerification(before, "coordinates ($x, $y)")
        return ScreenResult(
            success = true,
            message = if (outcome.verified) {
                "Tapped coordinates ($x, $y) (verified: ${outcome.signal})."
            } else {
                "Tapped coordinates ($x, $y), but I could not confirm a visible change."
            },
            verified = outcome.verified,
            verificationSignal = if (outcome.verified) outcome.signal else "coordinate_tap_dispatched_unverified",
        )
    }

    /**
     * Specifically discovers visible video cards / media entries on YouTube, feeds,
     * or browsers, and taps the video thumbnail/card at [index] (1-based: 1=first video).
     */
    suspend fun tapVideoItemVerified(index: Int = 1, query: String = ""): ScreenResult {
        var before = withContext(Dispatchers.Main) { captureFingerprint() }
        var cards = withContext(Dispatchers.Main) { findVisibleVideoCards(query) }

        // YouTube's home and search feeds animate in after the activity appears.
        // Wait for real cards before deciding that there is nothing to tap.
        if (cards.isEmpty()) {
            for (attempt in 0 until 4) {
                delay(700L)
                cards = withContext(Dispatchers.Main) { findVisibleVideoCards(query) }
                if (cards.isNotEmpty()) break
            }
        }

        if (cards.isEmpty()) {
            val fallback = withContext(Dispatchers.Main) { findBestYouTubeCandidate(query) }
            if (fallback != null && fallback.bounds.width() > 0 && fallback.bounds.height() > 0) {
                val clicked = withContext(Dispatchers.Main) {
                    runCatching {
                        fallback.clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrNull() ?: false
                }
                val sent = jarvisService?.dispatchTapGesture(
                    x = fallback.bounds.exactCenterX(),
                    y = fallback.bounds.top + fallback.bounds.height() * 0.34f,
                ) ?: false
                val outcome = pollForClickVerification(before, fallback.label)
                val playing = if (outcome.verified) awaitPlayback() else false
                return ScreenResult(
                    success = clicked || sent || outcome.verified,
                    message = if (outcome.verified) {
                        "Tapped \"${fallback.label.take(60)}\" and confirmed the player responded."
                    } else {
                        "Tapped \"${fallback.label.take(60)}\", but I could not confirm that playback started."
                    },
                    verified = outcome.verified || playing,
                    verificationSignal = if (outcome.verified || playing) {
                        "youtube_candidate_verified"
                    } else {
                        "youtube_candidate_tap_unverified"
                    },
                )
            }
            return ScreenResult(
                success = false,
                message = "I could not find a video card on this screen. Tell me to scroll down and try again.",
                verified = false,
                verificationSignal = "no_video_cards",
            )
        }

        val targetIdx = (index - 1).coerceIn(0, cards.size - 1)
        val card = cards[targetIdx]

        // The thumbnail is the reliable hit target. Titles and description rows
        // are often non-clickable overlay nodes on top of it.
        val targets = listOf(
            card.bounds.exactCenterX() to (card.bounds.top + card.bounds.height() * 0.30f),
            card.bounds.exactCenterX() to (card.bounds.top + card.bounds.height() * 0.55f),
            card.bounds.exactCenterX() to card.bounds.exactCenterY(),
        )

        var attempts = 0
        var anyTouch = false
        var lastOutcome = VerificationOutcome(false, "not_attempted")

        for ((tapX, tapY) in targets) {
            attempts++
            before = withContext(Dispatchers.Main) { captureFingerprint() }
            if (attempts == 1) {
                withContext(Dispatchers.Main) {
                    runCatching { card.clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                }
            }
            val sent = jarvisService?.dispatchTapGesture(tapX, tapY) ?: false
            if (sent) anyTouch = true
            lastOutcome = pollForClickVerification(before, card.label.ifBlank { "video" })
            if (lastOutcome.verified) break
            delay(280L)
        }

        val state = withContext(Dispatchers.Main) { captureFingerprint() }
        val playing = if (lastOutcome.verified) awaitPlayback() else false
        val playerOpen = state.hasYouTubePlayer || state.hasPauseControl

        return if (lastOutcome.verified || playing || playerOpen) {
            ScreenResult(
                success = true,
                message = "Opened video #${targetIdx + 1} \"${card.label.take(60)}\"" +
                    if (playing || state.isAudioPlaying || state.hasPauseControl) {
                        " and playback is active."
                    } else {
                        "."
                    },
                verified = playing || state.isAudioPlaying || state.hasPauseControl,
                verificationSignal = when {
                    playing || state.isAudioPlaying -> "audio_playback_started"
                    state.hasPauseControl -> "pause_control_visible"
                    else -> lastOutcome.signal
                },
                attempts = attempts,
            )
        } else if (anyTouch) {
            ScreenResult(
                success = true,
                message = "I tapped video #${targetIdx + 1} \"${card.label.take(60)}\", but the screen did not visibly change. I can try again or scroll first.",
                verified = false,
                verificationSignal = "video_touch_unverified",
                attempts = attempts,
            )
        } else {
            ScreenResult(
                success = false,
                message = "I found the video card but Android refused every touch gesture. Check that Jarvis accessibility is still enabled.",
                verified = false,
                verificationSignal = "gesture_refused",
                attempts = attempts,
            )
        }
    }

    /**
     * Synchronous single-tap method preserved for direct main-thread callers,
     * automatically falling back to physical coordinate touch gestures when nodes
     * are not marked clickable.
     */
    fun tap(text: String): ScreenResult {
        val wanted = normalize(text)
        if (wanted.isBlank()) return ScreenResult(false, "Tell me what to tap.", verified = false)
        val candidates = findTapCandidates(wanted)
        if (candidates.isEmpty()) {
            return ScreenResult(false, "I could not find \"$text\" on this screen.", verified = false)
        }
        for (candidate in candidates) {
            val clickable = candidate.clickableNode
            val performed = if (clickable != null) {
                runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            } else false
            if (performed) {
                val label = candidate.label.ifBlank { text }
                return ScreenResult(true, "Tapped \"$label\".")
            }
            // Fall back to coordinate touch gesture
            if (candidate.bounds.width() > 0 && candidate.bounds.height() > 0) {
                jarvisService?.let { s ->
                    kotlinx.coroutines.runBlocking {
                        s.dispatchTapGesture(candidate.bounds.exactCenterX(), candidate.bounds.exactCenterY())
                    }
                    val label = candidate.label.ifBlank { text }
                    return ScreenResult(true, "Tapped \"$label\" via touch.")
                }
            }
        }
        return ScreenResult(false, "I found \"$text\", but it is not something I can tap.", verified = false)
    }

    /**
     * Finds candidate targets matching [wanted], recognizing semantic queries,
     * element indexes (#1, #2), video items, and partial word matches.
     */
    fun findTapCandidates(wanted: String): List<CandidateTarget> {
        val root = service.rootInActiveWindow ?: return emptyList()

        // 1. Semantic video / card match
        val isVideoIntent = wanted == "video" || wanted.contains("video") || wanted.contains("watch") ||
            wanted == "play" || wanted.startsWith("first video") || wanted.startsWith("play video")
        if (isVideoIntent) {
            val filter = wanted.replace("first video", "").replace("video", "").replace("play", "").trim()
            val videoCards = findVisibleVideoCards(filter)
            if (videoCards.isNotEmpty()) {
                val idx = when {
                    wanted.contains("second") || wanted.contains("2") -> 1
                    wanted.contains("third") || wanted.contains("3") -> 2
                    else -> 0
                }
                val chosen = videoCards.getOrElse(idx) { videoCards.first() }
                return listOf(chosen.copy(score = 250)) + videoCards.filter { it != chosen }
            }
        }

        // 2. Element index match e.g. "#1", "#2", "item 1", "item 2"
        val indexMatch = Regex("""(?:#|item\s*)(\d+)""").find(wanted)
        val targetItemNum = indexMatch?.groupValues?.get(1)?.toIntOrNull()

        val rawMatches = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val queryWords = wanted.split(' ').filter { it.length >= 3 }
        walked = 0
        var itemCounter = 0

        walk(root) { node ->
            val label = normalize(
                node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty(),
            )
            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }
            val hasBounds = bounds.width() > 0 && bounds.height() > 0

            if (label.isNotBlank() && hasBounds) {
                itemCounter++
                var score = when {
                    label == wanted -> 100
                    label.startsWith(wanted) -> 85
                    label.contains(wanted) -> 70
                    wanted.contains(label) && label.length >= 3 -> 50
                    else -> 0
                }
                // Partial multi-word matching for long video titles or descriptions
                if (score == 0 && queryWords.isNotEmpty()) {
                    val matchedWords = queryWords.count { label.contains(it) }
                    if (matchedWords > 0) {
                        score = 40 + matchedWords * 20
                    }
                }
                // Index match
                if (targetItemNum != null && itemCounter == targetItemNum) {
                    score = 150
                }
                if (score > 0) rawMatches += score to node
            }
            rawMatches.size < MAX_CANDIDATES * 3
        }

        return rawMatches
            .sortedByDescending { it.first }
            .mapNotNull { (score, node) ->
                val clickable = clickableTarget(node)
                val bounds = Rect()
                runCatching { (clickable ?: node).getBoundsInScreen(bounds) }
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    null
                } else {
                    CandidateTarget(
                        score = score,
                        label = labelOf(node).ifBlank { labelOf(clickable ?: node) },
                        clickableNode = clickable,
                        bounds = bounds,
                    )
                }
            }
    }

    /**
     * Finds all visible video cards across the active hierarchy, sorting them
     * vertically from top to bottom.
     */
    fun findVisibleVideoCards(query: String = ""): List<CandidateTarget> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val cards = mutableListOf<CandidateTarget>()
        val queryWords = normalize(query).split(' ').filter { it.length >= 3 }
        walked = 0

        fun boundsOf(node: AccessibilityNodeInfo): Rect {
            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }
            return rect
        }

        walk(root) { node ->
            if (node.isEditable) return@walk true
            val label = (node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty()).trim()
            if (label.length < 4) return@walk true
            if (!isVideoCardNode(node)) return@walk true

            // A title or thumbnail node is often only 40-80 px tall. Walk up to
            // the card container so the tap lands on the thumbnail instead of a
            // one-line label that ignores touch.
            var chosen: AccessibilityNodeInfo? = null
            var parent = runCatching { node.parent }.getOrNull()
            var depth = 0
            while (parent != null && depth < 5) {
                val parentBounds = boundsOf(parent)
                if (parentBounds.width() >= 250 &&
                    parentBounds.height() in 180..900
                ) {
                    chosen = parent
                    break
                }
                parent = runCatching { parent?.parent }.getOrNull()
                depth++
            }
            val container = chosen ?: node
            val bounds = boundsOf(container)
            if (bounds.width() < 200 || bounds.height() < 80) return@walk true

            val norm = normalize("$label ${node.viewIdResourceName.orEmpty()}")
            var score = 60
            if (queryWords.isNotEmpty()) {
                val matched = queryWords.count { norm.contains(it) }
                score += matched * 30
            }
            cards += CandidateTarget(
                score = score,
                label = label.take(140),
                clickableNode = clickableTarget(node) ?: clickableTarget(container),
                bounds = bounds,
            )
            cards.size < 60
        }

        // The title, duration and thumbnail of one video often all qualify.
        // Collapse them into one card per vertical band, ordered top to bottom.
        return cards
            .sortedBy { it.bounds.top }
            .distinctBy { it.bounds.top / 90 }
    }

    private fun isVideoCardNode(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
        val combined = "$text $desc $viewId".lowercase(Locale.ROOT)

        if (viewId.contains("video_title") ||
            viewId.contains("thumbnail") ||
            viewId.contains("video_card") ||
            viewId.contains("watch_endpoint") ||
            viewId.contains("compact_video_item")
        ) {
            return true
        }
        if (combined.contains("views") && (combined.contains("ago") || combined.contains("streamed"))) {
            return true
        }
        if (Regex("""\b\d{1,2}:\d{2}\b""").containsMatchIn(combined) && (combined.contains("view") || combined.contains("play"))) {
            return true
        }
        if (combined.contains("play video") || combined.contains("video player")) {
            return true
        }
        return false
    }

    private suspend fun pollForClickVerification(
        before: UiStateFingerprint,
        targetLabel: String,
    ): VerificationOutcome {
        var latest = VerificationOutcome(false, "pending")
        repeat(VERIFY_POLL_ATTEMPTS + 2) {
            delay(VERIFY_POLL_INTERVAL_MS)
            val after = withContext(Dispatchers.Main) { captureFingerprint() }
            latest = StepVerificationEvaluator.evaluateClick(before, after, targetLabel)
            if (latest.verified) return latest
        }
        return latest
    }

    /**
     * Types into the focused or first editable field and verifies that the text
     * was accepted by the UI.
     */
    suspend fun typeVerified(text: String): ScreenResult {
        if (text.isEmpty()) return ScreenResult(false, "There is nothing to type.", verified = false)
        val before = withContext(Dispatchers.Main) { captureFingerprint() }
        val immediate = withContext(Dispatchers.Main) { type(text) }
        if (!immediate.success) return immediate.copy(verified = false)

        delay(VERIFY_POLL_INTERVAL_MS)
        val after = withContext(Dispatchers.Main) { captureFingerprint() }
        val verification = StepVerificationEvaluator.evaluateTextEntry(before, after, text)
        return immediate.copy(
            message = if (verification.verified) {
                "${immediate.message} (verified: ${verification.signal})"
            } else {
                immediate.message
            },
            verified = verification.verified,
            verificationSignal = verification.signal,
        )
    }

    /** Types into the focused field, or the first editable field on screen. */
    fun type(text: String): ScreenResult {
        if (text.isEmpty()) return ScreenResult(false, "There is nothing to type.", verified = false)
        val root = service.rootInActiveWindow
            ?: return ScreenResult(false, "I cannot see the screen yet.", verified = false)
        walked = 0
        val field = findFirst(root) { it.isEditable && it.isFocused }
            ?: run {
                walked = 0
                findFirst(root) { it.isEditable }
            }
            ?: return ScreenResult(false, "I could not find a text field on this screen.", verified = false)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)
        if (!ok) {
            runCatching { field.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            val retry = runCatching {
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
            if (!retry) return ScreenResult(false, "That field refused the text.", verified = false)
        }
        return ScreenResult(true, "Typed ${text.length} characters into the field.")
    }

    /**
     * Scrolls the current screen in [direction] ("down", "up", "left", "right") and
     * verifies that the scroll position or visible tree shifted.
     * Uses standard node ACTION_SCROLL when available and automatically falls back to
     * a physical touch swipe gesture injected directly into the window manager,
     * ensuring scrolling works on every Android app (YouTube feeds, Compose LazyColumn,
     * Flutter, WebViews, games, settings).
     */
    suspend fun scrollDirectionVerified(
        direction: String = "down",
        distance: String = "medium",
    ): ScreenResult {
        val before = withContext(Dispatchers.Main) { captureFingerprint() }
        val normDir = direction.lowercase().trim()
        val isForward = normDir != "up" && normDir != "right"
        val isHorizontal = normDir == "left" || normDir == "right"

        var nodeActionSucceeded = false
        val root = service.rootInActiveWindow

        if (root != null && !isHorizontal) {
            walked = 0
            val scrollable = findFirst(root) { node ->
                if (!node.isScrollable) return@findFirst false
                val b = Rect()
                runCatching { node.getBoundsInScreen(b) }
                // Avoid narrow horizontal chip bars (e.g. YouTube All/Gaming/Music chips)
                b.height() >= 280 || b.height() >= b.width()
            }
            if (scrollable != null) {
                val action = if (isForward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                nodeActionSucceeded = withContext(Dispatchers.Main) {
                    runCatching { scrollable.performAction(action) }.getOrDefault(false)
                }
            }
        }

        // Always perform a physical swipe too. Many feeds report ACTION_SCROLL
        // as accepted without moving at all, which made the old code claim a
        // successful scroll while the screen stayed perfectly still.
        var swipeSent = false
        run {
            val (width, height) = jarvisService?.getScreenDimensions() ?: (1080 to 2340)
            val strokeDuration = 280L

            val (startX, startY, endX, endY) = when (normDir) {
                "up" -> {
                    // Swipe downwards to scroll up
                    val mult = if (distance == "long") 0.60f else if (distance == "short") 0.20f else 0.45f
                    val top = height * 0.25f
                    val bottom = top + height * mult
                    listOf(width * 0.5f, top, width * 0.5f, bottom)
                }
                "left" -> {
                    // Swipe right to left
                    val mult = if (distance == "long") 0.70f else if (distance == "short") 0.25f else 0.50f
                    val right = width * 0.85f
                    val left = right - width * mult
                    listOf(right, height * 0.5f, left, height * 0.5f)
                }
                "right" -> {
                    // Swipe left to right
                    val mult = if (distance == "long") 0.70f else if (distance == "short") 0.25f else 0.50f
                    val left = width * 0.15f
                    val right = left + width * mult
                    listOf(left, height * 0.5f, right, height * 0.5f)
                }
                else -> {
                    // Swipe upwards to scroll down. Stay clear of the bottom
                    // system gesture strip, which otherwise swallows the swipe.
                    val mult = if (distance == "long") 0.55f else if (distance == "short") 0.16f else 0.40f
                    val bottom = height * 0.68f
                    val top = bottom - height * mult
                    listOf(width * 0.45f, bottom, width * 0.45f, top)
                }
            }

            swipeSent = jarvisService?.dispatchSwipeGesture(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = strokeDuration,
            ) ?: false
        }

        delay(VERIFY_POLL_INTERVAL_MS * 2)
        val after = withContext(Dispatchers.Main) { captureFingerprint() }
        var verification = StepVerificationEvaluator.evaluateScroll(before, after)

        // A second, longer swipe rescues stubborn feeds and media carousels.
        if (!verification.verified && !isHorizontal) {
            val (width, height) = jarvisService?.getScreenDimensions() ?: (1080 to 2340)
            val retryStart = if (isForward) height * 0.70f else height * 0.28f
            val retryEnd = if (isForward) height * 0.24f else height * 0.74f
            val retrySent = jarvisService?.dispatchSwipeGesture(
                startX = width * 0.45f,
                startY = retryStart,
                endX = width * 0.45f,
                endY = retryEnd,
                durationMs = 340L,
            ) ?: false
            swipeSent = swipeSent || retrySent
            if (retrySent) {
                delay(VERIFY_POLL_INTERVAL_MS * 2)
                val retryAfter = withContext(Dispatchers.Main) { captureFingerprint() }
                verification = StepVerificationEvaluator.evaluateScroll(before, retryAfter)
            }
        }

        val dirLabel = when (normDir) {
            "up" -> "up"
            "left" -> "left"
            "right" -> "right"
            else -> "down"
        }

        return if (verification.verified) {
            ScreenResult(
                success = true,
                message = "Scrolled $dirLabel (verified: ${verification.signal}).",
                verified = true,
                verificationSignal = verification.signal,
            )
        } else if (nodeActionSucceeded || swipeSent) {
            ScreenResult(
                success = true,
                message = "I swiped $dirLabel, but I could not confirm that the screen moved. I can try a longer scroll.",
                verified = false,
                verificationSignal = "swipe_dispatched_unverified",
            )
        } else {
            ScreenResult(
                success = false,
                message = "Could not scroll $dirLabel on this screen.",
                verified = false,
                verificationSignal = "scroll_failed",
            )
        }
    }

    /**
     * Scrolls the current screen vertically and verifies that the scroll position or visible
     * node tree updated.
     */
    suspend fun scrollVerified(forward: Boolean): ScreenResult =
        scrollDirectionVerified(direction = if (forward) "down" else "up")

    fun scroll(forward: Boolean): ScreenResult {
        val root = service.rootInActiveWindow
            ?: return ScreenResult(false, "I cannot see the screen yet.", verified = false)
        walked = 0
        val scrollable = findFirst(root) { node ->
            if (!node.isScrollable) return@findFirst false
            val b = Rect()
            runCatching { node.getBoundsInScreen(b) }
            b.height() >= 280 || b.height() >= b.width()
        }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = runCatching { scrollable?.performAction(action) ?: false }.getOrDefault(false)
        if (ok) {
            return ScreenResult(true, if (forward) "Scrolled down." else "Scrolled up.")
        }
        // Fall back to physical touch swipe
        val (width, height) = jarvisService?.getScreenDimensions() ?: (1080 to 2340)
        val startX = width * 0.5f
        val startY = if (forward) height * 0.75f else height * 0.25f
        val endY = if (forward) height * 0.25f else height * 0.75f
        jarvisService?.let { s ->
            kotlinx.coroutines.runBlocking {
                s.dispatchSwipeGesture(startX, startY, startX, endY, 280L)
            }
            return ScreenResult(true, if (forward) "Scrolled down via screen swipe." else "Scrolled up via screen swipe.")
        }
        return ScreenResult(false, "Could not scroll this screen.", verified = false)
    }

    fun press(key: String): ScreenResult {
        val action = when (key.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification shade" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot", "take screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return ScreenResult(
                false,
                "I do not know that button. Try back, home, recents, notifications, quick settings or screenshot.",
                verified = false,
            )
        }
        val ok = runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        return if (ok) {
            ScreenResult(true, "Pressed $key.")
        } else {
            ScreenResult(false, "Android refused that button.", verified = false)
        }
    }

    /** Finds a visible play/pause control by its label or view id. */
    private fun findPlayerControl(label: String): CandidateTarget? {
        val root = service.rootInActiveWindow ?: return null
        val wanted = normalize(label)
        var result: CandidateTarget? = null
        walked = 0
        walk(root) { node ->
            if (result == null && !node.isEditable) {
                val haystack = normalize(
                    "${node.contentDescription?.toString().orEmpty()} ${node.text?.toString().orEmpty()} " +
                        node.viewIdResourceName.orEmpty(),
                )
                if (haystack.contains(wanted)) {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        result = CandidateTarget(
                            score = 100,
                            label = label,
                            clickableNode = clickableTarget(node) ?: node,
                            bounds = bounds,
                        )
                        return@walk false
                    }
                }
            }
            true
        }
        return result
    }

    /**
     * Confirms that audio actually started, and taps the player's play control
     * once if it opened paused. Audio mutations are the strongest evidence that
     * a video tap really worked.
     */
    private suspend fun awaitPlayback(timeoutMs: Long = 3_400L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var tappedPlay = false
        while (System.currentTimeMillis() < deadline) {
            val state = withContext(Dispatchers.Main) { captureFingerprint() }
            if (state.isAudioPlaying || state.hasPauseControl) return true
            if (!tappedPlay && state.hasPlayControl) {
                tappedPlay = true
                tapVerified("play video")
            }
            delay(320L)
        }
        val finalState = withContext(Dispatchers.Main) { captureFingerprint() }
        return finalState.isAudioPlaying || finalState.hasPauseControl
    }

    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) return node
        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable && parent.isEnabled) return parent
            parent = runCatching { parent?.parent }.getOrNull()
            depth++
        }
        return null
    }

    private fun findFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found == null && predicate(node)) {
                found = node
                false
            } else {
                true
            }
        }
        return found
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString().orEmpty()

    private fun describe(node: AccessibilityNodeInfo, index: Int = 0): String {
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isBlank() && description.isBlank()) return ""
        if (node.isPassword) return "- [password field]"
        val label = if (text.isNotBlank()) text else description
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val isVideo = isVideoCardNode(node)
        val prefix = if (index > 0) "#$index: " else ""
        val suffix = buildString {
            if (isVideo) append(" [video card]")
            if (node.isEditable) append(" [editable]")
            if (node.isClickable) append(" [button]")
            if (node.isScrollable) append(" [scrollable]")
            if (node.isCheckable) append(if (node.isChecked) " [checked]" else " [unchecked]")
            if (bounds.width() > 0 && bounds.height() > 0) {
                append(" (${bounds.centerX()}, ${bounds.centerY()})")
            }
        }
        return "- $prefix$label$suffix".take(320)
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        visitor: (AccessibilityNodeInfo) -> Boolean,
    ) {
        if (depth > MAX_DEPTH || walked >= MAX_NODES) return
        walked++
        if (!visitor(node)) return
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            walk(child, depth + 1, visitor)
            if (walked >= MAX_NODES) return
        }
    }

    private companion object {
        const val MAX_NODES = 160
        const val MAX_DEPTH = 14
        const val MAX_CANDIDATES = 6
        const val VERIFY_POLL_ATTEMPTS = 7
        const val VERIFY_POLL_INTERVAL_MS = 320L
    }
}
