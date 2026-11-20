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
}
