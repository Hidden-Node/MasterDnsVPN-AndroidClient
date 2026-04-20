package com.masterdns.vpn.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.masterdns.vpn.R
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvRadius
import com.masterdns.vpn.ui.theme.MdvSpace

@Composable
fun MdvHomeHeader(onOpenInfo: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(MdvRadius.Md))
                .clickable { onOpenInfo() }
                .padding(horizontal = MdvSpace.S2, vertical = MdvSpace.S1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground_raw),
                contentDescription = "Open info page",
                modifier = Modifier.size(32.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(MdvSpace.S2))
            Text(
                text = "MDV-HN",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MdvColor.Primary
                )
            )
        }
        FilledIconButton(
            onClick = onOpenInfo,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MdvColor.PrimaryContainer.copy(alpha = 0.18f),
                contentColor = MdvColor.PrimaryContainer
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = "Open info page"
            )
        }
    }
}
