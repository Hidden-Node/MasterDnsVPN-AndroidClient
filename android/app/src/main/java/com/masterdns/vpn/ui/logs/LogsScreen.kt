package com.masterdns.vpn.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masterdns.vpn.util.VpnManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val logs by VpnManager.logs.collectAsState()
    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (autoScrollEnabled && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
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
                    FilledTonalIconButton(onClick = { autoScrollEnabled = !autoScrollEnabled }) {
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            state = listState,
            contentPadding = PaddingValues(8.dp)
        ) {
            items(logs) { line ->
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
