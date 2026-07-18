package com.lagradost

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.ShowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class FrenchStreamMetadataTest {
    @Test
    fun stripsSeasonAndLanguageSuffixes() {
        assertEquals("Silo", FrenchStreamMetadata.normalizeTitle("Silo - Saison 3 [VF]"))
        assertEquals(
            "House of the Dragon",
            FrenchStreamMetadata.normalizeTitle("House of the Dragon Saison 2 VOSTFR")
        )
    }

    @Test
    fun extractsRealSeasonNumber() {
        assertEquals(3, FrenchStreamMetadata.seasonNumber("Silo - Saison 3"))
        assertEquals(12, FrenchStreamMetadata.seasonNumber("Une série saison 12 VOSTFR"))
        assertNull(FrenchStreamMetadata.seasonNumber("Une série sans numéro"))
    }

    @Test
    fun cameraReleaseWinsOverHdResolution() {
        assertEquals(SearchQuality.Telesync, FrenchStreamMetadata.quality("TS VF HD"))
        assertEquals(SearchQuality.HdCam, FrenchStreamMetadata.quality("HDCAM 1080p"))
        assertEquals(SearchQuality.Cam, FrenchStreamMetadata.quality("CAM VF"))
    }

    @Test
    fun mapsVerifiedNonCameraQualityAndLeavesUnknownEmpty() {
        assertEquals(SearchQuality.HD, FrenchStreamMetadata.quality("VF FHD 1080p"))
        assertEquals(SearchQuality.WebRip, FrenchStreamMetadata.quality("WEB-DL VOSTFR"))
        assertEquals(SearchQuality.UHD, FrenchStreamMetadata.quality("2160p 4K"))
        assertNull(FrenchStreamMetadata.quality("VF + VOSTFR"))
        assertNull(FrenchStreamMetadata.quality(null))
    }

    @Test
    fun roundTripsLanguageLinksInOneEpisode() {
        val encoded = FrenchStreamMetadata.mergeEpisodePayload(
            episode = 2,
            links = mapOf(
                "VF" to listOf("https://vf.example/e2"),
                "VOSTFR" to listOf("https://vo.example/e2", "invalid")
            )
        )

        val payload = FrenchStreamMetadata.parseEpisodePayload(encoded)
        assertEquals(2, payload?.episode)
        assertEquals(listOf("https://vf.example/e2"), payload?.links?.get("VF"))
        assertEquals(listOf("https://vo.example/e2"), payload?.links?.get("VOSTFR"))
    }

    @Test
    fun rejectsMalformedEpisodePayload() {
        assertNull(FrenchStreamMetadata.parseEpisodePayload("not-json"))
        assertNull(FrenchStreamMetadata.parseEpisodePayload("{}"))
    }

    @Test
    fun acceptsOnlyCompatibleTmdbTitleAndYear() {
        assertTrue(FrenchStreamMetadata.isTmdbMatch("Silo - Saison 3", 2023, "Silo", 2023))
        assertTrue(FrenchStreamMetadata.isTmdbMatch("Le Bureau des Légendes", null, "Le bureau des legendes", 2015))
        assertFalse(FrenchStreamMetadata.isTmdbMatch("Silo", 2023, "Silo", 2011))
        assertFalse(FrenchStreamMetadata.isTmdbMatch("Silo", 2023, "Love of Silom", 2023))
    }

    @Test
    fun discoversOnlySiblingSeasonPages() {
        val document = Jsoup.parse(
            """
            <div class="short"><a class="short-poster" href="/index.php?newsid=3"></a><div class="short-title">Silo - Saison 3</div></div>
            <div class="short"><a class="short-poster" href="/index.php?newsid=2"></a><div class="short-title">Silo - Saison 2</div></div>
            <div class="short"><a class="short-poster" href="/index.php?newsid=1"></a><div class="short-title">Silo - Saison 1</div></div>
            <div class="short"><a class="short-poster" href="/index.php?newsid=9"></a><div class="short-title">Love of Silom - Saison 1</div></div>
            """.trimIndent(),
            "https://french-stream.example/search"
        )

        assertEquals(
            listOf(
                FrenchStreamSeasonRef(1, "Silo - Saison 1", "https://french-stream.example/index.php?newsid=1"),
                FrenchStreamSeasonRef(2, "Silo - Saison 2", "https://french-stream.example/index.php?newsid=2"),
                FrenchStreamSeasonRef(3, "Silo - Saison 3", "https://french-stream.example/index.php?newsid=3")
            ),
            FrenchStreamMetadata.seasonRefs(document, "Silo")
        )
    }

    @Test
    fun readsSeriesTagFromCurrentDetailPage() {
        val document = Jsoup.parse(
            """
            <div id="serie-data" data-newsid="15114081">
              <span class="sd-tagz"><a href="/s-tv/silo">s-125988</a></span>
            </div>
            """.trimIndent()
        )

        assertEquals("s-125988", FrenchStreamMetadata.seriesTag(document))
    }

    @Test
    fun parsesSiblingSeasonsFromSiteApi() {
        val response = org.json.JSONArray(
            """
            [
              {"id":15119075,"title":"Silo - Saison 2","full_url":"15119075-silo-saison-2-2023.html"},
              {"id":15129154,"title":"Silo - Saison 3","full_url":"/15129154-silo-saison-3-2023.html"},
              {"id":9,"title":"Love of Silom - Saison 1","full_url":"9-love-of-silom.html"}
            ]
            """.trimIndent()
        )

        assertEquals(
            listOf(
                FrenchStreamSeasonRef(2, "Silo - Saison 2", "https://french-stream.one/15119075-silo-saison-2-2023.html"),
                FrenchStreamSeasonRef(3, "Silo - Saison 3", "https://french-stream.one/15129154-silo-saison-3-2023.html")
            ),
            FrenchStreamMetadata.seasonRefs(response, "https://french-stream.one", "Silo")
        )
    }

    @Test
    fun parsesVfAndVostfrLinksByEpisode() {
        val root = org.json.JSONObject(
            """
            {
              "vf": {
                "1": {"HostA":"https://vf.example/e1"},
                "2": {"HostA":"https://vf.example/e2"}
              },
              "vostfr": {
                "1": {"HostB":"https://vo.example/e1"},
                "2": {"HostB":"", "HostC":"https://vo.example/e2"}
              }
            }
            """.trimIndent()
        )

        assertEquals(
            mapOf(
                1 to mapOf(
                    "VF" to listOf("https://vf.example/e1"),
                    "VOSTFR" to listOf("https://vo.example/e1")
                ),
                2 to mapOf(
                    "VF" to listOf("https://vf.example/e2"),
                    "VOSTFR" to listOf("https://vo.example/e2")
                )
            ),
            FrenchStreamMetadata.episodeLinks(root)
        )
    }

    @Test
    fun parsesEveryMovieLanguageAndDeduplicatesDefaultPlayer() {
        val root = org.json.JSONObject(
            """
            {
              "players": {
                "premium": {
                  "default":"https://fsvid.example/vff",
                  "vff":"https://fsvid.example/vff",
                  "vfq":"https://fsvid.example/vfq",
                  "vostfr":"https://fsvid.example/vostfr"
                },
                "vidzy": {
                  "default":"https://vidzy.example/vf",
                  "vostfr":"https://vidzy.example/vostfr"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            mapOf(
                "VF" to listOf("https://fsvid.example/vff", "https://vidzy.example/vf"),
                "VFQ" to listOf("https://fsvid.example/vfq"),
                "VOSTFR" to listOf("https://fsvid.example/vostfr", "https://vidzy.example/vostfr")
            ),
            FrenchStreamMetadata.movieLinks(root)
        )
    }

    @Test
    fun selectsExactTmdbResultWithCompatibleYear() {
        val results = org.json.JSONArray(
            """
            [
              {"id":1,"title":"Silo","release_date":"2011-01-01","vote_count":500},
              {"id":2,"title":"Silo","release_date":"2023-05-05","vote_count":100},
              {"id":3,"title":"Love of Silom","release_date":"2023-05-05","vote_count":900}
            ]
            """.trimIndent()
        )

        assertEquals(2, FrenchStreamMetadata.tmdbResult(results, "Silo", 2023, false)?.optInt("id"))
        assertNull(FrenchStreamMetadata.tmdbResult(results, "Silo", 2022, false))
    }

    @Test
    fun mapsTmdbCastTrailerStatusAndFrenchRating() {
        val details = org.json.JSONObject(
            """
            {
              "credits": {"cast": [
                {"name":"Actrice Exemple","character":"Personnage","profile_path":"/actor.jpg"},
                {"name":"Sans image","character":"Second rôle","profile_path":null}
              ]},
              "videos": {"results": [
                {"site":"YouTube","type":"Featurette","key":"skip"},
                {"site":"YouTube","type":"Trailer","key":"trailer-key"}
              ]},
              "content_ratings": {"results": [
                {"iso_3166_1":"US","rating":"TV-MA"},
                {"iso_3166_1":"FR","rating":"16"}
              ]},
              "release_dates": {"results": [
                {"iso_3166_1":"FR","release_dates":[{"certification":"12"}]}
              ]}
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                FrenchStreamCastInfo("Actrice Exemple", "/actor.jpg", "Personnage"),
                FrenchStreamCastInfo("Sans image", null, "Second rôle")
            ),
            FrenchStreamMetadata.cast(details)
        )
        assertEquals("https://www.youtube.com/watch?v=trailer-key", FrenchStreamMetadata.trailerUrl(details))
        assertEquals("16", FrenchStreamMetadata.tvContentRating(details))
        assertEquals("12", FrenchStreamMetadata.movieContentRating(details))
        assertEquals(ShowStatus.Ongoing, FrenchStreamMetadata.showStatus("Returning Series"))
        assertEquals(ShowStatus.Completed, FrenchStreamMetadata.showStatus("Ended"))
    }
}
