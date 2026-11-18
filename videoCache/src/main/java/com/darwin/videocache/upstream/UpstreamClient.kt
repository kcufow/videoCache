package com.darwin.videocache.upstream

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal class UpstreamClient(
    connectTimeoutSeconds: Long,
    readTimeoutSeconds: Long,
    private val defaultHeaders: Map<String, String>,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rangeStart: Long? = null,
        rangeEndInclusive: Long? = null,
    ): Response {
        val builder = Request.Builder().url(url)
        defaultHeaders.forEach { (k, v) -> builder.header(k, v) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (rangeStart != null) {
            val end = rangeEndInclusive?.toString().orEmpty()
            builder.header("Range", "bytes=$rangeStart-$end")
        }
        return client.newCall(builder.build()).execute()
    }

    fun fetchBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        rangeStart: Long? = null,
        rangeEndInclusive: Long? = null,
    ): UpstreamBody {
        val response = open(url, headers, rangeStart, rangeEndInclusive)
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw UpstreamException("HTTP ${response.code} for $url")
        }
        val body = response.body ?: run {
            response.close()
            throw UpstreamException("Empty body for $url")
        }
        val contentLength = resolveContentLength(response)
        val contentType = response.header("Content-Type")
        return UpstreamBody(
            response = response,
            contentLength = contentLength,
            contentType = contentType,
            totalLength = parseTotalLength(response, contentLength),
            bytes = body.byteStream(),
        )
    }

    fun fetchText(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String?> {
        open(url, headers).use { response ->
            if (!response.isSuccessful) {
                throw UpstreamException("HTTP ${response.code} for $url")
            }
            val type = response.header("Content-Type")
            val text = response.body?.string().orEmpty()
            return text to type
        }
    }

    private fun resolveContentLength(response: Response): Long {
        val contentRange = response.header("Content-Range")
        if (contentRange != null) {
            // bytes start-end/total
            val part = contentRange.substringAfter(" ").substringBefore("/")
            val start = part.substringBefore("-").toLongOrNull() ?: 0L
            val end = part.substringAfter("-").toLongOrNull() ?: return -1L
            return end - start + 1
        }
        return response.body?.contentLength() ?: -1L
    }

    private fun parseTotalLength(response: Response, partialLength: Long): Long {
        val contentRange = response.header("Content-Range")
        if (contentRange != null) {
            val total = contentRange.substringAfter("/", missingDelimiterValue = "").trim()
            if (total.isNotEmpty() && total != "*") {
                return total.toLongOrNull() ?: -1L
            }
        }
        return if (response.code == 200) partialLength else -1L
    }
}

internal class UpstreamBody(
    private val response: Response,
    val contentLength: Long,
    val contentType: String?,
    val totalLength: Long,
    val bytes: java.io.InputStream,
) : AutoCloseable {
    override fun close() {
        bytes.close()
        response.close()
    }
}

internal class UpstreamException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
