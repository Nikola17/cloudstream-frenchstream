package com.lagradost

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FSTVProviderTest {
    @Test
    fun scopesPlaylistWorkaroundToFstvStreams() = runBlocking {
        val provider = FSTVProvider()
        val fstvLink = newExtractorLink(
            provider.name,
            "Direct",
            "https://fstv.rest/live.php?dl=116",
            ExtractorLinkType.M3U8
        )
        val externalLink = newExtractorLink(
            provider.name,
            "External",
            "https://example.com/live.m3u8",
            ExtractorLinkType.M3U8
        )

        assertNotNull(provider.getVideoInterceptor(fstvLink))
        assertNull(provider.getVideoInterceptor(externalLink))
    }
}
