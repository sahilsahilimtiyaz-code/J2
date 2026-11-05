package com.jarvis.assistant.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.assistant.ui.components.ArcReactor
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.components.ReactorState
import com.jarvis.assistant.ui.theme.HudNumerals
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlinx.coroutines.delay

/**
 * Iron Man 3 J.A.R.V.I.S. Second Screen Experience Boot Sequence.
 *
 * Plays `/assets/boot/boot_intro.mp4` via native `TextureView` + `MediaPlayer`
 * (using `context.assets.openFd("boot/boot_intro.mp4")`) while simultaneously rendering
 * the interactive 60fps Iron Man 3 J.A.R.V.I.S. Orb and telemetry sequence.
 * Tapping anywhere skips immediately into the main HUD.
 */
@Composable
fun BootScreen(
    ready: Boolean,
    onFinished: () -> Unit,
) {
    JarvisBootScreen(
        ownerTitle = "sir",
        onBootComplete = {
            if (ready) onFinished()
        },
    )
}

@Composable
fun JarvisBootScreen(
    ownerTitle: String,
    onBootComplete: () -> Unit,
) {
    val context = LocalContext.current
    var bootStep by remember { mutableIntStateOf(0) }
    val progressAnim = remember { Animatable(0f) }
    val coreScale = remember { Animatable(0.75f) }
    var videoPlaying by remember { mutableStateOf(false) }

    val bootDiagnostics = remember(ownerTitle) {
        listOf(
            "INITIALIZING STARK INDUSTRIES NEURAL KERNEL v4.2...",
            "MOUNTING AES-256-GCM HARDWARE KEYSTORE VAULT... [OK]",
            "CALIBRATING PORCUPINE WAKE-WORD SENSOR (JARVIS)... [OK]",
            "LOADING TIER 1-3 AUTONOMOUS PHONE OPERATOR... [OK]",
            "SYNCHRONIZING PERSISTENT MEMORY & GOOGLE CLOUD... [OK]",
            "ALL SYSTEMS ONLINE. WELCOME BACK, ${ownerTitle.ifBlank { "SIR" }.uppercase()}.",
        )
    }

    LaunchedEffect(Unit) {
        coreScale.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        )
    }

    LaunchedEffect(Unit) {
        val totalMs = 3_600L
        val stepDelay = totalMs / bootDiagnostics.size
        for (i in bootDiagnostics.indices) {
            bootStep = i
            progressAnim.animateTo(
                targetValue = (i + 1).toFloat() / bootDiagnostics.size.toFloat(),
                animationSpec = tween(durationMillis = stepDelay.toInt(), easing = LinearEasing),
            )
        }
        delay(220L)
        onBootComplete()
    }

    val infinite = rememberInfiniteTransition(label = "boot_hud")
    val radarAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar_angle",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBootComplete,
            ),
        contentAlignment = Alignment.Center,
    ) {
        JarvisBackdrop(modifier = Modifier.fillMaxSize()) {}

        // Native TextureView playing assets/boot/boot_intro.mp4 directly from AssetFileDescriptor
        BootIntroAssetVideoLayer(
            context = context,
            onVideoReady = { videoPlaying = true },
            onVideoFinished = onBootComplete,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (videoPlaying) 0.90f else 0f),
        )

        // Corner HUD framing reticles
        Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            val stroke = 1.5.dp.toPx()
            val cornerLen = 28.dp.toPx()
            val cyan = JarvisPalette.CyanBright.copy(alpha = 0.68f)

            // Top-Left
            drawLine(cyan, Offset(0f, 0f), Offset(cornerLen, 0f), strokeWidth = stroke, cap = StrokeCap.Square)
            drawLine(cyan, Offset(0f, 0f), Offset(0f, cornerLen), strokeWidth = stroke, cap = StrokeCap.Square)
            // Top-Right
            drawLine(cyan, Offset(size.width - cornerLen, 0f), Offset(size.width, 0f), strokeWidth = stroke, cap = StrokeCap.Square)
            drawLine(cyan, Offset(size.width, 0f), Offset(size.width, cornerLen), strokeWidth = stroke, cap = StrokeCap.Square)
            // Bottom-Left
            drawLine(cyan, Offset(0f, size.height - cornerLen), Offset(0f, size.height), strokeWidth = stroke, cap = StrokeCap.Square)
            drawLine(cyan, Offset(0f, size.height), Offset(cornerLen, size.height), strokeWidth = stroke, cap = StrokeCap.Square)
            // Bottom-Right
            drawLine(cyan, Offset(size.width - cornerLen, size.height), Offset(size.width, size.height), strokeWidth = stroke, cap = StrokeCap.Square)
            drawLine(cyan, Offset(size.width, size.height - cornerLen), Offset(size.width, size.height), strokeWidth = stroke, cap = StrokeCap.Square)

            // Subtle outer radar ring around the center
            val center = Offset(size.width / 2f, size.height * 0.43f)
            val r = size.minDimension * 0.40f
            drawCircle(
                color = JarvisPalette.Cyan.copy(alpha = 0.16f),
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        JarvisPalette.CyanBright.copy(alpha = 0.45f),
                        Color.Transparent,
                    ),
                    center = center,
                ),
                startAngle = radarAngle,
                sweepAngle = 95f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Stark Industries header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "STARK INDUSTRIES // MARK VII OS",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.CyanBright.copy(alpha = 0.85f),
                    letterSpacing = 3.2.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "J.A.R.V.I.S.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = JarvisPalette.Ice,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                )
            }

            // Center Iron Man 3 J.A.R.V.I.S. Second Screen Experience Orb
            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                ArcReactor(
                    state = ReactorState.SPEAKING,
                    modifier = Modifier.fillMaxSize(),
                    audioLevel = 0.48f + 0.35f * progressAnim.value,
                    toolActive = false,
                )
            }

            // Bottom telemetry log + progress bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(JarvisPalette.Deep.copy(alpha = 0.78f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (idx in 0..bootStep.coerceAtMost(bootDiagnostics.lastIndex)) {
                        val isLatest = idx == bootStep
                        Text(
                            text = "> ${bootDiagnostics[idx]}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = if (isLatest) JarvisPalette.CyanBright else JarvisPalette.Ice.copy(alpha = 0.68f),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { progressAnim.value.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = JarvisPalette.CyanBright,
                        trackColor = JarvisPalette.Deep,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${(progressAnim.value * 100).toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = HudNumerals.fontFamily,
                        color = JarvisPalette.CyanBright,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "TAP ANYWHERE TO ENTER HUD",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.TextDim.copy(alpha = 0.75f),
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BootIntroAssetVideoLayer(
    context: android.content.Context,
    onVideoReady: () -> Unit,
    onVideoFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerHolder = remember { arrayOfNulls<MediaPlayer>(1) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                playerHolder[0]?.stop()
                playerHolder[0]?.release()
            }
            playerHolder[0] = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        runCatching {
                            val afd = context.assets.openFd("boot/boot_intro.mp4")
                            val mp = MediaPlayer()
                            playerHolder[0] = mp
                            mp.setSurface(Surface(surfaceTexture))
                            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                            mp.setVolume(0f, 0f)
                            mp.setOnPreparedListener { prepared ->
                                onVideoReady()
                                runCatching { prepared.start() }
                            }
                            mp.setOnCompletionListener {
                                onVideoFinished()
                            }
                            mp.prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        runCatching {
                            playerHolder[0]?.stop()
                            playerHolder[0]?.release()
                        }
                        playerHolder[0] = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
    )
}
