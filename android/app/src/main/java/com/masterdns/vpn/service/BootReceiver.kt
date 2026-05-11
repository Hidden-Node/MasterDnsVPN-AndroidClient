package com.masterdns.vpn.service

/*
 * TODO(auto-connect): keep this receiver dormant until the app has an explicit
 * auto-connect-on-boot setting and a permission-safe startup flow.
 *
 * Intended shape:
 *
 * class BootReceiver : BroadcastReceiver() {
 *     override fun onReceive(context: Context, intent: Intent) {
 *         if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
 *         // Check user setting, VPN permission, and selected profile.
 *         // Start MasterDnsVpnService only when all prerequisites are satisfied.
 *     }
 * }
 */
