package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
 * Real-time 60fps Neural Orb Physics simulation state:
 * - Hooke's law spring-mass-damper core displacement & velocity
 * - Rotational inertia & angular momentum integration with acoustic torque impulses
 * - Coupled 24-node radial spring-tethered neural swarm with neighbor wave propagation
 * - Acoustic pressure shockwave emission on speech/voice transients
 */
@Stable
class NeuralOrbPhysics(val nodeCount: Int = 24) {
    var spinAngleDeg by mutableFloatStateOf(0f)
    var counterSpinAngleDeg by mutableFloatStateOf(0f)
    var angularVelocity by mutableFloatStateOf(24f)
    var coreScale by mutableFloatStateOf(1f)
    var coreVelocity by mutableFloatStateOf(0f)
    var shockwaveProgress by mutableFloatStateOf(0f)
    var shockwaveIntensity by mutableFloatStateOf(0f)
    var frameTick by mutableFloatStateOf(0f)

    val nodeRadialOffset = FloatArray(nodeCount) { 0f }
    val nodeRadialVelocity = FloatArray(nodeCount) { 0f }
    val nodePhase = FloatArray(nodeCount) { idx -> (idx.toFloat() / nodeCount) * (2f * PI.toFloat()) }
    private var prevAudio = 0f

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

        // 1. Angular momentum & rotational inertia with acoustic torque
        val targetOmega = when {
            thinking -> 132f
            speaking -> 88f
            listening -> 52f
            else -> 22f
        } + audio * 115f
        val angularAccel = (targetOmega - angularVelocity) * 4.5f
        angularVelocity = (angularVelocity + angularAccel * dt).coerceIn(12f, 280f)
        spinAngleDeg = (spinAngleDeg + angularVelocity * dt) % 360f
        counterSpinAngleDeg = (counterSpinAngleDeg + angularVelocity * 0.72f * dt) % 360f

        // 2. Spring-mass-damper core radius (F = -k * x - c * v + F_audio)
        val targetScale = when {
            speaking -> 1.05f + audio * 0.16f
            thinking -> 1.04f + sin(frameTick * 5f) * 0.03f
            listening -> 1.02f + audio * 0.12f
            else -> 1.0f
        }
        val stiffness = 155f
        val damping = 13.5f
        val audioImpulse = (audio - prevAudio).coerceAtLeast(0f) * 4.2f
        val springForce = (targetScale - coreScale) * stiffness - coreVelocity * damping + audioImpulse * 28f
        coreVelocity += springForce * dt
        coreScale = (coreScale + coreVelocity * dt).coerceIn(0.86f, 1.32f)

        // 3. Acoustic shockwave trigger on sudden voice energy rise
        if (audio - prevAudio > 0.09f || (shockwaveIntensity <= 0.02f && (speaking || listening) && audio > 0.22f)) {
            shockwaveProgress = 0f
            shockwaveIntensity = (0.45f + audio * 0.55f).coerceAtMost(1f)
        }
        if (shockwaveIntensity > 0.01f) {
            shockwaveProgress += dt * (0.85f + audio * 0.65f)
            if (shockwaveProgress >= 1f) {
                shockwaveProgress = 0f
                shockwaveIntensity = 0f
            } else {
                shockwaveIntensity *= (1f - dt * 1.15f).coerceAtLeast(0f)
            }
        }
        prevAudio = audio

        // 4. Coupled spring-tethered neural node swarm with neighbor wave propagation
        val nodeStiffness = 92f
        val nodeDamping = 8.5f
        val coupling = 24f
        for (i in 0 until nodeCount) {
            val left = nodeRadialOffset[(i - 1 + nodeCount) % nodeCount]
            val right = nodeRadialOffset[(i + 1) % nodeCount]
            val harmonicDrive = sin(frameTick * 3.2f + nodePhase[i] * 3f) *
                (if (thinking || speaking || listening) 0.06f + audio * 0.18f else 0.02f)
            val acousticKick = if (i % 3 == ((frameTick * 10f).toInt() % 3)) audio * 0.24f else 0f
            val targetR = harmonicDrive + acousticKick
            val neighborForce = ((left + right) * 0.5f - nodeRadialOffset[i]) * coupling
            val force = (targetR - nodeRadialOffset[i]) * nodeStiffness +
                neighborForce -
                nodeRadialVelocity[i] * nodeDamping
            nodeRadialVelocity[i] += force * dt
            nodeRadialOffset[i] = (nodeRadialOffset[i] + nodeRadialVelocity[i] * dt).coerceIn(-0.22f, 0.32f)
        }

        frameTick = (frameTick + dt) % 1000f
    }
}

@Composable
fun rememberNeuralOrbPhysics(
    state: ReactorState,
    audioLevel: Float,
    toolActive: Boolean,
    nodeCount: Int = 24,
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
 * The J.A.R.V.I.S. Neural Orb: a professional, glassy sphere with real depth.
 * A dark glass body lit by a copper and a cyan rim, an internal depth-sorted 3D
 * neural lattice whose synapses pulse link to link, two counter-rotating orbit
 * rings and one quiet precision tick ring outside, all breathing with the live
 * audio level and the reactor state.
 *
 * Still driven in real time by [NeuralOrbPhysics].
 */
@Composable
fun ArcReactor(
    state: ReactorState,
    modifier: Modifier = Modifier,
    accent: Color = JarvisPalette.Cyan,
    audioLevel: Float = 0f,
    toolActive: Boolean = false,
) {
    val thinking = state == ReactorState.THINKING || toolActive
    val speaking = state == ReactorState.SPEAKING
    val listening = state == ReactorState.LISTENING
    val active = state != ReactorState.IDLE && state != ReactorState.BOOT

    val physics = rememberNeuralOrbPhysics(
        state = state,
        audioLevel = audioLevel,
        toolActive = toolActive,
        nodeCount = 18,
    )

    val smoothedAudio by animateFloatAsState(
        targetValue = audioLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 70, easing = LinearEasing),
        label = "orb_audio",
    )

    val spin = physics.spinAngleDeg
    val counterSpin = physics.counterSpinAngleDeg
    val neuralPhase = (physics.frameTick * 2.2f) % (2f * PI.toFloat())
    val breathe = physics.coreScale
    val copper = JarvisPalette.Amber

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val hairline = 0.9.dp.toPx()
        val fine = 1.3.dp.toPx()
        val medium = 2.0.dp.toPx()
        val stateGlow = when {
            thinking -> 1.00f
            speaking -> 0.92f
            listening -> 0.85f
            else -> 0.62f
        } + smoothedAudio * 0.18f

        // 1. Ambient halo that breathes with the voice.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.30f * stateGlow),
                    accent.copy(alpha = 0.10f * stateGlow),
                    copper.copy(alpha = if (thinking) 0.10f else 0.035f),
                    Color.Transparent,
                ),
                center = center,
                radius = radius * 1.12f,
            ),
            radius = radius * 1.05f,
            center = center,
        )

        // 2. Glass sphere body: deep navy glass, brighter toward the centre-top.
        val glassRadius = radius * 0.94f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF0D3550).copy(alpha = 0.94f),
                    Color(0xFF082741).copy(alpha = 0.96f),
                    Color(0xFF04121E).copy(alpha = 0.98f),
                    JarvisPalette.Void,
                ),
                center = Offset(center.x - glassRadius * 0.20f, center.y - glassRadius * 0.26f),
                radius = glassRadius * 1.55f,
            ),
            radius = glassRadius,
            center = center,
        )

        // 3. Rim light: a warm copper arc top-left, a cyan arc bottom-right.
        drawArc(
            brush = Brush.sweepGradient(
                0f to Color.Transparent,
                0.05f to copper.copy(alpha = 0.10f),
                0.16f to copper.copy(alpha = 0.62f),
                0.30f to copper.copy(alpha = 0.12f),
                0.34f to Color.Transparent,
                0.60f to Color.Transparent,
                0.74f to accent.copy(alpha = 0.55f),
                0.86f to accent.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = center,
            ),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(center.x - glassRadius, center.y - glassRadius),
            size = Size(glassRadius * 2f, glassRadius * 2f),
            style = Stroke(width = medium, cap = StrokeCap.Round),
        )
        drawCircle(
            color = JarvisPalette.Line.copy(alpha = 0.85f),
            radius = glassRadius,
            center = center,
            style = Stroke(width = hairline),
        )

        // 4. Internal neural lattice: depth-sorted, slowly rotating in 3D.
        val latticeRadius = glassRadius * 0.66f
        val latBands = 4
        val lonSteps = 9
        val rotation = spin * 0.45f * PI.toFloat() / 180f

        data class OrbNode(val x: Float, val y: Float, val depth: Float, val major: Boolean)

        val nodes = ArrayList<OrbNode>((latBands - 1) * lonSteps + 2)
        nodes.add(OrbNode(center.x, center.y - latticeRadius, 0.5f, true))
        for (lat in 1 until latBands) {
            val theta = (lat.toFloat() / latBands) * PI.toFloat()
            val y = -cos(theta)
            val ringScale = sin(theta)
            for (lon in 0 until lonSteps) {
                val phi = (lon.toFloat() / lonSteps) * 2f * PI.toFloat() + rotation
                val x3 = ringScale * cos(phi)
                val z3 = ringScale * sin(phi)
                nodes.add(
                    OrbNode(
                        x = center.x + x3 * latticeRadius,
                        y = center.y + y * latticeRadius * 0.94f,
                        depth = (z3 + 1f) / 2f,
                        major = lon % 3 == 0,
                    ),
                )
            }
        }
        nodes.add(OrbNode(center.x, center.y + latticeRadius, 0.5f, true))

        val ringLink = ArrayList<Pair<Int, Int>>()
        for (ring in 0 until latBands - 1) {
            val rowStart = 1 + ring * lonSteps
            for (lon in 0 until lonSteps) {
                ringLink += rowStart + lon to rowStart + (lon + 1) % lonSteps
                if (ring < latBands - 2) {
                    ringLink += rowStart + lon to rowStart + lonSteps + lon
                } else {
                    ringLink += rowStart + lon to nodes.size - 1
                }
                if (ring == 0) {
                    ringLink += rowStart + lon to 0
                }
            }
        }
        val sortedLinks = ringLink.sortedWith(
            compareBy { link -> (nodes[link.first].depth + nodes[link.second].depth) * 0.5f },
        )
        for (link in sortedLinks) {
            val a = nodes[link.first]
            val b = nodes[link.second]
            val depthAlpha = (a.depth + b.depth) * 0.5f
            drawLine(
                color = accent.copy(
                    alpha = (0.03f + depthAlpha * 0.20f).coerceIn(0.02f, 0.34f),
                ),
                start = Offset(a.x, a.y),
                end = Offset(b.x, b.y),
                strokeWidth = hairline,
                cap = StrokeCap.Round,
            )
        }

        // Living core that breathes with the voice.
        val coreRadius = latticeRadius *
            (0.30f + smoothedAudio * 0.10f) *
            (0.9f + (breathe - 1f) * 0.9f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.92f),
                    JarvisPalette.CyanBright.copy(alpha = 0.60f),
                    accent.copy(alpha = 0.26f),
                    Color.Transparent,
                ),
                center = center,
                radius = coreRadius * 2.2f,
            ),
            radius = coreRadius * 2.2f,
            center = center,
        )

        // Front-hemisphere nodes with synapse pulses that travel along links.
        nodes.forEachIndexed { index, node ->
            if (node.depth < 0.40f) return@forEachIndexed
            val frontAlpha = ((node.depth - 0.35f) * 1.7f).coerceIn(0.12f, 0.95f)
            drawCircle(
                color = when {
                    node.major && thinking -> copper.copy(alpha = frontAlpha)
                    node.major && (speaking || listening) ->
                        JarvisPalette.CyanBright.copy(alpha = frontAlpha)
                    node.major -> accent.copy(alpha = frontAlpha * 0.9f)
                    else -> Color.White.copy(alpha = frontAlpha * 0.75f)
                },
                radius = if (node.major) 2.1.dp.toPx() else 1.25.dp.toPx(),
                center = Offset(node.x, node.y),
            )
            if (node.major && (thinking || speaking || listening) && frontAlpha > 0.5f) {
                drawLine(
                    color = JarvisPalette.CyanBright.copy(
                        alpha = (0.22f + smoothedAudio * 0.45f).coerceIn(0.08f, 0.7f),
                    ),
                    start = Offset(node.x, node.y),
                    end = Offset(
                        center.x + (node.x - center.x) * 0.3f,
                        center.y + (node.y - center.y) * 0.3f,
                    ),
                    strokeWidth = hairline,
                    cap = StrokeCap.Round,
                )
            }
        }
        // Travelling synapse pulses on a few front links — thinking looks alive.
        if (active) {
            ringLink.chunked(6).forEachIndexed { groupIndex, group ->
                val linkIndex = groupIndex % group.size
                val link = group[linkIndex]
                val a = nodes[link.first]
                val b = nodes[link.second]
                if (a.depth > 0.48f && b.depth > 0.48f) {
                    val t = ((neuralPhase / (2f * PI.toFloat())) * 1.7f + groupIndex * 0.19f) % 1f
                    val px = a.x + (b.x - a.x) * t
                    val py = a.y + (b.y - a.y) * t
                    drawCircle(
                        color = if (thinking) {
                            copper.copy(alpha = 0.85f)
                        } else {
                            Color.White.copy(alpha = 0.6f + (a.depth + b.depth) * 0.15f)
                        },
                        radius = 1.5.dp.toPx(),
                        center = Offset(px, py),
                    )
                }
            }
        }

        // 5. Counter-rotating elliptical orbit rings around the sphere.
        rotate(degrees = spin * 0.55f, pivot = center) {
            drawOval(
                color = JarvisPalette.CyanBright.copy(alpha = 0.40f * stateGlow),
                topLeft = Offset(center.x - glassRadius * 1.14f, center.y - glassRadius * 0.22f),
                size = Size(glassRadius * 2.28f, glassRadius * 0.44f),
                style = Stroke(width = fine),
            )
        }
        rotate(degrees = -counterSpin * 0.62f + 18f, pivot = center) {
            drawOval(
                color = copper.copy(alpha = 0.22f * stateGlow),
                topLeft = Offset(center.x - glassRadius * 1.06f, center.y - glassRadius * 0.50f),
                size = Size(glassRadius * 2.12f, glassRadius * 1.0f),
                style = Stroke(width = hairline),
            )
        }

        // 6. Quiet precision tick ring — six major copper, sixty whisper ticks.
        rotate(degrees = spin * 0.16f, pivot = center) {
            val ticks = 60
            repeat(ticks) { index ->
                val angle = (index.toFloat() / ticks) * 2f * PI.toFloat()
                val major = index % 10 == 0
                val inner = glassRadius * 1.02f
                val outer = glassRadius * if (major) 1.10f else 1.06f
                drawLine(
                    color = if (major) {
                        copper.copy(alpha = 0.30f * stateGlow)
                    } else {
                        accent.copy(alpha = 0.14f * stateGlow)
                    },
                    start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                    end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                    strokeWidth = if (major) fine else hairline,
                    cap = StrokeCap.Round,
                )
            }
        }

        // 7. State ring: one thin arc that reacts to what Jarvis is doing.
        val stateArcSweep = when {
            thinking -> 120f + sin(neuralPhase * 2f) * 40f
            speaking -> 200f + smoothedAudio * 130f
            listening -> 110f + smoothedAudio * 60f
            else -> 60f + breathe * 10f
        }
        val stateArcSpeed = if (active) spin * 0.5f else 40f
        rotate(degrees = stateArcSpeed + counterSpin * 0.2f, pivot = center) {
            drawArc(
                color = (if (thinking) copper else JarvisPalette.CyanBright)
                    .copy(alpha = 0.42f * stateGlow),
                startAngle = -90f,
                sweepAngle = stateArcSweep,
                useCenter = false,
                topLeft = Offset(center.x - glassRadius * 1.13f, center.y - glassRadius * 1.13f),
                size = Size(glassRadius * 2.26f, glassRadius * 2.26f),
                style = Stroke(width = fine, cap = StrokeCap.Round),
            )
        }

        // 8. Specular highlight so the glass reads as real.
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.30f * stateGlow),
                    Color.White.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
            ),
            topLeft = Offset(
                center.x - glassRadius * 0.52f,
                center.y - glassRadius * 0.78f,
            ),
            size = Size(glassRadius * 0.72f, glassRadius * 0.46f),
            style = Stroke(width = hairline),
        )
    }
}

/**
 * 3D rotating Iron Man J.A.R.V.I.S. Holographic Neural Synaptic Globe with
 * real-time 60fps [NeuralOrbPhysics] simulation (spring-mass-damper core, rotational
 * inertia, coupled neural particle offsets, and acoustic shockwaves), used in Screen 3
 * (Voice Assistant) and Screen 6 (Memory Visualization).
 */
@Composable
fun HolographicGlobe(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    accent: Color = JarvisPalette.Cyan,
    audioLevel: Float = 0f,
    toolActive: Boolean = false,
) {
    val physics = rememberNeuralOrbPhysics(
        state = when {
            toolActive -> ReactorState.THINKING
            audioLevel > 0.08f -> ReactorState.SPEAKING
            active -> ReactorState.LISTENING
            else -> ReactorState.IDLE
        },
        audioLevel = audioLevel,
        toolActive = toolActive,
        nodeCount = 24,
    )

    val smoothedAudio by animateFloatAsState(
        targetValue = audioLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 65, easing = LinearEasing),
        label = "globe_audio",
    )

    val rotation = (physics.spinAngleDeg / 180f) * PI.toFloat()
    val gyroSpin = physics.counterSpinAngleDeg
    val pulse = physics.coreScale

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val globeRadius = size.minDimension * (0.36f + smoothedAudio * 0.03f) * (0.95f + (pulse - 1f) * 0.35f)

        // Ambient glow behind the sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    JarvisPalette.CyanBright.copy(alpha = 0.34f + smoothedAudio * 0.22f),
                    accent.copy(alpha = 0.16f + smoothedAudio * 0.10f),
                    if (toolActive) JarvisPalette.Amber.copy(alpha = 0.12f) else Color.Transparent,
                    Color.Transparent,
                ),
                center = center,
                radius = globeRadius * 1.62f,
            ),
            radius = globeRadius * 1.62f,
            center = center,
        )

        if (physics.shockwaveIntensity > 0.02f) {
            val swRadius = globeRadius * (0.65f + physics.shockwaveProgress * 0.72f)
            val swColor = if (toolActive) JarvisPalette.Amber else JarvisPalette.CyanBright
            drawCircle(
                color = swColor.copy(alpha = (physics.shockwaveIntensity * 0.68f).coerceIn(0f, 0.85f)),
                radius = swRadius,
                center = center,
                style = Stroke(width = (1.5f + physics.shockwaveIntensity * 1.6f).dp.toPx()),
            )
        }

        // 3 Tilted gyroscopic orbital rings around the globe
        rotate(degrees = -22f + sin(rotation) * 6f, pivot = center) {
            drawOval(
                color = JarvisPalette.CyanBright.copy(alpha = 0.48f + smoothedAudio * 0.25f),
                topLeft = Offset(center.x - globeRadius * 1.38f, center.y - globeRadius * 0.42f),
                size = Size(globeRadius * 2.76f, globeRadius * 0.84f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        rotate(degrees = 26f - cos(rotation) * 6f, pivot = center) {
            drawOval(
                color = accent.copy(alpha = 0.34f),
                topLeft = Offset(center.x - globeRadius * 1.28f, center.y - globeRadius * 0.36f),
                size = Size(globeRadius * 2.56f, globeRadius * 0.72f),
                style = Stroke(width = 1.1.dp.toPx()),
            )
        }
        rotate(degrees = gyroSpin * 0.25f, pivot = center) {
            drawArc(
                color = JarvisPalette.Amber.copy(alpha = if (active) 0.55f else 0.28f),
                startAngle = 15f,
                sweepAngle = 68f,
                useCenter = false,
                topLeft = Offset(center.x - globeRadius * 1.16f, center.y - globeRadius * 1.16f),
                size = Size(globeRadius * 2.32f, globeRadius * 2.32f),
                style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = JarvisPalette.Amber.copy(alpha = if (active) 0.55f else 0.28f),
                startAngle = 195f,
                sweepAngle = 68f,
                useCenter = false,
                topLeft = Offset(center.x - globeRadius * 1.16f, center.y - globeRadius * 1.16f),
                size = Size(globeRadius * 2.32f, globeRadius * 2.32f),
                style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // Audio-reactive polar corona wave around the sphere perimeter
        if (active) {
            val coronaPath = Path()
            val steps = 84
            for (i in 0..steps) {
                val a = (i.toFloat() / steps) * 2f * PI.toFloat()
                val mod = sin(a * 10f + rotation * 3f) * (0.02f + smoothedAudio * 0.08f)
                val r = globeRadius * (1.02f + mod)
                val px = center.x + cos(a) * r
                val py = center.y + sin(a) * r
                if (i == 0) coronaPath.moveTo(px, py) else coronaPath.lineTo(px, py)
            }
            coronaPath.close()
            drawPath(
                path = coronaPath,
                color = JarvisPalette.CyanBright.copy(alpha = 0.52f + smoothedAudio * 0.35f),
                style = Stroke(width = 1.3.dp.toPx()),
            )
        }

        // Sphere outline
        drawCircle(
            color = accent.copy(alpha = 0.58f),
            radius = globeRadius,
            center = center,
            style = Stroke(width = 1.4.dp.toPx()),
        )

        // Latitude ellipses
        val latCount = 5
        for (i in 1..latCount) {
            val frac = (i.toFloat() / (latCount + 1)) * 2f - 1f
            val yOffset = frac * globeRadius * 0.82f
            val latR = sqrt((1f - frac * frac).coerceAtLeast(0.05f)) * globeRadius
            drawOval(
                color = accent.copy(alpha = 0.22f),
                topLeft = Offset(center.x - latR, center.y + yOffset - latR * 0.22f),
                size = Size(latR * 2f, latR * 0.44f),
                style = Stroke(width = 0.9.dp.toPx()),
            )
        }

        // 3D projected meridian nodes and neural constellation links
        val latSteps = 7
        val lonSteps = 14
        val points = mutableListOf<Triple<Float, Float, Float>>() // x, y, z
        for (latIdx in 0..latSteps) {
            val theta = (latIdx.toFloat() / latSteps) * PI.toFloat()
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)
            for (lonIdx in 0 until lonSteps) {
                val phi = (lonIdx.toFloat() / lonSteps) * 2f * PI.toFloat() + rotation
                val x = sinTheta * cos(phi)
                val z = sinTheta * sin(phi)
                val y = cosTheta
                points.add(Triple(x, y, z))
            }
        }

        // Constellation links + live core-to-surface neural synaptic discharges
        for (i in points.indices step 2) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            val sx1 = center.x + p1.first * globeRadius
            val sy1 = center.y + p1.second * globeRadius
            val sx2 = center.x + p2.first * globeRadius
            val sy2 = center.y + p2.second * globeRadius
            if (p1.third > -0.2f && p2.third > -0.2f) {
                val alpha = ((p1.third + 1f) * 0.17f).coerceIn(0.05f, 0.32f)
                drawLine(
                    color = accent.copy(alpha = alpha),
                    start = Offset(sx1, sy1),
                    end = Offset(sx2, sy2),
                    strokeWidth = 0.85.dp.toPx(),
                )
            }
            // Synaptic lightning filament from the inner core to active front-facing nodes
            if (active && p1.third > 0.35f && (i % 7 == 0)) {
                val ctrlX = (center.x + sx1) * 0.5f + sin(rotation * 2f + i) * 10.dp.toPx()
                val ctrlY = (center.y + sy1) * 0.5f + cos(rotation * 2f + i) * 10.dp.toPx()
                val synapse = Path().apply {
                    moveTo(center.x, center.y)
                    quadraticTo(ctrlX, ctrlY, sx1, sy1)
                }
                drawPath(
                    path = synapse,
                    color = JarvisPalette.CyanBright.copy(alpha = (0.30f + smoothedAudio * 0.45f).coerceAtMost(0.85f)),
                    style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        // Draw glowing 3D surface particles
        points.forEachIndexed { idx, (x, y, z) ->
            val depthAlpha = ((z + 1.2f) / 2.2f).coerceIn(0.12f, 0.96f)
            val dotRadius = if (idx % 5 == 0) 2.7.dp.toPx() else 1.45.dp.toPx()
            val dotColor = if (idx % 9 == 0) JarvisPalette.Amber else JarvisPalette.CyanBright
            drawCircle(
                color = dotColor.copy(alpha = depthAlpha),
                radius = dotRadius * (0.75f + 0.35f * depthAlpha + smoothedAudio * 0.25f),
                center = Offset(center.x + x * globeRadius, center.y + y * globeRadius),
            )
        }

        // Bright glowing energy core inside the globe
        val coreR = globeRadius * (0.26f + smoothedAudio * 0.08f) * pulse
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.97f),
                    JarvisPalette.CyanBright.copy(alpha = 0.88f),
                    accent.copy(alpha = 0.34f),
                    Color.Transparent,
                ),
                center = center,
                radius = coreR * 1.95f,
            ),
            radius = coreR * 1.95f,
            center = center,
        )
    }
}

/**
 * Dynamic 3D flowing particle sine-wave ribbon visualization used inside AI response
 * cards (Screen 4) and the VOICE WAVES showcase.
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

        // Subtle background radial glow
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    JarvisPalette.Cyan.copy(alpha = 0.18f),
                    JarvisPalette.Navy.copy(alpha = 0.65f),
                    JarvisPalette.Void.copy(alpha = 0.92f),
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
                    val dotColor = if ((i + r) % 9 == 0) JarvisPalette.Amber else JarvisPalette.CyanBright
                    drawCircle(
                        color = dotColor.copy(alpha = (0.28f + 0.62f * envelope).coerceIn(0.15f, 0.95f)),
                        radius = if (r == 0) 2.2.dp.toPx() else 1.4.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        JarvisPalette.Cyan.copy(alpha = 0.65f - r * 0.12f),
                        JarvisPalette.CyanBright.copy(alpha = 0.85f - r * 0.15f),
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
 * assistant's state and live [audioLevel]. Three interference waves are mixed
 * so the silhouette never looks like a single looping sine, and every bar has
 * a bright peak cap plus a faint reflection, matching the reference waveform.
 */
@Composable
fun VoiceWave(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = JarvisPalette.CyanBright,
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

        // Faint centre rail, like the reference's holographic baseline.
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

            // Reflected ghost bar for the glassy double-exposure look.
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

            // Bright peak cap.
            drawCircle(
                color = Color.White.copy(alpha = (alpha * 0.85f).coerceIn(0f, 0.92f)),
                radius = (barWidth * 0.48f).coerceAtLeast(0.7f),
                center = Offset(x, centerY - barHeight),
            )
        }

        // Thin interference ribbon over the peaks.
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

        // One slow diagonal light sweep across the spectrum.
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
    color: Color = JarvisPalette.Cyan,
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
