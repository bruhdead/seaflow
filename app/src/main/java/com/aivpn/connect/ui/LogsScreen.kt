package com.aivpn.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aivpn.connect.util.LogBuffer

@Composable
fun LogsScreen(onClose: () -> Unit) {
    val lines by LogBuffer.flow.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to newest line when list grows
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CosmicBlack),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Header with close & clear
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "LOGS",
                    fontSize = 18.sp,
                    color = BrightPurple,
                    letterSpacing = 4.sp,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { LogBuffer.clear() },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CLEAR", fontSize = 12.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = StarWhite)
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0A0814), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                if (lines.isEmpty()) {
                    Text(
                        "No logs yet. Connect to start capturing events.",
                        color = DimStar,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(lines) { line ->
                            Text(
                                line,
                                color = colorForLevel(line),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NebulaPurple),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("CLOSE", letterSpacing = 3.sp)
            }
        }
    }
}

private fun colorForLevel(line: String): Color {
    // Expected format: "HH:mm:ss.SSS L/TAG: ..."
    val levelChar = line.getOrNull(13) ?: return StarWhite
    return when (levelChar) {
        'E' -> ErrorRed
        'W' -> NeonPink
        'I' -> ConnectedGreen
        'D' -> StarWhite
        else -> DimStar
    }
}
