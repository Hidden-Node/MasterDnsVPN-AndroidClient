package com.masterdns.vpn.service

internal class LineTooLongException : IllegalStateException("HTTP line exceeds 16384 bytes")

internal fun readLineUnbuffered(input: java.io.InputStream): String? {
    val out = java.io.ByteArrayOutputStream(256)
    while (true) {
        val next = input.read()
        if (next < 0) {
            if (out.size() == 0) return null
            break
        }
        if (next == '\n'.code) break
        if (next != '\r'.code) {
            out.write(next)
            if (out.size() > 16384) throw LineTooLongException()
        }
    }
    return String(out.toByteArray(), Charsets.ISO_8859_1)
}
