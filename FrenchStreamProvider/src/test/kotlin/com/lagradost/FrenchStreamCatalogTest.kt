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
    fun placesHboMaxImmediatelyBeforeNetflix() {
        val catalogs = FrenchStreamProvider().mainPage.map { it.data to it.name }
        val netflixIndex = catalogs.indexOf("s-tv/netflix-series-" to "Nouveautés Netflix")

        assertEquals(FRENCH_STREAM_HBO_MAX_CATALOG to "Nouveautés HBO Max", catalogs[netflixIndex - 1])
    }

    @Test
    fun mergesHboMaxMoviesAndSeriesByNewestRelease() {
        val movies = JSONArray(
            """
            [
              {"id": 10, "title": "Film récent", "original_title": "Recent Movie", "release_date": "2026-07-24", "vote_average": 7.4},
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
    }
}
