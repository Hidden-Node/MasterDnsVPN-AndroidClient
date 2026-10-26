package com.masterdns.vpn.ui.components.mdv.controls

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.masterdns.vpn.ui.theme.MdvColor

@Composable
fun MdvFilterChip(
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = leadingIcon,
        modifier = modifier,
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MdvColor.OutlineVariant,
            selectedBorderColor = MdvColor.Primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MdvColor.PrimaryContainer.copy(alpha = 0.22f),
            selectedLabelColor = MdvColor.Primary,
            selectedLeadingIconColor = MdvColor.Primary,
            containerColor = MdvColor.SurfaceHigh,
            labelColor = MdvColor.OnSurfaceVariant,
            iconColor = MdvColor.OnSurfaceVariant
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

