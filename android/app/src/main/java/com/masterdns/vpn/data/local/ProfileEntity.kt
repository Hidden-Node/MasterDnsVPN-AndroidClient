package com.masterdns.vpn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val domains: String,              // JSON array: ["v.domain.com"]
    val encryptionMethod: Int = 1,    // 0=None, 1=XOR, 2=ChaCha20, 3-5=AES-GCM
    val encryptionKey: String = "",
    val protocolType: String = "SOCKS5",  // SOCKS5 or TCP
    val listenPort: Int = 18000,
    val resolvers: String = "",       // newline-separated list
    val resolverBalancingStrategy: Int = 0,
    val packetDuplicationCount: Int = 2,
    val setupPacketDuplicationCount: Int = 3,
    val uploadCompression: Int = 0,
    val downloadCompression: Int = 0,
    val logLevel: String = "INFO",
    val isSelected: Boolean = false,
    val advancedJson: String = "{}",  // JSON blob for all other advanced settings
    val createdAt: Long = System.currentTimeMillis()
)
