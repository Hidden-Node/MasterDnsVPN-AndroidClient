package com.masterdns.vpn.ui.logs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masterdns.vpn.R
import com.masterdns.vpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.masterdns.vpn.ui.components.mdv.controls.MdvFilterChip
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvSpace
import com.masterdns.vpn.util.VpnManager

private enum class LogFilter(val labelRes: Int) {
    ALL(R.string.logs_filter_all),
    CORE(R.string.logs_filter_core),
    ANDROID(R.string.logs_filter_android)
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
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.logs_share_subject))
                putExtra(Intent.EXTRA_TEXT, content)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.logs_share_chooser)))
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
            // For high-frequency logs, jump scrolling is cheaper than animating each append.
            listState.scrollToItem(filteredLogs.size - 1)
        } else {
            val safeIndex = lockedIndex.coerceIn(0, (filteredLogs.size - 1).coerceAtLeast(0))
            listState.scrollToItem(safeIndex, lockedOffset)
        }
    }

    Scaffold(
        topBar = {
            MdvBackTopAppBar(
                title = stringResource(R.string.title_logs),
                onBack = onBack,
                actions = {
                    IconButton(onClick = shareLogs) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_share))
                    }
                    FilledTonalIconButton(onClick = {
                        if (autoScrollEnabled) {
                            lockedIndex = listState.firstVisibleItemIndex
                            lockedOffset = listState.firstVisibleItemScrollOffset
                        }
                        autoScrollEnabled = !autoScrollEnabled
                    }) {
                        Icon(Icons.Filled.AutoMode, contentDescription = stringResource(R.string.logs_auto))
                    }
                    Text(
                        text = if (autoScrollEnabled) {
                            stringResource(R.string.logs_auto_on)
                        } else {
                            stringResource(R.string.logs_auto_off)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MdvColor.OnSurfaceVariant,
                        modifier = Modifier.padding(end = MdvSpace.S1)
                    )
                    IconButton(onClick = { VpnManager.clearLogs() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.logs_clear))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MdvColor.Background)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MdvSpace.S2, vertical = MdvSpace.S1),
                horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)
            ) {
                LogFilter.entries.forEach { filter ->
                    MdvFilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = stringResource(filter.labelRes)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(MdvSpace.S2)
            ) {
                itemsIndexed(filteredLogs, key = { index, entry -> "${entry.source}:${entry.line.hashCode()}:$index" }) { _, entry ->
                    val line = entry.line
                    val color = when {
                        line.contains("[ERROR]", ignoreCase = true) -> Color(0xFFE57373)
                        line.contains("[WARN]", ignoreCase = true) -> Color(0xFFFFB74D)
                        line.contains("[INFO]", ignoreCase = true) -> Color(0xFF81C784)
                        else -> MdvColor.OnSurface.copy(alpha = 0.85f)
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
