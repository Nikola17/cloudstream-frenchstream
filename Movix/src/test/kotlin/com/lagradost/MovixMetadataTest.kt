package com.lagradost

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.ShowStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovixMetadataTest {
    @Test
    fun cameraLabelWinsOverHdResolutionInSameSource() {
        val root = JSONObject("""{"players":{"vf":[{"label":"TS VF HD"}]}}""")

        assertEquals(SearchQuality.Telesync, MovixMetadata.qualityFromJ1f(root))
    }

    @Test
    fun verifiedNonCameraSourceWinsOverCameraOnlySource() {
        val root = JSONObject(
            """
            {
              "players": {
                "vf": [
                  {"label": "TS VF HD"},
                  {"label": "VF FHD 1080p"}
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(SearchQuality.HD, MovixMetadata.qualityFromJ1f(root))
    }

    @Test
    fun preservesSpecificCameraAndWebQualities() {
        assertEquals(
            SearchQuality.HdCam,
            MovixMetadata.qualityFromJ1f(JSONObject("""{"players":{"vf":[{"label":"HDCAM"}]}}"""))
        )
        assertEquals(
            SearchQuality.WebRip,
            MovixMetadata.qualityFromJ1f(JSONObject("""{"players":{"vf":[{"label":"WEB-DL"}]}}"""))
        )
    }

    @Test
    fun missingOrGenericLabelsStayUnknown() {
        assertNull(MovixMetadata.qualityFromJ1f(JSONObject("""{"players":{}}""")))
        assertNull(
            MovixMetadata.qualityFromJ1f(
                JSONObject("""{"players":{"vf":[{"name":"host.example","label":"Sources VF"}]}}""")
            )
        )
    }

    @Test
    fun readsLabelsFromEveryLanguageGroup() {
        val root = JSONObject(
            """
            {
              "players": {
                "vf": [{"label":"VF HD"}],
                "vostfr": [{"label":"VOSTFR FHD"}]
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("VF HD", "VOSTFR FHD"), MovixMetadata.playerLabels(root))
    }

    @Test
    fun mapsCastTrailerStatusAndFrenchContentRating() {
        val details = JSONObject(
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
              ]}
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MovixCastInfo("Actrice Exemple", "/actor.jpg", "Personnage"),
                MovixCastInfo("Sans image", null, "Second rôle")
            ),
            MovixMetadata.cast(details)
        )
        assertEquals("https://www.youtube.com/watch?v=trailer-key", MovixMetadata.trailerUrl(details))
        assertEquals("16", MovixMetadata.tvContentRating(details))
        assertEquals(ShowStatus.Ongoing, MovixMetadata.showStatus("Returning Series"))
        assertEquals(ShowStatus.Completed, MovixMetadata.showStatus("Ended"))
    }
}
