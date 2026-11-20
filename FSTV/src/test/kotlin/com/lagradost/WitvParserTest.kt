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
        val slugs = channels.map { WitvParser.slugOf(it.url) }
        assertEquals(listOf("tf1", "canal-plus"), slugs)
        assertEquals("TF1", channels.first().name)
        assertEquals("https://witv.online/images/logo/tf1.png", channels.first().posterUrl)
        // Le 2e lien n'a qu'un texte générique : le nom vient du logo de sa carte.
        assertEquals("Canal+", channels[1].name)
        assertEquals("https://witv.online/images/logo/canal.png", channels[1].posterUrl)
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
