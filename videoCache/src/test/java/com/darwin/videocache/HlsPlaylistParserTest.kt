package com.darwin.videocache

import com.darwin.videocache.hls.HlsPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {
    @Test
    fun rewriteMediaPlaylist() {
        val raw = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            https://cdn.example.com/a/seg1.ts
        """.trimIndent()
        val parsed = HlsPlaylistParser.parseAndRewrite(
            playlistUrl = "https://cdn.example.com/a/index.m3u8",
            content = raw,
        ) { "http://127.0.0.1:1/proxy/${it.hashCode()}" }

        assertEquals(2, parsed.mediaUrls.size)
        assertTrue(parsed.rewritten.contains("http://127.0.0.1:1/proxy/"))
        assertTrue(parsed.mediaUrls[0].endsWith("/a/seg0.ts"))
        assertEquals("https://cdn.example.com/a/seg1.ts", parsed.mediaUrls[1])
    }
}
