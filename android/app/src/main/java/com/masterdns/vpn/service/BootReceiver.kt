package com.masterdns.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("MasterDnsVPN", "Boot completed, auto-connect can be triggered here")
            // TODO: Check settings if auto-connect on boot is enabled
            // If so, load selected profile and start VPN service
        }
    }
}
