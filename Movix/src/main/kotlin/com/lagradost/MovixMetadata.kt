package com.lagradost

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.ShowStatus
import org.json.JSONArray
import org.json.JSONObject

internal data class MovixCastInfo(
    val name: String,
    val profilePath: String?,
    val character: String?
)

internal object MovixMetadata {
    fun cast(details: JSONObject): List<MovixCastInfo> {
        val items = details.optJSONObject("credits")?.optJSONArray("cast") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MovixCastInfo(
                name = name,
                profilePath = item.optString("profile_path").trim().takeIf { it.isNotBlank() },
                character = item.optString("character").trim().takeIf { it.isNotBlank() }
            )
        }
    }

    fun trailerUrl(details: JSONObject): String? {
        val items = details.optJSONObject("videos")?.optJSONArray("results") ?: return null
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("site").equals("YouTube", ignoreCase = true) }
            .sortedBy {
                when (it.optString("type")) {
                    "Trailer" -> 0
                    "Teaser" -> 1
                    else -> 2
                }
            }
            .firstOrNull { it.optString("type") == "Trailer" || it.optString("type") == "Teaser" }
            ?.optString("key")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=$it" }
    }

    fun tvContentRating(details: JSONObject): String? {
        val items = details.optJSONObject("content_ratings")?.optJSONArray("results") ?: return null
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .firstOrNull { it.optString("iso_3166_1") == "FR" }
            ?.optString("rating")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun movieContentRating(details: JSONObject): String? {
        val countries = details.optJSONObject("release_dates")?.optJSONArray("results") ?: return null
        val france = (0 until countries.length()).mapNotNull { countries.optJSONObject(it) }
            .firstOrNull { it.optString("iso_3166_1") == "FR" }
            ?: return null
        val releases = france.optJSONArray("release_dates") ?: return null
        return (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
            .mapNotNull { it.optString("certification").trim().takeIf(String::isNotBlank) }
            .firstOrNull()
    }

    fun showStatus(status: String?): ShowStatus? {
        return when (status) {
            "Returning Series", "In Production", "Planned", "Pilot" -> ShowStatus.Ongoing
            "Ended", "Canceled" -> ShowStatus.Completed
            else -> null
        }
    }

    fun playerLabels(root: JSONObject): List<String> {
        val players = root.optJSONObject("players") ?: return emptyList()
        return players.keys().asSequence().flatMap { group ->
            val items = players.optJSONArray(group) ?: JSONArray()
            (0 until items.length()).asSequence().mapNotNull { index ->
                items.optJSONObject(index)?.optString("label")?.trim()?.takeIf { it.isNotBlank() }
            }
        }.toList()
    }

    fun qualityFromJ1f(root: JSONObject): SearchQuality? {
        val qualities = playerLabels(root).mapNotNull(::qualityFromLabel)
        val nonCamera = qualities.filterNot(::isCamera)
        if (nonCamera.isNotEmpty()) return nonCamera.maxByOrNull(::qualityRank)
        return qualities.maxByOrNull(::qualityRank)
    }

    private fun qualityFromLabel(label: String): SearchQuality? {
        val value = label.uppercase()
        return when {
            Regex("""\b(?:HDCAM|HDTS|HD-TC)\b""").containsMatchIn(value) -> SearchQuality.HdCam
            Regex("""\bCAM(?:RIP)?\b""").containsMatchIn(value) -> SearchQuality.Cam
            Regex("""\b(?:TS|TELESYNC)\b""").containsMatchIn(value) -> SearchQuality.Telesync
            Regex("""\b(?:2160P?|4K|UHD)\b""").containsMatchIn(value) -> SearchQuality.UHD
            Regex("""\bBLU-?RAY\b""").containsMatchIn(value) -> SearchQuality.BlueRay
            Regex("""\b(?:WEB-?DL|WEBRIP)\b""").containsMatchIn(value) -> SearchQuality.WebRip
            Regex("""\b(?:1080P?|FHD|HD)\b""").containsMatchIn(value) -> SearchQuality.HD
            else -> null
        }
    }

    private fun isCamera(quality: SearchQuality): Boolean {
        return quality == SearchQuality.Cam ||
            quality == SearchQuality.CamRip ||
            quality == SearchQuality.HdCam ||
            quality == SearchQuality.Telesync ||
            quality == SearchQuality.Telecine
    }

    private fun qualityRank(quality: SearchQuality): Int {
        return when (quality) {
            SearchQuality.UHD, SearchQuality.FourK -> 100
            SearchQuality.HDR -> 95
            SearchQuality.BlueRay -> 90
            SearchQuality.WebRip -> 80
            SearchQuality.HD -> 70
            SearchQuality.HQ -> 60
            SearchQuality.HdCam -> 30
            SearchQuality.Telesync -> 20
            SearchQuality.Cam, SearchQuality.CamRip -> 10
            else -> 0
        }
    }
}
