package com.darwin.videocache.hls

import com.darwin.videocache.util.UrlUtils

internal data class HlsPlaylist(
    val raw: String,
    val isMaster: Boolean,
    val mediaUrls: List<String>,
    val rewritten: String,
)

internal object HlsPlaylistParser {

    fun parseAndRewrite(
        playlistUrl: String,
        content: String,
        rewriteUrl: (absoluteUrl: String) -> String,
    ): HlsPlaylist {
        val lines = content.split("\n")
        val mediaUrls = ArrayList<String>()
        val out = StringBuilder()
        var isMaster = false
        var pendingUriLine = false

        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    isMaster = true
                    pendingUriLine = true
                    out.append(line).append('\n')
                }
                trimmed.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    out.append(rewriteTagUri(trimmed, playlistUrl, rewriteUrl)).append('\n')
                }
                trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    out.append(rewriteTagUri(trimmed, playlistUrl, rewriteUrl)).append('\n')
                }
                trimmed.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    out.append(rewriteTagUri(trimmed, playlistUrl, rewriteUrl)).append('\n')
                }
                trimmed.startsWith("#") || trimmed.isEmpty() -> {
                    out.append(line).append('\n')
                }
                else -> {
                    val absolute = UrlUtils.resolveAgainst(playlistUrl, trimmed)
                    mediaUrls += absolute
                    out.append(rewriteUrl(absolute)).append('\n')
                    pendingUriLine = false
                }
            }
            if (pendingUriLine && trimmed.startsWith("#") &&
                !trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)
            ) {
                // keep waiting for URI line
            }
        }
        return HlsPlaylist(
            raw = content,
            isMaster = isMaster || mediaUrls.any { it.substringBefore('?').endsWith(".m3u8", true) },
            mediaUrls = mediaUrls,
            rewritten = out.toString(),
        )
    }

    private fun rewriteTagUri(
        line: String,
        playlistUrl: String,
        rewriteUrl: (String) -> String,
    ): String {
        val regex = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        return regex.replace(line) { match ->
            val ref = match.groupValues[1]
            val absolute = UrlUtils.resolveAgainst(playlistUrl, ref)
            """URI="${rewriteUrl(absolute)}""""
        }
    }
}
