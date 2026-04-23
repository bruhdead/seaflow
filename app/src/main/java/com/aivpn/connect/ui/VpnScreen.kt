package com.aivpn.connect.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import kotlin.random.Random

data class VpnUiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val statusText: String = "Disconnected",
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val connectionKey: String = "",
    val timerSeconds: Long = 0L,
    val needsBatteryExemption: Boolean = false,
)

@Composable
fun VpnScreen(
    state: VpnUiState,
    onKeyChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onRequestBatteryExemption: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CosmicBlack)
    ) {
        // Animated nebula background
        NebulaBackground(state.connected)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // Title
            Text(
                "AIVPN",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.connected) ConnectedGreen else BrightPurple,
                letterSpacing = 8.sp,
            )
            Text(
                "CONNECT",
                fontSize = 14.sp,
                color = DimStar,
                letterSpacing = 6.sp,
            )

            Spacer(Modifier.height(16.dp))

            // Battery-optimization warning banner — shown until the user exempts Seaflow
            if (state.needsBatteryExemption) {
                BatteryOptBanner(onAllow = onRequestBatteryExemption)
                Spacer(Modifier.height(12.dp))
            }

            // Connection status orb
            ConnectionOrb(state.connected, state.connecting)

            Spacer(Modifier.height(16.dp))

            // Status text
            Text(
                state.statusText,
                fontSize = 16.sp,
                color = when {
                    state.connected -> ConnectedGreen
                    state.connecting -> BrightPurple
                    else -> DimStar
                },
                textAlign = TextAlign.Center,
            )

            // Timer
            if (state.connected && state.timerSeconds > 0) {
                Spacer(Modifier.height(8.dp))
                val h = state.timerSeconds / 3600
                val m = (state.timerSeconds % 3600) / 60
                val s = state.timerSeconds % 60
                Text(
                    String.format("%02d:%02d:%02d", h, m, s),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    color = StarWhite,
                    letterSpacing = 4.sp,
                )
            }

            // Traffic stats
            if (state.connected) {
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    TrafficStat(Icons.Default.ArrowUpward, "UP", state.uploadBytes, BrightPurple)
                    TrafficStat(Icons.Default.ArrowDownward, "DOWN", state.downloadBytes, NeonPink)
                }
            }

            Spacer(Modifier.weight(1f))

            // Connection key input
            if (!state.connected && !state.connecting) {
                OutlinedTextField(
                    value = state.connectionKey,
                    onValueChange = onKeyChanged,
                    label = { Text("Connection Key") },
                    placeholder = { Text("aivpn://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrightPurple,
                        unfocusedBorderColor = NebulaPurple,
                        cursorColor = BrightPurple,
                        focusedLabelColor = BrightPurple,
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(24.dp))
            }

            // Connect / Disconnect button
            val buttonColor by animateColorAsState(
                targetValue = if (state.connected) ErrorRed else BrightPurple,
                label = "btnColor"
            )

            Button(
                onClick = { if (state.connected || state.connecting) onDisconnect() else onConnect() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(16.dp),
                enabled = !state.connecting,
            ) {
                Icon(
                    imageVector = if (state.connected) Icons.Default.LinkOff else Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when {
                        state.connecting -> "CONNECTING..."
                        state.connected -> "DISCONNECT"
                        else -> "CONNECT"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = onOpenLogs,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = DimStar,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "LOGS",
                    color = DimStar,
                    fontSize = 12.sp,
                    letterSpacing = 3.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BatteryOptBanner(onAllow: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NeonPink.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.BatteryAlert,
            contentDescription = null,
            tint = NeonPink,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Фон отключён",
                color = StarWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "VPN может засыпать при блокировке экрана",
                color = DimStar,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text("РАЗРЕШИТЬ", fontSize = 11.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ConnectionOrb(connected: Boolean, connecting: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val orbColor by animateColorAsState(
        targetValue = when {
            connected -> ConnectedGreen
            connecting -> BrightPurple
            else -> NebulaPurple
        },
        animationSpec = tween(500),
        label = "orbColor"
    )

    val glowAlpha = if (connecting) pulseScale - 0.6f else if (connected) 0.4f else 0.15f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(120.dp)
    ) {
        // Glow
        Box(
            modifier = Modifier
                .size((80 * pulseScale).dp)
                .blur(24.dp)
                .clip(CircleShape)
                .background(orbColor.copy(alpha = glowAlpha))
        )
        // Core
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            orbColor.copy(alpha = 0.9f),
                            orbColor.copy(alpha = 0.3f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (connected) Icons.Default.Shield else Icons.Default.ShieldMoon,
                contentDescription = null,
                tint = StarWhite,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun TrafficStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    bytes: Long,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(formatBytes(bytes), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = StarWhite)
        Text(label, fontSize = 10.sp, color = DimStar, letterSpacing = 2.sp)
    }
}

@Composable
private fun NebulaBackground(connected: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "nebula")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
        ),
        label = "phase"
    )

    val nebulaColor by animateColorAsState(
        targetValue = if (connected) ConnectedGreen.copy(alpha = 0.06f) else BrightPurple.copy(alpha = 0.08f),
        animationSpec = tween(2000),
        label = "nebulaColor"
    )

    // Stars
    val stars = remember { List(80) { Offset(Random.nextFloat(), Random.nextFloat()) to Random.nextFloat() } }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Nebula glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(nebulaColor, Color.Transparent),
                center = Offset(w * 0.5f, h * 0.35f),
                radius = w * 0.7f,
            ),
            radius = w * 0.7f,
            center = Offset(w * 0.5f, h * 0.35f),
        )

        // Stars
        for ((pos, brightness) in stars) {
            val starAlpha = (0.3f + 0.7f * brightness * (0.5f + 0.5f * sin(phase + brightness * 10f))).coerceIn(0f, 1f)
            drawCircle(
                color = StarWhite.copy(alpha = starAlpha),
                radius = 1f + brightness * 2f,
                center = Offset(pos.x * w, pos.y * h),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
