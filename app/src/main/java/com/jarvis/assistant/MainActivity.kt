package com.jarvis.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jarvis.assistant.brain.SharedContentPayload
import com.jarvis.assistant.service.JarvisVoiceInteractionSession
import com.jarvis.assistant.service.JarvisWidgetProvider
import com.jarvis.assistant.ui.JarvisApp
import com.jarvis.assistant.ui.JarvisViewModel
import com.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            JarvisTheme {
                JarvisApp(vm = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                ingestSendIntent(intent, multiple = false)
                return
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                ingestSendIntent(intent, multiple = true)
                return
            }

            Intent.ACTION_PROCESS_TEXT -> {
                val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                if (selectedText.isNotBlank()) {
                    viewModel.ingestSharedContent(
                        SharedContentPayload(
                            mimeType = "text/plain",
                            title = "Selected Text",
                            textOrUrl = selectedText.take(6_000),
                        ),
                    )
                }
                return
            }
        }

        val shortcutAction = intent.getStringExtra("shortcut_action").orEmpty()
        if (shortcutAction == "task_planner") {
            viewModel.executeTaskPlan("Prepare a daily mission plan, check device status, and review my upcoming priorities.")
            return
        }

        val isAssist = intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.getBooleanExtra(JarvisVoiceInteractionSession.EXTRA_FROM_ASSIST, false) ||
            intent.getBooleanExtra(JarvisWidgetProvider.EXTRA_START_LISTENING, false)

        if (isAssist) {
            viewModel.onAssistTriggered()
        }

        val isDiag = intent.getBooleanExtra(JarvisWidgetProvider.EXTRA_RUN_DIAGNOSTICS, false)
        if (isDiag) {
            viewModel.send("Run full system diagnostics")
        }
    }

    private fun ingestSendIntent(intent: Intent, multiple: Boolean) {
        val mime = intent.type ?: "text/plain"
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()

        val singleUri: Uri? = if (!multiple) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            uris?.firstOrNull()
        }

        var extractedTitle = subject
        var extractedBody = sharedText
        var uriStr = ""

        if (singleUri != null) {
            uriStr = singleUri.toString()
            val meta = resolveUriMetadata(singleUri)
            if (extractedTitle.isBlank()) extractedTitle = meta.first
            if (extractedBody.isBlank() && (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("csv"))) {
                extractedBody = readTextPreviewFromUri(singleUri)
            } else if (extractedBody.isBlank()) {
                extractedBody = "Shared file: ${meta.first.ifBlank { singleUri.lastPathSegment ?: "attachment" }} (${meta.second})"
            }
        }

        viewModel.ingestSharedContent(
            SharedContentPayload(
                mimeType = mime,
                title = extractedTitle.ifBlank { "Shared Content" },
                textOrUrl = sharedText.take(6_000),
                uriString = uriStr,
                extractedPreview = if (sharedText.isBlank()) extractedBody.take(6_000) else "",
            ),
        )
    }

    private fun resolveUriMetadata(uri: Uri): Pair<String, String> = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                val sizeLabel = if (sizeBytes > 0) "${sizeBytes / 1024} KB" else "file"
                return@runCatching name to sizeLabel
            }
        }
        (uri.lastPathSegment ?: "attachment") to "file"
    }.getOrDefault("attachment" to "file")

    private fun readTextPreviewFromUri(uri: Uri): String = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val buf = CharArray(4096)
            val count = reader.read(buf)
            if (count > 0) String(buf, 0, count) else ""
        }.orEmpty()
    }.getOrDefault("")
}

