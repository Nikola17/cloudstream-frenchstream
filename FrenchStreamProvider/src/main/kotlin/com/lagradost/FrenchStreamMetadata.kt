package com.lagradost

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.ShowStatus
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
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

internal data class FrenchStreamCatalogItem(
    val id: Int,
    val title: String,
    val originalTitle: String?,
    val releaseDate: String,
    val year: Int?,
    val isSeries: Boolean,
    val score: Double?,
    val popularity: Double,
    val posterPath: String?
)

internal data class FrenchStreamSitemapRef(
    val url: String,
    val titleKey: String,
    val year: Int?,
    val isSeries: Boolean
)

internal object FrenchStreamMetadata {
    private const val PAYLOAD_KIND = "frenchstream_episode"
    private val seasonRegex = Regex("""\s*(?:-|–|—)?\s*saison\s+(\d+)\b""", RegexOption.IGNORE_CASE)
    private val languageSuffixRegex = Regex(
        """\s*(?:\[(?:VF|VOSTFR?|VFQ|VFF)(?:\s*\+\s*(?:VF|VOSTFR?|VFQ|VFF))*]|(?:VF|VOSTFR?|VFQ|VFF)(?:\s*\+\s*(?:VF|VOSTFR?|VFQ|VFF))*)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val yearSuffixRegex = Regex(
        """(?:\s*[-–—]\s*(?:\((?:19|20)\d{2}\)|(?:19|20)\d{2})|\s+\((?:19|20)\d{2}\))\s*$"""
    )
    private val yearRegex = Regex("""\b(?:19|20)\d{2}\b""")
    private val descriptionBoilerplateRegex = Regex(
        """^\s*r[ée]sum[ée]\s+(?:du\s+film|de\s+la\s+s[ée]rie)\s+.*?\s+en\s+streaming\s+complet.*?\bsans\s+inscription\b\s*""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tmdbImageSizeRegex = Regex("""(/t/p/)(?:w|h)\d+(/)""", RegexOption.IGNORE_CASE)
    private val sitemapSeasonSuffixRegex = Regex("""-saison-\d+(?:-(?:19|20)\d{2})?.*$""", RegexOption.IGNORE_CASE)
    private val sitemapStreamingSuffixRegex = Regex("""-(?:film-)?streaming.*$""", RegexOption.IGNORE_CASE)
    private val sitemapYearSuffixRegex = Regex("""-(?:19|20)\d{2}$""")
    private val sitemapLocRegex = Regex("""<loc>([^<]+)</loc>""", RegexOption.IGNORE_CASE)
    private val browserVerificationCookieRegex = Regex(
        """document\.cookie\s*=\s*["']fsschal=([^;"']+)""",
        RegexOption.IGNORE_CASE
    )

    fun browserVerificationCookie(html: String): Pair<String, String>? {
        val value = browserVerificationCookieRegex.find(html)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return "fsschal" to value
    }

    fun normalizeTitle(title: String): String {
        var value = title.trim()
        value = languageSuffixRegex.replace(value, "")
        value = seasonRegex.replace(value, "")
        value = yearSuffixRegex.replace(value, "")
        value = languageSuffixRegex.replace(value, "")
        return value.trim().trimEnd('-', '–', '—').trim()
    }

    fun seasonNumber(title: String): Int? {
        return seasonRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun year(vararg values: String?): Int? {
        return values.asSequence()
            .mapNotNull { value -> yearRegex.findAll(value.orEmpty()).lastOrNull()?.value?.toIntOrNull() }
            .firstOrNull()
    }

    fun cleanDescription(description: String?): String {
        return descriptionBoilerplateRegex.replace(description.orEmpty(), "").trim()
    }

    fun genres(document: Document): List<String> {
        val detailGenres = document.select("#s-list li").firstOrNull { item ->
            item.selectFirst("span")?.text()?.trim()?.startsWith("Genre", ignoreCase = true) == true
        }?.select("a")?.map { it.text().trim() }?.filter(String::isNotBlank).orEmpty()
        if (detailGenres.isNotEmpty()) return detailGenres.distinct()

        return document.selectFirst(".facts .genres")?.text()
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
    }

    fun highQualityImage(url: String?): String? {
        return url?.let { tmdbImageSizeRegex.replace(it, "$1w780$2") }
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

    fun seriesTag(document: Document): String? {
        val container = document.selectFirst("#serie-data") ?: return null
        return sequenceOf(
            container.attr("data-serie-tag"),
            container.selectFirst(".sd-tagz a")?.text(),
            container.selectFirst(".sd-tagz")?.text()
        ).mapNotNull { it?.trim()?.takeIf { value -> Regex("""s-\d+""").matches(value) } }
            .firstOrNull()
    }

    fun seasonRefs(
        items: JSONArray,
        baseUrl: String,
        canonicalTitle: String
    ): List<FrenchStreamSeasonRef> {
        val canonicalKey = titleKey(normalizeTitle(canonicalTitle))
        val origin = baseUrl.trimEnd('/')
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val season = seasonNumber(title) ?: return@mapNotNull null
            if (titleKey(normalizeTitle(title)) != canonicalKey) return@mapNotNull null
            val path = item.optString("full_url").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = if (isHttpUrl(path)) path else "$origin/${path.trimStart('/')}"
            FrenchStreamSeasonRef(season, title, url)
        }.distinctBy { it.season }.sortedBy { it.season }
    }

    fun movieLinks(root: JSONObject): Map<String, List<String>> {
        val players = root.optJSONObject("players") ?: return emptyMap()
        val links = linkedMapOf<String, MutableList<String>>()

        fun add(language: String, url: String?) {
            val value = url?.trim()?.takeIf(::isHttpUrl) ?: return
            links.getOrPut(language) { mutableListOf() }.add(value)
        }

        players.keys().asSequence().sorted().forEach { playerName ->
            val player = players.optJSONObject(playerName) ?: return@forEach
            add("VF", player.optString("default"))
            add("VF", player.optString("vff"))
            add("VFQ", player.optString("vfq"))
            add("VOSTFR", player.optString("vostfr"))
        }

        return links.mapValues { (_, urls) -> urls.distinct() }
            .filterValues { it.isNotEmpty() }
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

    fun hboMaxCatalogItems(movies: JSONArray, series: JSONArray): List<FrenchStreamCatalogItem> {
        fun parse(results: JSONArray, isSeries: Boolean): List<FrenchStreamCatalogItem> {
            return (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val translated = item.optString(if (isSeries) "name" else "title").trim()
                val original = item.optString(if (isSeries) "original_name" else "original_title").trim()
                val title = translated.ifBlank { original }.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val releaseDate = item.optString(if (isSeries) "first_air_date" else "release_date")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val id = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                FrenchStreamCatalogItem(
                    id = id,
                    title = title,
                    originalTitle = original.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
                    releaseDate = releaseDate,
                    year = releaseDate.take(4).toIntOrNull(),
                    isSeries = isSeries,
                    score = item.optDouble("vote_average").takeIf { it > 0.0 },
                    popularity = item.optDouble("popularity"),
                    posterPath = item.optString("poster_path").trim().takeIf(String::isNotBlank)
                )
            }
        }

        return (parse(movies, false) + parse(series, true))
            .distinctBy { "${it.isSeries}|${it.id}" }
            .sortedByDescending(FrenchStreamCatalogItem::releaseDate)
    }

    fun hboMaxCatalogCandidates(
        recentMovies: JSONArray,
        recentSeries: JSONArray,
        popularMovies: JSONArray,
        popularSeries: JSONArray,
        earliestPopularDate: String
    ): List<FrenchStreamCatalogItem> {
        val recent = hboMaxCatalogItems(recentMovies, recentSeries)
        val popular = hboMaxCatalogItems(popularMovies, popularSeries)
            .filter { it.releaseDate >= earliestPopularDate }
        return (recent + popular)
            .distinctBy { "${it.isSeries}|${it.id}" }
            .sortedByDescending(FrenchStreamCatalogItem::releaseDate)
    }

    /**
     * Le sitemap du site pèse ~7,5 Mo pour ~42 000 URLs : on extrait les <loc> au fil du texte
     * plutôt que de construire un DOM XML complet, inutilement coûteux en mémoire sur mobile.
     */
    fun sitemapRefs(xml: String): List<FrenchStreamSitemapRef> {
        return sitemapLocRegex.findAll(xml).mapNotNull { match ->
            val url = decodeXmlEntities(match.groupValues[1].trim()).takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val path = runCatching { URLDecoder.decode(URI(url).path, "UTF-8") }.getOrNull()
                ?: return@mapNotNull null
            val slug = path.substringAfterLast('/').removeSuffix(".html")
                .replace(Regex("""^\d+-"""), "")
            if (slug.isBlank() || slug == path) return@mapNotNull null
            val isSeries = sitemapSeasonSuffixRegex.containsMatchIn(slug)
            val year = Regex("""-(?:19|20)\d{2}$""").find(slug)?.value?.drop(1)?.toIntOrNull()
            val titleSlug = if (isSeries) {
                sitemapSeasonSuffixRegex.replace(slug, "")
            } else {
                sitemapYearSuffixRegex.replace(sitemapStreamingSuffixRegex.replace(slug, ""), "")
            }
            val key = titleKey(titleSlug.replace('-', ' ')).takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            FrenchStreamSitemapRef(url, key, year, isSeries)
        }.toList()
    }

    private fun decodeXmlEntities(value: String): String {
        if ('&' !in value) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    fun sitemapMatch(
        refs: List<FrenchStreamSitemapRef>,
        title: String,
        originalTitle: String?,
        isSeries: Boolean,
        year: Int? = null
    ): FrenchStreamSitemapRef? {
        val keys = listOfNotNull(title, originalTitle)
            .map { titleKey(normalizeTitle(it)) }
            .filter(String::isNotBlank)
            .toSet()
        val matches = refs.filter { it.isSeries == isSeries && it.titleKey in keys }
        return matches.firstOrNull { year != null && it.year == year } ?: matches.firstOrNull()
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
