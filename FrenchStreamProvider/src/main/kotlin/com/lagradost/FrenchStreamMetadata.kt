package com.lagradost

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.ShowStatus
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.text.Normalizer

internal data class FrenchStreamEpisodePayload(
    val episode: Int,
    val links: Map<String, List<String>>
)

internal data class FrenchStreamSeasonRef(
    val season: Int,
    val title: String,
    val url: String
)

internal data class FrenchStreamCastInfo(
    val name: String,
    val profilePath: String?,
    val character: String?
)

internal object FrenchStreamMetadata {
    private const val PAYLOAD_KIND = "frenchstream_episode"
    private val seasonRegex = Regex("""\s*(?:-|–|—)?\s*saison\s+(\d+)\b""", RegexOption.IGNORE_CASE)
    private val languageSuffixRegex = Regex(
        """\s*(?:\[(?:VF|VOSTFR?|VFQ|VFF)(?:\s*\+\s*(?:VF|VOSTFR?|VFQ|VFF))*]|(?:VF|VOSTFR?|VFQ|VFF)(?:\s*\+\s*(?:VF|VOSTFR?|VFQ|VFF))*)\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun normalizeTitle(title: String): String {
        var value = title.trim()
        value = languageSuffixRegex.replace(value, "")
        value = seasonRegex.replace(value, "")
        value = languageSuffixRegex.replace(value, "")
        return value.trim().trimEnd('-', '–', '—').trim()
    }

    fun seasonNumber(title: String): Int? {
        return seasonRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun seasonRefs(document: Document, canonicalTitle: String): List<FrenchStreamSeasonRef> {
        val canonicalKey = titleKey(normalizeTitle(canonicalTitle))
        return document.select(".short").mapNotNull { card ->
            val title = card.selectFirst(".short-title")?.text()?.trim().orEmpty()
            val season = seasonNumber(title) ?: return@mapNotNull null
            if (titleKey(normalizeTitle(title)) != canonicalKey) return@mapNotNull null
            val link = card.selectFirst("a.short-poster[href]") ?: card.selectFirst("a[href]")
            val url = link?.absUrl("href")?.takeIf(::isHttpUrl) ?: return@mapNotNull null
            FrenchStreamSeasonRef(season, title, url)
        }.distinctBy { it.season }.sortedBy { it.season }
    }

    fun episodeLinks(root: JSONObject): Map<Int, Map<String, List<String>>> {
        val episodes = sortedMapOf<Int, MutableMap<String, List<String>>>()
        listOf("vf", "vostfr").forEach { language ->
            val group = root.optJSONObject(language) ?: return@forEach
            group.keys().forEach { episodeKey ->
                val episode = episodeKey.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val hosts = group.optJSONObject(episodeKey) ?: return@forEach
                val urls = hosts.keys().asSequence()
                    .mapNotNull { hosts.optString(it).trim().takeIf(::isHttpUrl) }
                    .distinct()
                    .toList()
                if (urls.isNotEmpty()) {
                    episodes.getOrPut(episode) { linkedMapOf() }[language.uppercase()] = urls
                }
            }
        }
        return episodes
    }

    fun quality(label: String?): SearchQuality? {
        val value = label?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
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

    fun mergeEpisodePayload(episode: Int, links: Map<String, List<String>>): String {
        val groups = JSONObject()
        links.forEach { (language, urls) ->
            val valid = urls.map(String::trim).filter(::isHttpUrl).distinct()
            if (valid.isNotEmpty()) groups.put(language.uppercase(), JSONArray(valid))
        }
        return JSONObject()
            .put("kind", PAYLOAD_KIND)
            .put("episode", episode)
            .put("links", groups)
            .toString()
    }

    fun parseEpisodePayload(data: String): FrenchStreamEpisodePayload? {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return null
        if (root.optString("kind") != PAYLOAD_KIND) return null
        val episode = root.optInt("episode").takeIf { it > 0 } ?: return null
        val groups = root.optJSONObject("links") ?: return null
        val links = groups.keys().asSequence().mapNotNull { language ->
            val values = groups.optJSONArray(language) ?: return@mapNotNull null
            val urls = (0 until values.length())
                .mapNotNull { values.optString(it).trim().takeIf(::isHttpUrl) }
                .distinct()
            language.uppercase() to urls
        }.filter { it.second.isNotEmpty() }.toMap()
        return FrenchStreamEpisodePayload(episode, links)
    }

    fun isTmdbMatch(siteTitle: String, siteYear: Int?, tmdbTitle: String, tmdbYear: Int?): Boolean {
        if (titleKey(normalizeTitle(siteTitle)) != titleKey(normalizeTitle(tmdbTitle))) return false
        return siteYear == null || tmdbYear == null || siteYear == tmdbYear
    }

    fun tmdbResult(
        results: JSONArray,
        siteTitle: String,
        siteYear: Int?,
        isSeries: Boolean
    ): JSONObject? {
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { item ->
                val title = if (isSeries) item.optString("name") else item.optString("title")
                val date = if (isSeries) item.optString("first_air_date") else item.optString("release_date")
                isTmdbMatch(siteTitle, siteYear, title, date.take(4).toIntOrNull())
            }
            .maxWithOrNull(compareBy<JSONObject> { it.optInt("vote_count") }.thenBy { it.optDouble("popularity") })
    }

    fun cast(details: JSONObject): List<FrenchStreamCastInfo> {
        val items = details.optJSONObject("credits")?.optJSONArray("cast") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FrenchStreamCastInfo(
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

    private fun titleKey(title: String): String {
        return Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "")
    }

    private fun isHttpUrl(url: String): Boolean {
        return url.startsWith("https://") || url.startsWith("http://")
    }
}
