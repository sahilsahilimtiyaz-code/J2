package com.jarvis.assistant.ui

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.GlowDivider
import com.jarvis.assistant.ui.components.JarvisBackdrop
import com.jarvis.assistant.ui.theme.JarvisPalette

/**
 * Post-boot authentication gateway displayed immediately after [BootScreen]:
 *
 *   [ Arc Reactor App Logo ]
 *      J . A . R . V . I . S .
 *
 *      [  G  Login          > ]
 *               Or
 *      [  Guest             > ]
 */
@Composable
fun AuthScreen(
    defaultName: String = "",
    onGoogleSignIn: (email: String, displayName: String) -> Unit,
    onGuestSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGoogleSheet by rememberSaveable { mutableStateOf(false) }
    var emailInput by rememberSaveable { mutableStateOf("") }
    var nameInput by rememberSaveable(defaultName) { mutableStateOf(defaultName) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedEmail = result.data
                ?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                ?.trim()
                .orEmpty()
            if (pickedEmail.isNotBlank()) {
                val derivedName = nameInput.trim().ifBlank { deriveDisplayNameFromEmail(pickedEmail) }
                onGoogleSignIn(pickedEmail, derivedName)
                return@rememberLauncherForActivityResult
            }
        }
        // Fallback if the device has no system Google account or the system picker was dismissed
        showGoogleSheet = true
    }

    val transition = rememberInfiniteTransition(label = "auth_logo_orbit")
    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbit_angle",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    JarvisBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(8.dp))

            // Top: Official Triangular Arc Reactor App Logo + Holographic Orbit Rings
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(168.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val r = size.minDimension / 2f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    JarvisPalette.CyanBright.copy(alpha = 0.32f * pulseAlpha),
                                    JarvisPalette.Cyan.copy(alpha = 0.10f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = r,
                            ),
                            radius = r,
                            center = center,
                        )
                        rotate(orbitAngle, pivot = center) {
                            drawArc(
                                color = JarvisPalette.CyanBright.copy(alpha = 0.65f),
                                startAngle = 15f,
                                sweepAngle = 110f,
                                useCenter = false,
                                topLeft = Offset(center.x - r * 0.92f, center.y - r * 0.92f),
                                size = Size(r * 1.84f, r * 1.84f),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                            )
                            drawArc(
                                color = JarvisPalette.Cyan.copy(alpha = 0.45f),
                                startAngle = 195f,
                                sweepAngle = 110f,
                                useCenter = false,
                                topLeft = Offset(center.x - r * 0.92f, center.y - r * 0.92f),
                                size = Size(r * 1.84f, r * 1.84f),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }
                    }

                    com.jarvis.assistant.ui.components.ArcReactor(
                        state = com.jarvis.assistant.ui.components.ReactorState.IDLE,
                        modifier = Modifier.size(164.dp),
                        audioLevel = 0.25f,
                    )
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "J.A.R.V.I.S.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = JarvisPalette.Ice,
                    fontWeight = FontWeight.Light,
                    fontSize = 30.sp,
                    letterSpacing = 7.sp,
                )

                Spacer(Modifier.height(8.dp))

                GlowDivider(
                    modifier = Modifier.fillMaxWidth(0.55f),
                    color = JarvisPalette.CyanBright,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "SELECT ACCESS MODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = JarvisPalette.CyanBright.copy(alpha = 0.88f),
                    letterSpacing = 3.sp,
                )
            }

            // Center: G Login > / Or / Guest >
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. G Login >
                AuthActionButton(
                    leadingBadge = {
                        GoogleGEmblem(modifier = Modifier.size(24.dp))
                    },
                    label = "G Login",
                    subtitle = "Sign in with your Google account",
                    primary = true,
                    onClick = {
                        val launched = runCatching {
                            @Suppress("DEPRECATION")
                            val intent = AccountManager.newChooseAccountIntent(
                                null,
                                null,
                                arrayOf("com.google"),
                                null,
                                null,
                                null,
                                null,
                            )
                            accountPickerLauncher.launch(intent)
                        }.isSuccess
                        if (!launched) {
                            showGoogleSheet = true
                        }
                    },
                )

                // Or divider
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        JarvisPalette.Cyan.copy(alpha = 0.45f),
                                    ),
                                ),
                            ),
                    )
                    Text(
                        text = "Or",
                        style = MaterialTheme.typography.titleSmall,
                        color = JarvisPalette.Ice.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        JarvisPalette.Cyan.copy(alpha = 0.45f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }

                // 2. Guest >
                AuthActionButton(
                    leadingBadge = {
                        GuestBadge(modifier = Modifier.size(24.dp))
                    },
                    label = "Guest",
                    subtitle = "Continue locally without a Google account",
                    primary = false,
                    onClick = onGuestSignIn,
                )

                // Fallback Google account sheet when system picker isn't used or on emulators
                AnimatedVisibility(
                    visible = showGoogleSheet,
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(160)),
                ) {
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        corner = 20.dp,
                        accent = JarvisPalette.CyanBright,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = "Google Account Sign-In",
                            style = MaterialTheme.typography.titleSmall,
                            color = JarvisPalette.CyanBright,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Link your Google identity for Calendar, Gmail & personalised J.A.R.V.I.S. greetings.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarvisPalette.TextDim,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = {
                                emailInput = it
                                validationError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Google Email (e.g. you@gmail.com)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Display Name (optional)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )
                        validationError?.let { err ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { showGoogleSheet = false }) {
                                Text("Cancel", color = JarvisPalette.TextDim)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val cleanEmail = emailInput.trim()
                                    if (!cleanEmail.contains("@") || !cleanEmail.contains(".")) {
                                        validationError = "Please enter a valid Google email address."
                                    } else {
                                        val cleanName = nameInput.trim()
                                            .ifBlank { deriveDisplayNameFromEmail(cleanEmail) }
                                        onGoogleSignIn(cleanEmail, cleanName)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = JarvisPalette.Cyan,
                                    contentColor = JarvisPalette.Void,
                                ),
                            ) {
                                Text("Continue with Google >", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Footer security note
            Text(
                text = "Zero credentials leave this device. Protected by Android Keystore AES-256-GCM.",
                style = MaterialTheme.typography.labelSmall,
                color = JarvisPalette.TextDim.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun AuthActionButton(
    leadingBadge: @Composable () -> Unit,
    label: String,
    subtitle: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val borderBrush = if (primary) {
        Brush.horizontalGradient(
            listOf(
                JarvisPalette.CyanBright.copy(alpha = 0.85f),
                JarvisPalette.Cyan.copy(alpha = 0.45f),
                JarvisPalette.CyanBright.copy(alpha = 0.85f),
            ),
        )
    } else {
        Brush.horizontalGradient(
            listOf(
                JarvisPalette.Cyan.copy(alpha = 0.38f),
                JarvisPalette.Line.copy(alpha = 0.65f),
            ),
        )
    }
    val bgBrush = if (primary) {
        Brush.horizontalGradient(
            listOf(
                JarvisPalette.Cyan.copy(alpha = 0.22f),
                JarvisPalette.Panel.copy(alpha = 0.95f),
                JarvisPalette.Cyan.copy(alpha = 0.16f),
            ),
        )
    } else {
        Brush.horizontalGradient(
            listOf(
                JarvisPalette.Panel.copy(alpha = 0.88f),
                JarvisPalette.Deep.copy(alpha = 0.92f),
            ),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgBrush)
            .border(width = if (primary) 1.5.dp else 1.dp, brush = borderBrush, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (primary) {
                        Color.White.copy(alpha = 0.12f)
                    } else {
                        JarvisPalette.Cyan.copy(alpha = 0.10f)
                    },
                )
                .border(
                    width = 1.dp,
                    color = if (primary) {
                        JarvisPalette.CyanBright.copy(alpha = 0.65f)
                    } else {
                        JarvisPalette.Cyan.copy(alpha = 0.30f)
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            leadingBadge()
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = JarvisPalette.Ice,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = JarvisPalette.TextDim,
            )
        }

        Text(
            text = ">",
            style = MaterialTheme.typography.headlineSmall,
            color = if (primary) JarvisPalette.CyanBright else JarvisPalette.Ice.copy(alpha = 0.82f),
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
        )
    }
}

@Composable
private fun GoogleGEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 3.2.dp.toPx()
        val d = size.minDimension - stroke
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        val arcSize = Size(d, d)
        // Google 4-color G arcs
        drawArc(
            color = Color(0xFFEA4335), // Red top
            startAngle = 215f,
            sweepAngle = 95f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawArc(
            color = Color(0xFFFBBC05), // Yellow left
            startAngle = 145f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawArc(
            color = Color(0xFF34A853), // Green bottom
            startAngle = 45f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawArc(
            color = Color(0xFF4285F4), // Blue right + crossbar
            startAngle = -15f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.50f, size.height * 0.50f),
            end = Offset(size.width - stroke * 0.35f, size.height * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun GuestBadge(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        val cx = size.width / 2f
        // Head circle
        drawCircle(
            color = JarvisPalette.CyanBright,
            radius = size.minDimension * 0.20f,
            center = Offset(cx, size.height * 0.34f),
            style = Stroke(width = stroke),
        )
        // Shoulders arc
        val arcWidth = size.width * 0.68f
        val arcHeight = size.height * 0.48f
        drawArc(
            color = JarvisPalette.CyanBright,
            startAngle = 195f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset((size.width - arcWidth) / 2f, size.height * 0.56f),
            size = Size(arcWidth, arcHeight),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

internal fun deriveDisplayNameFromEmail(email: String): String {
    val local = email.substringBefore('@').trim()
    if (local.isBlank()) return ""
    return local
        .split('.', '_', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { ch -> ch.titlecase() }
        }
}
