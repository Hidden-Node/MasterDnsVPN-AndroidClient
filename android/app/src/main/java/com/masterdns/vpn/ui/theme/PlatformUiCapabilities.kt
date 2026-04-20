package com.masterdns.vpn.ui.theme

import android.os.Build

fun supportsRuntimeBlur(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

fun supportsEnhancedGlow(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

