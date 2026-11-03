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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
 * Real-time 60fps Brahma / J.A.R.V.I.S. 3D Neural Orb Physics:
 * - 3D Rotational yaw, pitch & harmonic undulation clock
 * - 5-Lobed parametric spherical wave oscillation matching Brahma AI screen recording
 * - Central vertical spindle / hourglass twist rotation & expansion
 * - Spring-mass-damper acoustic coupling driven by microphone RMS audio level
 * - Gyroscopic orbital satellites angular momentum
 * - Interactive 3D Touch Physics: horizontal drag (yaw rotation), vertical drag (funnel pitch tilt),
 *   elastic membrane depression at finger contact, expanding 3D surface ripples, and flick momentum.
 */
@Stable
class NeuralOrbPhysics(val nodeCount: Int = 24) {
    var spinAngleDeg by mutableFloatStateOf(0f)
    var pitchAngleDeg by mutableFloatStateOf(32f) // Cinematic 32° lookdown into funnel
    var wavePhase by mutableFloatStateOf(0f)
    var spindlePhase by mutableFloatStateOf(0f)
    var orbitAngle1 by mutableFloatStateOf(0f)
    var orbitAngle2 by mutableFloatStateOf(120f)
    var orbitAngle3 by mutableFloatStateOf(240f)
    var orbitAngle4 by mutableFloatStateOf(60f)

    var angularVelocity by mutableFloatStateOf(24f)
    var waveAmplitude by mutableFloatStateOf(0.24f)
    var spindleIntensity by mutableFloatStateOf(0f)
    var coreScale by mutableFloatStateOf(1f)
    var coreVelocity by mutableFloatStateOf(0f)
    var shockwaveIntensity by mutableFloatStateOf(0f)
    var shockwaveProgress by mutableFloatStateOf(0f)
    var frameTick by mutableFloatStateOf(0f)

    // Interactive 3D Touch Physics State
    var isTouched by mutableStateOf(false)
    var touchNormX by mutableFloatStateOf(0f)
    var touchNormY by mutableFloatStateOf(0f)
    var touchDeform by mutableFloatStateOf(0f)
    var touchRippleProgress by mutableFloatStateOf(0f)
    var touchRippleIntensity by mutableFloatStateOf(0f)
    var touchYawVelocity by mutableFloatStateOf(0f)
    var touchPitchVelocity by mutableFloatStateOf(0f)
    var touchImpactScale by mutableFloatStateOf(0f)

    private var prevAudio = 0f

    fun onTouchDown(normX: Float, normY: Float) {
        isTouched = true
        touchNormX = normX
        touchNormY = normY
        touchDeform = 0.45f
        touchRippleProgress = 0f
        touchRippleIntensity = 0.90f
        touchImpactScale = 0.08f
    }

    fun onTouchDrag(deltaX: Float, deltaY: Float, normX: Float, normY: Float) {
        touchNormX = normX
        touchNormY = normY
        // Horizontal drag directly spins the 3D orb around Y-axis
        spinAngleDeg = (spinAngleDeg + deltaX * 0.45f) % 360f
        // Vertical drag tilts the 3D camera around X-axis (look down into or up at the funnel)
        pitchAngleDeg = (pitchAngleDeg - deltaY * 0.35f).coerceIn(-48f, 78f)
        touchYawVelocity = deltaX * 16f
        touchPitchVelocity = -deltaY * 12f
        touchDeform = 0.50f
    }

    fun onTouchUp() {
        isTouched = false
        // Transfer flick velocity into angular momentum
        angularVelocity = (angularVelocity + touchYawVelocity * 0.15f).coerceIn(-260f, 260f)
    }

    fun onTap(normX: Float, normY: Float) {
        touchNormX = normX
        touchNormY = normY
        touchDeform = 0.60f
        touchRippleProgress = 0f
        touchRippleIntensity = 1.0f
        shockwaveProgress = 0f
        shockwaveIntensity = 0.95f
        touchImpactScale = 0.14f
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

        // 1. Angular momentum for main mesh and gyroscopic orbits
        val targetOmega = when {
            thinking -> 72f
            speaking -> 48f + audio * 90f
            listening -> 36f + audio * 40f
            else -> 20f
        }
        val angularAccel = (targetOmega - angularVelocity) * 4.5f
        angularVelocity = (angularVelocity + angularAccel * dt).coerceIn(-260f, 260f)
        spinAngleDeg = (spinAngleDeg + angularVelocity * dt) % 360f

        // Gyroscopic satellite orbits (different inclinations and rates)
        orbitAngle1 = (orbitAngle1 + (angularVelocity * 0.75f + 18f) * dt) % 360f
        orbitAngle2 = (orbitAngle2 - (angularVelocity * 0.65f + 14f) * dt) % 360f
        orbitAngle3 = (orbitAngle3 + (angularVelocity * 0.85f + 22f) * dt) % 360f
        orbitAngle4 = (orbitAngle4 - (angularVelocity * 0.55f + 12f) * dt) % 360f

        // 2. Touch Physics Dynamics (Inertial coasting, pitch spring-back, elastic membrane relaxation)
        if (!isTouched) {
            // Apply inertial friction to flick spin & pitch
            touchYawVelocity *= (1f - dt * 2.5f)
            touchPitchVelocity *= (1f - dt * 3.5f)
            pitchAngleDeg = (pitchAngleDeg + touchPitchVelocity * dt).coerceIn(-48f, 78f)
            // Soft spring pull back towards comfortable cinematic viewing angle (32 degrees)
            pitchAngleDeg += (32f - pitchAngleDeg) * (dt * 1.6f)
            // Elastic touch depression relaxation
            touchDeform += (0f - touchDeform) * (dt * 7.5f)
        }
        touchImpactScale += (0f - touchImpactScale) * (dt * 9.0f)

        // Propagate expanding 3D surface ripple waves
        if (touchRippleIntensity > 0.008f) {
            touchRippleProgress += dt * 2.3f
            touchRippleIntensity *= (1f - dt * 2.6f)
        } else {
            touchRippleIntensity = 0f
            touchRippleProgress = 0f
        }

        // 3. 5-Lobed Organic Harmonic Wave Amplitude
        val targetWaveAmp = when {
            thinking -> 0.38f // Crisp pronounced 5-lobed star geometry as in thinking frame
            speaking -> 0.28f + audio * 0.42f // Dynamic sound-wave undulation as in speaking frame
            listening -> 0.25f + audio * 0.20f
            else -> 0.22f // Smooth calm organic breathing in ready/idle
        }
        waveAmplitude += (targetWaveAmp - waveAmplitude) * (dt * 7.5f)
        val waveSpeed = when {
            thinking -> 3.2f
            speaking -> 2.6f + audio * 4.0f
            listening -> 1.8f
            else -> 1.2f
        }
        wavePhase = (wavePhase + waveSpeed * dt) % (2f * PI.toFloat() * 100f)

        // 4. Central Vertical Spindle / Hourglass Double-Helix intensity & spin
        val targetSpindle = when {
            thinking -> 1.0f // Golden/Amber prominent vertical hourglass
            speaking -> 0.85f + audio * 0.15f // Radiant cyan/white vertical spindle
            listening -> 0.45f
            else -> 0.25f // Subtle condensed core
        }
        spindleIntensity += (targetSpindle - spindleIntensity) * (dt * 6.0f)
        val spindleSpeed = when {
            thinking -> 180f
            speaking -> 140f + audio * 120f
            else -> 60f
        }
        spindlePhase = (spindlePhase + spindleSpeed * dt) % 360f

        // 5. Spring-mass-damper core radius (Hooke's law with voice transients and touch bounce)
        val targetScale = when {
            speaking -> 1.05f + audio * 0.20f
            thinking -> 1.03f + sin(frameTick * 6f) * 0.04f
            listening -> 1.02f + audio * 0.12f
            else -> 1.0f + sin(frameTick * 1.5f) * 0.025f
        } + touchImpactScale
        val stiffness = 160f
        val damping = 14f
        val audioImpulse = (audio - prevAudio).coerceAtLeast(0f) * 4.5f
        val springForce = (targetScale - coreScale) * stiffness - coreVelocity * damping + audioImpulse * 32f
        coreVelocity += springForce * dt
        coreScale = (coreScale + coreVelocity * dt).coerceIn(0.85f, 1.45f)

        // 6. Acoustic shockwave emission on speech bursts
        if (audio - prevAudio > 0.08f || (shockwaveIntensity <= 0.02f && speaking && audio > 0.25f)) {
            shockwaveProgress = 0f
            shockwaveIntensity = (0.5f + audio * 0.5f).coerceAtMost(1f)
        }
        if (shockwaveIntensity > 0.01f) {
            shockwaveProgress += dt * (0.9f + audio * 0.6f)
            if (shockwaveProgress >= 1f) {
                shockwaveProgress = 0f
                shockwaveIntensity = 0f
            } else {
                shockwaveIntensity *= (1f - dt * 1.3f).coerceAtLeast(0f)
            }
        }
        prevAudio = audio
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

// Pre-computed 3D background star-dust particles (elevation, azimuth, depth distance, phase)
private data class CosmicParticle(val el: Float, val az: Float, val dist: Float, val phase: Float)
private val COSMIC_STARDUST: List<CosmicParticle> = run {
    val list = mutableListOf<CosmicParticle>()
    val count = 38
    for (i in 0 until count) {
        val el = (-0.65f + 1.3f * ((i * 17) % count).toFloat() / count) * (PI.toFloat() / 2f)
        val az = ((i * 31) % count).toFloat() / count * (2f * PI.toFloat())
        val dist = 1.08f + 0.52f * (((i * 47) % count).toFloat() / count)
        val phase = ((i * 13) % count).toFloat() / count * (2f * PI.toFloat())
        list.add(CosmicParticle(el, az, dist, phase))
    }
    list
}

/**
 * 100% Micro-Pixel Match Brahma 3D Morphing Organic Wireframe Orb:
 *
 * Visual architecture decoded from screen recording:
 * 1. 3D undulating 5-lobed parametric organic wave lattice (superquadric harmonic surface)
 * 2. Concentric latitude contour isolines with stippled dashed lines and glowing vertex dots
 * 3. Volumetric white bloom on crests that face the camera, with depth-attenuated cyan in valleys
 * 4. Central vertical spinning spindle/hourglass double-helix (amber/gold in THINKING, cyan/white in SPEAKING)
 * 5. Multi-stop high-energy central nucleus with radiant flares
 * 6. 4 Gyroscopic inclined orbital wire rings with traveling glowing planetary beads and spark trails
 * 7. Ambient cosmic star-dust particles floating in 3D deep space
 */
@Composable
fun ArcReactor(
    state: ReactorState,
    modifier: Modifier = Modifier,
    accent: Color = JarvisPalette.Cyan,
    audioLevel: Float = 0f,
    toolActive: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val thinking = state == ReactorState.THINKING || toolActive
    val speaking = state == ReactorState.SPEAKING
    val listening = state == ReactorState.LISTENING

    val physics = rememberNeuralOrbPhysics(
        state = state,
        audioLevel = audioLevel,
        toolActive = toolActive,
        nodeCount = 24,
    )

    val smoothedAudio by animateFloatAsState(
        targetValue = audioLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 60, easing = LinearEasing),
        label = "brahma_audio",
    )

    Canvas(
        modifier = modifier
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
        drawBrahmaHolographicOrb(
            physics = physics,
            thinking = thinking,
            speaking = speaking,
            listening = listening,
            accent = accent,
            audio = smoothedAudio,
        )
    }
}

/**
 * Fullscreen Voice Assistant / Memory Globe variant — uses the identical pixel-matched 3D engine.
 */
@Composable
fun HolographicGlobe(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    accent: Color = JarvisPalette.Cyan,
    audioLevel: Float = 0f,
    toolActive: Boolean = false,
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
        onTap = onTap,
    )
}

/**
 * Renders the complete 3D Brahma Holographic Orb inside any Canvas [DrawScope].
 */
private fun DrawScope.drawBrahmaHolographicOrb(
    physics: NeuralOrbPhysics,
    thinking: Boolean,
    speaking: Boolean,
    listening: Boolean,
    accent: Color,
    audio: Float,
) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    val baseRadius = (size.minDimension / 2f) * 0.72f * physics.coreScale

    // 1. Cosmic Stardust Background Particles
    drawCosmicStarDust(center, baseRadius, physics.frameTick, accent, thinking)

    // 2. Gyroscopic Orbital Rings - Back Arcs (drawn behind the mesh for proper 3D occlusion)
    drawGyroscopicOrbits(
        center = center,
        radius = baseRadius,
        physics = physics,
        accent = accent,
        thinking = thinking,
        drawBackOnly = true,
        audio = audio,
    )

    // 3. Central Ambient Glow Halo
    val haloRadius = baseRadius * (1.15f + audio * 0.20f)
    val haloColor = if (thinking) JarvisPalette.Amber else JarvisPalette.CyanBright
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                haloColor.copy(alpha = if (thinking) 0.28f else 0.22f + audio * 0.18f),
                accent.copy(alpha = 0.10f + audio * 0.08f),
                Color.Transparent,
            ),
            center = center,
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = center,
    )

    // 4. Acoustic Shockwave Burst (on voice bursts)
    if (physics.shockwaveIntensity > 0.01f) {
        val swRadius = baseRadius * (0.6f + physics.shockwaveProgress * 0.85f)
        val swColor = if (thinking) JarvisPalette.Amber else JarvisPalette.CyanBright
        drawCircle(
            color = swColor.copy(alpha = (physics.shockwaveIntensity * 0.65f).coerceIn(0f, 0.85f)),
            radius = swRadius,
            center = center,
            style = Stroke(width = (1.5f + physics.shockwaveIntensity * 2.0f).dp.toPx()),
        )
    }

    // 5. The 3D Undulating 5-Lobed Morphing Organic Wave Mesh (The Core Visual)
    drawParametricBrahmaMesh(
        center = center,
        baseR = baseRadius,
        physics = physics,
        thinking = thinking,
        speaking = speaking,
        accent = accent,
        audio = audio,
    )

    // 6. Central Vertical Spindle / Hourglass Double-Helix (Amber in Thinking, Cyan/White in Speaking)
    drawCentralSpindleHourglass(
        center = center,
        baseR = baseRadius,
        physics = physics,
        thinking = thinking,
        speaking = speaking,
        audio = audio,
    )

    // 7. Glowing White/Cyan Center Nucleus
    drawCenterNucleus(
        center = center,
        baseR = baseRadius,
        thinking = thinking,
        audio = audio,
        coreScale = physics.coreScale,
    )

    // 8. Gyroscopic Orbital Rings - Front Arcs & Traveling Satellite Beads (drawn on top)
    drawGyroscopicOrbits(
        center = center,
        radius = baseRadius,
        physics = physics,
        accent = accent,
        thinking = thinking,
        drawBackOnly = false,
        audio = audio,
    )
}

/**
 * 3D Parametric Brahma Mesh:
 * Slices through concentric contour rings from inner core to outer rim.
 * Each contour ring undulates in 3D according to a 5-lobed harmonic superformula:
 *   R(theta, u) = R_base(u) * (1 + A_lobe * cos(5*theta - spin) * sin(u*pi) + A_harm * sin(3*theta) + ...)
 * Rendered with stippled dashes, glowing dots, and volumetric bloom on high-depth crests.
 */
private fun DrawScope.drawParametricBrahmaMesh(
    center: Offset,
    baseR: Float,
    physics: NeuralOrbPhysics,
    thinking: Boolean,
    speaking: Boolean,
    accent: Color,
    audio: Float,
) {
    val rings = 30 // Stacked contour latitude slices
    val pointsPerRing = 80 // Longitude resolution
    val spinRad = (physics.spinAngleDeg / 180f) * PI.toFloat()
    val wavePhase = physics.wavePhase
    val waveAmp = physics.waveAmplitude

    // Dynamic 3D Camera Pitch Tilt (user can drag up/down to tilt and look inside the funnel)
    val pitchAngle = (physics.pitchAngleDeg / 180f) * PI.toFloat()
    val cosPitch = cos(pitchAngle)
    val sinPitch = sin(pitchAngle)
    val cameraDist = baseR * 3.2f

    val hairline = 0.9.dp.toPx()
    val fineStroke = 1.4.dp.toPx()
    val bloomStroke = 3.2.dp.toPx()

    // Interactive 3D Touch Physics parameters
    val touchDeform = physics.touchDeform
    val touchX = physics.touchNormX * baseR
    val touchY = physics.touchNormY * baseR
    val rippleProg = physics.touchRippleProgress
    val rippleInt = physics.touchRippleIntensity

    // Reusable projection buffers for the current ring to avoid per-frame allocations
    val px = FloatArray(pointsPerRing)
    val py = FloatArray(pointsPerRing)
    val pz = FloatArray(pointsPerRing)
    val pCrest = FloatArray(pointsPerRing)

    val prevPx = FloatArray(pointsPerRing)
    val prevPy = FloatArray(pointsPerRing)
    val prevPz = FloatArray(pointsPerRing)
    var hasPrevRing = false

    for (ringIdx in 0 until rings) {
        val u = (ringIdx + 1).toFloat() / rings // 0..1 from core to outer rim
        // Radial profile: funnel-shaped, dipping in center and opening outward
        val rBase = baseR * (0.16f + 0.84f * sin(u * (PI.toFloat() / 2f)))
        val zBase = baseR * (0.32f * cos(u * PI.toFloat()) - 0.12f * (1f - u))

        // Precompute projected 2D coordinates for this ring
        for (ptIdx in 0 until pointsPerRing) {
            val theta = (ptIdx.toFloat() / pointsPerRing) * 2f * PI.toFloat()

            // 5-Lobed Harmonic Wave Perturbation (decoding Brahma AI's exact 5-crest geometry)
            val lobe5 = cos(5f * theta - spinRad) * sin(u * PI.toFloat())
            val harmonic3 = sin(3f * theta + spinRad * 1.2f + wavePhase * 0.5f) * sin(u * PI.toFloat()) * 0.32f
            val fine10 = cos(10f * theta - spinRad * 0.8f) * 0.12f
            val breathing = sin(u * 3f + wavePhase) * 0.08f

            val perturbation = waveAmp * (lobe5 + harmonic3 + fine10 + breathing)
            val r = rBase * (1f + perturbation)
            val z = zBase + baseR * 0.28f * perturbation * sin(u * PI.toFloat())

            // 3D coordinates before camera tilt
            var x3 = r * cos(theta)
            var y3 = r * sin(theta)
            var z3 = z

            // Interactive 3D Touch Physics: finger physically deforms the elastic lattice membrane
            if (touchDeform > 0.01f || rippleInt > 0.01f) {
                val dx = x3 - touchX
                val dy = y3 - touchY
                val distSq = dx * dx + dy * dy
                val touchRadiusSq = baseR * baseR * 0.40f
                if (touchDeform > 0.01f && distSq < touchRadiusSq * 3.5f) {
                    val gaussian = exp(-distSq / touchRadiusSq)
                    z3 -= baseR * 0.38f * touchDeform * gaussian
                }
                if (rippleInt > 0.01f) {
                    val d = sqrt(distSq) / baseR
                    val ripple = sin(d * 18f - rippleProg * 26f) *
                        exp(-abs(d - rippleProg * 1.15f) * 4.2f) * rippleInt
                    z3 += baseR * 0.26f * ripple
                }
            }

            // Pitch rotation (tilt towards viewer around X axis)
            val xCam = x3
            val yCam = y3 * cosPitch - z3 * sinPitch
            val zCam = y3 * sinPitch + z3 * cosPitch

            // Perspective projection
            val depthScale = cameraDist / (cameraDist + zCam)
            px[ptIdx] = center.x + xCam * depthScale
            py[ptIdx] = center.y + yCam * depthScale
            pz[ptIdx] = zCam
            pCrest[ptIdx] = lobe5
        }

        // Render the isoline ring with stippled dashes and depth-sorted glow
        for (i in 0 until pointsPerRing) {
            val nextIdx = (i + 1) % pointsPerRing
            val x1 = px[i]
            val y1 = py[i]
            val x2 = px[nextIdx]
            val y2 = py[nextIdx]

            val avgZ = (pz[i] + pz[nextIdx]) * 0.5f
            val avgCrest = (pCrest[i] + pCrest[nextIdx]) * 0.5f

            // Normalized depth: 1 = facing viewer in foreground, 0 = far background
            val depthFactor = ((avgZ + baseR * 1.2f) / (baseR * 2.4f)).coerceIn(0f, 1f)
            val crestFactor = (avgCrest + 0.5f).coerceIn(0f, 1f)

            // Stipple dash rhythm: alternates drawn lines and micro-gaps matching the screen recording
            val isDashDrawn = (i + ringIdx * 2) % 6 < 4

            if (isDashDrawn) {
                // Color & Opacity:
                // High crests facing forward are brilliant glowing white (#FFFFFF);
                // Lower valleys and rear-facing rings are deep luminous cyan (#00F2FE / #0A4D68)
                val isPeakCrest = avgCrest > 0.35f && depthFactor > 0.42f
                val strokeW = when {
                    isPeakCrest -> fineStroke * (1.2f + audio * 0.4f)
                    depthFactor > 0.5f -> fineStroke
                    else -> hairline
                }

                // If peak crest, draw volumetric bloom layer underneath
                if (isPeakCrest) {
                    val bloomAlpha = ((depthFactor * 0.45f) + audio * 0.35f).coerceIn(0.15f, 0.85f)
                    drawLine(
                        color = (if (thinking) JarvisPalette.Amber else JarvisPalette.CyanBright)
                            .copy(alpha = bloomAlpha * 0.45f),
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = bloomStroke,
                        cap = StrokeCap.Round,
                    )
                }

                // Sharp foreground line
                val lineColor = when {
                    isPeakCrest -> Color.White.copy(alpha = (0.75f + depthFactor * 0.25f).coerceAtMost(1f))
                    depthFactor > 0.55f -> JarvisPalette.CyanBright.copy(alpha = 0.45f + depthFactor * 0.45f)
                    thinking -> JarvisPalette.Amber.copy(alpha = 0.15f + depthFactor * 0.35f)
                    else -> accent.copy(alpha = 0.08f + depthFactor * 0.35f)
                }

                drawLine(
                    color = lineColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }

            // Draw glowing vertex stipple dots at intervals and peak crests
            if (i % 4 == 0 && depthFactor > 0.38f) {
                val dotRadius = if (avgCrest > 0.4f) 2.2.dp.toPx() else 1.3.dp.toPx()
                val dotAlpha = ((depthFactor - 0.25f) * 1.35f).coerceIn(0.15f, 0.95f)
                val dotColor = if (avgCrest > 0.4f) {
                    Color.White.copy(alpha = dotAlpha)
                } else {
                    JarvisPalette.CyanBright.copy(alpha = dotAlpha * 0.85f)
                }
                drawCircle(
                    color = dotColor,
                    radius = dotRadius * (0.8f + audio * 0.3f),
                    center = Offset(x1, y1),
                )
            }
        }

        // Draw connecting 3D meridian wireframe ribs (creates the exact Brahma AI lattice cage)
        if (hasPrevRing) {
            for (ptIdx in 0 until pointsPerRing step 5) {
                val ribAvgZ = (pz[ptIdx] + prevPz[ptIdx]) * 0.5f
                val ribDepth = ((ribAvgZ + baseR * 1.2f) / (baseR * 2.4f)).coerceIn(0f, 1f)
                if (ribDepth > 0.28f) {
                    val ribAlpha = if (thinking) ribDepth * 0.26f else ribDepth * 0.34f + audio * 0.20f
                    val ribColor = (if (thinking) JarvisPalette.Amber else JarvisPalette.CyanBright)
                        .copy(alpha = ribAlpha.coerceIn(0.06f, 0.85f))
                    drawLine(
                        color = ribColor,
                        start = Offset(prevPx[ptIdx], prevPy[ptIdx]),
                        end = Offset(px[ptIdx], py[ptIdx]),
                        strokeWidth = if (ribDepth > 0.6f) fineStroke else hairline,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        for (i in 0 until pointsPerRing) {
            prevPx[i] = px[i]
            prevPy[i] = py[i]
            prevPz[i] = pz[i]
        }
        hasPrevRing = true
    }
}

/**
 * Central Vertical Spindle / Hourglass Double-Helix:
 * Exactly matching the vertical helix visible in `thinking_full.png` and `speaking_full.png`!
 * Formed of stacked horizontal loops rotated around the vertical axis in an hourglass silhouette.
 */
private fun DrawScope.drawCentralSpindleHourglass(
    center: Offset,
    baseR: Float,
    physics: NeuralOrbPhysics,
    thinking: Boolean,
    speaking: Boolean,
    audio: Float,
) {
    val intensity = physics.spindleIntensity
    if (intensity < 0.05f) return

    val slices = 26
    val spindleH = baseR * (0.46f + audio * 0.12f)
    val maxRadius = baseR * (0.19f + audio * 0.08f) * intensity
    val spinRad = (physics.spindlePhase / 180f) * PI.toFloat()
    val pitchAngle = (physics.pitchAngleDeg / 180f) * PI.toFloat()
    val cosPitch = cos(pitchAngle)
    val sinPitch = sin(pitchAngle)
    val cameraDist = baseR * 3.2f

    val strokeW = 1.3.dp.toPx()

    for (k in 0 until slices) {
        val eta = (k.toFloat() / (slices - 1)) * 2f - 1f // -1 to +1
        // Hourglass radius profile: pinches to zero in the center, widens at top and bottom
        val loopR = maxRadius * abs(eta).coerceAtLeast(0.06f)
        val y3 = eta * spindleH
        val twist = spinRad + eta * (2.8f * PI.toFloat())

        // Sample 16 points around this elliptical spindle loop
        val loopPts = 16
        val lx = FloatArray(loopPts)
        val ly = FloatArray(loopPts)
        val lz = FloatArray(loopPts)

        for (p in 0 until loopPts) {
            val beta = (p.toFloat() / loopPts) * 2f * PI.toFloat()
            // Flat elliptical loop tilted along the twist direction
            val lx3 = loopR * cos(beta) * cos(twist) - (loopR * 0.35f) * sin(beta) * sin(twist)
            val lz3 = loopR * cos(beta) * sin(twist) + (loopR * 0.35f) * sin(beta) * cos(twist)

            val xCam = lx3
            val yCam = y3 * cosPitch - lz3 * sinPitch
            val zCam = y3 * sinPitch + lz3 * cosPitch

            val scale = cameraDist / (cameraDist + zCam)
            lx[p] = center.x + xCam * scale
            ly[p] = center.y + yCam * scale
            lz[p] = zCam
        }

        // Draw loop segments
        for (p in 0 until loopPts) {
            val pNext = (p + 1) % loopPts
            val avgZ = (lz[p] + lz[pNext]) * 0.5f
            val depthAlpha = ((avgZ + baseR) / (baseR * 2f)).coerceIn(0.2f, 1f)

            val loopColor = if (thinking) {
                // Golden / Amber spindle in THINKING state (exact match to thinking_full.png)
                if (depthAlpha > 0.6f) {
                    Color(0xFFFFDF80).copy(alpha = depthAlpha * intensity)
                } else {
                    JarvisPalette.Amber.copy(alpha = depthAlpha * 0.85f * intensity)
                }
            } else {
                // Radiant cyan / pure white spindle in SPEAKING / LISTENING states
                if (depthAlpha > 0.65f) {
                    Color.White.copy(alpha = depthAlpha * intensity)
                } else {
                    JarvisPalette.CyanBright.copy(alpha = depthAlpha * 0.85f * intensity)
                }
            }

            drawLine(
                color = loopColor,
                start = Offset(lx[p], ly[p]),
                end = Offset(lx[pNext], ly[pNext]),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Intense multi-stop glowing center nucleus.
 */
private fun DrawScope.drawCenterNucleus(
    center: Offset,
    baseR: Float,
    thinking: Boolean,
    audio: Float,
    coreScale: Float,
) {
    val coreR = baseR * (0.13f + audio * 0.06f) * coreScale

    // 1. Broad soft atmospheric glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                (if (thinking) JarvisPalette.Amber else JarvisPalette.CyanBright).copy(alpha = 0.55f + audio * 0.30f),
                (if (thinking) Color(0xFFD97706) else JarvisPalette.Cyan).copy(alpha = 0.22f),
                Color.Transparent,
            ),
            center = center,
            radius = coreR * 3.2f,
        ),
        radius = coreR * 3.2f,
        center = center,
    )

    // 2. High-intensity middle flare
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.98f),
                (if (thinking) Color(0xFFFDE68A) else JarvisPalette.CyanBright).copy(alpha = 0.88f),
                Color.Transparent,
            ),
            center = center,
            radius = coreR * 1.6f,
        ),
        radius = coreR * 1.6f,
        center = center,
    )

    // 3. Pure white solid nucleus point
    drawCircle(
        color = Color.White,
        radius = coreR * 0.55f,
        center = center,
    )
}

/**
 * 4 Gyroscopic Inclined Orbital Wire Rings with Traveling Planetary Satellite Beads:
 * Decodes the 4 intersecting orbit ellipses seen in the screen recording.
 * Supports drawing back arcs (behind the mesh) and front arcs (in front of the mesh) for true 3D depth.
 */
private fun DrawScope.drawGyroscopicOrbits(
    center: Offset,
    radius: Float,
    physics: NeuralOrbPhysics,
    accent: Color,
    thinking: Boolean,
    drawBackOnly: Boolean,
    audio: Float,
) {
    // 4 Distinct gyroscopic inclinations: (inclinationX, tiltY, ringRadiusRatio, orbitAngleDeg)
    val orbits = listOf(
        OrbitConfig(incX = 24f, incY = 18f, rRatio = 1.18f, angle = physics.orbitAngle1),
        OrbitConfig(incX = -44f, incY = -28f, rRatio = 1.28f, angle = physics.orbitAngle2),
        OrbitConfig(incX = 66f, incY = 48f, rRatio = 1.34f, angle = physics.orbitAngle3),
        OrbitConfig(incX = -72f, incY = -62f, rRatio = 1.40f, angle = physics.orbitAngle4),
    )

    val fineStroke = 1.15.dp.toPx()
    val steps = 64

    orbits.forEachIndexed { orbitIdx, cfg ->
        val ringR = radius * cfg.rRatio
        val incXRad = (cfg.incX / 180f) * PI.toFloat()
        val incYRad = (cfg.incY / 180f) * PI.toFloat()
        val cosX = cos(incXRad)
        val sinX = sin(incXRad)
        val cosY = cos(incYRad)
        val sinY = sin(incYRad)

        // Precompute 3D points of the orbit ellipse
        val ptsX = FloatArray(steps)
        val ptsY = FloatArray(steps)
        val ptsZ = FloatArray(steps)

        for (s in 0 until steps) {
            val beta = (s.toFloat() / steps) * 2f * PI.toFloat()
            val x0 = ringR * cos(beta)
            val y0 = ringR * sin(beta)

            // 3D rotation by inclination angles
            val x1 = x0 * cosY
            val y1 = y0
            val z1 = -x0 * sinY

            val x2 = x1
            val y2 = y1 * cosX - z1 * sinX
            val z2 = y1 * sinX + z1 * cosX

            ptsX[s] = center.x + x2
            ptsY[s] = center.y + y2
            ptsZ[s] = z2
        }

        // Draw ring segments according to front/back pass
        for (s in 0 until steps) {
            val nextS = (s + 1) % steps
            val avgZ = (ptsZ[s] + ptsZ[nextS]) * 0.5f
            val isBack = avgZ < 0f

            if (isBack == drawBackOnly) {
                val alpha = if (isBack) {
                    0.14f
                } else {
                    0.38f + audio * 0.18f
                }
                val ringColor = (if (thinking && orbitIdx % 2 == 1) JarvisPalette.Amber else accent)
                    .copy(alpha = alpha)

                drawLine(
                    color = ringColor,
                    start = Offset(ptsX[s], ptsY[s]),
                    end = Offset(ptsX[nextS], ptsY[nextS]),
                    strokeWidth = fineStroke,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Draw traveling satellite bead on front pass
        if (!drawBackOnly) {
            val beadRad = (cfg.angle / 180f) * PI.toFloat()
            val bx0 = ringR * cos(beadRad)
            val by0 = ringR * sin(beadRad)
            val bx1 = bx0 * cosY
            val by1 = by0
            val bz1 = -bx0 * sinY
            val bx2 = bx1
            val by2 = by1 * cosX - bz1 * sinX
            val bz2 = by1 * sinX + bz1 * cosX

            // Satellite bead is visible predominantly in front hemisphere
            val beadDepth = ((bz2 + ringR) / (2f * ringR)).coerceIn(0.15f, 1f)
            val beadPos = Offset(center.x + bx2, center.y + by2)

            // Outer soft glow
            val glowColor = if (thinking && orbitIdx % 2 == 1) JarvisPalette.Amber else JarvisPalette.CyanBright
            drawCircle(
                color = glowColor.copy(alpha = (0.35f * beadDepth).coerceAtMost(0.85f)),
                radius = 6.0.dp.toPx(),
                center = beadPos,
            )

            // Bright core dot
            drawCircle(
                color = Color.White.copy(alpha = beadDepth),
                radius = 2.4.dp.toPx(),
                center = beadPos,
            )

            // Short trailing spark tail
            for (trail in 1..3) {
                val trailRad = beadRad - trail * 0.07f
                val tx0 = ringR * cos(trailRad)
                val ty0 = ringR * sin(trailRad)
                val tx1 = tx0 * cosY
                val ty1 = ty0
                val tz1 = -tx0 * sinY
                val tx2 = tx1
                val ty2 = ty1 * cosX - tz1 * sinX

                drawCircle(
                    color = glowColor.copy(alpha = (0.24f / trail) * beadDepth),
                    radius = (2.2f - trail * 0.5f).dp.toPx(),
                    center = Offset(center.x + tx2, center.y + ty2),
                )
            }
        }
    }
}

private data class OrbitConfig(val incX: Float, val incY: Float, val rRatio: Float, val angle: Float)

/**
 * Cosmic background stardust micro-particles twinkling in 3D.
 */
private fun DrawScope.drawCosmicStarDust(
    center: Offset,
    baseR: Float,
    frameTick: Float,
    accent: Color,
    thinking: Boolean,
) {
    COSMIC_STARDUST.forEachIndexed { i, p ->
        val r = baseR * p.dist
        val x = center.x + r * cos(p.el) * cos(p.az)
        val y = center.y + r * sin(p.el)
        val twinkle = (0.20f + 0.60f * (sin(frameTick * 2.2f + p.phase) * 0.5f + 0.5f)).coerceIn(0.10f, 0.90f)
        val dotColor = if (thinking && i % 3 == 0) JarvisPalette.Amber else JarvisPalette.CyanBright
        drawCircle(
            color = dotColor.copy(alpha = twinkle * 0.55f),
            radius = (1.0f + (i % 2) * 0.6f).dp.toPx(),
            center = Offset(x, y),
        )
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
 * assistant's state and live [audioLevel].
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
