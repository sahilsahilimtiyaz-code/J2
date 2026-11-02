package com.jarvis.assistant.service

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.R
import com.jarvis.assistant.core.CoreState
import com.jarvis.assistant.core.JarvisCore
import com.jarvis.assistant.ui.components.ArcReactor
import com.jarvis.assistant.ui.components.HudGlyph
import com.jarvis.assistant.ui.components.HudIcon
import com.jarvis.assistant.ui.components.ReactorState
import com.jarvis.assistant.ui.theme.JarvisPalette
import com.jarvis.assistant.ui.theme.JarvisTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "Jarvis is calling" experience.
 *
 * Phase 1 (RINGING): an animated identity avatar wrapped in expanding sonar
 * rings, the reason card explaining why Jarvis called, and two wide premium
 * answer/decline pills.
 *
 * Phase 2 (IN CALL): Jarvis actually speaks — an audio-reactive reactor, a live
 * duration timer, a live caption of every sentence he says (read the call), and
 * an End call pill.
 */
class JarvisIncomingCallActivity : ComponentActivity() {

    private var vibrator: android.os.Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen and show over keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Priority Audio Link"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Good morning, sir. Jarvis standing by."

        triggerHapticsAndAlert()

        setContent {
            JarvisTheme {
                var answered by rememberSaveable { mutableStateOf(false) }
                IncomingCallScreen(
                    title = title,
                    message = message,
                    answered = answered,
                    onAnswer = {
                        if (!answered) {
                            answered = true
                            stopHaptics()
                            runCatching { JarvisCore.get(applicationContext).speakAsync(message) }
                        }
                    },
                    onDecline = {
                        if (answered) {
                            runCatching { JarvisCore.get(applicationContext).endSpokenCall() }
                        } else {
                            stopHaptics()
                        }
                        finish()
                    },
                )
            }
        }
    }

    private fun triggerHapticsAndAlert() {
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            val timings = longArrayOf(0, 250, 120, 250, 120, 250, 800)
            val amplitudes = intArrayOf(0, 180, 0, 220, 0, 220, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(timings, amplitudes, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(timings, 0)
            }
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.jarvis.assistant.voice.SoundFxEngine.play(com.jarvis.assistant.voice.SoundFxEngine.SoundType.ALERT_WARNING)
        }
    }

    private fun stopHaptics() {
        runCatching { vibrator?.cancel() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHaptics()
    }

    companion object {
        const val EXTRA_TITLE = "extra_call_title"
        const val EXTRA_MESSAGE = "extra_call_message"
    }
}

@Composable
private fun IncomingCallScreen(
    title: String,
    message: String,
    answered: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    val core = remember { runCatching { JarvisCore.get(context.applicationContext) }.getOrNull() }
    val coreState: CoreState? = core?.state?.collectAsState(initial = null)?.value
    val speaking = coreState?.speaking == true
    val audioLevel = if (speaking) coreState?.audioLevel ?: 0f else 0f
    val caption = coreState?.spokenCaption

    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(answered) {
        if (answered) {
            while (true) {
                delay(1_000)
                elapsedSeconds += 1
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        JarvisPalette.Void,
                        JarvisPalette.Deep,
                        JarvisPalette.Navy,
                        JarvisPalette.Void,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient glow behind the orb
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            JarvisPalette.Cyan.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = Offset(0.5f, 0.38f),
                        radius = 1_100f,
                    ),
                ),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(vertical = 40.dp, horizontal = 24.dp),
        ) {
            // ---------------------------------------------------------- header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "J . A . R . V . I . S .",
                    style = MaterialTheme.typography.titleMedium,
                    color = JarvisPalette.Cyan,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (answered) "AUDIO LINK — CONNECTED" else "INCOMING AUDIO TRANSMISSION",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (answered) JarvisPalette.Success else JarvisPalette.TextDim,
                    letterSpacing = 2.sp,
                )
                if (answered) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatCallDuration(elapsedSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        color = JarvisPalette.Ice,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp,
                    )
                }
            }

            // ------------------------------------------------------- center art
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (answered) {
                    Box(
                        modifier = Modifier.size(230.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ArcReactor(
                            state = ReactorState.SPEAKING,
                            audioLevel = audioLevel,
                            modifier = Modifier.size(210.dp),
                        )
                    }
                    AnimatedVisibility(
                        visible = caption != null,
                        enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 },
                        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(JarvisPalette.Panel.copy(alpha = 0.72f))
                                .border(1.dp, JarvisPalette.Line, RoundedCornerShape(22.dp))
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = caption.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = JarvisPalette.Ice,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    RingingAvatar()
                    Spacer(Modifier.height(26.dp))
                    // Reason card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        JarvisPalette.Panel.copy(alpha = 0.9f),
                                        JarvisPalette.Deep.copy(alpha = 0.72f),
                                    ),
                                ),
                            )
                            .border(1.dp, JarvisPalette.CyanDim.copy(alpha = 0.7f), RoundedCornerShape(26.dp))
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = JarvisPalette.Ice,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            if (message.isNotBlank() && message != title) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = JarvisPalette.TextDim,
                                    textAlign = TextAlign.Center,
                                    maxLines = 4,
                                )
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------- controls
            if (answered) {
                WideCallButton(
                    label = "END CALL",
                    glyph = HudGlyph.CLOSE,
                    color = JarvisPalette.Danger,
                    fill = 0.62f,
                    onClick = onDecline,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WideCallButton(
                        label = "DECLINE",
                        glyph = HudGlyph.CLOSE,
                        color = JarvisPalette.Danger,
                        fill = 0.42f,
                        onClick = onDecline,
                    )
                    Spacer(Modifier.width(12.dp))
                    WideCallButton(
                        label = "ANSWER",
                        glyph = HudGlyph.MIC,
                        color = JarvisPalette.Success,
                        fill = 0.42f,
                        onClick = onAnswer,
                    )
                }
            }
        }
    }
}

/** The app identity avatar wrapped in staggered, expanding sonar rings. */
@Composable
private fun RingingAvatar() {
    val transition = rememberInfiniteTransition(label = "sonar")
    val avatarPulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "avatarPulse",
    )
    val ringProgress = List(3) { ring ->
        transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.32f,
            animationSpec = infiniteRepeatable(
                animation = tween(2_100, delayMillis = ring * 700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ring$ring",
        ).value
    }
    val ringAlpha = List(3) { ring ->
        transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2_100, delayMillis = ring * 700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ringAlpha$ring",
        ).value
    }

    Box(
        modifier = Modifier.size(300.dp),
        contentAlignment = Alignment.Center,
    ) {
        ringProgress.forEachIndexed { index, progress ->
            val alpha = ringAlpha[index]
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .scale(progress)
                    .alpha(alpha)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .border(2.dp, JarvisPalette.Cyan.copy(alpha = 0.9f), androidx.compose.foundation.shape.CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(192.dp)
                .scale(avatarPulse)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(JarvisPalette.PanelLight.copy(alpha = 0.9f), JarvisPalette.Deep),
                    ),
                )
                .border(2.dp, JarvisPalette.Cyan, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = "Jarvis",
                modifier = Modifier.size(150.dp),
            )
        }
    }
}

/** A wide, tactile modern call action pill with a press animation. */
@Composable
private fun WideCallButton(
    label: String,
    glyph: HudGlyph,
    color: Color,
    fill: Float,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "pressScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth(fill)
            .scale(scale)
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(color.copy(alpha = 0.20f))
            .border(1.5.dp, color.copy(alpha = 0.9f), RoundedCornerShape(29.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudIcon(
            glyph = glyph,
            color = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

private fun formatCallDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return String.format("%02d:%02d", minutes, remainder)
}
