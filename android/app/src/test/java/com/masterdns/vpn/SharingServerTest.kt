package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.service.SharingServer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharingServerTest {

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = input.read(buf, total, n - total)
            if (r < 0) throw IllegalStateException("stub EOF")
            total += r
        }
        return buf
    }

    /**
     * Runs [script] against one accepted loopback connection on a daemon thread.
     * Returns (port, thread). Always join with timeout + assert not-alive:
     * a stuck stub must fail the test, never hang the suite.
     */
    private fun runStub(script: (input: InputStream, output: OutputStream) -> Unit): Pair<Int, Thread> {
        val server = java.net.ServerSocket(0)
        val t = Thread {
            server.use { srv ->
                srv.soTimeout = 5000
                val conn = runCatching { srv.accept() }.getOrNull() ?: return@Thread
                conn.use { c ->
                    c.soTimeout = 3000
                    runCatching { script(c.getInputStream(), c.getOutputStream()) }
                }
            }
        }
        t.isDaemon = true
        t.start()
        return server.localPort to t
    }

    private fun joinStub(t: Thread) {
        t.join(5000)
        assertThat(t.isAlive).isFalse()
    }

    /** Loopback pair: [first] is the test-side client, [second] is fed to the handler. */
    private fun socketPair(): Pair<java.net.Socket, java.net.Socket> {
        val server = java.net.ServerSocket(0)
        val accepted = java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
        val t = Thread { runCatching { accepted.set(server.accept()) } }
        t.isDaemon = true
        t.start()
        val clientSide = java.net.Socket("127.0.0.1", server.localPort)
        t.join(5000)
        assertThat(t.isAlive).isFalse()
        server.close()
        return clientSide to accepted.get()!!
    }

    /** Drives the handler like the accept loop would (own thread, IO-like). */
    private fun runHandler(block: () -> Unit): Thread {
        val t = Thread { runCatching { block() } }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun joinHandler(t: Thread) {
        t.join(5000)
        assertThat(t.isAlive).isFalse()
    }

    private fun readN(s: java.net.Socket, n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        val input = s.getInputStream()
        while (total < n) {
            val r = input.read(buf, total, n - total)
            if (r < 0) throw IllegalStateException("client EOF")
            total += r
        }
        return buf
    }

    private fun readUntilBlank(s: java.net.Socket): String {
        val sb = StringBuilder()
        val input = s.getInputStream()
        var prev = 0.toChar()
        while (true) {
            val r = input.read()
            if (r < 0) break
            val c = r.toChar()
            sb.append(c)
            if (prev == '\r' && c == '\n' && sb.toString().endsWith("\r\n\r\n")) break
            prev = c
        }
        return sb.toString()
    }

    /** SOCKS5 upstream stub that completes a greeting + CONNECT and holds open. */
    private fun socksUpstreamStub(): Pair<Int, Thread> = runStub { input, output ->
        readExactly(input, 3)
        output.write(byteArrayOf(0x05, 0x00)); output.flush()
        readExactly(input, 4 + 1 + "example.com".length + 2)
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
        readExactly(input, 1) // hold until the handler closes the tunnel
    }

    @Test
    fun socksClient_wrongPassword_getsFailureAndDisconnect() {
        val (c, s) = socketPair()
        val out = c.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x02)); out.flush()
        val h = runHandler { runBlocking { SharingServer.handleSocksClient(s, 0, "user", "correct") } }
        val m = readN(c, 2)
        assertThat(m[0]).isEqualTo(0x05.toByte())
        assertThat(m[1]).isEqualTo(0x02.toByte())
        val user = "user".toByteArray()
        val pass = "xx".toByteArray()
        out.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        out.flush()
        val result = readN(c, 2)
        assertThat(result[0]).isEqualTo(0x01.toByte())
        assertThat(result[1]).isEqualTo(0x01.toByte())
        assertThat(c.getInputStream().read()).isEqualTo(-1)
        joinHandler(h)
        c.close()
    }

    @Test
    fun socksClient_correctPassword_getsSuccessAndTunnel() {
        val (stubPort, stub) = socksUpstreamStub()
        val (c, s) = socketPair()
        val out = c.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x02)); out.flush()
        val h = runHandler { runBlocking { SharingServer.handleSocksClient(s, stubPort, "user", "correct") } }
        val m = readN(c, 2)
        assertThat(m[1]).isEqualTo(0x02.toByte())
        val user = "user".toByteArray()
        val pass = "correct".toByteArray()
        out.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        out.flush()
        val auth = readN(c, 2)
        assertThat(auth[1]).isEqualTo(0x00.toByte())
        val host = "example.com".toByteArray()
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host + byteArrayOf(0, 80))
        out.flush()
        val reply = readN(c, 10)
        assertThat(reply[1]).isEqualTo(0x00.toByte())
        c.close() // end the bridge so the handler returns
        joinHandler(h)
        joinStub(stub)
    }

    @Test
    fun socksClient_noAuthOffered_getsNoAcceptableMethod() {
        val (c, s) = socketPair()
        val out = c.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val h = runHandler { runBlocking { SharingServer.handleSocksClient(s, 0, "user", "pass") } }
        val m = readN(c, 2)
        assertThat(m[0]).isEqualTo(0x05.toByte())
        assertThat(m[1]).isEqualTo(0xFF.toByte())
        joinHandler(h)
        c.close()
    }

    @Test
    fun httpClient_missingAuth_gets407() {
        val (c, s) = socketPair()
        c.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\n\r\n".toByteArray())
        c.getOutputStream().flush()
        val h = runHandler { runBlocking { SharingServer.handleHttpClient(s, 0, "user", "pass") } }
        val resp = readUntilBlank(c)
        assertThat(resp).startsWith("HTTP/1.1 407")
        assertThat(resp).contains("Proxy-Authenticate")
        joinHandler(h)
        c.close()
    }

    @Test
    fun httpClient_validAuth_connects() {
        val (stubPort, stub) = socksUpstreamStub()
        val (c, s) = socketPair()
        val cred = Base64.getEncoder().encodeToString("user:pass".toByteArray())
        c.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic $cred\r\n\r\n").toByteArray())
        c.getOutputStream().flush()
        val h = runHandler { runBlocking { SharingServer.handleHttpClient(s, stubPort, "user", "pass") } }
        val resp = readUntilBlank(c)
        assertThat(resp).startsWith("HTTP/1.1 200 Connection Established")
        c.close() // end the bridge so the handler returns
        joinHandler(h)
        joinStub(stub)
    }

    @Test
    fun httpClient_headerFlood_gets431() {
        val (c, s) = socketPair()
        val req = StringBuilder("GET / HTTP/1.1\r\n")
        repeat(101) { req.append("X-Flood-$it: v\r\n") }
        req.append("\r\n")
        c.getOutputStream().write(req.toString().toByteArray()); c.getOutputStream().flush()
        val h = runHandler { runBlocking { SharingServer.handleHttpClient(s, 0, "user", "pass") } }
        val resp = readUntilBlank(c)
        assertThat(resp).startsWith("HTTP/1.1 431")
        joinHandler(h)
        c.close()
    }

    @Test
    fun httpClient_proxyAuthHeaderStrippedOnForward() {
        val forwarded = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val (stubPort, stub) = runStub { input, output ->
            readExactly(input, 3)
            output.write(byteArrayOf(0x05, 0x00)); output.flush()
            readExactly(input, 4 + 1 + "example.com".length + 2) // tunnel request
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
            val reqLen = "GET /path HTTP/1.1\r\n".length + "Host: example.com\r\n".length + "Other: y\r\n".length + 2
            val b = readExactly(input, reqLen)
            forwarded.set(String(b, Charsets.ISO_8859_1))
            output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()); output.flush()
            readExactly(input, 1) // hold until the handler closes the tunnel
        }
        val (c, s) = socketPair()
        c.getOutputStream().write("GET http://example.com/path HTTP/1.1\r\nProxy-Authorization: Basic xxx\r\nOther: y\r\n\r\n".toByteArray())
        c.getOutputStream().flush()
        val h = runHandler { runBlocking { SharingServer.handleHttpClient(s, stubPort, "", "") } }
        val resp = readUntilBlank(c)
        assertThat(resp).startsWith("HTTP/1.1 200 OK")
        c.close() // end the bridge so the handler returns
        joinHandler(h)
        joinStub(stub)
        val sent = forwarded.get()
        assertThat(sent).isNotNull()
        assertThat(sent!!.startsWith("GET /path HTTP/1.1")).isTrue()
        assertThat(sent).contains("Host:")
        assertThat(sent.lines().none { it.startsWith("Proxy-Authorization:") }).isTrue()
    }
}
