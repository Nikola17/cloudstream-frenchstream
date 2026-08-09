package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FSTVParserTest {
    @Test
    fun exposesOnlyThePagePrimaryStreamForPlayback() {
        val config = FSTVPlayerConfig(
            name = "Bein Sport 1",
            streamUrl = "https://fstv.rest/live.php?dl=116",
            posterUrl = null
        )

        assertEquals(
            listOf(FSTVPlaybackSource("Direct", config.streamUrl, 576)),
            FSTVParser.playbackSources(config, 576)
        )
    }

    @Test
    fun placesRankedAlternativeSourcesBeforeThePageFallback() {
        val config = FSTVPlayerConfig(
            name = "Bein Sport 1",
            streamUrl = "https://fstv.rest/live.php?dl=116",
            posterUrl = null
        )
        val alternatives = listOf(
            FSTVSource("fr-fhd", 1080, "FR FHD TNT"),
            FSTVSource("fr-hd", 720, "FR HD TNT")
        )

        assertEquals(
            listOf(
                FSTVPlaybackSource("FR FHD TNT", "https://fstv.rest/live.php?id=fr-fhd", 1080),
                FSTVPlaybackSource("FR HD TNT", "https://fstv.rest/live.php?id=fr-hd", 720),
                FSTVPlaybackSource("Direct", config.streamUrl, 576)
            ),
            FSTVParser.playbackSources(config, 576, alternatives)
        )
    }

    @Test
    fun keepsSportAndCinemaCatalogsFirst() {
        assertEquals(
            listOf("sport", "cinema", "generaliste", "enfants", "documentaire", "musique", "information"),
            FSTVParser.categories.map { it.slug }
        )
    }

    @Test
    fun parsesLiveChannelCards() {
        val document = Jsoup.parse(
            """
            <div class="short">
              <a class="short-poster" href="https://fstv.rest/index.php?newsid=4" alt="Bein Sport 1">
                <img src="/chaineimg/bein-sports-1-logo.png" alt="Bein Sport 1">
              </a>
              <div class="short-title"> Bein Sport 1 </div>
            </div>
            <div class="short">
              <a class="short-poster" href="/index.php?newsid=76">
                <img data-src="/chaineimg/canal-plus-cinema.png">
              </a>
              <div class="short-title">CANAL+ Cinéma</div>
            </div>
            """.trimIndent(),
            "https://fstv.rest/"
        )

        assertEquals(
            listOf(
                FSTVChannel(
                    "Bein Sport 1",
                    "https://fstv.rest/index.php?newsid=4",
                    "https://fstv.rest/chaineimg/bein-sports-1-logo.png"
                ),
                FSTVChannel(
                    "CANAL+ Cinéma",
                    "https://fstv.rest/index.php?newsid=76",
                    "https://fstv.rest/chaineimg/canal-plus-cinema.png"
                )
            ),
            FSTVParser.channels(document)
        )
    }

    @Test
    fun parsesPlayerConfigurationAndPoster() {
        val document = Jsoup.parse(
            """
            <title>Bein Sport 1 » French Stream TV</title>
            <script>window.FSTV_NAME="Bein Sport 1";</script>
            <script>window.FSTV_SRC="/live.php?dl=116&amp;backup=1";</script>
            <img id="posterImage" src="/chaineimg/bein-sports-1-logo.png">
            """.trimIndent(),
            "https://fstv.rest/index.php?newsid=4"
        )

        assertEquals(
            FSTVPlayerConfig(
                "Bein Sport 1",
                "https://fstv.rest/live.php?dl=116&backup=1",
                "https://fstv.rest/chaineimg/bein-sports-1-logo.png"
            ),
            FSTVParser.playerConfig(document)
        )
    }

    @Test
    fun keepsTheOfficialSourceOrderWhilePrioritizingFrenchAlternatives() {
        val sources = FSTVParser.sources(
            """
            [
              {"id":"turkey-hd","q":"HD","s":"satellite","ct":"Turkey"},
              {"id":"fr-hd","q":"HD","s":"basic","ct":"France"},
              {"id":"fr-fhd","q":"FHD","s":"cable","ct":"France"},
              {"id":"fr-unknown","q":"","s":"basic","ct":"France"}
            ]
            """.trimIndent()
        )

        assertEquals(listOf("fr-hd", "fr-fhd", "fr-unknown", "turkey-hd"), sources.map { it.id })
        assertEquals(listOf(720, 1080, 0, 720), sources.map { it.quality })
        assertEquals("FR HD TNT", sources.first().label)
    }

    @Test
    fun rejectsPagesWithoutAConfiguredStream() {
        val document = Jsoup.parse("<title>Chaîne indisponible</title>", "https://fstv.rest/")

        assertNull(FSTVParser.playerConfig(document))
    }

    @Test
    fun discoversTvDomainFromOfficialPortal() {
        val document = Jsoup.parse(
            """
            <a href="https://fs20.lol">Films et séries</a>
            <a href="https://new-fstv.example/">Regarder La TV</a>
            <a href="https://fsound.lol">Musique</a>
            """.trimIndent(),
            "https://fstream.info/"
        )

        assertEquals("https://new-fstv.example", FSTVParser.portalTvBaseUrl(document))
    }

    @Test
    fun parsesCurrentAndNextProgram() {
        val program = FSTVParser.program(
            """
            {
              "current":{"title":"Football en direct","category":"Sport","remaining_sec":1800},
              "next":{"title":"Magazine du soir"}
            }
            """.trimIndent()
        )

        assertEquals(
            FSTVProgram("Football en direct", "Sport", 1800, "Magazine du soir"),
            program
        )
    }

    @Test
    fun findsHighestHlsResolution() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5500000,RESOLUTION=1920x1080
            1080.m3u8
        """.trimIndent()

        assertEquals(1080, FSTVParser.highestHlsQuality(manifest))
    }

    @Test
    fun raisesShortTargetDurationForIrregularLivePlaylist() {
        val manifest = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:30297
            #EXTINF:4.000,
            segment.ts
        """.trimIndent()

        assertEquals(
            manifest.replace("#EXT-X-TARGETDURATION:4", "#EXT-X-TARGETDURATION:8"),
            FSTVParser.normalizeHlsManifest(manifest)
        )
    }

    @Test
    fun keepsFstvProxyInPlaylistAndExtractsItsDirectFallback() {
        val manifest = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:30297
            #EXTINF:4.000,
            https://fstv.rest/live.php?seg=https%3A%2F%2Fexample.r2.dev%2Fsegment.png%3FX-Amz-Algorithm%3DAWS4-HMAC-SHA256%26X-Amz-Signature%3Dabc123&r=https%3A%2F%2Fplayer.example%2F
        """.trimIndent()

        assertEquals(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:8
                #EXT-X-MEDIA-SEQUENCE:30297
                #EXTINF:4.000,
                https://fstv.rest/live.php?seg=https%3A%2F%2Fexample.r2.dev%2Fsegment.png%3FX-Amz-Algorithm%3DAWS4-HMAC-SHA256%26X-Amz-Signature%3Dabc123&r=https%3A%2F%2Fplayer.example%2F
            """.trimIndent(),
            FSTVParser.normalizeHlsManifest(manifest)
        )
        assertEquals(
            "https://example.r2.dev/segment.png?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc123",
            FSTVParser.directSegmentUrl(manifest.lineSequence().last())
        )
        assertNull(FSTVParser.directSegmentUrl("https://fstv.rest/live.php?dl=116"))
        assertEquals(
            true,
            FSTVParser.isProxiedMediaPlaylist(
                "https://fstv.rest/live.php?seg=https%3A%2F%2Fstream.example%2Fmono.m3u8&r=https%3A%2F%2Fplayer.example%2F"
            )
        )
        assertEquals(false, FSTVParser.isProxiedMediaPlaylist(manifest.lineSequence().last()))
    }

    @Test
    fun leavesMasterAndAlreadyRelaxedPlaylistsUntouched() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2190000,RESOLUTION=1024x576
            media.m3u8
        """.trimIndent()
        val relaxed = """
            #EXTM3U
            #EXT-X-TARGETDURATION:15
            #EXT-X-MEDIA-SEQUENCE:42
        """.trimIndent()

        assertEquals(master, FSTVParser.normalizeHlsManifest(master))
        assertEquals(relaxed, FSTVParser.normalizeHlsManifest(relaxed))
    }
}
