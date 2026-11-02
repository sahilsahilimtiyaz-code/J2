package com.jarvis.assistant.ui

import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceView
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.assistant.ui.components.ArcReactor
import com.jarvis.assistant.ui.components.EnergyBar
import com.jarvis.assistant.ui.components.GlowDivider
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.ReactorState
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlinx.coroutines.delay

private val BootSteps = listOf(
    "LOADING AI CORE",
    "CONNECTING MODULES",
    "CALIBRATING INTERFACE",
    "INITIALIZING VOICE",
    "SYSTEMS ONLINE",
)

/**
 * Screen 1 — Boot sequence: the owner's custom cinematic intro video plays
 * full-screen, muted and looping, with the J.A.R.V.I.S. wordmark, a glowing
 * progress bar and the 5-step systems checklist overlaid on top.
 *
 * If the video asset fails to load (old install, broken asset), the previous
 * Arc Reactor boot screen takes over so the boot never breaks.
 */
@Composable
fun BootScreen(
    ready: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var videoFailed by remember { mutableStateOf(false) }

    var visibleSteps by remember { mutableIntStateOf(0) }
    var visibleLetters by remember { mutableIntStateOf(0) }
    val title = "J.A.R.V.I.S."
    val progress by animateFloatAsState(
        targetValue = (visibleSteps.toFloat() / BootSteps.size).coerceIn(0.12f, 1f),
        animationSpec = tween(durationMillis = 620),
        label = "boot_progress",
    )

    LaunchedEffect(Unit) {
        title.indices.forEach { index ->
            delay(85)
            visibleLetters = index + 1
        }
        BootSteps.indices.forEach { index ->
            delay(if (index == 0) 220 else 300)
            visibleSteps = index + 1
        }
    }

    LaunchedEffect(ready, visibleSteps) {
        if (ready && visibleSteps >= BootSteps.size) {
            delay(380)
            onFinished()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(JarvisPalette.Void)) {
        if (videoFailed) {
            JarvisBackdrop(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ArcReactor(
                        state = ReactorState.BOOT,
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
                            .aspectRatio(1f),
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.Center) {
                        title.forEachIndexed { index, character ->
                            Text(
                                text = character.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (index < visibleLetters) {
                                    JarvisPalette.Ice
                                } else {
                                    JarvisPalette.Ice.copy(alpha = 0.08f)
                                },
                                fontWeight = FontWeight.Light,
                                fontSize = 34.sp,
                                letterSpacing = 6.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    GlowDivider(
                        modifier = Modifier.fillMaxWidth(0.64f),
                        color = JarvisPalette.CyanBright,
                    )
                }
            }
        } else {
            // Full-screen, muted, looping intro video.
            AndroidView(
                factory = { viewContext ->
                    VideoView(viewContext).apply {
                        setMediaController(null)
                        setZOrderOnTop(false)
                        isSoundEffectsEnabled = false
                        try {
                            setVideoURI(Uri.parse("asset:///boot/boot_intro.mp4"))
                        } catch (e: Exception) {
                            videoFailed = true
                        }
                        setOnPreparedListener { player ->
                            runCatching {
                                player.isLooping = true
                                player.setVolume(0f, 0f) // mute — no boot audio
                                player.start()
                            }.onFailure { videoFailed = true }
                        }
                        setOnErrorListener { _, _, _ ->
                            videoFailed = true
                            true
                        }
                        setOnInfoListener { _, what, _ ->
                            // Never let the video use the audio track even if it remuxes.
                            if (what == MediaPlayer.MEDIA_INFO_VIDEO_NOT_PLAYING) videoFailed = true
                            false
                        }
                    }
                },
                update = { /* looping handled by the player */ },
                onRelease = { view -> runCatching { view.stopPlayback() } },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ------------------------------------------------------------- overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            com.jarvis.assistant.ui.components.ReactorGlyph(
                modifier = Modifier.size(84.dp),
            )

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.Center) {
                title.forEachIndexed { index, character ->
                    Text(
                        text = character.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (index < visibleLetters) {
                            JarvisPalette.Ice
                        } else {
                            JarvisPalette.Ice.copy(alpha = 0.10f)
                        },
                        fontWeight = FontWeight.Light,
                        fontSize = 32.sp,
                        letterSpacing = 6.sp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            GlowDivider(
                modifier = Modifier.fillMaxWidth(0.64f),
                color = JarvisPalette.CyanBright,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "INITIALIZING SYSTEMS...",
                style = MaterialTheme.typography.labelMedium,
                color = JarvisPalette.CyanBright.copy(alpha = 0.92f),
                letterSpacing = 3.2.sp,
            )

            Spacer(Modifier.height(12.dp))

            EnergyBar(
                progress = progress,
                modifier = Modifier.fillMaxWidth(0.86f),
                color = JarvisPalette.CyanBright,
                height = 5.dp,
            )

            Spacer(Modifier.height(22.dp))

            Column(
                modifier = Modifier.fillMaxWidth(0.88f),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                BootSteps.forEachIndexed { index, step ->
                    AnimatedVisibility(
                        visible = index < visibleSteps,
                        enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 3 },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HollowRingDot(
                                active = index <= visibleSteps - 1,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = step,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (index == visibleSteps - 1) {
                                    JarvisPalette.Ice
                                } else {
                                    JarvisPalette.TextDim
                                },
                                letterSpacing = 2.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HollowRingDot(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        drawCircle(
            color = if (active) JarvisPalette.CyanBright else JarvisPalette.CyanDim,
            radius = r * 0.78f,
            style = Stroke(width = 2.dp.toPx()),
        )
        if (active) {
            drawCircle(
                color = JarvisPalette.CyanBright.copy(alpha = 0.35f),
                radius = r * 0.32f,
            )
        }
    }
}
