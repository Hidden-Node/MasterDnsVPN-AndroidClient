package com.masterdns.vpn.ui.logs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masterdns.vpn.util.VpnManager

private enum class LogFilter(val label: String) {
    ALL("All"),
    CORE("Core"),
    ANDROID("Android")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val logEntries by VpnManager.logEntries.collectAsState()
    var activeFilter by remember { mutableStateOf(LogFilter.ALL) }
    val filteredLogs = remember(logEntries, activeFilter) {
        when (activeFilter) {
            LogFilter.ALL -> logEntries
            LogFilter.CORE -> logEntries.filter { it.source == VpnManager.LogSource.CORE }
            LogFilter.ANDROID -> logEntries.filter { it.source == VpnManager.LogSource.ANDROID }
        }
    }
    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var lockedIndex by remember { mutableStateOf(0) }
    var lockedOffset by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val shareLogs: () -> Unit = {
        if (filteredLogs.isNotEmpty()) {
            val content = filteredLogs.joinToString("\n") { it.line }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MasterDnsVPN Logs")
                putExtra(Intent.EXTRA_TEXT, content)
            }
            context.startActivity(Intent.createChooser(intent, "Share Logs"))
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, autoScrollEnabled) {
        if (!autoScrollEnabled) {
            lockedIndex = listState.firstVisibleItemIndex
            lockedOffset = listState.firstVisibleItemScrollOffset
        }
    }

    // Auto-scroll to bottom when new logs arrive, otherwise keep viewport locked
    LaunchedEffect(filteredLogs.size, activeFilter) {
        if (filteredLogs.isEmpty()) return@LaunchedEffect
        if (autoScrollEnabled) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        } else {
            val safeIndex = lockedIndex.coerceIn(0, (filteredLogs.size - 1).coerceAtLeast(0))
            listState.scrollToItem(safeIndex, lockedOffset)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = shareLogs) {
                        Icon(Icons.Filled.Share, contentDescription = "Share Logs")
                    }
                    FilledTonalIconButton(onClick = {
                        if (autoScrollEnabled) {
                            lockedIndex = listState.firstVisibleItemIndex
                            lockedOffset = listState.firstVisibleItemScrollOffset
                        }
                        autoScrollEnabled = !autoScrollEnabled
                    }) {
                        Icon(Icons.Filled.AutoMode, contentDescription = "Auto")
                    }
                    Text(
                        text = if (autoScrollEnabled) "Auto ON" else "Auto OFF",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    IconButton(onClick = { VpnManager.clearLogs() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear Logs")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.label) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(8.dp)
            ) {
                items(filteredLogs) { entry ->
                    val line = entry.line
                    val color = when {
                        line.contains("[ERROR]", ignoreCase = true) -> Color(0xFFE57373)
                        line.contains("[WARN]", ignoreCase = true) -> Color(0xFFFFB74D)
                        line.contains("[INFO]", ignoreCase = true) -> Color(0xFF81C784)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    }
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = color,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
