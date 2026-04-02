package com.masterdns.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.masterdns.vpn.ui.navigation.AppNavigation
import com.masterdns.vpn.ui.theme.MasterDnsVPNTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MasterDnsVPNTheme {
                AppNavigation()
            }
        }
    }
}
