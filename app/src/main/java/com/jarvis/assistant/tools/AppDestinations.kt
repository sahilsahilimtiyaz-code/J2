package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.accessibility.ActionableNodeSnapshot
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Opens a *specific place* inside an app and verifies it arrived there.
 *
 * This is deliberately not a coordinate tapper. It launches the app, reads the
 * accessibility tree, matches the exact destination label, taps only that node,
 * and checks the resulting screen. If the screen is wrong it stops and says so
 * instead of drifting into a random tab such as YouTube Shorts.
 */
object AppDestinationOpener {

    private data class Step(
        val labels: List<String>,
        val viewIdHints: List<String> = emptyList(),
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
        delay(700L)

        for ((index, step) in plan.steps.withIndex()) {
            var match = findDestination(controller.snapshotActionableNodes(), step, sectionQuery)
            if (match == null && index == 0 && plan.steps.size == 1) {
                repeat(2) {
                    controller.scroll(forward = true)
                    delay(450L)
                    if (match == null) {
                        match = findDestination(controller.snapshotActionableNodes(), step, sectionQuery)
                    }
                }
            }
            if (match == null) {
                if (step.optional) break
                return ToolResult(
                    false,
                    "I opened ${best.label}, but I could not find \"${step.labels.first()}\" on screen. " +
                        "I stopped rather than tapping a random place.",
                )
            }
            val result = controller.executeRefAction(
                tempRef = match.tempRef,
                actionType = "click",
                userGoal = sectionQuery,
            )
            if (!result.success) {
                return ToolResult(false, "I found ${step.labels.first()} in ${best.label} but Android did not accept the tap.")
            }
            delay(750L)
        }

        val visible = controller.snapshotActionableNodes()
            .joinToString(" ") { "${it.text} ${it.contentDescription} ${it.viewId}" }
            .lowercase(Locale.ROOT)
        val verified = plan.verifyAny.isEmpty() || plan.verifyAny.any { visible.contains(it) }
        if (verified) {
            return ToolResult(true, "Opened ${best.label} · ${plan.title}.")
        }

        // If we accidentally landed on Shorts, back out and never claim success.
        if (visible.contains("shorts") && !sectionQuery.contains("shorts", ignoreCase = true)) {
            controller.press("back")
            delay(350L)
        }
        return ToolResult(
            false,
            "I opened ${best.label}, but the screen does not match \"${plan.title}\", so I stopped. " +
                "Tell me the exact screen name and I will try again.",
        )
    }

    private fun findDestination(
        nodes: List<ActionableNodeSnapshot>,
        step: Step,
        goal: String,
    ): ActionableNodeSnapshot? {
        val wantsShorts = goal.contains("shorts", ignoreCase = true)
        return nodes
            .filter { node ->
                val haystack = "${node.text} ${node.contentDescription}".lowercase(Locale.ROOT)
                if (!wantsShorts && haystack.contains("shorts")) {
                    return@filter false
                }
                val text = node.text.lowercase(Locale.ROOT).trim()
                val description = node.contentDescription.lowercase(Locale.ROOT).trim()
                val viewId = node.viewId.lowercase(Locale.ROOT)
                val labelHit = step.labels.any { raw ->
                    val label = raw.lowercase(Locale.ROOT)
                    text == label || description == label ||
                        text.startsWith(label) || description.startsWith(label)
                }
                val idHit = step.viewIdHints.any { hint ->
                    viewId.contains(hint.lowercase(Locale.ROOT))
                }
                labelHit || idHit
            }
            .maxByOrNull { node ->
                step.labels.maxOf { label ->
                    matchScore(label, "${node.text} ${node.contentDescription}")
                }
            }
    }

    private fun planFor(appLabel: String, rawSection: String): Plan {
        val section = rawSection.trim().lowercase(Locale.ROOT)
        val youtube = appLabel.contains("youtube", ignoreCase = true)

        if (youtube) {
            return when {
                section in setOf("profile", "my profile", "account", "my account", "channel", "my channel", "you") ->
                    Plan(
                        title = "Your profile",
                        steps = listOf(
                            Step(
                                labels = listOf("you", "account", "profile"),
                                viewIdHints = listOf("account", "avatar", "bottom_bar"),
                            ),
                            Step(
                                labels = listOf("view channel", "your channel", "your profile"),
                                optional = true,
                            ),
                        ),
                        verifyAny = listOf(
                            "customize channel", "your videos", "history", "playlists",
                            "subscribers", "your channel", "downloads",
                        ),
                    )

                section.startsWith("subscription") -> Plan(
                    "Subscriptions",
                    listOf(Step(listOf("subscriptions"))),
                    listOf("subscriptions", "latest", "today"),
                )

                section in setOf("history", "watch history") -> Plan(
                    "Watch history",
                    listOf(Step(listOf("you", "account")), Step(listOf("history", "watch history"))),
                    listOf("history", "today", "yesterday"),
                )

                section in setOf("liked", "liked videos") -> Plan(
                    "Liked videos",
                    listOf(Step(listOf("you", "account")), Step(listOf("liked videos", "liked"))),
                    listOf("liked videos", "liked"),
                )

                section in setOf("watch later") -> Plan(
                    "Watch later",
                    listOf(Step(listOf("you", "account")), Step(listOf("watch later"))),
                    listOf("watch later"),
                )

                section in setOf("downloads", "download") -> Plan(
                    "Downloads",
                    listOf(Step(listOf("you", "account")), Step(listOf("downloads"))),
                    listOf("downloads", "smart downloads"),
                )

                section in setOf("library") -> Plan(
                    "Library",
                    listOf(Step(listOf("you", "account")), Step(listOf("library"))),
                    listOf("library", "history", "playlists"),
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
                        viewIdHints = listOf("profile", "avatar", "account", "tab"),
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
