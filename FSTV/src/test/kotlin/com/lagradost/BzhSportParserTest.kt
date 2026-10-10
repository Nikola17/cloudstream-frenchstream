package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BzhSportParserTest {
    private val home = Jsoup.parse(
        """
        <div class="channels"><a href="https://bzh.lat/regarder-la-chaine-Eurosport-1-en-direct-streaming-gratuit"> Eurosport 1 </a></div>
        <div class="channels"><a href="https://bzh.lat/regarder-la-chaine-canal-plus-Sport360-en-direct-streaming-gratuit"> canal+ Sport360 </a></div>
        <div class="channels"><a href="https://bzh.lat/regarder-la-chaine-L-Equipe-en-direct-streaming-gratuit"> Equipe </a></div>
        <div class="channels"><a href="https://bzh.lat/regarder-la-chaine-beIN-SPORT-1-en-direct-streaming-gratuit"> beIN SPORT 1 </a></div>
        <table><tbody class="events">
          <tr class="event">
            <td><img src="https://bzh.lat/assets/uploads/cat/6.png" /></td>
            <td>aujourd'hui</td><td class="dt">09:35</td><td class="hidden-xs"></td>
            <td class="home event-title">Tour de Lombardie</td>
            <td><a href="https://bzh.lat/Tour-de-Lombardie-streaming-direct" class="btn">Regarder</a></td>
            <td><img src="https://bzh.lat/assets/images/live.gif" /></td>
          </tr>
          <tr class="event">
            <td><img src="https://bzh.lat/assets/uploads/cat/34." /></td>
            <td>aujourd'hui</td><td class="dt">12:00</td><td class="hidden-xs">Bundesliga 2</td>
            <td class="home event-title">Darmstadt - Cottbus</td>
            <td><a href="https://bzh.lat/Darmstadt---Cottbus-streaming-direct" class="btn">Regarder</a></td>
            <td><img src="https://bzh.lat/assets/images/live.gif" /></td>
          </tr>
          <tr class="event">
            <td><img src="https://bzh.lat/assets/uploads/cat/1.png" /></td>
            <td>aujourd'hui</td><td class="dt">19:45</td><td class="hidden-xs">Ligue 1</td>
            <td class="home event-title">PSG - Le Mans </td>
            <td><a href="https://bzh.lat/PSG---Le-Mans-streaming-direct" class="btn">Regarder</a></td>
            <td><img src="https://bzh.lat/assets/images/notlive.png" /></td>
          </tr>
          <tr class="event">
            <td><img src="https://bzh.lat/assets/uploads/cat/13.png" /></td>
            <td>demain</td><td class="dt">18:30</td><td class="hidden-xs">Magazine</td>
            <td class="home event-title">Canal Football Club</td>
            <td><a href="https://bzh.lat/Canal-Football-Club-streaming-direct" class="btn">Regarder</a></td>
            <td><img src="https://bzh.lat/assets/images/notlive.png" /></td>
          </tr>
          <tr class="event">
            <td><img src="https://bzh.lat/assets/uploads/cat/1.png" /></td>
            <td>13-Oct-2026</td><td class="dt">20:00</td><td class="hidden-xs">Ligue Des Champions</td>
            <td class="home event-title">Arsenal - Lille OSC</td>
            <td><a href="https://bzh.lat/Arsenal---Lille-OSC-streaming-direct" class="btn">Regarder</a></td>
            <td><img src="https://bzh.lat/assets/images/notlive.png" /></td>
          </tr>
        </tbody></table>
        """.trimIndent(),
        "https://bzh.lat/"
    )

    @Test
    fun parsesScheduledEvents() {
        val events = BzhSportParser.events(home)

        assertEquals(5, events.size)
        assertEquals(
            BzhEvent(
                title = "Tour de Lombardie",
                competition = null,
                dayLabel = "aujourd'hui",
                day = BzhEventDay.TODAY,
                time = "09:35",
                url = "https://bzh.lat/Tour-de-Lombardie-streaming-direct",
                sport = "cyclisme",
                isLive = true,
                position = 0
            ),
            events.first()
        )
        assertEquals("pingpong", events[1].sport)
        assertEquals("magazine", events[3].sport)
        assertEquals("PSG - Le Mans", events[2].title)
        assertFalse(events[2].isLive)
        assertEquals(BzhEventDay.TOMORROW, events[3].day)
        assertEquals(BzhEventDay.LATER, events[4].day)
    }

    @Test
    fun ranksPopularEventsFirst() {
        val ranked = BzhSportParser.ranked(BzhSportParser.events(home)).map { it.title }

        assertEquals(
            listOf(
                "Arsenal - Lille OSC",
                "PSG - Le Mans",
                "Tour de Lombardie",
                "Darmstadt - Cottbus",
                "Canal Football Club"
            ),
            ranked
        )
    }

    @Test
    fun keepsHandballChampionsLeagueBelowFootball() {
        val football = event("Real Madrid - Villarreal", "Laliga")
        val handball = event("Gyor - Metz", "Ligue des champions EHF")
        val france = event("France F - Lettonie F", "Amical International")

        assertTrue(BzhSportParser.popularity(football) > BzhSportParser.popularity(handball))
        assertTrue(BzhSportParser.popularity(france) > BzhSportParser.popularity(football))
    }

    @Test
    fun matchesFrenchStreamChannelNames() {
        val channels = BzhSportParser.channels(home)

        assertEquals("Eurosport 1", BzhSportParser.findChannel(channels, "EUROSPORT 1")?.name)
        assertEquals("canal+ Sport360", BzhSportParser.findChannel(channels, "Canal + Sport 360")?.name)
        assertEquals("Equipe", BzhSportParser.findChannel(channels, "L'EQUIPE Live TV")?.name)
        assertEquals("beIN SPORT 1", BzhSportParser.findChannel(channels, "Bein Sport 1")?.name)
        assertNull(BzhSportParser.findChannel(channels, "Eurosport 2"))
    }

    @Test
    fun parsesPlayerEmbedsFromScript() {
        val document = Jsoup.parse(
            """
            <div class="video-item"><ul class="nav nav-tabs"><li><a class="video-btn" href="">Eurosport 1</a></li></ul></div>
            <script>
              embeds = new Array(); titles = new Array();
              embeds[0] = '<iframe src=" https://zonalive.click/player/4/15" width="100%"></iframe>'; titles[0] = 'Link 1';
              embeds[1] = '<iframe src="//xstream.st/hlsspanich.php?stream=H16"></iframe>';
              embeds[2] = '<iframe src="https://embedme/player/4/45"></iframe>'; titles[2] = 'Broken';
              if(embeds.length == 0) { embeds[0] = '<img src="https://bzh.lat/assets/images/nochannel.gif" />;'; titles[0] = ''; }
            </script>
            """.trimIndent(),
            "https://bzh.lat/Tour-de-Lombardie-streaming-direct"
        )

        assertEquals(
            listOf(
                BzhEmbed("Lecteur 1", "https://zonalive.click/player/4/15"),
                BzhEmbed("Lecteur 2", "https://xstream.st/hlsspanich.php?stream=H16")
            ),
            BzhSportParser.embeds(document)
        )
    }

    @Test
    fun extractsHlsAndNestedPlayers() {
        val player = """
            <script>const streamUrls=["https://instreams.pro/live3/H15/index.m3u8?st=abc&e=179","https:\/\/instreams.live\/live3\/H15\/index.m3u8?st=abc&e=179"];</script>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://instreams.pro/live3/H15/index.m3u8?st=abc&e=179",
                "https://instreams.live/live3/H15/index.m3u8?st=abc&e=179"
            ),
            BzhSportParser.hlsUrls(player)
        )

        val wrapper = """
            <body><iframe src="//xstream.st/hlsspanich.php?stream=H15" width="100%"></iframe>
            <iframe src="https://freeshot.live/ads/FR300x250.php"></iframe></body>
        """.trimIndent()
        assertEquals(
            listOf("https://xstream.st/hlsspanich.php?stream=H15"),
            BzhSportParser.iframeUrls(wrapper, "https://zonalive.click/player/4/15")
        )
    }

    @Test
    fun discoversCurrentDomainFromStatusPages() {
        val rentry = Jsoup.parse(
            """
            <a class="external" href="https://streamed.pk/">Streamed</a>
            <a class="external" href="https://volkamax.club">Ohquelbut</a>
            <a class="external" href="https://ewenethan22bzh.chatango.com/">Chatango Status</a>
            """.trimIndent(),
            "https://rentry.co/alpagang"
        )
        assertEquals(listOf("https://volkamax.club"), BzhSportParser.rentryBaseUrls(rentry))

        val chatango = """
            <html><head><title>🔔✅➡️https://bzh.lat/</title></head>
            <body><div class="group_title">🔔✅➡️https://bzh.lat/</div><a href="https://chatango.com">Chatango</a></body></html>
        """.trimIndent()
        assertEquals(listOf("https://bzh.lat"), BzhSportParser.chatangoBaseUrls(chatango))
    }

    @Test
    fun recognisesSitePages() {
        assertTrue(BzhSportParser.isSitePath("https://nouveau-domaine.xyz/PSG---Le-Mans-streaming-direct"))
        assertTrue(BzhSportParser.isSitePath("https://bzh.lat/regarder-la-chaine-TF1-en-direct-streaming-gratuit"))
        assertFalse(BzhSportParser.isSitePath("https://fstv.rest/index.php?newsid=4"))
        assertTrue(BzhSportParser.isSiteDocument(home))
    }

    @Test
    fun picksSportPosterFromCategoryThenKeywords() {
        assertEquals("f1", BzhSportParser.sport("https://bzh.lat/assets/uploads/cat/7.png", "Grand Prix"))
        assertEquals("judo", BzhSportParser.sport("https://bzh.lat/assets/uploads/cat/32.png", "Championnats du monde"))
        assertEquals("pingpong", BzhSportParser.sport("https://bzh.lat/assets/uploads/cat/34.", "Championnats d Europe"))
        assertEquals("basket", BzhSportParser.sport("https://bzh.lat/assets/uploads/cat/99.png", "NBA Boston - Miami"))
        assertEquals("sport", BzhSportParser.sport("", "Evenement inconnu"))
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/sports/f1.webp",
            BzhSportParser.posterUrl("f1")
        )
        assertEquals(
            "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/sports/sport.webp",
            BzhSportParser.posterUrl("inconnu")
        )
    }

    private fun event(title: String, competition: String?) = BzhEvent(
        title = title,
        competition = competition,
        dayLabel = "aujourd'hui",
        day = BzhEventDay.TODAY,
        time = "20:00",
        url = "https://bzh.lat/x-streaming-direct",
        sport = "foot",
        isLive = false,
        position = 0
    )
}
