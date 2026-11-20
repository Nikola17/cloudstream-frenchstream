package com.lagradost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MovixLinkParserTest {
    @Test
    fun fstreamMovieReadsPlayerGroupsAndDeduplicatesUrls() {
        val json = JSONObject(
            """
            {
              "players": {
                "VFQ": [
                  {"url": "https://video.example/vfq"},
                  {"url": "https://video.example/shared"}
                ],
                "VFF": [{"url": "https://video.example/vff"}],
                "VOSTFR": [{"url": "https://video.example/shared"}],
                "Default": [{"url": "not-a-url"}]
              }
            }
            """.trimIndent()
        )

        // VFF (doublage FR) en premier, puis VF/VFQ, puis VOSTFR ; Default en dernier.
        assertEquals(
            listOf(
                "https://video.example/vff",
                "https://video.example/vfq",
                "https://video.example/shared"
            ),
            MovixLinkParser.fstreamMovie(json)
        )
    }

    @Test
    fun sortsEachLanguageGroupByQualityDescending() {
        val json = JSONObject(
            """
            {
              "players": {
                "VFF": [
                  {"url": "https://video.example/vff-hd", "quality": "HD"},
                  {"url": "https://video.example/vff-4k", "quality": "4K"},
                  {"url": "https://video.example/vff-unknown"}
                ],
                "VOSTFR": [{"url": "https://video.example/vostfr-hd", "quality": "HD"}]
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://video.example/vff-4k",
                "https://video.example/vff-hd",
                "https://video.example/vff-unknown",
                "https://video.example/vostfr-hd"
            ),
            MovixLinkParser.fstreamMovie(json)
        )
    }

    @Test
    fun fstreamTvReadsOnlyRequestedEpisodeLanguages() {
        val json = JSONObject(
            """
            {
              "episodes": {
                "1": {"languages": {"VF": [{"url": "https://video.example/e1"}]}},
                "2": {
                  "languages": {
                    "VF": [{"url": "https://video.example/e2-vf"}],
                    "VOSTFR": [{"url": "https://video.example/e2-vostfr"}],
                    "Default": []
                  }
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("https://video.example/e2-vf", "https://video.example/e2-vostfr"),
            MovixLinkParser.fstreamTv(json, 2)
        )
    }

    @Test
    fun customMovieReadsStringAndObjectLinks() {
        val json = JSONObject(
            """
            {
              "data": {
                "links": [
                  "https://video.example/custom-one",
                  {"url": "https://video.example/custom-two"},
                  "invalid"
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("https://video.example/custom-one", "https://video.example/custom-two"),
            MovixLinkParser.customMovie(json)
        )
    }

    @Test
    fun wiflixMovieAndTvReadTheirKnownGroups() {
        val movie = JSONObject(
            """
            {"players": {
              "vf": [{"url": "https://video.example/movie-vf"}],
              "vostfr": [{"url": "https://video.example/movie-vostfr"}]
            }}
            """.trimIndent()
        )
        val tv = JSONObject(
            """
            {"episodes": {
              "3": {
                "vf": [{"url": "https://video.example/tv-vf"}],
                "vostfr": [{"url": "https://video.example/tv-vostfr"}]
              }
            }}
            """.trimIndent()
        )

        assertEquals(
            listOf("https://video.example/movie-vf", "https://video.example/movie-vostfr"),
            MovixLinkParser.wiflixMovie(movie)
        )
        assertEquals(
            listOf("https://video.example/tv-vf", "https://video.example/tv-vostfr"),
            MovixLinkParser.wiflixTv(tv, 3)
        )
    }

    @Test
    fun malformedPayloadsReturnNoLinks() {
        val json = JSONObject("""{"message":"Contenu non disponible"}""")

        assertEquals(emptyList<String>(), MovixLinkParser.fstreamMovie(json))
        assertEquals(emptyList<String>(), MovixLinkParser.fstreamTv(json, 1))
        assertEquals(emptyList<String>(), MovixLinkParser.customMovie(json))
        assertEquals(emptyList<String>(), MovixLinkParser.wiflixMovie(json))
        assertEquals(emptyList<String>(), MovixLinkParser.wiflixTv(json, 1))
    }
}
