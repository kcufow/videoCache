package com.darwin.videocache.cache

import com.darwin.videocache.upstream.UpstreamClient
import java.io.OutputStream

/**
 * 负责把上游数据写入 [CacheEntry]，并可选边写边输出给播放器。
 */
internal class CacheFiller(
    private val store: CacheStore,
    private val upstream: UpstreamClient,
) {
    fun ensureRange(
        entry: CacheEntry,
        start: Long,
        endExclusive: Long,
        headers: Map<String, String>,
        sink: OutputStream? = null,
        onProgress: ((Long) -> Unit)? = null,
    ) {
        var cursor = start
        val buffer = ByteArray(DEFAULT_BUFFER)
        while (cursor < endExclusive) {
            val continuousEnd = continuousCachedEnd(entry, cursor, endExclusive)
            if (continuousEnd > cursor) {
                if (sink == null) {
                    cursor = continuousEnd
                    onProgress?.invoke(cursor - start)
                    continue
                }
                var pos = cursor
                while (pos < continuousEnd) {
                    val toRead = minOf(buffer.size.toLong(), continuousEnd - pos).toInt()
                    val read = entry.read(pos, buffer, 0, toRead)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    pos += read
                }
                cursor = pos
                onProgress?.invoke(cursor - start)
                continue
            }

            val missing = entry.firstMissing(cursor, endExclusive) ?: break
            val fetchStart = missing.first
            val fetchEndInclusive = missing.last
            store.ensureSpace(fetchEndInclusive - fetchStart + 1)

            upstream.fetchBytes(
                url = entry.originUrl,
                headers = headers,
                rangeStart = fetchStart,
                rangeEndInclusive = fetchEndInclusive,
            ).use { body ->
                if (body.totalLength > 0) {
                    entry.updateMeta(contentLength = body.totalLength, contentType = body.contentType)
                } else if (body.contentType != null) {
                    entry.updateMeta(contentType = body.contentType)
                }
                var writeAt = fetchStart
                while (writeAt <= fetchEndInclusive) {
                    val read = body.bytes.read(buffer)
                    if (read <= 0) break
                    val allowed = (fetchEndInclusive - writeAt + 1).toInt()
                    val chunk = minOf(read, allowed)
                    entry.write(writeAt, buffer, 0, chunk)
                    if (writeAt == cursor) {
                        sink?.write(buffer, 0, chunk)
                        cursor += chunk
                        onProgress?.invoke(cursor - start)
                    }
                    writeAt += chunk
                    if (chunk < read) break
                }
            }
        }
        sink?.flush()
    }

    /**
     * 未知 content-length 时边下边缓存边输出。
     */
    fun fillAndStream(
        entry: CacheEntry,
        headers: Map<String, String>,
        sink: OutputStream,
        onProgress: ((Long) -> Unit)? = null,
    ) {
        store.ensureSpace(DEFAULT_UNKNOWN_RESERVE)
        upstream.fetchBytes(entry.originUrl, headers).use { body ->
            entry.updateMeta(
                contentLength = if (body.totalLength > 0) body.totalLength else -1L,
                contentType = body.contentType,
            )
            val buffer = ByteArray(DEFAULT_BUFFER)
            var writeAt = 0L
            while (true) {
                val read = body.bytes.read(buffer)
                if (read <= 0) break
                store.ensureSpace(read.toLong())
                entry.write(writeAt, buffer, 0, read)
                sink.write(buffer, 0, read)
                writeAt += read
                onProgress?.invoke(writeAt)
            }
            if (entry.contentLength <= 0) {
                entry.updateMeta(contentLength = writeAt)
            }
            sink.flush()
        }
    }

    fun fillFully(
        entry: CacheEntry,
        headers: Map<String, String>,
        onProgress: ((Long) -> Unit)? = null,
    ) {
        if (entry.contentLength <= 0) {
            upstream.fetchBytes(entry.originUrl, headers, rangeStart = 0, rangeEndInclusive = 0).use { body ->
                if (body.totalLength > 0) {
                    entry.updateMeta(contentLength = body.totalLength, contentType = body.contentType)
                } else {
                    entry.updateMeta(contentType = body.contentType)
                }
            }
        }
        val length = entry.contentLength
        if (length > 0) {
            ensureRange(entry, 0L, length, headers, sink = null, onProgress = onProgress)
            return
        }
        store.ensureSpace(DEFAULT_UNKNOWN_RESERVE)
        upstream.fetchBytes(entry.originUrl, headers).use { body ->
            entry.updateMeta(
                contentLength = if (body.totalLength > 0) body.totalLength else -1L,
                contentType = body.contentType,
            )
            val buffer = ByteArray(DEFAULT_BUFFER)
            var writeAt = 0L
            while (true) {
                val read = body.bytes.read(buffer)
                if (read <= 0) break
                store.ensureSpace(read.toLong())
                entry.write(writeAt, buffer, 0, read)
                writeAt += read
                onProgress?.invoke(writeAt)
            }
            if (entry.contentLength <= 0) {
                entry.updateMeta(contentLength = writeAt)
            }
        }
    }

    private fun continuousCachedEnd(entry: CacheEntry, start: Long, endExclusive: Long): Long {
        if (!entry.hasRange(start, start + 1)) return start
        val missing = entry.firstMissing(start, endExclusive)
        return missing?.first ?: endExclusive
    }

    companion object {
        private const val DEFAULT_BUFFER = 64 * 1024
        private const val DEFAULT_UNKNOWN_RESERVE = 2L * 1024 * 1024
    }
}
