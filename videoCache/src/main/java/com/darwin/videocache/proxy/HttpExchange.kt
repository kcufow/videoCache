package com.darwin.videocache.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val range: LongRange?,
)

internal class HttpExchange(private val socket: Socket) : AutoCloseable {
    private val input: InputStream = BufferedInputStream(socket.getInputStream())
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())

    fun readRequest(): HttpRequest? {
        val requestLine = readLine() ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }
        val range = parseRange(headers["range"])
        return HttpRequest(method, path, headers, range)
    }

    fun writeHeaders(
        code: Int,
        reason: String,
        headers: Map<String, String>,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        headers.forEach { (k, v) ->
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    fun writeBytes(body: ByteArray) {
        output.write(body)
        output.flush()
    }

    fun bodySink(): OutputStream = output

    fun writeError(code: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        writeHeaders(
            code = code,
            reason = message,
            headers = mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Connection" to "close",
            ),
        )
        writeBytes(body)
    }

    private fun readLine(): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(StandardCharsets.US_ASCII.name())
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString(StandardCharsets.US_ASCII.name())
    }

    private fun parseRange(header: String?): LongRange? {
        if (header.isNullOrBlank()) return null
        // bytes=start-end | bytes=start-
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substring(6).substringBefore(',').trim()
        val startText = spec.substringBefore('-')
        val endText = spec.substringAfter('-', missingDelimiterValue = "")
        val start = startText.toLongOrNull() ?: return null
        val end = endText.toLongOrNull()
        return if (end != null) LongRange(start, end) else LongRange(start, Long.MAX_VALUE - 1)
    }

    override fun close() {
        runCatching { output.flush() }
        runCatching { socket.close() }
    }
}
