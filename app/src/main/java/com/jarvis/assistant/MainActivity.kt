package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
        val isAssist = intent.action == Intent.ACTION_ASSIST ||
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
}
