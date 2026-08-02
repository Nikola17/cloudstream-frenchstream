package com.lagradost

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class FrenchStreamCatalogTest {
    @Test
    fun exposesRequestedMovieAndSeriesCatalogs() {
        val expected = listOf(
            "films/actions" to "Films Action",
            "films/comedies" to "Films Comédie",
            "films/epouvante-horreurs" to "Films Épouvante & Horreur",
            "films/science-fictions" to "Films Science-fiction",
            "films/fantastiques" to "Films Fantastique",
            "action-serie-" to "Séries Action",
            "comedie-serie-" to "Séries Comédie",
            "documentaire-serie-" to "Séries Documentaire",
            "fantastique-series-" to "Séries Fantastique",
            "science-fiction-series-" to "Séries Science-fiction",
            "policier-series-" to "Séries Policier",
            "horreur-serie-" to "Séries Horreur",
            "k-drama-" to "K-Drama"
        )
        val catalogs = FrenchStreamProvider().mainPage.map { it.data to it.name }

        assertEquals(expected, catalogs.takeLast(expected.size))
    }

    @Test
    fun placesHboMaxImmediatelyAfterNetflix() {
        val catalogs = FrenchStreamProvider().mainPage.map { it.data to it.name }
        val netflixIndex = catalogs.indexOf("s-tv/netflix-series-" to "Nouveautés Netflix")

        assertEquals(FRENCH_STREAM_HBO_MAX_CATALOG to "Nouveautés HBO Max", catalogs[netflixIndex + 1])
    }

    @Test
    fun mergesHboMaxMoviesAndSeriesByNewestRelease() {
        val movies = JSONArray(
            """
            [
              {"id": 10, "title": "Film récent", "original_title": "Recent Movie", "release_date": "2026-07-24", "vote_average": 7.4, "popularity": 42.5, "poster_path": "/film.jpg"},
              {"id": 11, "title": "Film ancien", "original_title": "Old Movie", "release_date": "2026-05-10", "vote_average": 6.2}
            ]
            """.trimIndent()
        )
        val series = JSONArray(
            """
            [
              {"id": 20, "name": "Série récente", "original_name": "Recent Show", "first_air_date": "2026-07-26", "vote_average": 8.1},
              {"id": 21, "name": "", "original_name": "Sans titre traduit", "first_air_date": "2026-06-01"}
            ]
            """.trimIndent()
        )

        val items = FrenchStreamMetadata.hboMaxCatalogItems(movies, series)

        assertEquals(listOf("Série récente", "Film récent", "Sans titre traduit", "Film ancien"), items.map { it.title })
        assertEquals(listOf(true, false, true, false), items.map { it.isSeries })
        assertEquals("Recent Show", items.first().originalTitle)
        assertEquals(2026, items.first().year)
        assertEquals(42.5, items[1].popularity, 0.0)
        assertEquals("/film.jpg", items[1].posterPath)
    }

    @Test
    fun ordersHboMaxCatalogByNewestReleaseRegardlessOfPopularity() {
        // Garde-fou : un vieux blockbuster très populaire ne doit jamais passer devant une nouveauté.
        val movies = JSONArray(
            """
            [
              {"id": 1, "title": "Blockbuster ancien", "release_date": "2002-05-01", "popularity": 900.0},
              {"id": 2, "title": "Nouveauté", "release_date": "2026-07-24", "popularity": 1.0}
            ]
            """.trimIndent()
        )
        val series = JSONArray(
            """
            [{"id": 3, "name": "Série culte", "first_air_date": "2011-04-17", "popularity": 800.0}]
            """.trimIndent()
        )

        val items = FrenchStreamMetadata.hboMaxCatalogItems(movies, series)

        assertEquals(listOf("Nouveauté", "Série culte", "Blockbuster ancien"), items.map { it.title })
    }

    @Test
    fun completesHboMaxReleasesWithRecentPopularTitlesOnly() {
        val recentMovies = JSONArray(
            """[{"id":1,"title":"Documentaire récent","release_date":"2026-08-01"}]"""
        )
        val recentSeries = JSONArray()
        val popularMovies = JSONArray(
            """
            [
              {"id":2,"title":"Film HBO récent","release_date":"2026-07-15","popularity":300.0},
              {"id":3,"title":"Vieux blockbuster","release_date":"2002-05-01","popularity":900.0}
            ]
            """.trimIndent()
        )
        val popularSeries = JSONArray(
            """[{"id":4,"name":"Série HBO récente","first_air_date":"2025-11-10","popularity":250.0}]"""
        )

        val items = FrenchStreamMetadata.hboMaxCatalogCandidates(
            recentMovies,
            recentSeries,
            popularMovies,
            popularSeries,
            earliestPopularDate = "2024-08-02"
        )

        assertEquals(
            listOf("Documentaire récent", "Film HBO récent", "Série HBO récente"),
            items.map { it.title }
        )
    }

    @Test
    fun scansThreeTmdbPagesForEachHboMaxCatalogPage() {
        assertEquals(1..3, FrenchStreamTmdbClient.hboMaxApiPages(1))
        assertEquals(4..6, FrenchStreamTmdbClient.hboMaxApiPages(2))
    }

    @Test
    fun resolvesCatalogTitlesFromFrenchStreamSitemap() {
        val sitemap = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://french-stream.one/15130000-house-of-the-dragon-saison-3-2022.html</loc></url>
              <url><loc>https://french-stream.one/15129999-spider-man-no-way-home-2021.html</loc></url>
              <url><loc>https://french-stream.one/12-salems-lot-film-streaming-complet-vf.html</loc></url>
            </urlset>
        """.trimIndent()
        val refs = FrenchStreamMetadata.sitemapRefs(sitemap)

        assertEquals(
            "https://french-stream.one/15130000-house-of-the-dragon-saison-3-2022.html",
            FrenchStreamMetadata.sitemapMatch(refs, "House of the Dragon", null, true)?.url
        )
        assertEquals(
            "https://french-stream.one/15129999-spider-man-no-way-home-2021.html",
            FrenchStreamMetadata.sitemapMatch(refs, "Spider-Man : No Way Home", null, false)?.url
        )
        assertEquals(
            "https://french-stream.one/12-salems-lot-film-streaming-complet-vf.html",
            FrenchStreamMetadata.sitemapMatch(refs, "Salem's Lot", null, false)?.url
        )
    }
}
