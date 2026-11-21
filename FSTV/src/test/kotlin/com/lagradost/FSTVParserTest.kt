package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class FSTVParserTest {

    @Test
    fun keepsChannelNameWithLinkDataAndReadsLegacyUrls() {
        val data = FSTVParser.linkData("https://witv.online/chaines-live/tf1", "TF1")

        assertEquals(
            FSTVLinkData("https://witv.online/chaines-live/tf1", "TF1"),
            FSTVParser.parseLinkData(data)
        )
        assertEquals(
            FSTVLinkData("https://fstv.lol/index.php?newsid=4"),
            FSTVParser.parseLinkData("https://fstv.lol/index.php?newsid=4")
        )
    }

    @Test
    fun ignoresMalformedPayloads() {
        assertEquals(
            FSTVLinkData("not-json"),
            FSTVParser.parseLinkData("not-json")
        )
    }

    @Test
    fun rejectsMalformedOrLoopingLiveHlsAndExtractsHighestQuality() {
        val looping = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            index.m3u8
        """.trimIndent()
        val liveMedia = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:42
            #EXTINF:4.0,
            chunk_42.ts
        """.trimIndent()
        val liveMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
            720/live.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            1080/live.m3u8
        """.trimIndent()

        assertEquals(false, FSTVStreamVerifier.isValidMediaPlaylist(looping))
        assertEquals(false, FSTVStreamVerifier.isValidMediaPlaylist("<html>502 Bad Gateway</html>"))
        assertEquals(true, FSTVStreamVerifier.isValidMediaPlaylist(liveMedia))
        assertEquals(
            FSTVHlsCheck.Media(null, "https://edge.example/live/chunk_42.ts"),
            FSTVStreamVerifier.inspectHlsManifest(liveMedia, "https://edge.example/live/index.m3u8")
        )
        assertEquals(
            FSTVHlsCheck.Master(1080, "https://edge.example/1080/live.m3u8"),
            FSTVStreamVerifier.inspectHlsManifest(liveMaster, "https://edge.example/master.m3u8")
        )
    }
}
