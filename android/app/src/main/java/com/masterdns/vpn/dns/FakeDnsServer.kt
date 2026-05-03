package com.masterdns.vpn.dns

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake DNS server that returns fake IPs from 198.18.0.0/16 range.
 * IMPORTANT: Must bind to 0.0.0.0 (not 10.0.0.1) to receive packets from Android
 */
class FakeDnsServer(
    private val listenPort: Int = 53
) {
    
    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private val fakeIpCounter = AtomicInteger(1)
    private val hostnameToFakeIp = ConcurrentHashMap<String, String>()
    private val fakeIpToHostname = ConcurrentHashMap<String, String>()
    
    companion object {
        private const val TAG = "FakeDnsServer"
        private const val FAKE_IP_PREFIX = "198.18"
        private const val MAX_FAKE_IPS = 65535
    }
    
    fun start() {
        if (running) return
        running = true
        
        Thread {
            try {
                // Bind to 0.0.0.0 (all interfaces) to receive packets from VPN interface
                socket = DatagramSocket(listenPort, InetAddress.getByName("0.0.0.0"))
                socket?.reuseAddress = true
                Log.i(TAG, "Fake DNS server started on 0.0.0.0:$listenPort")
                
                val buffer = ByteArray(512)
                while (running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)
                        
                        Log.d(TAG, "Received DNS query from ${packet.address}:${packet.port}")
                        
                        val query = buffer.copyOf(packet.length)
                        val response = processQuery(query)
                        
                        if (response != null) {
                            val responsePacket = DatagramPacket(
                                response, response.size,
                                packet.address, packet.port
                            )
                            socket?.send(responsePacket)
                            Log.d(TAG, "Sent DNS response to ${packet.address}:${packet.port}")
                        }
                    } catch (e: Exception) {
                        if (running) {
                            Log.e(TAG, "Error processing packet: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Fake DNS server error", e)
                }
            } finally {
                socket?.close()
            }
        }.apply {
            name = "FakeDnsServer"
            isDaemon = true
        }.start()
    }
    
    fun stop() {
        running = false
        socket?.close()
        socket = null
    }
    
    private fun processQuery(query: ByteArray): ByteArray? {
        try {
            // Parse DNS query
            val hostname = parseDnsQuery(query) ?: return null
            
            // Generate or retrieve fake IP
            val fakeIp = getFakeIpForHostname(hostname)
            
            Log.i(TAG, "DNS query: $hostname -> $fakeIp")
            
            // Build DNS response
            return buildDnsResponse(query, fakeIp)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing DNS query", e)
            return null
        }
    }
    
    private fun parseDnsQuery(query: ByteArray): String? {
        if (query.size < 12) return null
        
        var pos = 12 // Skip DNS header
        val labels = mutableListOf<String>()
        
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) break
            if (len > 63) return null // Invalid label length
            
            pos++
            if (pos + len > query.size) return null
            
            val label = String(query, pos, len, Charsets.UTF_8)
            labels.add(label)
            pos += len
        }
        
        return if (labels.isNotEmpty()) labels.joinToString(".") else null
    }
    
    private fun getFakeIpForHostname(hostname: String): String {
        return hostnameToFakeIp.getOrPut(hostname) {
            val counter = fakeIpCounter.getAndIncrement()
            if (counter > MAX_FAKE_IPS) {
                // Wrap around if we exceed the range
                fakeIpCounter.set(1)
            }
            val octet3 = (counter shr 8) and 0xFF
            val octet4 = counter and 0xFF
            val fakeIp = "$FAKE_IP_PREFIX.$octet3.$octet4"
            fakeIpToHostname[fakeIp] = hostname
            Log.d(TAG, "Mapped $hostname -> $fakeIp")
            fakeIp
        }
    }
    
    fun getHostnameForFakeIp(fakeIp: String): String? {
        return fakeIpToHostname[fakeIp]
    }
    
    fun getMappingCount(): Int = hostnameToFakeIp.size
    
    private fun buildDnsResponse(query: ByteArray, fakeIp: String): ByteArray {
        val response = ByteBuffer.allocate(512)
        
        // Copy query
        response.put(query)
        response.position(0)
        
        // Modify flags: QR=1 (response), AA=1 (authoritative)
        val flags = response.getShort(2).toInt()
        response.putShort(2, ((flags or 0x8400) and 0xFFFF).toShort())
        
        // Set answer count to 1
        response.putShort(6, 1)
        
        // Position at end of query
        response.position(query.size)
        
        // Add answer section
        // Name pointer to question (0xC00C)
        response.putShort(0xC00C.toShort())
        
        // Type A (1), Class IN (1)
        response.putShort(1)
        response.putShort(1)
        
        // TTL (60 seconds)
        response.putInt(60)
        
        // Data length (4 bytes for IPv4)
        response.putShort(4)
        
        // IP address
        val ipParts = fakeIp.split(".")
        ipParts.forEach { response.put(it.toInt().toByte()) }
        
        val result = ByteArray(response.position())
        response.position(0)
        response.get(result)
        return result
    }
}
