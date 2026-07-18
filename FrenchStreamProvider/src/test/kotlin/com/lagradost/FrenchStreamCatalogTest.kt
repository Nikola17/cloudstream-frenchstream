package com.lagradost

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
}
