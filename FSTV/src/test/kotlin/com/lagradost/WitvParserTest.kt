package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WitvParserTest {

    private val listPage = Jsoup.parse(
        """
        <div class="card">
          <a href="/chaines-live/tf1"><img alt="TF1" src="/images/logo/tf1.png"/>Live Regarder</a>
        </div>
        <div class="card">
          <img alt="Canal+" src="/images/logo/canal.png"/>
          <a href="https://witv.channel/chaines-live/27-canal-plus.html">Regarder</a>
        </div>
        <a href="/chaines-live/sport/">Sport</a>
        <a href="/chaines-live/cinema/">Cinéma</a>
        <a href="/chaines-live">Toutes les chaînes</a>
        <div class="card">
          <a href="/chaines-live/6ter"><img src="data:image/gif;base64,xxx" data-src="/images/logo/6ter-logo.png"/>Live</a>
        </div>
        <div class="card" style="background-image:url('/images/logo/arte-logo.png')">
          <a href="/chaines-live/arte">Regarder</a>
        </div>
        <div class="ann-short_item">
          <a href="https://witv.channel/chaines-live/70-comedie.html">Regarder</a>
          <div class="ann-short_price">Comédie+ HD</div>
          <img src="/images/logo/Comédie+logo.png" alt="Comédie+ HD"/>
        </div>
        <div class="ann-short_item">
          <a href="https://witv.channel/chaines-live/200-tnt-sports-2-uk.html">Regarder</a>
          <div class="ann-short_price">TNT Sports 2 UK</div>
          <img src="https://media.info/l/o/1/1540.1690027877.png" alt="TNT Sports 2 UK"/>
        </div>
        <div class="ann-short_item">
          <a href="/chaines-live/ab1">Regarder</a>
          <div class="ann-short_price">AB1</div>
          <img src="/assets/img/channel-placeholder.svg" alt="AB1"/>
        </div>
        <a href="https://witv.channel/chaines-live/page/2/">Suivant</a>
        """.trimIndent(),
        "https://witv.channel/chaines-live/"
    )

    private val channelPage = Jsoup.parse(
        """
        <a href="/player/source1/?id=469">Source 1</a>
        <a href="/player/source2/?id=webtv_tf1_hd">Source 2</a>
        <a href="/chaines-live/m6">M6</a>
        <iframe src="https://zonalive.example/embed/abc"></iframe>
        <iframe src="https://www.youtube.com/embed/xyz"></iframe>
        """.trimIndent(),
        "https://witv.channel/chaines-live/tf1"
    )

    @Test
    fun prioritizesOfficialWitvChannelDomainFirst() {
        assertEquals("https://witv.channel", WitvParser.knownBases.first())
    }

    @Test
    fun parsesChannelLinksEncodesAccentsAndAppliesStudioAndFallbackLogos() {
        val channels = WitvParser.channels(listPage)
        val bySlug = channels.associateBy { WitvParser.slugOf(it.url) }
        assertEquals(
            setOf("tf1", "canal-plus", "6ter", "arte", "comedie", "tnt-sports-2-uk", "ab1"),
            bySlug.keys
        )
        assertEquals("TF1", bySlug["tf1"]?.name)
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/channels/tf1.webp",
            bySlug["tf1"]?.posterUrl
        )
        assertEquals("Canal+", bySlug["canal-plus"]?.name)
        assertEquals("https://witv.channel/images/logo/canal.png", bySlug["canal-plus"]?.posterUrl)
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/channels/6ter.webp",
            bySlug["6ter"]?.posterUrl
        )
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/channels/arte-france.webp",
            bySlug["arte"]?.posterUrl
        )
        // Chaîne inconnue avec accents encodés en ASCII pour Android/Coil
        assertEquals("Comédie+ HD", bySlug["comedie"]?.name)
        assertEquals("https://witv.channel/images/logo/Com%C3%A9die+logo.png", bySlug["comedie"]?.posterUrl)
        // Chaîne connue avec logo source media.info remplacée par le poster studio HD 960x540
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/channels/tnt-sports-2-uk.webp",
            bySlug["tnt-sports-2-uk"]?.posterUrl
        )
        // Placeholder SVG remplacé par le poster studio HD 960x540
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/channels/ab1.webp",
            bySlug["ab1"]?.posterUrl
        )
        // Pagination DLE détectée
        assertEquals(
            "https://witv.channel/chaines-live/page/2/",
            WitvParser.nextPageUrl(listPage, "https://witv.channel/chaines-live/", 2)
        )
    }

    @Test
    fun decodesWitvChannelVmapPlayerLinksInOrder() {
        val vmapJson = """{"wt1_second":"/player/source2/?id=222","wt1_first":"/player/source2/?id=111"}"""
        val b64 = Base64.getEncoder().encodeToString(vmapJson.toByteArray(Charsets.UTF_8))
        val payload = "key123|salt456|$b64"
        val mask = 119
        val encodedArray = payload.map { it.code xor mask }.joinToString(",")

        val doc = Jsoup.parse(
            """
            <div class="player-content" id="player1" data-v="wt1_first"></div>
            <div class="player-content" id="player2" data-v="wt1_second"></div>
            <script type="speculationrules">{"not":{"href_matches":"/player/*"}}</script>
            <script>(function(){var _a=[$encodedArray],_m=$mask,_in=1195;})();</script>
            """.trimIndent(),
            "https://witv.channel/chaines-live/18-canal-foot.html"
        )

        assertEquals(
            listOf(
                "https://witv.channel/player/source2/?id=111",
                "https://witv.channel/player/source2/?id=222"
            ),
            WitvParser.playerLinks(doc)
        )
    }

    @Test
    fun extractsDirectWtPayloadAndServerMirrorsWhileIgnoringNoticeDecoy() {
        val realPath = "/player/source2/?vp=e1_realstream&tk=abc123&vc=stream1"
        val reversedB64 = Base64.getEncoder().encodeToString(realPath.toByteArray(Charsets.UTF_8)).reversed()

        val directHtml = """
            <script>
              var m3u8Url = "\/player\/source2\/?vp=d2l0dl9ub3RpY2UubTN1OA&vc=notice";
              var _wtPayload = "$reversedB64";
              m3u8Url = atob(_wtPayload.split('').reverse().join(''));
            </script>
        """.trimIndent()

        val direct = WitvParser.extractPlayerStreams(directHtml, "https://witv.channel/player/source2/?id=111")
        assertEquals(
            listOf("https://witv.channel/player/source2/?vp=e1_realstream&tk=abc123&vc=stream1"),
            direct.directHls
        )

        val mirrorHtml = """
            <button class="server-btn active" data-url="https://foot2026.lat/part/Ll.php?id=3/24">S1</button>
            <button class="server-btn" data-url="https://wideiptv.top/daddy.php?stream=TF1FR">S2</button>
            <iframe id="mainStreamFrame" src="https://foot2026.lat/part/Ll.php?id=3/24"></iframe>
        """.trimIndent()

        val mirrors = WitvParser.extractPlayerStreams(mirrorHtml, "https://witv.channel/player/source2/?id=469")
        assertTrue(mirrors.directHls.isEmpty())
        assertEquals(
            listOf(
                "https://foot2026.lat/part/Ll.php?id=3/24",
                "https://wideiptv.top/daddy.php?stream=TF1FR"
            ),
            mirrors.embedUrls
        )
    }

    @Test
    fun prefersLogoImagesOnChannelPages() {
        val page = Jsoup.parse(
            """
            <img src="/images/nav.png"/>
            <img src="/images/logo/custom-logo.png"/>
            """.trimIndent(),
            "https://witv.channel/chaines-live/chaine-inconnue"
        )
        assertEquals("https://witv.channel/images/logo/custom-logo.png", WitvParser.pagePoster(page))
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/channels/m6.webp",
            WitvParser.pagePoster(Jsoup.parse("<img src='/images/logo/m6.png'/>", "https://witv.channel/chaines-live/m6"))
        )
        assertNotNull(WitvParser.fallbackLogo("RTS1"))
    }

    @Test
    fun exposesTheOfficialCategories() {
        assertEquals(
            listOf("sport", "cinema", "documentaire", "generaliste", "enfants", "info", "musique", "belgique", "suisse"),
            WitvParser.channelCategories.map { it.slug }
        )
        assertEquals(
            "https://witv.channel/chaines-live/sport/",
            WitvParser.categoryUrl("https://witv.channel", "sport")
        )
    }

    @Test
    fun extractsPlayerSourcesAndUsableFramesOnly() {
        val links = WitvParser.playerLinks(channelPage)
        assertEquals(
            listOf(
                "https://witv.channel/player/source1/?id=469",
                "https://witv.channel/player/source2/?id=webtv_tf1_hd",
                "https://zonalive.example/embed/abc"
            ),
            links
        )
    }

    @Test
    fun matchesChannelNamesAcrossSites() {
        val channels = listOf(
            WitvChannel("TF1", "https://witv.channel/chaines-live/tf1"),
            WitvChannel("L'équipe - La Chaine", "https://witv.channel/chaines-live/31-lequipe-la-chaine.html"),
            WitvChannel("beIN SPORT 1", "https://witv.channel/chaines-live/bein-sport-1-fr")
        )
        assertEquals("https://witv.channel/chaines-live/tf1", WitvParser.findChannel(channels, "TF1")?.url)
        assertEquals(
            "https://witv.channel/chaines-live/31-lequipe-la-chaine.html",
            WitvParser.findChannel(channels, "L'EQUIPE Live TV")?.url
        )
        assertEquals(
            "https://witv.channel/chaines-live/bein-sport-1-fr",
            WitvParser.findChannel(channels, "beIN SPORT 1")?.url
        )
        assertEquals(null, WitvParser.findChannel(channels, "M6"))
    }

    @Test
    fun findsAChannelByItsPageUrl() {
        val channels = listOf(WitvChannel("TF1", "https://witv.channel/chaines-live/tf1"))
        assertEquals(channels.first(), WitvParser.findChannelByUrl(channels, "https://witv.team/chaines-live/tf1"))
        assertEquals(null, WitvParser.findChannelByUrl(channels, "https://witv.channel/chaines-live/m6"))
    }

    @Test
    fun cleansChannelPageTitles() {
        assertEquals(
            "TF1",
            WitvParser.pageTitle(Jsoup.parse("<h1>TF1 - Live Tv Streaming</h1>", "https://witv.channel/chaines-live/tf1"))
        )
        assertEquals(
            "M6",
            WitvParser.pageTitle(Jsoup.parse("<title>M6 – Live Tv Streaming</title>", "https://witv.channel/chaines-live/m6"))
        )
    }

    @Test
    fun recognisesWitvDocuments() {
        assertTrue(WitvParser.isSiteDocument(listPage))
        assertTrue(WitvParser.isSiteDocument(Jsoup.parse("<title>WiTv - Live</title>", "https://witv.channel/")))
    }
}
