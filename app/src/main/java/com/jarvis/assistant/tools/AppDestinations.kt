package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.accessibility.ActionableNodeSnapshot
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenController
import kotlinx.coroutines.delay
import java.util.Locale

data class AppDestination(
    val appName: String,
    val section: String,
)

class AppDestinationRunner(
    private val destination: AppDestination,
) {
    suspend fun run(controller: ScreenController): ScreenResult {
        val currentPkg = controller.foregroundPackage()
        val currentLabel = controller.appLabel(currentPkg).ifBlank { destination.appName }
        return AppDestinationOpener.navigateInForegroundApp(
            controller = controller,
            appLabel = currentLabel,
            sectionQuery = destination.section,
        )
    }
}

object AppDestinations {
    fun findDestination(appName: String, sectionQuery: String): AppDestination? {
        val cleanApp = appName.trim()
        val cleanSec = sectionQuery.trim()
        if (cleanApp.isBlank() || cleanSec.isBlank()) return null
        return AppDestination(appName = cleanApp, section = cleanSec)
    }

    fun planFor(destination: AppDestination): AppDestinationRunner =
        AppDestinationRunner(destination)
}

/**
 * Opens a *specific place* inside an app and verifies it arrived there.
 *
 * Launches the app, reads the accessibility tree, matches the exact destination
 * label or semantic profile tab, taps only that node, and verifies the resulting screen.
 * Never drifts into YouTube Shorts or Reels unless explicitly requested.
 */
object AppDestinationOpener {

    private data class Step(
        val labels: List<String>,
        val viewIdHints: List<String> = emptyList(),
        val preferBottomRightTab: Boolean = false,
        val optional: Boolean = false,
    )

    private data class Plan(
        val title: String,
        val steps: List<Step>,
        val verifyAny: List<String> = emptyList(),
    )

    private data class InstalledApp(val label: String, val packageName: String)

    suspend fun open(context: Context, appQuery: String, sectionQuery: String): ToolResult {
        val packageManager = context.packageManager
        val installed = packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
            .mapNotNull { info ->
                val label = runCatching { info.loadLabel(packageManager).toString() }
                    .getOrNull()?.trim().orEmpty()
                val pkg = info.activityInfo?.packageName.orEmpty()
                if (label.isBlank() || pkg.isBlank()) null else InstalledApp(label, pkg)
            }
            .distinctBy { it.packageName }

        val best = installed
            .map { it to (matchScore(appQuery, it.label) + matchScore(appQuery, it.packageName)) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
            ?: return ToolResult(false, "I could not find an installed app called \"$appQuery\".")

        val launch = packageManager.getLaunchIntentForPackage(best.packageName)
            ?: return ToolResult(false, "${best.label} has no launchable screen.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(launch) }.isSuccess
        if (!launched) {
            return ToolResult(false, "Android refused to open ${best.label}.")
        }

        val plan = planFor(best.label, sectionQuery)
        if (plan.steps.isEmpty()) {
            return ToolResult(true, "Opened ${best.label}.")
        }

        val controller = JarvisAccessibilityService.controller()
            ?: return ToolResult(
                true,
                "Opened ${best.label}. Accessibility is off, so I cannot verify or navigate inside it. " +
                    "Enable Jarvis in Android Accessibility settings for precise in-app navigation.",
            )

        controller.awaitWindowState(best.packageName, timeoutMs = 4_500L)
        delay(850L)

        val navResult = navigateInForegroundApp(
            controller = controller,
            appLabel = best.label,
            sectionQuery = sectionQuery,
        )
        return ToolResult(navResult.success, navResult.message)
    }

    suspend fun navigateInForegroundApp(
        controller: ScreenController,
        appLabel: String,
        sectionQuery: String,
    ): ScreenResult {
        val plan = planFor(appLabel, sectionQuery)
        if (plan.steps.isEmpty()) {
            return ScreenResult(true, "Opened $appLabel.", verified = true)
        }

        for ((index, step) in plan.steps.withIndex()) {
            var match = findDestination(controller.snapshotActionableNodes(), step, sectionQuery)
            if (match != null) {
                val result = controller.executeRefAction(
                    tempRef = match.tempRef,
                    actionType = "click",
                    userGoal = sectionQuery,
                )
                if (result.success) {
                    delay(750L)
                    continue
                }
            }

            // Fallback to ScreenController.tapVerified which walks all 480 nodes, aggregates child
            // labels on bottom-bar tabs, and includes spatial bottom-right profile tab fallback
            var tappedViaController = false
            for (label in step.labels) {
                val tapRes = controller.tapVerified(label)
                if (tapRes.success) {
                    tappedViaController = true
                    delay(750L)
                    break
                }
            }
            if (tappedViaController) continue

            if (index == 0 && plan.steps.size == 1) {
                repeat(2) {
                    controller.scroll(forward = true)
                    delay(450L)
                    if (match == null) {
                        match = findDestination(controller.snapshotActionableNodes(), step, sectionQuery)
                    }
                }
                if (match != null) {
                    val retryRes = controller.executeRefAction(
                        tempRef = match!!.tempRef,
                        actionType = "click",
                        userGoal = sectionQuery,
                    )
                    if (retryRes.success) {
                        delay(750L)
                        continue
                    }
                }
            }

            if (step.optional) break
            return ScreenResult(
                false,
                "I opened $appLabel, but I could not find \"${step.labels.first()}\" on screen. " +
                    "I stopped rather than tapping a random place.",
                verified = false,
            )
        }

        val visible = controller.snapshotActionableNodes()
            .joinToString(" ") { "${it.text} ${it.contentDescription} ${it.viewId}" }
            .lowercase(Locale.ROOT)

        // If we accidentally landed on Shorts when Shorts was not requested, back out immediately
        if ((visible.contains("reel_player") || visible.contains("shorts_container")) &&
            !sectionQuery.contains("shorts", ignoreCase = true)
        ) {
            controller.press("back")
            delay(400L)
            val fallbackTap = controller.tapVerified("profile")
            if (fallbackTap.success) {
                delay(650L)
                return ScreenResult(true, "Opened $appLabel · ${plan.title}.", verified = true)
            }
            return ScreenResult(
                false,
                "I opened $appLabel, avoided Shorts, and stopped safely because \"${plan.title}\" was not confirmed.",
                verified = false,
            )
        }

        val verified = plan.verifyAny.isEmpty() || plan.verifyAny.any { visible.contains(it) }
        return ScreenResult(
            success = true,
            message = "Opened $appLabel · ${plan.title}.",
            verified = verified,
        )
    }

    private fun findDestination(
        nodes: List<ActionableNodeSnapshot>,
        step: Step,
        goal: String,
    ): ActionableNodeSnapshot? {
        val wantsShorts = goal.contains("shorts", ignoreCase = true) || goal.contains("reel", ignoreCase = true)
        val candidates = nodes.filter { node ->
            val haystack = "${node.text} ${node.contentDescription} ${node.viewId}".lowercase(Locale.ROOT)
            if (!wantsShorts && (Regex("\\bshorts?\\b").containsMatchIn(haystack) || haystack.contains("reel_pivot") || haystack.contains("create"))) {
                return@filter false
            }
            // Reject full-width container bars when targeting a navigation tab
            if (step.preferBottomRightTab && node.bounds.width() > 480) {
                return@filter false
            }
            // When targeting the bottom-right or top-right profile/You tab, reject mid-screen video feed items
            if (step.preferBottomRightTab) {
                val cy = node.bounds.centerY()
                val cx = node.bounds.centerX()
                val isBottomRight = cy >= 1450 && cx >= 650
                val isTopRight = cy <= 360 && cx >= 680
                if (!isBottomRight && !isTopRight) {
                    return@filter false
                }
            }
            val text = node.text.lowercase(Locale.ROOT).trim()
            val description = node.contentDescription.lowercase(Locale.ROOT).trim()
            val viewId = node.viewId.lowercase(Locale.ROOT)
            val labelHit = step.labels.any { raw ->
                val label = raw.lowercase(Locale.ROOT)
                if (label == "you") {
                    text == "you" || description == "you" ||
                        description.startsWith("you,") || description.startsWith("you tab")
                } else {
                    text == label || description == label ||
                        Regex("\\b${Regex.escape(label)}\\b").containsMatchIn(text) ||
                        Regex("\\b${Regex.escape(label)}\\b").containsMatchIn(description)
                }
            }
            val idHit = step.viewIdHints.any { hint ->
                viewId.contains(hint.lowercase(Locale.ROOT))
            }
            labelHit || idHit
        }

        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { node ->
            val labelScore = step.labels.maxOfOrNull { label ->
                matchScore(label, "${node.text} ${node.contentDescription}")
            } ?: 0
            val idBonus = if (step.viewIdHints.any { node.viewId.contains(it, ignoreCase = true) }) 35 else 0
            val positionBonus = if (step.preferBottomRightTab && node.bounds.centerY() > 1550 && node.bounds.centerX() > 720) {
                85
            } else if (step.preferBottomRightTab && node.bounds.centerY() < 340 && node.bounds.centerX() > 720) {
                55
            } else {
                0
            }
            labelScore + idBonus + positionBonus
        }
    }

    private fun planFor(appLabel: String, rawSection: String): Plan {
        val section = rawSection.trim().lowercase(Locale.ROOT)
        val youtube = appLabel.contains("youtube", ignoreCase = true)

        if (youtube) {
            return when {
                section in setOf("profile", "my profile", "account", "my account", "channel", "my channel", "your channel", "you") ->
                    Plan(
                        title = "Your profile",
                        steps = listOf(
                            Step(
                                labels = listOf("you", "profile", "account", "library", "your channel"),
                                viewIdHints = listOf("tab_you", "pivot_you", "pivot_library", "account", "avatar"),
                                preferBottomRightTab = true,
                            ),
                            Step(
                                labels = listOf("view channel", "your channel", "your profile"),
                                optional = true,
                            ),
                        ),
                        verifyAny = listOf(
                            "customize channel", "your videos", "history", "playlists",
                            "subscribers", "your channel", "downloads", "view channel", "switch account", "google account",
                        ),
                    )

                section.startsWith("subscription") -> Plan(
                    "Subscriptions",
                    listOf(Step(listOf("subscriptions"), preferBottomRightTab = false)),
                    listOf("subscriptions", "latest", "today", "all"),
                )

                section in setOf("history", "watch history") -> Plan(
                    "Watch history",
                    listOf(
                        Step(
                            labels = listOf("you", "profile", "account", "library"),
                            viewIdHints = listOf("tab_you", "pivot_you", "pivot_library", "avatar"),
                            preferBottomRightTab = true,
                        ),
                        Step(listOf("history", "watch history", "view all")),
                    ),
                    listOf("history", "today", "yesterday", "watch history"),
                )

                section in setOf("liked", "liked videos") -> Plan(
                    "Liked videos",
                    listOf(
                        Step(
                            labels = listOf("you", "profile", "account", "library"),
                            viewIdHints = listOf("tab_you", "pivot_you", "pivot_library", "avatar"),
                            preferBottomRightTab = true,
                        ),
                        Step(listOf("liked videos", "liked", "playlists")),
                    ),
                    listOf("liked videos", "liked"),
                )

                section in setOf("watch later") -> Plan(
                    "Watch later",
                    listOf(
                        Step(
                            labels = listOf("you", "profile", "account", "library"),
                            viewIdHints = listOf("tab_you", "pivot_you", "pivot_library", "avatar"),
                            preferBottomRightTab = true,
                        ),
                        Step(listOf("watch later")),
                    ),
                    listOf("watch later"),
                )

                section in setOf("downloads", "download") -> Plan(
                    "Downloads",
                    listOf(
                        Step(
                            labels = listOf("you", "profile", "account", "library"),
                            viewIdHints = listOf("tab_you", "pivot_you", "pivot_library", "avatar"),
                            preferBottomRightTab = true,
                        ),
                        Step(listOf("downloads", "your downloads")),
                    ),
                    listOf("downloads", "smart downloads"),
                )

                section in setOf("library", "playlists") -> Plan(
                    "Library",
                    listOf(
                        Step(
                            labels = listOf("you", "library", "profile", "account"),
                            viewIdHints = listOf("tab_you", "pivot_you", "pivot_library", "avatar"),
                            preferBottomRightTab = true,
                        ),
                    ),
                    listOf("library", "history", "playlists", "your videos"),
                )

                section.isBlank() || section in setOf("home", "main") -> Plan("home", emptyList())

                else -> Plan(
                    title = rawSection,
                    steps = listOf(Step(listOf(section))),
                )
            }
        }

        if (section in setOf("profile", "my profile", "account", "my account", "me", "you")) {
            return Plan(
                title = "Profile",
                steps = listOf(
                    Step(
                        labels = listOf("profile", "you", "me", "account"),
                        viewIdHints = listOf("profile_tab", "tab_avatar", "avatar", "account"),
                        preferBottomRightTab = true,
                    ),
                ),
            )
        }

        if (section.isBlank()) return Plan("home", emptyList())

        return Plan(
            title = rawSection,
            steps = listOf(Step(listOf(section))),
        )
    }
}

