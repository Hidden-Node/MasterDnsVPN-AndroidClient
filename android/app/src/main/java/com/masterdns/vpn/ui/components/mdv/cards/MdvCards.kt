package com.masterdns.vpn.ui.components.mdv.cards

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.masterdns.vpn.ui.theme.MdvColor
import com.masterdns.vpn.ui.theme.MdvRadius

@Composable
fun MdvCardLow(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(MdvRadius.Lg),
        colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceLow),
        content = content
    )
}

@Composable
fun MdvCardHigh(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(MdvRadius.Xl),
        colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh),
        content = content
    )
}

