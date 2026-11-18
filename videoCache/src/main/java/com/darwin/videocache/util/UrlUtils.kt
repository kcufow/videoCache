package com.darwin.videocache.util

import com.darwin.videocache.api.ResourceType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object UrlUtils {

    fun detectType(url: String, forced: ResourceType? = null): ResourceType {
        if (forced != null) return forced
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return if (path.endsWith(".m3u8")) ResourceType.HLS else ResourceType.PROGRESSIVE
    }

    fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun decodePathSegment(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    fun resolveAgainst(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://", ignoreCase = true) ||
            ref.startsWith("https://", ignoreCase = true)
        ) {
            return ref
        }
        return URI(baseUrl).resolve(ref).toString()
    }

    fun guessMime(url: String, fallback: String = "application/octet-stream"): String {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".mov") -> "video/quicktime"
            path.endsWith(".flv") -> "video/x-flv"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".m4s") -> "video/iso.segment"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".aac") -> "audio/aac"
            else -> fallback
        }
    }
}
