package com.masterdns.vpn.ui.components.mdv.controls

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.masterdns.vpn.ui.theme.MdvColor

@Composable
fun MdvFilterChip(
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { 
            Text(
                text = label,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium
            ) 
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MdvColor.PrimaryContainer.copy(alpha = 0.16f),
            selectedLabelColor = MdvColor.Primary,
            containerColor = MdvColor.SurfaceHigh,
            labelColor = MdvColor.OnSurfaceVariant
        )
    )
}

@Composable
fun MdvStatusChip(
    label: String,
    container: Color = MdvColor.PrimaryContainer.copy(alpha = 0.16f),
    contentColor: Color = MdvColor.Primary
) {
    androidx.compose.material3.AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, color = contentColor) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor = contentColor
        )
    )
}

