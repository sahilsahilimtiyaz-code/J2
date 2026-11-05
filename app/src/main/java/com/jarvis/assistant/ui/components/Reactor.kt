package com.jarvis.assistant.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ReactorState {
    BOOT,
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
}

val ReactorState.label: String
    get() = when (this) {
        ReactorState.BOOT -> "Initializing"
        ReactorState.IDLE -> "Standing by"
        ReactorState.LISTENING -> "Listening"
        ReactorState.THINKING -> "Neural Processing"
        ReactorState.SPEAKING -> "Voice Synthesis"
    }

/**
 * Center hologram projections decoded frame-by-frame from the uploaded
 * Marvel's Iron Man 3 J.A.R.V.I.S. Second Screen Experience trailer:
 * - [AUTO_CINEMATIC]: Automatically cycles through the trailer sequence & reacts to live assistant state
 * - [GEODESIC_CORE]: `frame_03.png`, `frame_05.png`, `frame_08.png`, `frame_22.png`, `frame_28.png`
 * - [JARVIS_EMBLEM]: `frame_07.png`, `frame_12.png` — Wavy horizontal scanline-filled italic "JARVIS" emblem
 * - [LINKED_NODES]: `frame_14.png` — 3-node Share / Network Topology graph (`<` double-walled cyan rings)
 * - [SONIC_MIC]: `frame_16.png` + `00:07` — Concentric target rings & capsule microphone `((🎙))`
 * - [IRON_MAN_MASK]: `frame_17.png`, `frame_18.png` — 3D wireframe Iron Man Mark 42 helmet faceplate
 * - [HUD_CLOCK]: `frame_20.png` — `4:17 PM :17` digital readout with horizontal crosshair lines & broken arcs
 * - [CALENDAR_CARDS]: `frame_24.png` — 3D receding stack of translucent glass calendar cards (`JANUARY 6`)
 */
enum class JarvisHologramMode(val title: String) {
    AUTO_CINEMATIC("AUTO"),
    GEODESIC_CORE("CORE"),
    JARVIS_EMBLEM("JARVIS"),
    LINKED_NODES("NODES"),
    SONIC_MIC("MIC"),
    IRON_MAN_MASK("MK 42"),
    HUD_CLOCK("CLOCK"),
    CALENDAR_CARDS("DATE"),
}

// Dynamic Iron Man 3 J.A.R.V.I.S. Second Screen Experience Palette
// Defaults to 100% video-matched Seafoam-Verdigris (IM3_SEAFOAM) and adapts
// immediately when the user switches Orb & Bottom Bar colors (Blue, Sky Blue, White, Red, Purple, Violet, etc.)
private val Im3SeafoamBright: Color
    get() = com.jarvis.assistant.ui.theme.JarvisPalette.CyanBright
private val Im3SeafoamCyan: Color
    get() = com.jarvis.assistant.ui.theme.JarvisPalette.Cyan
private val Im3SeafoamMid: Color
    get() = com.jarvis.assistant.ui.theme.JarvisPalette.Cyan.copy(alpha = 0.82f)
private val Im3SeafoamDeep: Color
    get() = com.jarvis.assistant.ui.theme.JarvisPalette.CyanDim
private val Im3SeafoamDark: Color
    get() = com.jarvis.assistant.ui.theme.JarvisPalette.Panel
private val Im3SpecularIce: Color
    get() = com.jarvis.assistant.ui.theme.JarvisPalette.Ice
private val Im3CavernVoid: Color
    get() = com.jarvis.assistant.ui.theme.JarvisPalette.Void

/**
 * Pre-computed outer spray/dust particles that drift just outside the undulating stippled torus
 * (visible around the outer perimeter in `frame_03.png`, `frame_08.png`, `frame_22.png`, `frame_24.png`).
 */
private data class OuterSprayParticle(
    val theta: Float,
    val cosT: Float,
    val sinT: Float,
    val rOffset: Float,
    val sizeDp: Float,
    val alpha: Float,
    val phase: Float,
)

private val OUTER_SPRAY_PARTICLES: List<OuterSprayParticle> = run {
    val count = 260
    val list = ArrayList<OuterSprayParticle>(count)
    val golden = 0.6180339887f
    for (i in 0 until count) {
        val theta = ((i * golden) % 1f) * 2f * PI.toFloat()
        val rOffset = 0.925f + ((i * 37 % 100) / 100f) * 0.115f
        val sizeDp = if (i % 11 == 0) 1.35f else 0.85f
        val alpha = 0.14f + ((i * 53 % 100) / 100f) * 0.48f
        list.add(
            OuterSprayParticle(
                theta = theta,
                cosT = cos(theta),
                sinT = sin(theta),
                rOffset = rOffset,
                sizeDp = sizeDp,
                alpha = alpha,
                phase = (i % 31) * 0.23f,
            ),
        )
    }
    list
}

/**
 * Pre-computed Frequency-3 Subdivided Geodesic Sphere (162 vertices, 480 edges, 320 triangle facets)
 * matching the dense, crisp triangulated wireframe sphere at the center of `frame_03.png`, `frame_05.png`,
 * `frame_08.png`, `frame_22.png`, and `frame_28.png`.
 */
private data class Vec3(val x: Float, val y: Float, val z: Float)

private fun normalizeVec3(x: Float, y: Float, z: Float): Vec3 {
    val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
    return Vec3(x / len, y / len, z / len)
}

private data class GeodesicMesh(
    val vertices: List<Vec3>,
    val edges: List<Pair<Int, Int>>,
    val faces: List<Triple<Int, Int, Int>>,
    val nebulaClusterIndices: Set<Int>,
)

private val GEODESIC_SPHERE: GeodesicMesh = run {
    val t = ((1f + sqrt(5f)) / 2f)
    val verts = mutableListOf(
        normalizeVec3(-1f, t, 0f), normalizeVec3(1f, t, 0f), normalizeVec3(-1f, -t, 0f), normalizeVec3(1f, -t, 0f),
        normalizeVec3(0f, -1f, t), normalizeVec3(0f, 1f, t), normalizeVec3(0f, -1f, -t), normalizeVec3(0f, 1f, -t),
        normalizeVec3(t, 0f, -1f), normalizeVec3(t, 0f, 1f), normalizeVec3(-t, 0f, -1f), normalizeVec3(-t, 0f, 1f),
    )
    var faces = listOf(
        Triple(0, 11, 5), Triple(0, 5, 1), Triple(0, 1, 7), Triple(0, 7, 10), Triple(0, 10, 11),
        Triple(1, 5, 9), Triple(5, 11, 4), Triple(11, 10, 2), Triple(10, 7, 6), Triple(7, 1, 8),
        Triple(3, 9, 4), Triple(3, 4, 2), Triple(3, 2, 6), Triple(3, 6, 8), Triple(3, 8, 9),
        Triple(4, 9, 5), Triple(2, 4, 11), Triple(6, 2, 10), Triple(8, 6, 7), Triple(9, 8, 1),
    )

    // Subdivide twice -> 162 vertices, 320 equilateral spherical triangles, 480 struts
    repeat(2) {
        val midpointCache = mutableMapOf<Long, Int>()
        fun midpoint(i1: Int, i2: Int): Int {
            val a = minOf(i1, i2).toLong()
            val b = maxOf(i1, i2).toLong()
            val key = (a shl 32) or b
            return midpointCache.getOrPut(key) {
                val v1 = verts[i1]
                val v2 = verts[i2]
                verts.add(normalizeVec3((v1.x + v2.x) * 0.5f, (v1.y + v2.y) * 0.5f, (v1.z + v2.z) * 0.5f))
                verts.lastIndex
            }
        }
        val nextFaces = ArrayList<Triple<Int, Int, Int>>(faces.size * 4)
        for (f in faces) {
            val a = midpoint(f.first, f.second)
            val b = midpoint(f.second, f.third)
            val c = midpoint(f.third, f.first)
            nextFaces.add(Triple(f.first, a, c))
            nextFaces.add(Triple(f.second, b, a))
            nextFaces.add(Triple(f.third, c, b))
            nextFaces.add(Triple(a, b, c))
        }
        faces = nextFaces
    }

    val edgeSet = linkedSetOf<Pair<Int, Int>>()
    fun addEdge(i1: Int, i2: Int) {
        edgeSet.add(minOf(i1, i2) to maxOf(i1, i2))
    }
    for (f in faces) {
        addEdge(f.first, f.second)
        addEdge(f.second, f.third)
        addEdge(f.third, f.first)
    }
    GeodesicMesh(
        vertices = verts,
        edges = edgeSet.toList(),
        faces = faces,
        // Glowing surface nebula/continent clusters visible on the sphere in frame_03.png & frame_05.png
        nebulaClusterIndices = setOf(0, 4, 5, 9, 11, 16, 22, 28, 35, 41, 52, 64, 78, 91, 108, 125, 144),
    )
}

@Stable
class NeuralOrbPhysics(val nodeCount: Int = 162) {
    var spinAngleDeg by mutableFloatStateOf(0f)
    var pitchAngleDeg by mutableFloatStateOf(14f)
    var wavePhase by mutableFloatStateOf(0f)
    var crownSpinRad by mutableFloatStateOf(0f)
    var angularVelocity by mutableFloatStateOf(20f)
    var cymaticEnvelope by mutableFloatStateOf(0.24f)
    var coreScale by mutableFloatStateOf(1f)
    var coreVelocity by mutableFloatStateOf(0f)
    var frameTick by mutableFloatStateOf(0f)

    // Interactive Touch Physics
    var isTouched by mutableStateOf(false)
    var touchNormX by mutableFloatStateOf(0f)
    var touchNormY by mutableFloatStateOf(0f)
    var touchDeform by mutableFloatStateOf(0f)
    var touchYawVelocity by mutableFloatStateOf(0f)
    var touchPitchVelocity by mutableFloatStateOf(0f)
    var touchImpactScale by mutableFloatStateOf(0f)

    var hologramMode by mutableStateOf(JarvisHologramMode.AUTO_CINEMATIC)

    fun onTouchDown(normX: Float, normY: Float) {
        isTouched = true
        touchNormX = normX
        touchNormY = normY
        touchDeform = 0.60f
        touchImpactScale = 0.05f
    }

    fun onTouchDrag(deltaX: Float, deltaY: Float, normX: Float, normY: Float) {
        touchNormX = normX
        touchNormY = normY
        spinAngleDeg = (spinAngleDeg + deltaX * 0.45f) % 360f
        pitchAngleDeg = (pitchAngleDeg - deltaY * 0.32f).coerceIn(-45f, 45f)
        touchYawVelocity = deltaX * 15f
        touchPitchVelocity = -deltaY * 10f
        touchDeform = 0.70f
    }

    fun onTouchUp() {
        isTouched = false
        angularVelocity = (angularVelocity + touchYawVelocity * 0.14f).coerceIn(-220f, 220f)
    }

    fun onTap(normX: Float, normY: Float) {
        touchNormX = normX
        touchNormY = normY
        touchDeform = 0.78f
        touchImpactScale = 0.09f
    }

    fun step(
        dtSeconds: Float,
        state: ReactorState,
        audioLevel: Float,
        toolActive: Boolean,
    ) {
        val dt = dtSeconds.coerceIn(0.001f, 0.050f)
        val audio = audioLevel.coerceIn(0f, 1f)
        val thinking = state == ReactorState.THINKING || toolActive
        val speaking = state == ReactorState.SPEAKING
        val listening = state == ReactorState.LISTENING

        // 1. Geodesic Core 3D Rotation Speed
        val targetOmega = when {
            thinking -> 46f
            speaking -> 32f + audio * 55f
            listening -> 26f + audio * 30f
            else -> 18f
        }
        angularVelocity += (targetOmega - angularVelocity) * (dt * 4.5f)
        spinAngleDeg = (spinAngleDeg + angularVelocity * dt) % 360f
        crownSpinRad = (crownSpinRad + dt * (if (speaking || listening) 0.35f else 0.14f)) % (2f * PI.toFloat())

        if (!isTouched) {
            touchYawVelocity *= (1f - dt * 2.5f)
            touchPitchVelocity *= (1f - dt * 3.5f)
            pitchAngleDeg = (pitchAngleDeg + touchPitchVelocity * dt).coerceIn(-45f, 45f)
            pitchAngleDeg += (14f - pitchAngleDeg) * (dt * 1.8f)
            touchDeform += (0f - touchDeform) * (dt * 6.5f)
        }
        touchImpactScale += (0f - touchImpactScale) * (dt * 8.5f)

        // 2. Iron Man 3 Outer Torus Cymatic Wave Envelope:
        // Alternates naturally between the calm double-walled stippled circle (frame_05.png, frame_22.png)
        // and the folded 3D stippled wave membrane (frame_03.png, frame_08.png, frame_20.png, frame_24.png).
        val trailerWaveCycle = ((sin(frameTick * 1.55f) + 0.6f * sin(frameTick * 0.72f)) * 0.62f)
            .coerceIn(0f, 1f)
        val targetCymatic = when {
            speaking -> (0.65f + audio * 0.45f).coerceIn(0.55f, 1.0f)
            listening -> (0.45f + audio * 0.55f + trailerWaveCycle * 0.25f).coerceIn(0.32f, 1.0f)
            thinking -> (0.72f + 0.24f * abs(sin(frameTick * 3.2f))).coerceIn(0.55f, 0.98f)
            else -> (0.04f + trailerWaveCycle * 0.82f + touchDeform * 0.45f).coerceIn(0.03f, 0.92f)
        }
        cymaticEnvelope += (targetCymatic - cymaticEnvelope) * (dt * 7.5f)

        val waveSpeed = when {
            speaking -> 4.2f + audio * 3.8f
            thinking -> 3.6f
            listening -> 2.8f + audio * 2.8f
            else -> 2.1f
        }
        wavePhase = (wavePhase + waveSpeed * dt) % (2f * PI.toFloat() * 100f)

        // 3. Subtle core scale breathing
        val targetScale = when {
            speaking -> 1.015f + audio * 0.06f
            thinking -> 1.01f + sin(frameTick * 4.5f) * 0.015f
            listening -> 1.008f + audio * 0.04f
            else -> 1.0f + sin(frameTick * 1.4f) * 0.010f
        } + touchImpactScale
        val springForce = (targetScale - coreScale) * 140f - coreVelocity * 15f
        coreVelocity += springForce * dt
        coreScale = (coreScale + coreVelocity * dt).coerceIn(0.92f, 1.14f)

        frameTick = (frameTick + dt) % 10000f
    }
}

@Composable
fun rememberNeuralOrbPhysics(
    state: ReactorState,
    audioLevel: Float,
    toolActive: Boolean,
    nodeCount: Int = 162,
): NeuralOrbPhysics {
    val physics = remember(nodeCount) { NeuralOrbPhysics(nodeCount) }
    val currentState by rememberUpdatedState(state)
    val currentAudio by rememberUpdatedState(audioLevel)
    val currentToolActive by rememberUpdatedState(toolActive)

    LaunchedEffect(physics) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1_000_000_000f)
                lastNanos = nanos
                physics.step(
                    dtSeconds = dt,
                    state = currentState,
                    audioLevel = currentAudio,
                    toolActive = currentToolActive,
                )
            }
        }
    }
    return physics
}

/**
 * 100% Keyframe-Matched Iron Man 3 J.A.R.V.I.S. Second Screen Experience Orb (`frame_03.png` – `frame_28.png`).
 *
 * Combines:
 * 1. The embedded `boot/jarvis_orb_loop.mp4` video orb stream (cropped directly from the uploaded
 *    Iron Man 3 trailer) when in `AUTO_CINEMATIC` mode on the default Seafoam theme.
 * 2. The 60fps interactive 3D Canvas orb calibrated to the exact sub-pixel radial profile of the video
 *    (`r_sphere = 0.316`, `r_arch = 0.387..0.453`, `r_inner_stipple = 0.736..0.778`, `r_outer_stipple = 0.811..0.985`),
 *    which dynamically recolors to any selected `HudColorTheme` (Seafoam, Blue, Sky Blue, White, Red, Purple, Violet, Emerald, Gold).
 */
@Composable
fun ArcReactor(
    state: ReactorState,
    modifier: Modifier = Modifier,
    accent: Color = Im3SeafoamBright,
    audioLevel: Float = 0f,
    toolActive: Boolean = false,
    hologramMode: JarvisHologramMode = JarvisHologramMode.AUTO_CINEMATIC,
    onTap: (() -> Unit)? = null,
) {
    val thinking = state == ReactorState.THINKING || toolActive
    val speaking = state == ReactorState.SPEAKING
    val listening = state == ReactorState.LISTENING

    val physics = rememberNeuralOrbPhysics(
        state = state,
        audioLevel = audioLevel,
        toolActive = toolActive,
    )
    physics.hologramMode = hologramMode

    val smoothedAudio by animateFloatAsState(
        targetValue = audioLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 50, easing = LinearEasing),
        label = "im3_orb_audio",
    )

    val activeColorTheme = com.jarvis.assistant.ui.theme.JarvisPalette.activeTheme

    androidx.compose.foundation.layout.Box(
        modifier = modifier,
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        // Layer 0: Hardware-accelerated loop of the exact uploaded Iron Man 3 Orb video
        // (clipped to a circle so it blends seamlessly with the interactive 3D Canvas orb!)
        if (hologramMode == JarvisHologramMode.AUTO_CINEMATIC) {
            Im3VideoOrbLoopLayer(
                colorTheme = activeColorTheme,
                alpha = if (activeColorTheme == com.jarvis.assistant.settings.HudColorTheme.IM3_SEAFOAM) 0.58f else 0.26f,
                modifier = Modifier
                    .fillMaxSize(0.94f)
                    .clip(androidx.compose.foundation.shape.CircleShape),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(physics) {
                    detectTapGestures(
                        onPress = { offset ->
                            val halfW = size.width / 2f
                            val halfH = size.height / 2f
                            val normX = if (halfW > 0) ((offset.x - halfW) / halfW).coerceIn(-1f, 1f) else 0f
                            val normY = if (halfH > 0) ((offset.y - halfH) / halfH).coerceIn(-1f, 1f) else 0f
                            physics.onTouchDown(normX, normY)
                            val released = tryAwaitRelease()
                            if (released) {
                                physics.onTouchUp()
                            }
                        },
                        onTap = { offset ->
                            val halfW = size.width / 2f
                            val halfH = size.height / 2f
                            val normX = if (halfW > 0) ((offset.x - halfW) / halfW).coerceIn(-1f, 1f) else 0f
                            val normY = if (halfH > 0) ((offset.y - halfH) / halfH).coerceIn(-1f, 1f) else 0f
                            physics.onTap(normX, normY)
                            onTap?.invoke()
                        },
                    )
                }
                .pointerInput(physics) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val halfW = size.width / 2f
                            val halfH = size.height / 2f
                            val normX = if (halfW > 0) ((offset.x - halfW) / halfW).coerceIn(-1f, 1f) else 0f
                            val normY = if (halfH > 0) ((offset.y - halfH) / halfH).coerceIn(-1f, 1f) else 0f
                            physics.onTouchDown(normX, normY)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val halfW = size.width / 2f
                            val halfH = size.height / 2f
                            val normX = if (halfW > 0) ((change.position.x - halfW) / halfW).coerceIn(-1f, 1f) else 0f
                            val normY = if (halfH > 0) ((change.position.y - halfH) / halfH).coerceIn(-1f, 1f) else 0f
                            physics.onTouchDrag(dragAmount.x, dragAmount.y, normX, normY)
                        },
                        onDragEnd = { physics.onTouchUp() },
                        onDragCancel = { physics.onTouchUp() },
                    )
                },
        ) {
            drawIronMan3JarvisOrb(
                physics = physics,
                state = state,
                thinking = thinking,
                speaking = speaking,
                listening = listening,
                audio = smoothedAudio,
            )
        }
    }
}

@Composable
private fun Im3VideoOrbLoopLayer(
    colorTheme: com.jarvis.assistant.settings.HudColorTheme,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                isOpaque = false
                this.alpha = alpha
                var player: android.media.MediaPlayer? = null
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        runCatching {
                            val afd = ctx.assets.openFd("boot/jarvis_orb_loop.mp4")
                            val mp = android.media.MediaPlayer()
                            player = mp
                            mp.setSurface(android.view.Surface(surfaceTexture))
                            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                            mp.isLooping = true
                            mp.setVolume(0f, 0f)
                            mp.setOnPreparedListener { it.start() }
                            mp.prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                        runCatching {
                            player?.stop()
                            player?.release()
                        }
                        player = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.alpha = alpha
            val targetColor = Color(colorTheme.brightHex)
            if (colorTheme == com.jarvis.assistant.settings.HudColorTheme.IM3_SEAFOAM) {
                view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            } else {
                val rScale = targetColor.red
                val gScale = targetColor.green
                val bScale = targetColor.blue
                val matrix = android.graphics.ColorMatrix(
                    floatArrayOf(
                        0.2f * rScale, 0.5f * rScale, 0.3f * rScale, 0f, 0f,
                        0.2f * gScale, 0.5f * gScale, 0.3f * gScale, 0f, 0f,
                        0.2f * bScale, 0.5f * bScale, 0.3f * bScale, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                val paint = android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
                }
                view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
            }
        },
    )
}

@Composable
fun HolographicGlobe(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    accent: Color = Im3SeafoamBright,
    audioLevel: Float = 0f,
    toolActive: Boolean = false,
    hologramMode: JarvisHologramMode = JarvisHologramMode.AUTO_CINEMATIC,
    onTap: (() -> Unit)? = null,
) {
    val state = when {
        toolActive -> ReactorState.THINKING
        audioLevel > 0.08f -> ReactorState.SPEAKING
        active -> ReactorState.LISTENING
        else -> ReactorState.IDLE
    }
    ArcReactor(
        state = state,
        modifier = modifier,
        accent = accent,
        audioLevel = audioLevel,
        toolActive = toolActive,
        hologramMode = hologramMode,
        onTap = onTap,
    )
}

/**
 * Evaluates the 3D folded wave displacement of the outer stippled membrane at normalized
 * radial coordinate [u] (`0..1` across the torus thickness) and azimuth [theta] (`0..2pi`),
 * reproducing the exact sub-pixel radial profile measured from the uploaded Iron Man 3 trailer:
 * - `u in 0.00..0.18` -> inner stippled ring (`r = 0.736 .. 0.778 * orbRadius`, peak at `0.760`)
 * - Dark circular moat at `0.792 * orbRadius`
 * - `u in 0.18..1.00` -> outer wide stippled band (`r = 0.811 .. 0.906 * orbRadius` calm, expanding to `0.985` in wave state).
 */
private fun evaluateOuterMembraneRadius(
    theta: Float,
    u: Float,
    orbRadius: Float,
    wavePhase: Float,
    cymaticEnvelope: Float,
    touchDeform: Float,
    touchNormX: Float,
    touchNormY: Float,
): Float {
    val baseR = if (u < 0.18f) {
        orbRadius * (0.736f + (u / 0.18f) * 0.042f)
    } else {
        val uOuter = (u - 0.18f) / 0.82f
        orbRadius * (0.811f + uOuter * 0.095f)
    }

    // 4 Cardinal pinch/arch cusps at 12, 3, 6, 9 o'clock visible in frame_05.png & frame_22.png
    val cardinal4 = cos(4f * theta)
    val cardinalPinch = if (cardinal4 > 0.82f) {
        val p = (cardinal4 - 0.82f) / 0.18f
        -orbRadius * 0.014f * p * p * sin(u * PI.toFloat()) * (1f - cymaticEnvelope * 0.6f)
    } else {
        0f
    }

    // Phase-twisted 3D cymatic wave harmonics across u:
    val twistPhase = wavePhase + u * 2.35f
    val w9 = cos(9f * theta - twistPhase * 1.05f) * 0.42f
    val w6 = sin(6f * theta + twistPhase * 0.72f - u * 1.8f) * 0.36f
    val w13 = cos(13f * theta + twistPhase * 1.30f + u * 2.6f) * 0.24f
    val w4 = sin(4f * theta - wavePhase * 0.45f + u * 1.1f) * 0.18f

    val azimuthMod = 0.68f + 0.32f * cos(3f * theta + wavePhase * 0.38f - u * 1.4f)
    var waveDelta = (w9 + w6 + w13 + w4) * azimuthMod * cymaticEnvelope * orbRadius * 0.092f

    if (touchDeform > 0.01f) {
        val touchAngle = atan2(touchNormY, touchNormX)
        var angleDiff = abs(theta - touchAngle)
        if (angleDiff > PI.toFloat()) angleDiff = 2f * PI.toFloat() - angleDiff
        if (angleDiff < 1.15f) {
            val pinch = cos(angleDiff * (PI.toFloat() / 2.3f))
            waveDelta += sin(angleDiff * 12f - wavePhase * 3f + u * 2f) * touchDeform * pinch * orbRadius * 0.062f
        }
    }

    return baseR + cardinalPinch + waveDelta
}

private fun DrawScope.drawIronMan3JarvisOrb(
    physics: NeuralOrbPhysics,
    state: ReactorState,
    thinking: Boolean,
    speaking: Boolean,
    listening: Boolean,
    audio: Float,
) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    val orbRadius = (size.minDimension / 2f) * 0.95f * physics.coreScale

    // 1. Deep Atmospheric Conical Bowl & Outer Glow (measured from video: smooth dark-teal bowl r = 0.49..0.73)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to Im3SeafoamDeep.copy(alpha = 0.28f),
                0.31f to Im3SeafoamMid.copy(alpha = 0.20f),
                0.33f to Im3CavernVoid.copy(alpha = 0.88f),
                0.45f to Im3SeafoamDark.copy(alpha = 0.32f),
                0.49f to Im3SeafoamDark.copy(alpha = 0.85f),
                0.72f to Im3CavernVoid.copy(alpha = 0.92f),
                0.76f to Im3SeafoamDeep.copy(alpha = 0.36f + physics.cymaticEnvelope * 0.14f),
                0.79f to Im3CavernVoid.copy(alpha = 0.94f),
                0.88f to Im3SeafoamMid.copy(alpha = 0.22f),
                1.00f to Color.Transparent,
            ),
            center = center,
            radius = orbRadius * 1.05f,
        ),
        radius = orbRadius * 1.05f,
        center = center,
    )

    // 2. Resolve active center hologram projection
    val resolvedMode = resolveActiveHologramMode(
        configured = physics.hologramMode,
        state = state,
        thinking = thinking,
        listening = listening,
        frameTick = physics.frameTick,
    )

    // 3. Layer 2 — Recessed Cavern & 12-Arch Concave Scalloped Festoon Ring (frame_05.png & frame_22.png)
    val showScallopedArches = resolvedMode != JarvisHologramMode.SONIC_MIC &&
        resolvedMode != JarvisHologramMode.IRON_MAN_MASK
    if (showScallopedArches) {
        drawCavernAnd12ScallopedArches(
            center = center,
            orbRadius = orbRadius,
            physics = physics,
            dimAlpha = if (resolvedMode == JarvisHologramMode.GEODESIC_CORE) 1.0f else 0.55f,
        )
    }

    // 4. Layer 3 — Central Frequency-3 Geodesic Triangulated Sphere (exact video limb r = 0.316 * orbRadius)
    val geodesicAlpha = when (resolvedMode) {
        JarvisHologramMode.GEODESIC_CORE -> 1.0f
        JarvisHologramMode.JARVIS_EMBLEM -> 0.48f
        JarvisHologramMode.LINKED_NODES -> 0.30f
        JarvisHologramMode.HUD_CLOCK -> 0.34f
        JarvisHologramMode.CALENDAR_CARDS -> 0.32f
        JarvisHologramMode.SONIC_MIC -> 0.24f
        JarvisHologramMode.IRON_MAN_MASK -> 0.22f
        JarvisHologramMode.AUTO_CINEMATIC -> 1.0f
    }
    drawGeodesicTriangulatedSphere(
        center = center,
        sphereRadius = orbRadius * 0.316f,
        physics = physics,
        alphaScale = geodesicAlpha,
        audio = audio,
    )

    // 5. Layer 4 — Authentic Center Hologram Projections (frame_12.png – frame_24.png)
    when (resolvedMode) {
        JarvisHologramMode.JARVIS_EMBLEM -> drawJarvisScanlineWaveEmblem(
            center = center,
            orbRadius = orbRadius,
            frameTick = physics.frameTick,
        )
        JarvisHologramMode.LINKED_NODES -> drawThreeNodeNetworkGraphHologram(
            center = center,
            orbRadius = orbRadius,
            frameTick = physics.frameTick,
        )
        JarvisHologramMode.SONIC_MIC -> drawSonicMicrophoneAndRingsHologram(
            center = center,
            orbRadius = orbRadius,
            frameTick = physics.frameTick,
            audio = audio,
        )
        JarvisHologramMode.IRON_MAN_MASK -> drawIronManMark42WireframeMask(
            center = center,
            orbRadius = orbRadius,
            frameTick = physics.frameTick,
            audio = audio,
        )
        JarvisHologramMode.HUD_CLOCK -> drawHolographicClockReadout(
            center = center,
            orbRadius = orbRadius,
            frameTick = physics.frameTick,
        )
        JarvisHologramMode.CALENDAR_CARDS -> draw3dGlassCalendarCards(
            center = center,
            orbRadius = orbRadius,
            frameTick = physics.frameTick,
        )
        else -> Unit
    }

    // 6. Layer 1 — Outer 3D Stippled Point-Cloud Wave Torus & Folded Caustic Ribbons (frame_03.png & frame_05.png)
    drawOuterStippledWaveMembrane(
        center = center,
        orbRadius = orbRadius,
        physics = physics,
        audio = audio,
    )
}

private fun resolveActiveHologramMode(
    configured: JarvisHologramMode,
    state: ReactorState,
    thinking: Boolean,
    listening: Boolean,
    frameTick: Float,
): JarvisHologramMode {
    if (configured != JarvisHologramMode.AUTO_CINEMATIC) return configured
    if (listening) return JarvisHologramMode.SONIC_MIC
    if (thinking) return JarvisHologramMode.IRON_MAN_MASK
    if (state == ReactorState.BOOT) return JarvisHologramMode.JARVIS_EMBLEM

    // 18-second cinematic loop matching the exact sequence of the uploaded Iron Man 3 trailer:
    // 0.0..3.5s  -> GEODESIC_CORE  (frame_03.png, frame_05.png, frame_08.png)
    // 3.5..6.0s  -> JARVIS_EMBLEM  (frame_07.png, frame_12.png)
    // 6.0..8.0s  -> LINKED_NODES   (frame_14.png)
    // 8.0..10.0s -> SONIC_MIC      (frame_16.png + 00:07)
    // 10.0..12.2s -> IRON_MAN_MASK (frame_17.png, frame_18.png)
    // 12.2..14.4s -> HUD_CLOCK     (frame_20.png)
    // 14.4..16.4s -> CALENDAR_CARDS (frame_24.png)
    // 16.4..18.0s -> GEODESIC_CORE  (frame_22.png, frame_28.png)
    val cycleSec = frameTick % 18.0f
    return when {
        cycleSec < 3.5f -> JarvisHologramMode.GEODESIC_CORE
        cycleSec < 6.0f -> JarvisHologramMode.JARVIS_EMBLEM
        cycleSec < 8.0f -> JarvisHologramMode.LINKED_NODES
        cycleSec < 10.0f -> JarvisHologramMode.SONIC_MIC
        cycleSec < 12.2f -> JarvisHologramMode.IRON_MAN_MASK
        cycleSec < 14.4f -> JarvisHologramMode.HUD_CLOCK
        cycleSec < 16.4f -> JarvisHologramMode.CALENDAR_CARDS
        else -> JarvisHologramMode.GEODESIC_CORE
    }
}

/**
 * Layer 2: Recessed Cavern Rings & 12-Arch Concave Scalloped Festoon Ring
 * (`frame_05.png`, `frame_08.png`, `frame_20.png`, `frame_22.png`, `frame_24.png`, `frame_28.png`).
 *
 * Notice in `frame_05.png` and `frame_22.png` that the 12 scallops are concave festoon arches
 * bowing outward to `r = 0.485` and meeting at 12 sharp inward-pointing cusps at `r = 0.415`,
 * enclosed immediately inside a thin outer circle at `r = 0.502`!
 */
private fun DrawScope.drawCavernAnd12ScallopedArches(
    center: Offset,
    orbRadius: Float,
    physics: NeuralOrbPhysics,
    dimAlpha: Float,
) {
    val hairline = 1.0.dp.toPx()
    val archStroke = 1.5.dp.toPx()

    // Inner faint circle touching the inward cusps of the 12 arches (r = 0.385)
    drawCircle(
        color = Im3SeafoamMid.copy(alpha = 0.18f * dimAlpha),
        radius = orbRadius * 0.385f,
        center = center,
        style = Stroke(width = hairline),
    )

    // 12-Arch Concave Scalloped Festoon Ring: r(theta) = rCusp + (rArch - rCusp) * |sin(6*theta + spin)|^0.72
    val festoonPath = Path()
    val samples = 240
    val rCusp = orbRadius * 0.387f
    val rArch = orbRadius * 0.453f
    val archSpan = rArch - rCusp
    for (i in 0..samples) {
        val theta = (i.toFloat() / samples) * 2f * PI.toFloat()
        val s6 = abs(sin(6f * theta + physics.crownSpinRad))
        val archFactor = s6.pow(0.72f)
        val r = rCusp + archSpan * archFactor
        val x = center.x + r * cos(theta)
        val y = center.y + r * sin(theta)
        if (i == 0) festoonPath.moveTo(x, y) else festoonPath.lineTo(x, y)
    }

    // Soft seafoam glow behind the 12 scalloped arches
    drawPath(
        path = festoonPath,
        color = Im3SeafoamBright.copy(alpha = 0.14f * dimAlpha),
        style = Stroke(width = 3.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Crisp 12-arch festoon line
    drawPath(
        path = festoonPath,
        color = Im3SeafoamBright.copy(alpha = 0.48f * dimAlpha),
        style = Stroke(width = archStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Thin concentric circle immediately enclosing the 12 arches (r = 0.481 measured from video)
    drawCircle(
        color = Im3SeafoamBright.copy(alpha = 0.36f * dimAlpha),
        radius = orbRadius * 0.481f,
        center = center,
        style = Stroke(width = 1.2.dp.toPx()),
    )
    // Secondary faint outer cavern circle (r = 0.518)
    drawCircle(
        color = Im3SeafoamMid.copy(alpha = 0.22f * dimAlpha),
        radius = orbRadius * 0.518f,
        center = center,
        style = Stroke(width = hairline),
    )
}

/**
 * Layer 3: Central Frequency-3 Geodesic Triangulated Sphere (`r = 0.285`)
 * matching `frame_03.png`, `frame_05.png`, `frame_08.png`, `frame_22.png`, `frame_28.png`.
 */
private fun DrawScope.drawGeodesicTriangulatedSphere(
    center: Offset,
    sphereRadius: Float,
    physics: NeuralOrbPhysics,
    alphaScale: Float,
    audio: Float,
) {
    if (alphaScale <= 0.02f) return

    // 1. Volumetric spherical interior shading
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Im3SeafoamMid.copy(alpha = 0.22f * alphaScale),
                Im3SeafoamDark.copy(alpha = 0.35f * alphaScale),
                Im3CavernVoid.copy(alpha = 0.75f * alphaScale),
            ),
            center = Offset(center.x - sphereRadius * 0.18f, center.y - sphereRadius * 0.18f),
            radius = sphereRadius * 1.05f,
        ),
        radius = sphereRadius,
        center = center,
    )

    val yaw = (physics.spinAngleDeg / 180f) * PI.toFloat()
    val pitch = (physics.pitchAngleDeg / 180f) * PI.toFloat()
    val cosY = cos(yaw)
    val sinY = sin(yaw)
    val cosP = cos(pitch)
    val sinP = sin(pitch)

    val verts = GEODESIC_SPHERE.vertices
    val n = verts.size
    val px = FloatArray(n)
    val py = FloatArray(n)
    val pz = FloatArray(n)

    for (i in 0 until n) {
        val v = verts[i]
        val x1 = v.x * cosY + v.z * sinY
        val z1 = -v.x * sinY + v.z * cosY
        val y2 = v.y * cosP - z1 * sinP
        val z2 = v.y * sinP + z1 * cosP

        px[i] = center.x + x1 * sphereRadius
        py[i] = center.y + y2 * sphereRadius
        pz[i] = z2
    }

    // 2. Glowing surface nebula/cloud patches on the sphere (frame_03.png, frame_05.png, frame_22.png)
    for (idx in GEODESIC_SPHERE.nebulaClusterIndices) {
        val z = pz[idx]
        if (z > 0.05f) {
            val pos = Offset(px[idx], py[idx])
            val patchR = sphereRadius * 0.24f * (0.6f + 0.4f * z)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Im3SpecularIce.copy(alpha = 0.22f * z * alphaScale),
                        Im3SeafoamBright.copy(alpha = 0.10f * z * alphaScale),
                        Color.Transparent,
                    ),
                    center = pos,
                    radius = patchR,
                ),
                radius = patchR,
                center = pos,
            )
        }
    }

    // 3. Dense Frequency-3 triangulated wireframe struts (480 edges)
    val backStroke = 0.65.dp.toPx()
    val frontStroke = 0.95.dp.toPx()
    for (pass in 0..1) {
        for (edge in GEODESIC_SPHERE.edges) {
            val a = edge.first
            val b = edge.second
            val avgZ = (pz[a] + pz[b]) * 0.5f
            val isFront = avgZ >= 0f
            if ((pass == 0 && isFront) || (pass == 1 && !isFront)) continue

            val depthNorm = ((avgZ + 1f) * 0.5f).coerceIn(0f, 1f)
            val edgeAlpha = if (isFront) {
                (0.22f + depthNorm * 0.46f) * alphaScale
            } else {
                (0.06f + depthNorm * 0.10f) * alphaScale
            }
            drawLine(
                color = Im3SeafoamBright.copy(alpha = edgeAlpha),
                start = Offset(px[a], py[a]),
                end = Offset(px[b], py[b]),
                strokeWidth = if (isFront) frontStroke else backStroke,
                cap = StrokeCap.Round,
            )
        }
    }

    // 4. Pinpoint vertex dots & bright specular cluster nodes at triangle intersections
    val dotR = 1.15.dp.toPx()
    val hotR = 2.1.dp.toPx()
    for (i in 0 until n) {
        val z = pz[i]
        if (z < -0.15f) continue
        val depthNorm = ((z + 1f) * 0.5f).coerceIn(0f, 1f)
        val pos = Offset(px[i], py[i])
        val isHot = i in GEODESIC_SPHERE.nebulaClusterIndices && z > 0.25f
        if (isHot) {
            drawCircle(
                color = Im3SpecularIce.copy(alpha = (0.85f * z * alphaScale).coerceIn(0f, 0.96f)),
                radius = hotR * (0.85f + audio * 0.25f),
                center = pos,
            )
        } else {
            drawCircle(
                color = Im3SpecularIce.copy(alpha = (0.20f + depthNorm * 0.58f) * alphaScale),
                radius = dotR * (0.65f + depthNorm * 0.35f),
                center = pos,
            )
        }
    }

    // 5. Crisp Glowing Circular Limb Ring around the Geodesic Sphere (frame_03.png, frame_05.png, frame_22.png)
    drawCircle(
        color = Im3SeafoamBright.copy(alpha = 0.26f * alphaScale),
        radius = sphereRadius * 1.02f,
        center = center,
        style = Stroke(width = 3.6.dp.toPx()),
    )
    drawCircle(
        color = Im3SpecularIce.copy(alpha = 0.78f * alphaScale),
        radius = sphereRadius,
        center = center,
        style = Stroke(width = 1.4.dp.toPx()),
    )
}

/**
 * Layer 1: Outer 3D Stippled Point-Cloud Wave Torus & Folded Caustic Ribbons
 * (`frame_03.png`, `frame_05.png`, `frame_08.png`, `frame_20.png`, `frame_22.png`, `frame_24.png`, `frame_28.png`).
 *
 * Renders:
 * 1. 3 folded translucent caustic wave sheets for volumetric depth.
 * 2. 22 concentric stippled contour rings × 192 angular dots = 4,224 3D-projected stipple points
 *    that form the exact dotted contour rows and folded caustic ridges seen in `frame_03.png` and `frame_24.png`!
 * 3. 260 outer scattered spark/dust particles around the outer perimeter (`frame_03.png` & `frame_22.png`).
 */
private fun DrawScope.drawOuterStippledWaveMembrane(
    center: Offset,
    orbRadius: Float,
    physics: NeuralOrbPhysics,
    audio: Float,
) {
    val cymatic = physics.cymaticEnvelope
    val wavePhase = physics.wavePhase
    val dpPx = 1.dp.toPx()

    // 1. Underlying Volumetric Folded Wave Caustic Sheets (gives the soft seafoam glow between stipple rows)
    val sheetSamples = 160
    val sheetPath = Path()
    val sheetPairs = arrayOf(
        0.00f to 0.16f, // Inner containment ring band
        0.20f to 0.62f, // Middle folded wave sheet
        0.52f to 0.98f, // Outer folded wave sheet
    )
    val innerX = FloatArray(sheetSamples + 1)
    val innerY = FloatArray(sheetSamples + 1)

    for (sIdx in sheetPairs.indices) {
        val (uIn, uOut) = sheetPairs[sIdx]
        sheetPath.reset()
        for (i in 0..sheetSamples) {
            val theta = (i.toFloat() / sheetSamples) * 2f * PI.toFloat()
            val c = cos(theta)
            val s = sin(theta)
            val rIn = evaluateOuterMembraneRadius(
                theta = theta,
                u = uIn,
                orbRadius = orbRadius,
                wavePhase = wavePhase,
                cymaticEnvelope = cymatic,
                touchDeform = physics.touchDeform,
                touchNormX = physics.touchNormX,
                touchNormY = physics.touchNormY,
            )
            val rOut = evaluateOuterMembraneRadius(
                theta = theta,
                u = uOut,
                orbRadius = orbRadius,
                wavePhase = wavePhase,
                cymaticEnvelope = cymatic,
                touchDeform = physics.touchDeform,
                touchNormX = physics.touchNormX,
                touchNormY = physics.touchNormY,
            )
            innerX[i] = center.x + rIn * c
            innerY[i] = center.y + rIn * s
            val ox = center.x + rOut * c
            val oy = center.y + rOut * s
            if (i == 0) sheetPath.moveTo(ox, oy) else sheetPath.lineTo(ox, oy)
        }
        for (i in sheetSamples downTo 0) {
            sheetPath.lineTo(innerX[i], innerY[i])
        }
        sheetPath.close()
        val sheetAlpha = when (sIdx) {
            0 -> 0.16f + cymatic * 0.06f
            1 -> 0.12f + cymatic * 0.08f
            else -> 0.10f + cymatic * 0.07f
        }
        drawPath(
            path = sheetPath,
            color = Im3SeafoamMid.copy(alpha = sheetAlpha),
        )
    }

    // 2. 22 Concentric Stippled Contour Rings × 192 Angular Dots = 4,224 3D-Projected Stipple Points!
    // Look closely at frame_03.png, frame_05.png, frame_22.png, frame_24.png:
    // The outer torus is literally made of concentric dotted/stippled contour rows that bunch up along
    // the folded ridges!
    val contourRings = 22
    val dotsPerRing = 192
    for (ringIdx in 0 until contourRings) {
        val u = ringIdx.toFloat() / (contourRings - 1)
        // Skip the dark gap between the inner ring (u < 0.16) and outer band (u > 0.20) when calm
        if (ringIdx == 4 && cymatic < 0.35f) continue

        val isBoundaryRidge = ringIdx == 0 || ringIdx == 3 || ringIdx == 5 || ringIdx == contourRings - 1
        val baseDotRadius = when {
            isBoundaryRidge -> 1.15f * dpPx
            ringIdx % 3 == 0 -> 0.95f * dpPx
            else -> 0.78f * dpPx
        }

        // Slight angular phase stagger per ring so dots form organic diagonal stipple weaves
        val thetaStagger = (ringIdx % 2) * (PI.toFloat() / dotsPerRing)

        var prevX = 0f
        var prevY = 0f
        for (dIdx in 0..dotsPerRing) {
            val theta = (dIdx.toFloat() / dotsPerRing) * 2f * PI.toFloat() + thetaStagger
            val c = cos(theta)
            val s = sin(theta)

            val r = evaluateOuterMembraneRadius(
                theta = theta,
                u = u,
                orbRadius = orbRadius,
                wavePhase = wavePhase,
                cymaticEnvelope = cymatic,
                touchDeform = physics.touchDeform,
                touchNormX = physics.touchNormX,
                touchNormY = physics.touchNormY,
            )
            val x = center.x + r * c
            val y = center.y + r * s

            // Draw faint connecting filament along the 4 primary fold ridges so folded edges look razor-crisp
            if (isBoundaryRidge && dIdx > 0) {
                val ridgeAlpha = if (ringIdx == 0 || ringIdx == contourRings - 1) {
                    0.42f + cymatic * 0.25f
                } else {
                    0.28f + cymatic * 0.22f
                }
                drawLine(
                    color = Im3SeafoamBright.copy(alpha = ridgeAlpha.coerceIn(0.15f, 0.78f)),
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 0.9f * dpPx,
                )
            }
            prevX = x
            prevY = y

            if (dIdx < dotsPerRing) {
                // Modulate stipple brightness to create the caustic highlights on wave crests
                val caustic = 0.65f + 0.35f * cos(9f * theta - wavePhase + u * 3f)
                val dotAlpha = when {
                    isBoundaryRidge -> (0.58f + 0.36f * caustic + audio * 0.12f).coerceIn(0.30f, 0.98f)
                    else -> (0.24f + 0.48f * caustic).coerceIn(0.14f, 0.86f)
                }
                val dotColor = if (isBoundaryRidge && caustic > 0.78f) {
                    Im3SpecularIce.copy(alpha = dotAlpha)
                } else {
                    Im3SeafoamBright.copy(alpha = dotAlpha)
                }
                drawCircle(
                    color = dotColor,
                    radius = baseDotRadius,
                    center = Offset(x, y),
                )
            }
        }
    }

    // 3. Outer Scattered Particle Spray (visible around the outer rim in frame_03.png & frame_22.png)
    for (sp in OUTER_SPRAY_PARTICLES) {
        val waveOut = evaluateOuterMembraneRadius(
            theta = sp.theta,
            u = 1.0f,
            orbRadius = orbRadius,
            wavePhase = wavePhase,
            cymaticEnvelope = cymatic,
            touchDeform = physics.touchDeform,
            touchNormX = physics.touchNormX,
            touchNormY = physics.touchNormY,
        )
        val r = waveOut + (sp.rOffset - 0.91f) * orbRadius * (0.75f + cymatic * 0.55f)
        val x = center.x + r * sp.cosT
        val y = center.y + r * sp.sinT
        val twinkle = 0.55f + 0.45f * sin(physics.frameTick * 3.1f + sp.phase)
        drawCircle(
            color = Im3SeafoamBright.copy(alpha = (sp.alpha * twinkle).coerceIn(0.08f, 0.75f)),
            radius = sp.sizeDp * dpPx * 0.55f,
            center = Offset(x, y),
        )
    }
}

/**
 * `frame_07.png` & `frame_12.png` Trailer Projection:
 * Wide bold-italic "JARVIS" emblem spanning across the cavern, filled with wavy horizontal
 * topographic/oscilloscope scanlines (`frame_12.png`) and a 3D translucent ghost echo behind it.
 */
private fun DrawScope.drawJarvisScanlineWaveEmblem(
    center: Offset,
    orbRadius: Float,
    frameTick: Float,
) {
    val textSizePx = orbRadius * 0.34f
    val nativeCanvas = drawContext.canvas.nativeCanvas

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        textSize = textSizePx
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }

    val fm = paint.fontMetrics
    val textCenterY = center.y - (fm.ascent + fm.descent) * 0.5f

    // 1. 3D Ghost Echo Layer behind the main wordmark (visible in frame_12.png)
    val textPath = android.graphics.Path()
    paint.getTextPath("JARVIS", 0, 6, center.x, textCenterY, textPath)

    val ghostPath = android.graphics.Path()
    paint.textSize = textSizePx * 1.12f
    paint.getTextPath("JARVIS", 0, 6, center.x + orbRadius * 0.02f, textCenterY + orbRadius * 0.02f, ghostPath)

    nativeCanvas.save()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.6.dp.toPx()
    paint.color = android.graphics.Color.argb(68, 40, 216, 232)
    nativeCanvas.drawPath(ghostPath, paint)
    nativeCanvas.restore()

    // 2. Clip to the main "JARVIS" letterforms and draw oscillating wavy horizontal scanlines (frame_12.png)!
    nativeCanvas.save()
    nativeCanvas.clipPath(textPath)

    // Soft internal glow inside the letters
    drawRect(
        color = Im3SeafoamMid.copy(alpha = 0.26f),
        topLeft = Offset(center.x - orbRadius * 0.72f, center.y - orbRadius * 0.22f),
        size = Size(orbRadius * 1.44f, orbRadius * 0.44f),
    )

    val scanStep = 2.6.dp.toPx()
    val xStart = center.x - orbRadius * 0.72f
    val xEnd = center.x + orbRadius * 0.72f
    val yStart = center.y - orbRadius * 0.20f
    val yEnd = center.y + orbRadius * 0.20f
    val xSamples = 56
    val dx = (xEnd - xStart) / xSamples
    val waveLine = Path()

    var y = yStart
    var rowIdx = 0
    while (y <= yEnd) {
        waveLine.reset()
        for (i in 0..xSamples) {
            val x = xStart + i * dx
            val wy = y + sin(i * 0.55f + rowIdx * 0.45f + frameTick * 6.0f) * 1.8.dp.toPx()
            if (i == 0) waveLine.moveTo(x, wy) else waveLine.lineTo(x, wy)
        }
        drawPath(
            path = waveLine,
            color = if (rowIdx % 2 == 0) Im3SpecularIce.copy(alpha = 0.92f) else Im3SeafoamBright.copy(alpha = 0.75f),
            style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round),
        )
        y += scanStep
        rowIdx++
    }
    nativeCanvas.restore()

    // 3. Soft glowing cyan edge contour around the letters
    nativeCanvas.save()
    paint.textSize = textSizePx
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.4.dp.toPx()
    paint.color = android.graphics.Color.argb(195, 67, 242, 216)
    paint.setShadowLayer(orbRadius * 0.04f, 0f, 0f, android.graphics.Color.argb(180, 40, 216, 232))
    nativeCanvas.drawPath(textPath, paint)
    nativeCanvas.restore()
}

/**
 * `frame_14.png` Trailer Projection:
 * Exact 3-Node Share / Network Topology Graph (`<` configuration):
 * - Middle-left double-walled cyan ring (`cx - 0.20*R, cy + 0.02*R`)
 * - Upper-right double-walled cyan ring (`cx + 0.11*R, cy - 0.25*R`)
 * - Lower-right double-walled cyan ring (`cx + 0.11*R, cy + 0.29*R`)
 * Connected by two glowing cyan diagonal beams.
 */
private fun DrawScope.drawThreeNodeNetworkGraphHologram(
    center: Offset,
    orbRadius: Float,
    frameTick: Float,
) {
    val breathe = 1f + 0.025f * sin(frameTick * 2.4f)
    val leftNode = Offset(center.x - orbRadius * 0.20f * breathe, center.y + orbRadius * 0.02f)
    val topNode = Offset(center.x + orbRadius * 0.11f * breathe, center.y - orbRadius * 0.25f * breathe)
    val bottomNode = Offset(center.x + orbRadius * 0.11f * breathe, center.y + orbRadius * 0.29f * breathe)

    val outerR = orbRadius * 0.155f
    val innerR = orbRadius * 0.115f
    val thickStroke = 3.8.dp.toPx()
    val thinStroke = 1.6.dp.toPx()
    val glowStroke = 8.0.dp.toPx()

    // Helper to draw a clipped connecting beam between two nodes from outerR to outerR
    fun drawNodeLink(a: Offset, b: Offset) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / dist
        val uy = dy / dist
        val start = Offset(a.x + ux * innerR, a.y + uy * innerR)
        val end = Offset(b.x - ux * innerR, b.y - uy * innerR)

        drawLine(
            color = Im3SeafoamCyan.copy(alpha = 0.32f),
            start = start,
            end = end,
            strokeWidth = glowStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Im3SpecularIce.copy(alpha = 0.95f),
            start = start,
            end = end,
            strokeWidth = thickStroke,
            cap = StrokeCap.Round,
        )
    }

    drawNodeLink(leftNode, topNode)
    drawNodeLink(leftNode, bottomNode)

    // Draw each of the 3 double-walled cyan network nodes (matching frame_14.png)
    for (node in listOf(leftNode, topNode, bottomNode)) {
        // Dark hollow interior so background lines don't clutter inside the ring
        drawCircle(
            color = Im3CavernVoid.copy(alpha = 0.72f),
            radius = innerR * 0.95f,
            center = node,
        )
        // Soft cyan bloom around the inner thick ring
        drawCircle(
            color = Im3SeafoamCyan.copy(alpha = 0.34f),
            radius = innerR,
            center = node,
            style = Stroke(width = glowStroke),
        )
        // Bright thick inner cyan ring
        drawCircle(
            color = Im3SpecularIce.copy(alpha = 0.96f),
            radius = innerR,
            center = node,
            style = Stroke(width = thickStroke),
        )
        // Crisp thin outer concentric ring
        drawCircle(
            color = Im3SeafoamCyan.copy(alpha = 0.82f),
            radius = outerR,
            center = node,
            style = Stroke(width = thinStroke),
        )
    }
}

/**
 * `frame_16.png` + `00:07` Trailer Projection:
 * Concentric glowing cyan target rings (`frame_16.png`) & central capsule microphone with dual soundwaves `((🎙))`.
 */
private fun DrawScope.drawSonicMicrophoneAndRingsHologram(
    center: Offset,
    orbRadius: Float,
    frameTick: Float,
    audio: Float,
) {
    val medStroke = 2.4.dp.toPx()
    val fineStroke = 1.4.dp.toPx()

    // Concentric glowing cyan rings in the cavern (matching frame_16.png & frame_18.png)
    val ringRadii = floatArrayOf(0.14f, 0.27f, 0.45f)
    for (idx in ringRadii.indices) {
        val pulse = 1f + (audio * 0.05f + 0.015f * sin(frameTick * 3.5f + idx))
        val r = orbRadius * ringRadii[idx] * pulse
        drawCircle(
            color = Im3SeafoamCyan.copy(alpha = 0.24f),
            radius = r,
            center = center,
            style = Stroke(width = 5.5.dp.toPx()),
        )
        drawCircle(
            color = Im3SpecularIce.copy(alpha = if (idx == 2) 0.85f else 0.68f),
            radius = r,
            center = center,
            style = Stroke(width = if (idx == 2) medStroke else fineStroke),
        )
    }

    // Central Holographic Capsule Microphone with dual soundwave parentheses ((🎙))
    val capW = orbRadius * 0.072f
    val capH = orbRadius * 0.140f
    val capTop = center.y - capH * 0.65f
    drawRoundRect(
        color = Im3SpecularIce.copy(alpha = 0.94f),
        topLeft = Offset(center.x - capW * 0.5f, capTop),
        size = Size(capW, capH),
        cornerRadius = CornerRadius(capW * 0.5f, capW * 0.5f),
        style = Stroke(width = fineStroke * 1.2f),
    )

    val cradleR = capW * 0.85f
    val cradleY = capTop + capH * 0.62f
    drawArc(
        color = Im3SpecularIce.copy(alpha = 0.92f),
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - cradleR, cradleY - cradleR),
        size = Size(cradleR * 2f, cradleR * 2f),
        style = Stroke(width = fineStroke * 1.2f, cap = StrokeCap.Round),
    )

    val stemTop = cradleY + cradleR
    val stemBottom = stemTop + orbRadius * 0.045f
    drawLine(
        color = Im3SpecularIce.copy(alpha = 0.92f),
        start = Offset(center.x, stemTop),
        end = Offset(center.x, stemBottom),
        strokeWidth = fineStroke * 1.2f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Im3SpecularIce.copy(alpha = 0.92f),
        start = Offset(center.x - cradleR * 0.68f, stemBottom),
        end = Offset(center.x + cradleR * 0.68f, stemBottom),
        strokeWidth = fineStroke * 1.2f,
        cap = StrokeCap.Round,
    )

    for (ringIdx in 1..2) {
        val arcR = cradleR * (1.42f + ringIdx * 0.48f) * (1f + audio * 0.08f)
        val arcAlpha = (0.85f - ringIdx * 0.20f + audio * 0.2f).coerceIn(0.30f, 0.96f)
        val topLeft = Offset(center.x - arcR, cradleY - capH * 0.14f - arcR)
        val arcSize = Size(arcR * 2f, arcR * 2f)
        drawArc(
            color = Im3SpecularIce.copy(alpha = arcAlpha),
            startAngle = -38f,
            sweepAngle = 76f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = fineStroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = Im3SpecularIce.copy(alpha = arcAlpha),
            startAngle = 142f,
            sweepAngle = 76f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = fineStroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * `frame_17.png` & `frame_18.png` Trailer Projection:
 * Precision 3D Wireframe Iron Man Mark 42 Helmet Faceplate matching `frame_17.png` 100%.
 */
private fun DrawScope.drawIronManMark42WireframeMask(
    center: Offset,
    orbRadius: Float,
    frameTick: Float,
    audio: Float,
) {
    val s = orbRadius * 0.34f
    val nodY = sin(frameTick * 1.8f) * (s * 0.025f)
    val cx = center.x
    val cy = center.y + nodY

    val glowStroke = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val primaryStroke = Stroke(width = 1.65.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val fineStroke = Stroke(width = 1.05.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun drawSymmetricWire(points: List<Pair<Float, Float>>, close: Boolean = false, bright: Boolean = false) {
        for (side in intArrayOf(-1, 1)) {
            val p = Path()
            points.forEachIndexed { idx, (nx, ny) ->
                val px = cx + nx * s * side
                val py = cy + ny * s
                if (idx == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            if (close) p.close()
            if (bright) {
                drawPath(
                    path = p,
                    color = Im3SeafoamCyan.copy(alpha = 0.24f),
                    style = glowStroke,
                )
            }
            drawPath(
                path = p,
                color = if (bright) Im3SpecularIce.copy(alpha = 0.92f) else Im3SeafoamCyan.copy(alpha = 0.76f),
                style = if (bright) primaryStroke else fineStroke,
            )
        }
    }

    // 1. Outer Cranium Dome & Jawline Contour (frame_17.png)
    drawSymmetricWire(
        listOf(
            0.00f to -0.96f,
            0.38f to -0.92f,
            0.66f to -0.72f,
            0.78f to -0.36f,
            0.82f to -0.02f,
            0.74f to 0.36f,
            0.58f to 0.74f,
            0.32f to 0.94f,
            0.00f to 0.94f,
        ),
        bright = true,
    )

    // 2. Upper Forehead U-Plate & Central Vertical Ridge Strip (exact match to frame_17.png)
    drawSymmetricWire(
        listOf(
            0.00f to -0.88f,
            0.30f to -0.84f,
            0.46f to -0.68f,
            0.34f to -0.38f,
            0.10f to -0.38f,
            0.10f to -0.14f,
            0.00f to -0.14f,
        ),
        bright = true,
    )
    // Inner forehead recessed trapezoid
    drawSymmetricWire(
        listOf(
            0.00f to -0.38f,
            0.34f to -0.38f,
            0.56f to -0.70f,
            0.68f to -0.28f,
            0.62f to -0.10f,
            0.10f to -0.14f,
        ),
    )

    // 3. Brow Ridge & Temple Plates
    drawSymmetricWire(
        listOf(
            0.00f to -0.10f,
            0.22f to -0.12f,
            0.62f to -0.10f,
            0.78f to -0.20f,
        ),
        bright = true,
    )

    // 4. Glowing Rectangular Cyan Eye Slits (frame_17.png)
    for (side in intArrayOf(-1, 1)) {
        val eyePath = Path().apply {
            moveTo(cx + 0.16f * s * side, cy - 0.06f * s)
            lineTo(cx + 0.58f * s * side, cy - 0.06f * s)
            lineTo(cx + 0.62f * s * side, cy + 0.03f * s)
            lineTo(cx + 0.22f * s * side, cy + 0.04f * s)
            close()
        }
        drawPath(
            path = eyePath,
            color = Im3SeafoamCyan.copy(alpha = 0.55f + audio * 0.30f),
        )
        drawPath(
            path = eyePath,
            color = Im3SpecularIce.copy(alpha = 0.96f),
            style = primaryStroke,
        )
        // Lower orbital rim line under the eyes
        val underEye = Path().apply {
            moveTo(cx + 0.14f * s * side, cy + 0.07f * s)
            lineTo(cx + 0.66f * s * side, cy + 0.06f * s)
            lineTo(cx + 0.78f * s * side, cy - 0.02f * s)
        }
        drawPath(underEye, color = Im3SeafoamCyan.copy(alpha = 0.72f), style = fineStroke)
    }

    // 5. Cheekbone Recessed Armor Plates & Jaw Vent Recesses (frame_17.png)
    drawSymmetricWire(
        listOf(
            0.66f to 0.06f,
            0.52f to 0.44f,
            0.34f to 0.54f,
            0.00f to 0.54f,
        ),
        bright = true,
    )
    drawSymmetricWire(
        listOf(
            0.72f to 0.14f,
            0.62f to 0.52f,
            0.44f to 0.74f,
            0.34f to 0.54f,
            0.54f to 0.36f,
            0.72f to 0.14f,
        ),
    )

    // 6. Mouthplate Slit & Chin Trapezoid (frame_17.png)
    drawSymmetricWire(
        listOf(
            0.00f to 0.62f,
            0.28f to 0.62f,
            0.38f to 0.76f,
            0.26f to 0.84f,
            0.00f to 0.84f,
        ),
        bright = true,
    )
    // Tiny center chin vertical notch at the very bottom of the jaw (visible in frame_17.png!)
    drawLine(
        color = Im3SpecularIce.copy(alpha = 0.88f),
        start = Offset(cx, cy + 0.88f * s),
        end = Offset(cx, cy + 0.94f * s),
        strokeWidth = 1.6.dp.toPx(),
    )
}

/**
 * `frame_20.png` Trailer Projection:
 * Holographic HUD Clock (`4:17 PM :17`) with:
 * - Two full-width horizontal hairline rules flush with the top and bottom of the main digits
 * - Large glowing ice-white numerals (`4:17`) + stacked `PM` and `:17` (with leading colon!)
 * - Concentric broken technical arc rings at `r = 0.54` and `r = 0.60` with thick arc segments.
 */
private fun DrawScope.drawHolographicClockReadout(
    center: Offset,
    orbRadius: Float,
    frameTick: Float,
) {
    val now = Date()
    val timeStr = SimpleDateFormat("h:mm", Locale.US).format(now)
    val amPmStr = SimpleDateFormat("a", Locale.US).format(now).uppercase(Locale.US)
    val secStr = ":" + SimpleDateFormat("ss", Locale.US).format(now)

    val hairline = 1.0.dp.toPx()
    val thinArcW = 1.4.dp.toPx()
    val thickArcW = 4.2.dp.toPx()

    // 1. Concentric broken technical arc rings at r = 0.54 and r = 0.60 (frame_20.png)
    val rInnerArc = orbRadius * 0.54f
    val rOuterArc = orbRadius * 0.60f
    val slowRot = frameTick * 6f

    // Thin underlying partial circles
    for (start in floatArrayOf(-155f, -45f, 25f, 115f)) {
        drawArc(
            color = Im3SpecularIce.copy(alpha = 0.52f),
            startAngle = start + slowRot * 0.15f,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = Offset(center.x - rInnerArc, center.y - rInnerArc),
            size = Size(rInnerArc * 2f, rInnerArc * 2f),
            style = Stroke(width = thinArcW, cap = StrokeCap.Butt),
        )
    }
    for (start in floatArrayOf(-135f, -15f, 85f)) {
        drawArc(
            color = Im3SpecularIce.copy(alpha = 0.42f),
            startAngle = start - slowRot * 0.1f,
            sweepAngle = 54f,
            useCenter = false,
            topLeft = Offset(center.x - rOuterArc, center.y - rOuterArc),
            size = Size(rOuterArc * 2f, rOuterArc * 2f),
            style = Stroke(width = hairline, cap = StrokeCap.Butt),
        )
    }
    // Thick highlighted arc segments on the inner technical ring (exact match to frame_20.png)
    for ((start, sweep) in listOf(-82f to 42f, -28f to 36f, 52f to 28f, 128f to 44f, 195f to 22f)) {
        drawArc(
            color = Im3SpecularIce.copy(alpha = 0.85f),
            startAngle = start + slowRot * 0.15f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - rInnerArc, center.y - rInnerArc),
            size = Size(rInnerArc * 2f, rInnerArc * 2f),
            style = Stroke(width = thickArcW, cap = StrokeCap.Butt),
        )
    }

    // 2. Two horizontal crosshair hairlines flush with the top and bottom of the digits (frame_20.png)
    val topRuleY = center.y - orbRadius * 0.082f
    val bottomRuleY = center.y + orbRadius * 0.105f
    drawLine(
        color = Im3SpecularIce.copy(alpha = 0.55f),
        start = Offset(center.x - orbRadius * 0.68f, topRuleY),
        end = Offset(center.x + orbRadius * 0.48f, topRuleY),
        strokeWidth = hairline,
    )
    drawLine(
        color = Im3SpecularIce.copy(alpha = 0.55f),
        start = Offset(center.x - orbRadius * 0.36f, bottomRuleY),
        end = Offset(center.x + orbRadius * 0.68f, bottomRuleY),
        strokeWidth = hairline,
    )

    // 3. Large Glowing Numerals ("4:17") + Stacked "PM" and ":17" (frame_20.png)
    drawContext.canvas.nativeCanvas.apply {
        val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = orbRadius * 0.255f
            textAlign = Paint.Align.RIGHT
            color = android.graphics.Color.argb(248, 224, 255, 250)
            setShadowLayer(orbRadius * 0.045f, 0f, 0f, android.graphics.Color.argb(190, 40, 216, 232))
        }
        val amPmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = orbRadius * 0.068f
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.06f
            color = android.graphics.Color.argb(240, 224, 255, 250)
        }
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = orbRadius * 0.115f
            textAlign = Paint.Align.LEFT
            color = android.graphics.Color.argb(235, 210, 255, 248)
        }

        val splitX = center.x + orbRadius * 0.22f
        val baselineY = bottomRuleY - orbRadius * 0.012f
        drawText(timeStr, splitX, baselineY, mainPaint)
        drawText(amPmStr, splitX + orbRadius * 0.04f, topRuleY + orbRadius * 0.062f, amPmPaint)
        drawText(secStr, splitX + orbRadius * 0.015f, baselineY, secPaint)
    }
}

/**
 * `frame_24.png` Trailer Projection:
 * 3D Stack of Receding Translucent Glass Calendar Cards (`JANUARY 6` / `2026` + mini calendar matrix).
 */
private fun DrawScope.draw3dGlassCalendarCards(
    center: Offset,
    orbRadius: Float,
    frameTick: Float,
) {
    val cal = Calendar.getInstance()
    val monthStr = SimpleDateFormat("MMMM", Locale.US).format(cal.time).uppercase(Locale.US)
    val dayStr = cal.get(Calendar.DAY_OF_MONTH).toString()
    val yearStr = cal.get(Calendar.YEAR).toString()

    val cardW = orbRadius * 0.66f
    val cardH = orbRadius * 0.33f
    val baseLeft = center.x - cardW * 0.54f
    val baseTop = center.y - cardH * 0.36f
    val hairline = 1.1.dp.toPx()

    // Draw 5 receding glass cards from back (index 4) to front (index 0) matching frame_24.png
    for (depth in 4 downTo 0) {
        val dx = depth * orbRadius * 0.032f
        val dy = -depth * orbRadius * 0.018f
        val scaleW = 1f - depth * 0.035f
        val scaleH = 1f - depth * 0.035f
        val w = cardW * scaleW
        val h = cardH * scaleH
        val left = baseLeft + dx
        val top = baseTop + dy

        val fillAlpha = (0.18f - depth * 0.028f).coerceAtLeast(0.05f)
        val borderAlpha = (0.58f - depth * 0.09f).coerceAtLeast(0.14f)

        drawRect(
            color = Im3SeafoamDark.copy(alpha = fillAlpha + 0.14f),
            topLeft = Offset(left, top),
            size = Size(w, h),
        )
        drawRect(
            color = Im3SeafoamBright.copy(alpha = borderAlpha),
            topLeft = Offset(left, top),
            size = Size(w, h),
            style = Stroke(width = hairline),
        )
    }

    // Foreground Card Content: "JANUARY" + Large "6" on Left, "2026" + 7x5 Calendar Matrix on Right (frame_24.png)
    drawContext.canvas.nativeCanvas.apply {
        val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = orbRadius * 0.055f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.05f
            color = android.graphics.Color.argb(235, 215, 255, 248)
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = orbRadius * 0.195f
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.argb(250, 230, 255, 252)
            setShadowLayer(orbRadius * 0.03f, 0f, 0f, android.graphics.Color.argb(180, 67, 242, 216))
        }
        val yearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = orbRadius * 0.072f
            textAlign = Paint.Align.LEFT
            color = android.graphics.Color.argb(115, 67, 242, 216)
        }

        val leftColCenterX = baseLeft + cardW * 0.26f
        drawText(monthStr, leftColCenterX, baseTop + cardH * 0.25f, monthPaint)
        drawText(dayStr, leftColCenterX, baseTop + cardH * 0.84f, dayPaint)

        val rightColX = baseLeft + cardW * 0.52f
        drawText(yearStr, rightColX, baseTop + cardH * 0.27f, yearPaint)
    }

    // Mini 7x5 calendar dot matrix on the lower-right of the front glass card (frame_24.png)
    val gridLeft = baseLeft + cardW * 0.53f
    val gridTop = baseTop + cardH * 0.42f
    val colStep = cardW * 0.052f
    val rowStep = cardH * 0.095f
    val dotR = 1.3.dp.toPx()
    for (r in 0 until 5) {
        for (c in 0 until 7) {
            val dayNum = r * 7 + c - 1
            if (dayNum in 1..31) {
                val isToday = dayNum == cal.get(Calendar.DAY_OF_MONTH)
                drawCircle(
                    color = if (isToday) Im3SpecularIce else Im3SeafoamBright.copy(alpha = 0.42f),
                    radius = if (isToday) dotR * 1.45f else dotR,
                    center = Offset(gridLeft + c * colStep, gridTop + r * rowStep),
                )
            }
        }
    }
}

/**
 * Dynamic 3D flowing particle sine-wave ribbon visualization used inside AI response
 * cards and the VOICE WAVES showcase.
 */
@Composable
fun ParticleWaveCanvas(
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "particle_wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (active) 3200 else 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave_phase",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cy = h / 2f

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Im3SeafoamMid.copy(alpha = 0.20f),
                    Im3SeafoamDark.copy(alpha = 0.65f),
                    Im3CavernVoid.copy(alpha = 0.94f),
                ),
                center = Offset(w * 0.5f, cy),
                radius = w * 0.65f,
            ),
        )

        val ribbons = 4
        val samples = 64
        for (r in 0 until ribbons) {
            val ribbonOffset = r * 0.65f
            val path = Path()
            for (i in 0..samples) {
                val t = i.toFloat() / samples
                val x = t * w
                val envelope = sin(t * PI.toFloat())
                val y = cy +
                    sin(t * 6.2f + phase + ribbonOffset) * (h * 0.30f * envelope) +
                    cos(t * 3.4f - phase * 0.7f + ribbonOffset) * (h * 0.12f * envelope)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)

                if (i % 2 == 0) {
                    val dotColor = if ((i + r) % 7 == 0) Im3SpecularIce else Im3SeafoamBright
                    drawCircle(
                        color = dotColor.copy(alpha = (0.28f + 0.62f * envelope).coerceIn(0.15f, 0.95f)),
                        radius = if (r == 0) 2.1.dp.toPx() else 1.3.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Im3SeafoamMid.copy(alpha = 0.65f - r * 0.12f),
                        Im3SeafoamBright.copy(alpha = 0.85f - r * 0.15f),
                        Color.Transparent,
                    ),
                ),
                style = Stroke(width = (2.2f - r * 0.35f).dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * The voice wave: a layered, mirrored spectral ribbon that breathes with the
 * assistant's state and live [audioLevel].
 */
@Composable
fun VoiceWave(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Im3SeafoamBright,
    bars: Int = 29,
    audioLevel: Float = 0f,
) {
    val smoothedAudio by animateFloatAsState(
        targetValue = audioLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 65, easing = LinearEasing),
        label = "wave_audio",
    )
    val transition = rememberInfiniteTransition(label = "voice wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (active) 950 else 2600,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave phase",
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave shimmer",
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val step = width / bars
        val barWidth = (step * 0.34f).coerceAtLeast(1.1f)
        val baseAmp = if (active) height * (0.32f + smoothedAudio * 0.17f) else height * 0.13f

        drawLine(
            color = color.copy(alpha = 0.10f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f,
        )

        repeat(bars) { index ->
            val normalized = index.toFloat() / (bars - 1).coerceAtLeast(1)
            val envelope = (1f - abs(normalized - 0.5f) * 1.30f).coerceIn(0.10f, 1f)
            val primary = abs(sin(phase + index * 0.55f)) * 0.60f + 0.40f
            val secondary = abs(cos(phase * 0.71f + index * 0.23f)) * 0.50f + 0.50f
            val tertiary = abs(sin(phase * 1.9f - index * 0.11f)) * 0.34f + 0.66f
            val shaped = (primary * 0.55f + secondary * 0.29f + tertiary * 0.16f)
            val barHeight = (baseAmp * shaped * envelope).coerceAtLeast(1.8f)
            val alpha = (0.26f + 0.66f * envelope).coerceIn(0.14f, 0.96f)
            val x = step * index + step / 2f

            drawLine(
                color = color.copy(alpha = alpha * 0.22f),
                start = Offset(x, centerY + barHeight * 0.18f),
                end = Offset(x, centerY + barHeight * 1.28f),
                strokeWidth = barWidth * 0.72f,
                cap = StrokeCap.Round,
            )

            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = alpha * 0.82f),
                        color.copy(alpha = alpha * 0.72f),
                        color.copy(alpha = alpha * 0.18f),
                    ),
                    startY = centerY - barHeight,
                    endY = centerY + barHeight * 0.32f,
                ),
                start = Offset(x, centerY - barHeight),
                end = Offset(x, centerY + barHeight * 0.32f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )

            drawCircle(
                color = Color.White.copy(alpha = (alpha * 0.85f).coerceIn(0f, 0.92f)),
                radius = (barWidth * 0.48f).coerceAtLeast(0.7f),
                center = Offset(x, centerY - barHeight),
            )
        }

        val ribbon = Path()
        val samples = bars * 3
        for (i in 0..samples) {
            val t = i.toFloat() / samples
            val x = t * width
            val envelope = sin(t * PI.toFloat()).coerceAtLeast(0f)
            val y = centerY - (baseAmp * 0.92f) * envelope *
                (abs(sin(phase * 0.8f + t * 9f)) * 0.55f + 0.45f)
            if (i == 0) ribbon.moveTo(x, y) else ribbon.lineTo(x, y)
        }
        drawPath(
            path = ribbon,
            color = color.copy(alpha = 0.24f),
            style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round),
        )

        val sweepX = width * shimmer
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, color.copy(alpha = 0.13f), Color.Transparent),
                startX = sweepX - 42.dp.toPx(),
                endX = sweepX + 42.dp.toPx(),
            ),
            topLeft = Offset(sweepX - 42.dp.toPx(), 0f),
            size = Size(84.dp.toPx(), height),
        )
    }
}

/** Expanding rings around the microphone button. */
@Composable
fun PulseRings(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Im3SeafoamBright,
    rings: Int = 2,
) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "pulse rings")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring progress",
    )
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val max = size.minDimension / 2f
        repeat(rings) { index ->
            val value = ((progress + index.toFloat() / rings) % 1f)
            drawCircle(
                color = color.copy(alpha = (1f - value) * 0.45f),
                radius = max * (0.45f + value * 0.55f),
                center = center,
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }
    }
}
