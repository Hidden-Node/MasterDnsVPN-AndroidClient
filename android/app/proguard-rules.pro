# ProGuard rules for MasterDnsVPN Android

# Keep Go mobile bindings
-keep class mobile.** { *; }
-keep class go.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }

# Keep Room entities
-keep class com.masterdns.vpn.data.local.** { *; }
