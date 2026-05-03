package com.masterdns.vpn.dns

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * SOCKS5 proxy that intercepts connections to fake IPs and replaces them
 * with real hostnames before forwarding to the upstream SOCKS5 proxy.
 */
class FakeDnsInterceptor(
    private val listenPort: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val fakeDnsServer: FakeDnsServer
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    
    companion object {
        private const val TAG = "FakeDnsInterceptor"
        private const val BUFFER_SIZE = 8192
    }
    
    fun start() {
        if (running) return
        running = true
        
        Thread {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress("127.0.0.1", listenPort))
                Log.i(TAG, "Fake DNS interceptor started on port $listenPort")
                
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        Thread { handleClient(client) }.apply {
                            name = "FakeDnsInterceptor-Client"
                            isDaemon = true
                        }.start()
                    } catch (e: Exception) {
                        if (running) {
                            Log.e(TAG, "Error accepting client: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Fake DNS interceptor error", e)
                }
            } finally {
                serverSocket?.close()
            }
        }.apply {
            name = "FakeDnsInterceptor"
            isDaemon = true
        }.start()
    }
    
    fun stop() {
        running = false
        serverSocket?.close()
        serverSocket = null
    }
    
    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 30000 // 30 second timeout
            val input = client.getInputStream()
            val output = client.getOutputStream()
            
            // Read SOCKS5 greeting
            val version = input.read()
            if (version != 5) {
                Log.w(TAG, "Invalid SOCKS version: $version")
                return
            }
            
            val nmethods = input.read()
            if (nmethods <= 0) return
            
            val methods = ByteArray(nmethods)
            input.read(methods)
            
            // Send "no authentication required"
            output.write(byteArrayOf(5, 0))
            output.flush()
            
            // Read SOCKS5 request
            val requestHeader = ByteArray(4)
            if (input.read(requestHeader) != 4) return
            
            if (requestHeader[1].toInt() != 1) { // Only support CONNECT
                Log.w(TAG, "Unsupported SOCKS command: ${requestHeader[1]}")
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)) // Command not supported
                return
            }
            
            val addressType = requestHeader[3].toInt()
            val targetHost: String
            val targetPort: Int
            
            when (addressType) {
                1 -> { // IPv4
                    val addr = ByteArray(4)
                    if (input.read(addr) != 4) return
                    
                    val fakeIp = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    
                    // Check if this is a fake IP and resolve to real hostname
                    targetHost = fakeDnsServer.getHostnameForFakeIp(fakeIp) ?: fakeIp
                    
                    val portBytes = ByteArray(2)
                    if (input.read(portBytes) != 2) return
                    targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    
                    if (fakeIp != targetHost) {
                        Log.d(TAG, "Intercepted fake IP: $fakeIp -> $targetHost:$targetPort")
                    }
                }
                3 -> { // Domain name
                    val len = input.read()
                    if (len <= 0) return
                    
                    val domain = ByteArray(len)
                    if (input.read(domain) != len) return
                    targetHost = String(domain, Charsets.UTF_8)
                    
                    val portBytes = ByteArray(2)
                    if (input.read(portBytes) != 2) return
                    targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                }
                else -> {
                    Log.w(TAG, "Unsupported address type: $addressType")
                    output.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)) // Address type not supported
                    return
                }
            }
            
            // Connect to upstream SOCKS5 proxy
            upstream = Socket()
            upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), 5000)
            upstream.soTimeout = 30000
            
            val upInput = upstream.getInputStream()
            val upOutput = upstream.getOutputStream()
            
            // SOCKS5 handshake with upstream
            upOutput.write(byteArrayOf(5, 1, 0))
            upOutput.flush()
            
            val upGreeting = ByteArray(2)
            if (upInput.read(upGreeting) != 2) {
                output.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)) // General failure
                return
            }
            
            // Send CONNECT request with real hostname to upstream
            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            val request = ByteArray(7 + hostBytes.size)
            request[0] = 5 // Version
            request[1] = 1 // CONNECT
            request[2] = 0 // Reserved
            request[3] = 3 // Domain name
            request[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
            request[5 + hostBytes.size] = (targetPort shr 8).toByte()
            request[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            
            upOutput.write(request)
            upOutput.flush()
            
            // Read upstream response
            val upResponse = ByteArray(4)
            if (upInput.read(upResponse) != 4) {
                output.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)) // General failure
                return
            }
            
            // Read bound address
            when (upResponse[3].toInt()) {
                1 -> upInput.read(ByteArray(6)) // IPv4 + port
                3 -> {
                    val len = upInput.read()
                    if (len > 0) upInput.read(ByteArray(len + 2))
                }
                4 -> upInput.read(ByteArray(18)) // IPv6 + port
            }
            
            // Check if upstream connection succeeded
            if (upResponse[1].toInt() != 0) {
                Log.w(TAG, "Upstream SOCKS5 error: ${upResponse[1]}")
                output.write(byteArrayOf(5, upResponse[1], 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            
            // Send success response to client
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            
            // Relay data bidirectionally
            val clientToUpstream = Thread {
                relay(input, upOutput, "client->upstream")
                upstream.shutdownOutput()
            }.apply {
                name = "Relay-C2U"
                isDaemon = true
            }
            
            val upstreamToClient = Thread {
                relay(upInput, output, "upstream->client")
                client.shutdownOutput()
            }.apply {
                name = "Relay-U2C"
                isDaemon = true
            }
            
            clientToUpstream.start()
            upstreamToClient.start()
            
            clientToUpstream.join()
            upstreamToClient.join()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
        }
    }
    
    private fun relay(input: InputStream, output: OutputStream, direction: String) {
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
            // Connection closed or error
        }
    }
}
