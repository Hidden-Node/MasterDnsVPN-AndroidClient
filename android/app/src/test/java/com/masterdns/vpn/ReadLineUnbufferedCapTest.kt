package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.service.LineTooLongException
import com.masterdns.vpn.service.readLineUnbuffered
import org.junit.Test
import java.io.ByteArrayInputStream

class ReadLineUnbufferedCapTest {

    @Test
    fun `normal line is read`() {
        val input = ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray())
        assertThat(readLineUnbuffered(input)).isEqualTo("GET / HTTP/1.1")
    }

    @Test
    fun `empty stream returns null`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertThat(readLineUnbuffered(input)).isNull()
    }

    @Test
    fun `line over 16384 bytes throws`() {
        val big = "A".repeat(20000) + "\n"
        val input = ByteArrayInputStream(big.toByteArray(Charsets.ISO_8859_1))
        val thrown = runCatching { readLineUnbuffered(input) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(LineTooLongException::class.java)
    }

    @Test
    fun `16384-byte line is OK`() {
        val big = "A".repeat(16384) + "\n"
        val input = ByteArrayInputStream(big.toByteArray(Charsets.ISO_8859_1))
        assertThat(readLineUnbuffered(input)).isEqualTo("A".repeat(16384))
    }

    @Test
    fun `16385-byte line throws`() {
        val big = "A".repeat(16385) + "\n"
        val input = ByteArrayInputStream(big.toByteArray(Charsets.ISO_8859_1))
        val thrown = runCatching { readLineUnbuffered(input) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(LineTooLongException::class.java)
    }

    private class SingleByteInputStream(private val bytes: ByteArray) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos >= bytes.size) -1 else (bytes[pos++].toInt() and 0xFF)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            b[off] = bytes[pos++]
            return 1
        }
    }

    @Test
    fun `single-byte delivery matches bulk delivery`() {
        val text = "GET /path?q=1 HTTP/1.1\r\n"
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val bulk = readLineUnbuffered(ByteArrayInputStream(bytes))
        val single = readLineUnbuffered(SingleByteInputStream(bytes))
        assertThat(single).isEqualTo(bulk)
        assertThat(single).isEqualTo("GET /path?q=1 HTTP/1.1")
    }

    @Test
    fun `embedded CR is stripped`() {
        val input = ByteArrayInputStream("a\rb\r\n".toByteArray(Charsets.ISO_8859_1))
        assertThat(readLineUnbuffered(input)).isEqualTo("ab")
    }

    @Test
    fun `CR-only line returns empty string`() {
        val input = ByteArrayInputStream("\r\n".toByteArray(Charsets.ISO_8859_1))
        assertThat(readLineUnbuffered(input)).isEqualTo("")
    }

    @Test
    fun `high bytes decode as ISO-8859-1`() {
        val eAcute = byteArrayOf(0xE9.toByte(), 0x0A)
        assertThat(readLineUnbuffered(ByteArrayInputStream(eAcute))).isEqualTo("é")
        val twoBytes = byteArrayOf(0xC3.toByte(), 0xA9.toByte(), 0x0A)
        assertThat(readLineUnbuffered(ByteArrayInputStream(twoBytes))).isEqualTo("Ã©")
    }
}
