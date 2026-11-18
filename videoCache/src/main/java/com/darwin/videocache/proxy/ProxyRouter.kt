package com.darwin.videocache.proxy

import com.darwin.videocache.api.CacheKeys
import com.darwin.videocache.api.ResourceType
import com.darwin.videocache.cache.CacheEntry
import com.darwin.videocache.cache.CacheFiller
import com.darwin.videocache.cache.CacheStore
import com.darwin.videocache.core.ResourceRegistry
import com.darwin.videocache.hls.HlsPlaylistParser
import com.darwin.videocache.upstream.UpstreamClient
import com.darwin.videocache.util.UrlUtils
import java.util.Locale

internal class ProxyRouter(
    private val registry: ResourceRegistry,
    private val store: CacheStore,
    private val filler: CacheFiller,
    private val upstream: UpstreamClient,
    private val baseUrlProvider: () -> String,
) {
    fun handle(exchange: HttpExchange) {
        val request = exchange.readRequest()
        if (request == null) {
            exchange.writeError(400, "Bad Request")
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            exchange.writeError(405, "Method Not Allowed")
            return
        }

        val path = request.path.substringBefore('?')
        when {
            path.startsWith("/p/") -> handleProgressive(exchange, request, path.removePrefix("/p/"))
            path.startsWith("/h/") -> handleHls(exchange, request, path.removePrefix("/h/"))
            path == "/health" -> {
                exchange.writeHeaders(
                    200,
                    "OK",
                    mapOf(
                        "Content-Type" to "text/plain",
                        "Content-Length" to "2",
                        "Connection" to "close",
                    ),
                )
                if (request.method == "GET") exchange.writeBytes("OK".toByteArray())
            }
            else -> exchange.writeError(404, "Not Found")
        }
    }

    private fun handleProgressive(exchange: HttpExchange, request: HttpRequest, keyPart: String) {
        val key = UrlUtils.decodePathSegment(keyPart)
        val resource = registry.get(key)
        if (resource == null) {
            exchange.writeError(404, "Unknown resource")
            return
        }
        val entry = store.getOrCreate(key, resource.originUrl, ResourceType.PROGRESSIVE)
        store.protect(key)
        try {
            // 探测长度
            if (entry.contentLength <= 0) {
                runCatching {
                    filler.ensureRange(entry, 0, 1, resource.headers, sink = null)
                }
            }
            val total = entry.contentLength
            val mime = entry.contentType ?: UrlUtils.guessMime(resource.originUrl, "video/mp4")
            val range = request.range
            val start: Long
            val endInclusive: Long
            if (range != null && total > 0) {
                start = range.first.coerceIn(0, total - 1)
                endInclusive = if (range.last >= Long.MAX_VALUE / 2) {
                    total - 1
                } else {
                    range.last.coerceIn(start, total - 1)
                }
            } else if (range != null) {
                start = range.first
                endInclusive = if (range.last >= Long.MAX_VALUE / 2) start + DEFAULT_CHUNK - 1 else range.last
            } else {
                start = 0L
                endInclusive = if (total > 0) total - 1 else -1L
            }

            if (request.method == "HEAD") {
                val headers = LinkedHashMap<String, String>()
                headers["Content-Type"] = mime
                headers["Accept-Ranges"] = "bytes"
                headers["Connection"] = "close"
                if (total > 0) headers["Content-Length"] = total.toString()
                exchange.writeHeaders(200, "OK", headers)
                return
            }

            if (total > 0 && range != null) {
                val length = endInclusive - start + 1
                exchange.writeHeaders(
                    206,
                    "Partial Content",
                    mapOf(
                        "Content-Type" to mime,
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to length.toString(),
                        "Content-Range" to "bytes $start-$endInclusive/$total",
                        "Connection" to "close",
                    ),
                )
                filler.ensureRange(
                    entry = entry,
                    start = start,
                    endExclusive = endInclusive + 1,
                    headers = resource.headers,
                    sink = exchange.bodySink(),
                )
            } else if (total > 0) {
                exchange.writeHeaders(
                    200,
                    "OK",
                    mapOf(
                        "Content-Type" to mime,
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to total.toString(),
                        "Connection" to "close",
                    ),
                )
                filler.ensureRange(
                    entry = entry,
                    start = 0,
                    endExclusive = total,
                    headers = resource.headers,
                    sink = exchange.bodySink(),
                )
            } else {
                // 未知长度：边下边写，用 chunked
                exchange.writeHeaders(
                    200,
                    "OK",
                    mapOf(
                        "Content-Type" to mime,
                        "Accept-Ranges" to "bytes",
                        "Transfer-Encoding" to "chunked",
                        "Connection" to "close",
                    ),
                )
                val chunked = ChunkedOutputStream(exchange.bodySink())
                filler.fillAndStream(entry, resource.headers, chunked)
                chunked.finish()
            }
        } catch (t: Throwable) {
            runCatching { exchange.writeError(502, t.message ?: "Upstream error") }
        } finally {
            store.unprotect(key)
        }
    }

    private fun handleHls(exchange: HttpExchange, request: HttpRequest, rest: String) {
        // /{key}/index.m3u8  or /{key}/seg/{segKey}
        val parts = rest.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            exchange.writeError(404, "Not Found")
            return
        }
        val playlistKey = UrlUtils.decodePathSegment(parts[0])
        val resource = registry.get(playlistKey)
        if (resource == null) {
            exchange.writeError(404, "Unknown playlist")
            return
        }

        if (parts.size >= 3 && parts[1] == "seg") {
            val segKey = UrlUtils.decodePathSegment(parts[2])
            handleProgressive(exchange, request, UrlUtils.encodePathSegment(segKey))
            return
        }

        // playlist：优先读本地缓存（支持预加载后断网播已缓存分片）
        store.protect(playlistKey)
        try {
            val entry = store.getOrCreate(playlistKey, resource.originUrl, ResourceType.HLS)
            val (text, contentType) = loadPlaylistText(entry, resource.originUrl, resource.headers)
            val parsed = HlsPlaylistParser.parseAndRewrite(resource.originUrl, text) { absolute ->
                val segKey = CacheKeys.sha256(absolute)
                registry.registerSegment(playlistKey, segKey, absolute, resource.headers)
                val typePath = if (absolute.substringBefore('?').lowercase(Locale.US).endsWith(".m3u8")) {
                    "${baseUrlProvider()}/h/${UrlUtils.encodePathSegment(segKey)}/index.m3u8"
                } else {
                    "${baseUrlProvider()}/h/${UrlUtils.encodePathSegment(playlistKey)}/seg/${UrlUtils.encodePathSegment(segKey)}"
                }
                typePath
            }
            // 嵌套 m3u8 也注册
            parsed.mediaUrls.filter { it.substringBefore('?').endsWith(".m3u8", true) }.forEach { nested ->
                val nestedKey = CacheKeys.sha256(nested)
                registry.register(nestedKey, nested, ResourceType.HLS, resource.headers)
            }

            val body = parsed.rewritten.toByteArray(Charsets.UTF_8)
            val mime = contentType ?: "application/vnd.apple.mpegurl"
            exchange.writeHeaders(
                200,
                "OK",
                mapOf(
                    "Content-Type" to mime,
                    "Content-Length" to body.size.toString(),
                    "Cache-Control" to "no-cache",
                    "Connection" to "close",
                ),
            )
            if (request.method == "GET") {
                exchange.writeBytes(body)
            }
        } catch (t: Throwable) {
            exchange.writeError(502, t.message ?: "HLS error")
        } finally {
            store.unprotect(playlistKey)
        }
    }

    private fun loadPlaylistText(
        entry: CacheEntry,
        originUrl: String,
        headers: Map<String, String>,
    ): Pair<String, String?> {
        entry.readFullyOrNull()?.let { cached ->
            return String(cached, Charsets.UTF_8) to entry.contentType
        }
        val (text, contentType) = upstream.fetchText(originUrl, headers)
        val bytes = text.toByteArray(Charsets.UTF_8)
        store.ensureSpace(bytes.size.toLong())
        entry.writeFully(bytes, contentType ?: "application/vnd.apple.mpegurl")
        return text to contentType
    }

    companion object {
        private const val DEFAULT_CHUNK = 1024L * 1024L
    }
}

internal class ChunkedOutputStream(
    private val delegate: java.io.OutputStream,
) : java.io.OutputStream() {
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val sizeHex = Integer.toHexString(len)
        delegate.write("$sizeHex\r\n".toByteArray(Charsets.US_ASCII))
        delegate.write(b, off, len)
        delegate.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    fun finish() {
        delegate.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        delegate.flush()
    }

    override fun flush() {
        delegate.flush()
    }
}
