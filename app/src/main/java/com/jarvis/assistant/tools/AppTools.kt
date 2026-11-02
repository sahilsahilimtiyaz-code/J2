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
        description = "Open an installed app on the phone by its name, for example " +
            "\"YouTube\", \"WhatsApp\", \"Camera\" or \"Spotify\".",
        parameters = objectSchema(
            required = listOf("app"),
            properties = mapOf(
                "app" to stringProperty("The app name as the owner would say it."),
            ),
        ),
    )

    override val label = "Open app"
    override val category = ToolCategory.APPS

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.argString("app")
            ?: return ToolResult(false, "Tell me which app to open.")

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
                    onSuccess = { ToolResult(true, "Opened ${best.label}.") },
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
