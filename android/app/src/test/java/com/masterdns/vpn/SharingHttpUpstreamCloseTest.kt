package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.service.SharingServer
import com.masterdns.vpn.util.VpnManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharingHttpUpstreamCloseTest {

    /** Stream whose writes ALWAYS fail: makes the handler's `200` write throw deterministically. */
    private class FailOnWriteStream : OutputStream() {
        override fun write(b: Int): Unit = throw java.io.IOException("boom")
        override fun write(b: ByteArray, off: Int, len: Int): Unit = throw java.io.IOException("boom")
    }

    /**
     * Client socket whose input delegates to a real loopback socket but whose
     * output ALWAYS throws: the handler parses the request and creates the
     * upstream tunnel, then the `200 Connection Established` write fails.
     */
    private class WriteFailSocket(private val delegate: java.net.Socket) : java.net.Socket() {
        override fun getInputStream(): InputStream = delegate.getInputStream()
        override fun getOutputStream(): OutputStream = FailOnWriteStream()
        override fun setSoTimeout(timeout: Int) {
            delegate.soTimeout = timeout
        }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun close() = delegate.close()
        override fun isClosed(): Boolean = delegate.isClosed
    }

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

    /** Loopback pair: [first] is the test-side driver, [second] feeds the handler. */
    private fun socketPair(): Pair<java.net.Socket, java.net.Socket> {
        val server = java.net.ServerSocket(0)
        val accepted = AtomicReference<java.net.Socket>()
        val t = Thread { runCatching { accepted.set(server.accept()) } }
        t.isDaemon = true
        t.start()
        val clientSide = java.net.Socket("127.0.0.1", server.localPort)
        t.join(5000)
        assertThat(t.isAlive).isFalse()
        server.close()
        return clientSide to accepted.get()!!
    }

    private fun readUntilBlank(s: java.net.Socket): String {
        val sb = StringBuilder()
        val input = s.getInputStream()
        while (true) {
            val r = input.read()
            if (r < 0) break
            sb.append(r.toChar())
            if (sb.toString().endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    @Test
    fun `failed 200-write closes upstream tunnel`() = runBlocking {
        // Warm up VpnManager before the measured window: its first-ever
        // appendLog pays one-time init (object init, coroutine scope, date
        // formats) that stalls the handler's catch path ~100ms, during which
        // the stub spuriously observes EOF even with the upstream leaked.
        // Warmed, the catch returns instantly: pre-fix the leak stays open
        // (stub read times out -> eofSeen -2 -> RED), post-fix the finally
        // closes it (EOF -> eofSeen -1 -> GREEN).
        VpnManager.appendLog("warmup plan-054")
        withTimeout(15000) {
            val eofSeen = AtomicInteger(-2)
            val (stubPort, stub) = runStub { input, output ->
                readExactly(input, 3)
                output.write(byteArrayOf(0x05, 0x00)); output.flush()
                readExactly(input, 4 + 1 + "example.com".length + 2)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
                eofSeen.set(input.read()) // -1 iff the handler closed the tunnel
            }
            // Driver pair: driver holds one end, the handler gets a
            // WriteFailSocket whose input reads the driver's request bytes.
            val listener = java.net.ServerSocket(0)
            val accepted = AtomicReference<java.net.Socket>()
            val acceptThread = Thread { runCatching { accepted.set(listener.accept()) } }
            acceptThread.isDaemon = true
            acceptThread.start()
            val real = java.net.Socket("127.0.0.1", listener.localPort)
            acceptThread.join(5000)
            assertThat(acceptThread.isAlive).isFalse()
            listener.close()
            val driver = accepted.get()!!
            driver.soTimeout = 5000
            try {
                driver.getOutputStream().write(
                    "CONNECT example.com:80 HTTP/1.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                )
                driver.getOutputStream().flush()
                val handlerSocket = WriteFailSocket(real)
                SharingServer.handleHttpClient(handlerSocket, stubPort, "", "")
                joinStub(stub)
                // -1 proves the tunnel was created AND closed; -2 means the
                // stub never saw EOF (pre-fix leak: stub read times out).
                assertThat(eofSeen.get()).isEqualTo(-1)
            } finally {
                runCatching { driver.close() }
                runCatching { real.close() }
            }
        }
    }

    @Test
    fun `tunnel-create failure closes nothing and returns`() = runBlocking {
        withTimeout(15000) {
            val probe = java.net.ServerSocket(0)
            val closedPort = probe.localPort
            probe.close()
            val (c, s) = socketPair()
            c.soTimeout = 10000
            try {
                c.getOutputStream().write(
                    "CONNECT example.com:80 HTTP/1.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                )
                c.getOutputStream().flush()
                // Tunnel throws (connection refused) -> 502 path, upstream
                // stays null; the new finally line must be null-safe.
                SharingServer.handleHttpClient(s, closedPort, "", "")
                val resp = readUntilBlank(c)
                assertThat(resp).startsWith("HTTP/1.1 502")
            } finally {
                runCatching { c.close() }
            }
        }
    }
}
