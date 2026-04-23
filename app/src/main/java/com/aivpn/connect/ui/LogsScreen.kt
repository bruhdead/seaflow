package com.aivpn.connect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aivpn.connect.util.LogBuffer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(onClose: () -> Unit) {
    val lines by LogBuffer.flow.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

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
            // Header
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
                Text(
                    "${lines.size} lines",
                    fontSize = 11.sp,
                    color = DimStar,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = StarWhite)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Toolbar: COPY / SHARE / CLEAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolbarButton(
                    icon = Icons.Default.ContentCopy,
                    label = "COPY",
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val text = lines.joinToString("\n")
                        copyToClipboard(context, text)
                        Toast.makeText(context, "Скопировано: ${lines.size} строк", Toast.LENGTH_SHORT).show()
                    },
                )
                ToolbarButton(
                    icon = Icons.Default.Share,
                    label = "SHARE",
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val file = writeLogFile(context, lines)
                        if (file != null) shareLogFile(context, file)
                        else Toast.makeText(context, "Не удалось сохранить лог", Toast.LENGTH_SHORT).show()
                    },
                )
                ToolbarButton(
                    icon = Icons.Default.DeleteSweep,
                    label = "CLEAR",
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = { LogBuffer.clear() },
                )
            }

            Spacer(Modifier.height(8.dp))

            // Log content
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

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, letterSpacing = 2.sp)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("seaflow logs", text))
}

/** Write log lines into cache/logs/seaflow-<ts>.txt, return the file (null on failure). */
private fun writeLogFile(context: Context, lines: List<String>): File? {
    return try {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "seaflow-$ts.txt")
        file.bufferedWriter().use { w ->
            w.write("# seaflow log — exported ${Date()} — ${lines.size} lines\n")
            lines.forEach { w.write(it); w.newLine() }
        }
        file
    } catch (e: Exception) {
        android.util.Log.e("LogsScreen", "writeLogFile failed: ${e.message}", e)
        null
    }
}

private fun shareLogFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "seaflow log — ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Поделиться логом")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        android.util.Log.e("LogsScreen", "shareLogFile failed: ${e.message}", e)
        Toast.makeText(context, "Не удалось открыть Share: ${e.message}", Toast.LENGTH_LONG).show()
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
