package com.jarvis.assistant.tools

import android.content.Intent
import com.jarvis.assistant.brain.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenAppTool : Tool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "open_app",
        description = "Open an installed app, optionally on one exact screen inside it. " +
            "Use section for destinations such as 'profile', 'subscriptions', 'history', " +
            "'liked videos', 'watch later', 'downloads', 'library' or 'account'. " +
            "The destination is matched in the app's accessibility tree and verified, " +
            "never guessed with a coordinate tap.",
        parameters = objectSchema(
            required = listOf("app"),
            properties = mapOf(
                "app" to stringProperty("The app name as the owner would say it."),
                "section" to stringProperty(
                    "Optional exact screen inside the app, e.g. 'profile', 'subscriptions', " +
                        "'history', 'liked videos', 'watch later' or 'account'. Leave empty to just open the app.",
                ),
            ),
        ),
    )

    override val label = "Open app"
    override val category = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("app")
            ?: return ToolResult(false, "Tell me which app to open.")
        val section = arguments.argString("section").orEmpty()
        if (section.isNotBlank()) {
            return AppDestinationOpener.open(context.appContext, query, section)
        }

        return withContext(Dispatchers.IO) {
            val packageManager = context.appContext.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installed = packageManager
                .queryIntentActivities(launcherIntent, 0)
                .mapNotNull { info ->
                    val label = runCatching { info.loadLabel(packageManager).toString() }
                        .getOrNull()
                        ?.trim()
                        .orEmpty()
                    if (label.isBlank()) {
                        null
                    } else {
                        InstalledApp(
                            label = label,
                            packageName = info.activityInfo?.packageName.orEmpty(),
                        )
                    }
                }
                .filter { it.packageName.isNotBlank() }
                .distinctBy { it.packageName }

            if (installed.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "I cannot see any installed apps. Android needs the \"Show all apps\" " +
                        "package permission, which this build requests at install time.",
                )
            }

            val best = installed
                .map { it to (matchScore(query, it.label) + matchScore(query, it.packageName)) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first

            if (best == null) {
                val suggestions = installed
                    .map { it.label to matchScore(query, it.label) }
                    .sortedByDescending { it.second }
                    .take(5)
                    .joinToString(", ") { it.first }
                return@withContext ToolResult(
                    false,
                    "I could not find an app called \"$query\". Some installed apps: $suggestions.",
                )
            }

            val launch = packageManager.getLaunchIntentForPackage(best.packageName)
                ?: return@withContext ToolResult(
                    false,
                    "${best.label} has no launchable screen.",
                )
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            runCatching { context.appContext.startActivity(launch) }
                .fold(
                    onSuccess = {
                        if (com.jarvis.assistant.accessibility.JarvisAccessibilityService.isConnected()) {
                            for (attempt in 0 until 12) {
                                kotlinx.coroutines.delay(200L)
                                val fg = com.jarvis.assistant.accessibility.JarvisAccessibilityService
                                    .controller()?.foregroundPackage().orEmpty()
                                if (fg == best.packageName) {
                                    kotlinx.coroutines.delay(450L)
                                    break
                                }
                            }
                        }
                        ToolResult(true, "Opened ${best.label}.")
                    },
                    onFailure = { error ->
                        ToolResult(
                            false,
                            "Android refused to open ${best.label}: " +
                                "${error.message ?: "unknown error"}.",
                        )
                    },
                )
        }
    }

    private data class InstalledApp(val label: String, val packageName: String)
}
