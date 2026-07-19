package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchMangaParserTest {
    @Test
    fun exposesRequestedCatalogsInHomepageOrder() {
        assertEquals(
            listOf(
                "Récemment mises à jour", "Coups de Cœur", "Shonen", "Seinen", "Dark Fantasy",
                "Gore", "Isekai", "Thriller",
                "Aventure", "Action & Aventure", "Science-Fiction & Fantastique"
            ),
            FrenchMangaParser.categories.map { it.label }
        )
        assertEquals(
            listOf(
                "/manga-streaming-1/",
                "/manga-streaming-1/coups-de-cur/"
            ),
            FrenchMangaParser.categories.take(2).map { it.path }
        )
    }

    @Test
    fun parsesAndGroupsSeasonCards() {
        val document = Jsoup.parse(
            """
            <div class="short">
              <div class="short-in">
                <span class="film-version">VF+VOSTFR</span>
                <a class="short-poster" href="/1498684-hells-paradise-saison-2-2026.html">
                  <img src="/poster-s2.jpg" alt="Hell's Paradise - Saison 2 affiche">
                </a>
                <span class="mli-type">Anime</span>
                <div class="short-title">Hell's Paradise - Saison 2</div>
              </div>
            </div>
            <div class="short">
              <div class="short-in">
                <span class="film-version">VF</span>
                <a class="short-poster" href="/1497945-hells-paradise-saison-1-2023.html">
                  <img src="/poster-s1.jpg">
                </a>
                <span class="mli-type">Anime</span>
                <div class="short-title">Hell's Paradise - Saison 1</div>
              </div>
            </div>
            <div class="short">
              <div class="short-in">
                <span class="film-version">VOSTFR</span>
                <a class="short-poster" href="/1498981-movie.html"><img src="/movie.jpg"></a>
                <span class="mli-type">Film</span>
                <div class="short-title">The Dangers in my Heart : The Movie</div>
              </div>
            </div>
            """.trimIndent(),
            "https://w16.french-manga.net/"
        )

        val grouped = FrenchMangaParser.groupCards(FrenchMangaParser.cards(document))

        assertEquals(2, grouped.size)
        assertEquals("Hell's Paradise", grouped[0].title)
        assertEquals(2, grouped[0].season)
        assertFalse(grouped[0].isMovie)
        assertEquals("https://w16.french-manga.net/poster-s2.jpg", grouped[0].posterUrl)
        assertTrue(grouped[1].isMovie)
    }

    @Test
    fun parsesAjaxSearchAndGroupsResults() {
        val document = Jsoup.parse(
            """
            <div class='search-item' onclick="location.href='/1498684-hells-paradise-saison-2-2026.html'">
              <div class='search-poster'><img src='/s2.jpg' alt="Hell's Paradise - Saison 2"></div>
              <div class='search-title'>Hell's Paradise - Saison 2 (2026)</div>
            </div>
            <div class='search-item' onclick="location.href='/1497945-hells-paradise-saison-1-2023.html'">
              <div class='search-poster'><img src='/s1.jpg' alt="Hell's Paradise - Saison 1"></div>
              <div class='search-title'>Hell's Paradise - Saison 1 (2023)</div>
            </div>
            """.trimIndent(),
            "https://w16.french-manga.net/"
        )

        val results = FrenchMangaParser.groupCards(FrenchMangaParser.searchResults(document))

        assertEquals(1, results.size)
        assertEquals("Hell's Paradise", results.single().title)
        assertEquals(2, results.single().season)
        assertEquals(2026, results.single().year)
    }

    @Test
    fun parsesDetailMetadataAndActorPhotos() {
        val document = Jsoup.parse(
            """
            <div id="manga-data" data-newsid="1498684" data-title="Hell's Paradise - Saison 2"
              data-affiche="https://img/poster.jpg" data-trailer="abc123" data-tagz="dark fantasy, shounen"></div>
            <div class="hero-backdrop" style="background-image: url('https://img/backdrop.jpg')"></div>
            <div class="facts"><span class="release">2026</span><span class="genres"><a>Animation</a><a>Action</a></span></div>
            <div class="flist clearfix"><div class="fdesc"><p>Synopsis français.</p></div></div>
            <div class="short-meta short-qual">Anime</div>
            <script>
              window.actorData = ["Chiaki Kobayashi (Gabimaru (voice)) - https://img/chiaki.jpg"];
            </script>
            """.trimIndent(),
            "https://w16.french-manga.net/page.html"
        )

        val detail = FrenchMangaParser.detail(document)!!

        assertEquals("Hell's Paradise", detail.title)
        assertEquals(2, detail.season)
        assertEquals(2026, detail.year)
        assertEquals("Synopsis français.", detail.plot)
        assertEquals(listOf("Animation", "Action"), detail.genres)
        assertEquals("https://img/backdrop.jpg", detail.backdropUrl)
        assertEquals(FrenchMangaActor("Chiaki Kobayashi", "Gabimaru (voice)", "https://img/chiaki.jpg"), detail.actors.single())
        assertFalse(detail.isMovie)
    }

    @Test
    fun parsesOnlyRealSeasonsFromSeasonApi() {
        val seasons = FrenchMangaParser.seasons(
            """
            [
              {"id":"18040","title":"Saison 1","full_url":"manga-streaming-1/18040-one-piece-saison-1.html","affiche":"https://img/s1.jpg","serie_anne":"1999","season_number":1},
              {"id":"1497878","title":"One Piece Film - Red","full_url":"manga-streaming-1/film.html","season_number":999},
              {"id":"1498700","title":"Saison 23","full_url":"manga-streaming-1/1498700-one-piece-saison-23.html","affiche":"https://img/s23.jpg","serie_anne":"2026","season_number":23}
            ]
            """.trimIndent(),
            "https://w16.french-manga.net/"
        )

        assertEquals(listOf(1, 23), seasons.map { it.number })
        assertEquals("https://w16.french-manga.net/manga-streaming-1/18040-one-piece-saison-1.html", seasons.first().url)
    }

    @Test
    fun parsesEpisodesAndKeepsVfSourcesFirst() {
        val data = FrenchMangaParser.episodes(
            """
            {
              "vf":{"1":{"vidzy":"https://vidzy.live/vf1","luluvid":"https://luluvdo.com/vf1"}},
              "vostfr":{"1":{"vidzy":"https://vidzy.live/vo1"},"2":{"vidzy":"https://vidzy.live/vo2"}},
              "info":{"1":{"title":"Premier épisode","synopsis":"Résumé","poster":"https://img/e1.jpg"}}
            }
            """.trimIndent()
        )

        assertEquals(listOf(1, 2), data.map { it.number })
        assertEquals("Premier épisode", data.first().title)
        assertEquals(listOf("VF", "VF", "VOSTFR"), data.first().sources.map { it.language })
        assertEquals("Épisode 2", data[1].title)
    }

    @Test
    fun encodesAndDecodesEpisodePayload() {
        val payload = FrenchMangaParser.episodePayload(
            "https://w16.french-manga.net/manga-streaming-1/18040-one-piece.html",
            12
        )

        assertEquals(
            FrenchMangaEpisodePayload(
                "https://w16.french-manga.net/manga-streaming-1/18040-one-piece.html",
                12
            ),
            FrenchMangaParser.episodePayload(payload)
        )
    }

    @Test
    fun extractsPackedPlayerMediaUrls() {
        val html = """
            <script>player({sources:[{file:"https://cdn.example/video/master.m3u8"}]});</script>
            <script>var fallback = "https://cdn.example/video.mp4";</script>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example/video/master.m3u8", "https://cdn.example/video.mp4"),
            FrenchMangaPackedPlayerParser.extractMediaUrls(html)
        )
    }
}
