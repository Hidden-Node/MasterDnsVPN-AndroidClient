package com.masterdns.vpn.ui.components.mdv.controls

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.masterdns.vpn.ui.theme.MdvColor

@Composable
fun MdvPrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentDescription: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MdvColor.PrimaryContainer,
            contentColor = Color(0xFF001F24)
        )
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = contentDescription)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text)
    }
}

