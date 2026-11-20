package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WitvParserTest {

    private val listPage = Jsoup.parse(
        """
        <div class="card">
          <a href="/chaines-live/tf1"><img alt="TF1" src="/images/logo/tf1.png"/>Live Regarder</a>
        </div>
        <div class="card">
          <img alt="Canal+" src="/images/logo/canal.png"/>
          <a href="https://witv.online/chaines-live/27-canal-plus.html">Regarder</a>
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
        """.trimIndent(),
        "https://witv.online/chaines-live"
    )

    private val channelPage = Jsoup.parse(
        """
        <a href="/player/source1/?id=469">Source 1</a>
        <a href="/player/source2/?id=webtv_tf1_hd">Source 2</a>
        <a href="/chaines-live/m6">M6</a>
        <iframe src="https://zonalive.example/embed/abc"></iframe>
        <iframe src="https://www.youtube.com/embed/xyz"></iframe>
        """.trimIndent(),
        "https://witv.online/chaines-live/tf1"
    )

    @Test
    fun parsesChannelLinksAndSkipsCategories() {
        val channels = WitvParser.channels(listPage)
        val bySlug = channels.associateBy { WitvParser.slugOf(it.url) }
        assertEquals(setOf("tf1", "canal-plus", "6ter", "arte"), bySlug.keys)
        assertEquals("TF1", bySlug["tf1"]?.name)
        assertEquals("https://witv.online/images/logo/tf1.png", bySlug["tf1"]?.posterUrl)
        // Le 2e lien n'a qu'un texte générique : le nom vient du logo de sa carte.
        assertEquals("Canal+", bySlug["canal-plus"]?.name)
        assertEquals("https://witv.online/images/logo/canal.png", bySlug["canal-plus"]?.posterUrl)
        // data-src prioritaire sur un src placeholder ; background-image CSS en secours.
        assertEquals("https://witv.online/images/logo/6ter-logo.png", bySlug["6ter"]?.posterUrl)
        assertEquals("https://witv.online/images/logo/arte-logo.png", bySlug["arte"]?.posterUrl)
        assertEquals("arte", bySlug["arte"]?.name)
    }

    @Test
    fun prefersLogoImagesOnChannelPages() {
        val page = Jsoup.parse(
            """
            <img src="/images/nav.png"/>
            <img src="/images/logo/m6-logo.png"/>
            """.trimIndent(),
            "https://witv.online/chaines-live/m6"
        )
        assertEquals("https://witv.online/images/logo/m6-logo.png", WitvParser.pagePoster(page))
    }

    @Test
    fun exposesTheOfficialCategories() {
        assertEquals(
            listOf("sport", "cinema", "documentaire", "generaliste", "enfants", "info", "musique", "belgique", "suisse"),
            WitvParser.channelCategories.map { it.slug }
        )
        assertEquals(
            "https://witv.online/chaines-live/sport/",
            WitvParser.categoryUrl("https://witv.online", "sport")
        )
    }

    @Test
    fun extractsPlayerSourcesAndUsableFramesOnly() {
        val links = WitvParser.playerLinks(channelPage)
        assertEquals(
            listOf(
                "https://witv.online/player/source1/?id=469",
                "https://witv.online/player/source2/?id=webtv_tf1_hd",
                "https://zonalive.example/embed/abc"
            ),
            links
        )
    }

    @Test
    fun matchesChannelNamesAcrossSites() {
        val channels = listOf(
            WitvChannel("TF1", "https://witv.online/chaines-live/tf1"),
            WitvChannel("L'équipe - La Chaine", "https://witv.online/chaines-live/31-lequipe-la-chaine.html"),
            WitvChannel("beIN SPORT 1", "https://witv.online/chaines-live/bein-sport-1-fr")
        )
        assertEquals("https://witv.online/chaines-live/tf1", WitvParser.findChannel(channels, "TF1")?.url)
        assertEquals(
            "https://witv.online/chaines-live/31-lequipe-la-chaine.html",
            WitvParser.findChannel(channels, "L'EQUIPE Live TV")?.url
        )
        assertEquals(
            "https://witv.online/chaines-live/bein-sport-1-fr",
            WitvParser.findChannel(channels, "beIN SPORT 1")?.url
        )
        assertEquals(null, WitvParser.findChannel(channels, "M6"))
    }

    @Test
    fun findsAChannelByItsPageUrl() {
        val channels = listOf(WitvChannel("TF1", "https://witv.online/chaines-live/tf1"))
        assertEquals(channels.first(), WitvParser.findChannelByUrl(channels, "https://witv.team/chaines-live/tf1"))
        assertEquals(null, WitvParser.findChannelByUrl(channels, "https://witv.online/chaines-live/m6"))
    }

    @Test
    fun cleansChannelPageTitles() {
        assertEquals(
            "TF1",
            WitvParser.pageTitle(Jsoup.parse("<h1>TF1 - Live Tv Streaming</h1>", "https://witv.online/chaines-live/tf1"))
        )
        assertEquals(
            "M6",
            WitvParser.pageTitle(Jsoup.parse("<title>M6 – Live Tv Streaming</title>", "https://witv.online/chaines-live/m6"))
        )
    }

    @Test
    fun recognisesWitvDocuments() {
        assertTrue(WitvParser.isSiteDocument(listPage))
        assertTrue(WitvParser.isSiteDocument(Jsoup.parse("<title>WiTv - Live</title>", "https://witv.channel/")))
    }
}
