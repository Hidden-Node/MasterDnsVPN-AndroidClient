package com.masterdns.vpn.ui.resolvers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.masterdns.vpn.R
import com.masterdns.vpn.ui.components.mdv.cards.MdvCardLow
import com.masterdns.vpn.ui.theme.ConnectedGreen
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvSpace
import com.masterdns.vpn.util.VpnManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolversScreen() {
    val activeResolvers by VpnManager.activeResolvers.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MdvColor.Background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.title_resolvers),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MdvColor.OnSurface
                )
            },
            actions = {
                IconButton(onClick = {
                    val textToCopy = activeResolvers.joinToString("\n")
                    if (textToCopy.isNotBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Active Resolvers", textToCopy)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied ${activeResolvers.size} resolvers", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No active resolvers", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MdvColor.PrimaryContainer)
                }
                IconButton(onClick = {
                    val textToShare = activeResolvers.joinToString("\n")
                    if (textToShare.isNotBlank()) {
                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, textToShare)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        context.startActivity(shareIntent)
                    } else {
                        Toast.makeText(context, "No active resolvers to share", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = MdvColor.PrimaryContainer)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MdvColor.Background,
                titleContentColor = MdvColor.OnSurface,
                actionIconContentColor = MdvColor.OnSurfaceVariant
            )
        )

        if (activeResolvers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No active resolvers found yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MdvColor.OnSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = MdvSpace.S4),
                contentPadding = PaddingValues(bottom = MdvSpace.S6),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeResolvers) { resolver ->
                    ResolverItem(resolver)
                }
            }
        }
    }
}

@Composable
fun ResolverItem(resolver: String) {
    MdvCardLow(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(ConnectedGreen, androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = resolver,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MdvColor.OnSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
