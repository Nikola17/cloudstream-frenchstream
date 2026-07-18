package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FrenchStreamProvider : MainAPI() {
    override var mainUrl = "https://french-stream.one"
    private val mirrors = listOf(
        "https://french-stream.one",
        "https://french-stream.pink",
        "https://fstream.info"
    )
    override var name = "French-Stream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val uqloadSourceRegex = Regex(
        """sources:.*?["'](https?://[^"']+)["']""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val directVideoRegex = Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val packedScriptRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\).*?</script>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val externalHeaders = mapOf("User-Agent" to "Mozilla/5.0")

    private data class SiteEpisode(val season: Int, val episode: Int, val data: String)

    private suspend fun safeGet(url: String) = app.get(url).let { initial ->
        if (initial.isSuccessful) return@let initial

        var response = initial
        mirrorCandidates(url).drop(1).forEach { (candidate, origin) ->
            response = app.get(candidate)
            if (response.isSuccessful) {
                mainUrl = origin
                return@let response
            }
        }
        response
    }

    private fun mirrorCandidates(url: String): List<Pair<String, String>> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url to mainUrl)
        val sourceOrigin = "${uri.scheme}://${uri.authority}"
        if (mirrors.none { runCatching { URI(it).host }.getOrNull() == uri.host }) {
            return listOf(url to sourceOrigin)
        }
        val suffix = uri.rawPath.orEmpty() + uri.rawQuery?.let { "?$it" }.orEmpty()
        return (listOf(sourceOrigin) + mirrors)
            .distinct()
            .map { origin -> "$origin$suffix" to origin }
    }

    /** Extract numeric ID from URLs like /15126665-title.html or /index.php?newsid=15126665 */
    private fun extractContentId(url: String): String? {
        return Regex("""[?&]newsid=([0-9]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/([0-9]+)-[^/]+\.html""").find(url)?.groupValues?.get(1)
    }

    private fun toResult(element: Element): SearchResponse? {
        val anchor = element.selectFirst("a.short-poster") ?: element.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = anchor.attr("title").takeIf { it.isNotBlank() }
            ?: anchor.attr("alt").takeIf { it.isNotBlank() }
            ?: element.selectFirst(".short-title")?.text()
            ?: return null
        var poster = fixUrlNull(element.selectFirst("img")?.attr("src"))
        if (poster == null || poster.startsWith("data:")) {
            poster = fixUrlNull(element.selectFirst("img")?.attr("data-src"))
        }
        val year = element.selectFirst(".date")?.text()?.substringBefore("-")?.trim()?.toIntOrNull()

        val isSeries = title.contains("saison", ignoreCase = true)
                || href.contains("/series/", ignoreCase = true)
                || href.contains("/s-tv/", ignoreCase = true)

        // Extract version badge: VF or VOSTFR
        val versionText = element.selectFirst(".film-version")?.text() ?: ""
        val hasVF = versionText.contains("VF", ignoreCase = true)
        val hasVOSTFR = versionText.contains("VOSTFR", ignoreCase = true)

        val badge = when {
            hasVF -> " [VF]"
            hasVOSTFR -> " [VOSTFR]"
            else -> ""
        }

        val quality = FrenchStreamMetadata.quality(
            element.selectFirst(".film-quality, .quality, .short-quality")?.text()
        )

        return if (isSeries) {
            newTvSeriesSearchResponse(FrenchStreamMetadata.normalizeTitle(title), href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title + badge, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
            }
        }
    }

    private fun deduplicate(items: List<SearchResponse>): List<SearchResponse> {
        return items.distinctBy {
            if (it.type == TvType.TvSeries) {
                "tv|${FrenchStreamMetadata.normalizeTitle(it.name).lowercase()}"
            } else {
                "movie|${it.url}"
            }
        }
    }

    private suspend fun enrichCards(items: List<SearchResponse>) {
        withTimeoutOrNull(3_000L) {
            for (batch in items.chunked(8)) {
                coroutineScope {
                    batch.map { item ->
                        async {
                            val match = FrenchStreamTmdbClient.find(
                                item.name,
                                searchYear(item),
                                item.type == TvType.TvSeries
                            ) ?: return@async
                            item.id = match.optInt("id").takeIf { it > 0 }
                            item.score = Score.from10(match.optDouble("vote_average").takeIf { it > 0.0 })
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private fun searchYear(item: SearchResponse): Int? {
        return when (item) {
            is MovieSearchResponse -> item.year
            is TvSeriesSearchResponse -> item.year
            else -> null
        }
    }

    private fun actors(details: JSONObject?): List<Pair<Actor, String?>> {
        if (details == null) return emptyList()
        return FrenchStreamMetadata.cast(details).take(30).map { cast ->
            Actor(cast.name, FrenchStreamTmdbClient.image(cast.profilePath, "w185")) to cast.character
        }
    }

    private fun logo(details: JSONObject?): String? {
        val logos = details?.optJSONObject("images")?.optJSONArray("logos") ?: return null
        val items = (0 until logos.length()).mapNotNull { logos.optJSONObject(it) }
        val selected = items.firstOrNull { it.optString("iso_639_1") == "fr" }
            ?: items.firstOrNull { it.optString("iso_639_1") == "en" }
            ?: items.firstOrNull()
        return FrenchStreamTmdbClient.image(selected?.optString("file_path"), "w500")
    }

    private fun siteRecommendations(document: Document): List<SearchResponse> {
        return deduplicate(document.select("div.short").mapNotNull(::toResult)).take(20)
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)?.time }.getOrNull()
    }

    private fun normalizeExtractorUrl(url: String): String {
        return url.trim()
            .replace("http://uqload.is/", "https://uqload.cx/")
            .replace("https://uqload.is/", "https://uqload.cx/")
            .replace("http://www.uqload.is/", "https://uqload.cx/")
            .replace("https://www.uqload.is/", "https://uqload.cx/")
            .replace("http://vidmoly.me/", "https://vidmoly.me/")
    }

    private fun extractorPriority(url: String): Int {
        val lower = url.lowercase()
        return when {
            "uqload." in lower -> 0
            "lulustream." in lower -> 1
            "vidoza." in lower -> 2
            "voe." in lower -> 3
            "goodstream." in lower -> 4
            "vidmoly." in lower -> 5
            "wishonly." in lower -> 6
            else -> 50
        }
    }

    private suspend fun loadUqloadDirect(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching { app.get(url, referer = mainUrl).text }.getOrNull() ?: return false
        val directUrl = uqloadSourceRegex.find(response)?.groupValues?.getOrNull(1) ?: return false
        callback(newExtractorLink("Uqload", "Uqload", directUrl) {
            referer = "https://uqload.is/"
        })
        return true
    }

    private fun sourceNameFromUrl(url: String): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        return when {
            "fsvid" in host -> "Fsvid"
            "uqload" in host -> "Uqload"
            "vidzy" in host -> "Vidzy"
            "kokoflix" in host -> "Kokoflix"
            else -> host.substringBefore('.').replaceFirstChar { it.uppercase() }.ifBlank { "French-Stream" }
        }
    }

    private fun extractDirectVideoUrl(html: String): String? {
        directVideoRegex.find(html)?.value?.let { return it }

        val packedScripts = packedScriptRegex.findAll(html).map { it.value }.toList()
        for (packed in packedScripts) {
            val unpacked = runCatching {
                JsUnpacker(packed).takeIf { it.detect() }?.unpack()
            }.getOrNull()
            directVideoRegex.find(unpacked ?: continue)?.value?.let { return it }
        }

        val unpackedPage = runCatching {
            JsUnpacker(html).takeIf { it.detect() }?.unpack()
        }.getOrNull()
        return directVideoRegex.find(unpackedPage ?: return null)?.value
    }

    private suspend fun loadDirectPackedHost(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(url, headers = externalHeaders, referer = mainUrl).text
        }.getOrNull() ?: return false
        val directUrl = extractDirectVideoUrl(response) ?: return false
        val sourceName = sourceNameFromUrl(url)
        val type = if (directUrl.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        callback(newExtractorLink(sourceName, sourceName, directUrl, type) {
            referer = url
            headers = externalHeaders + mapOf("Referer" to url)
        })
        return true
    }

    private suspend fun loadHostedLink(
        url: String,
        language: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emit: (ExtractorLink) -> Unit = { callback(withLanguage(it, language)) }
        if (loadDirectPackedHost(url, emit)) {
            return true
        }

        val normalized = normalizeExtractorUrl(url)
        for (candidate in listOf(normalized, url).distinct()) {
            var emitted = false
            runCatching {
                loadExtractor(candidate, subtitleCallback) { link ->
                    emitted = true
                    emit(link)
                }
            }
            if (emitted) return true
        }
        if (url.contains("uqload.is", ignoreCase = true)) {
            return loadUqloadDirect(url, emit)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun withLanguage(link: ExtractorLink, language: String?): ExtractorLink {
        val label = language?.uppercase()?.takeIf { it.isNotBlank() } ?: return link
        return ExtractorLink(
            source = "$label ${link.source}",
            name = "$label ${link.name}",
            url = link.url,
            referer = link.referer,
            quality = link.quality,
            headers = link.headers,
            extractorData = link.extractorData,
            type = link.type,
            audioTracks = link.audioTracks
        )
    }

    override val mainPage = mainPageOf(
        "films" to "Derniers Films",
        "s-tv" to "Dernières Séries",
        "films/top-film" to "Top Films",
        "sries-du-moment" to "Séries du moment",
        "s-tv/netflix-series-" to "Nouveautés Netflix",
        "s-tv/series-disney-plus" to "Nouveautés Disney+",
        "s-tv/series-apple-tv" to "Nouveautés Apple TV+",
        "s-tv/serie-amazon-prime-videos" to "Nouveautés Prime Video"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "$mainUrl/${request.data}/page/$page"
        } else {
            "$mainUrl/${request.data}"
        }
        val doc = safeGet(url).document
        val items = deduplicate(doc.select("div.short").mapNotNull { toResult(it) })
        enrichCards(items)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=${URLEncoder.encode(query, "UTF-8")}"
        val doc = safeGet(url).document
        val items = deduplicate(doc.select("div.short").mapNotNull { toResult(it) })
        enrichCards(items)
        return items
    }

    private suspend fun discoverSeasonRefs(
        canonicalTitle: String,
        currentTitle: String,
        currentUrl: String,
        currentSeason: Int,
        currentDocument: Document
    ): List<FrenchStreamSeasonRef> {
        val seriesTag = FrenchStreamMetadata.seriesTag(currentDocument)
        val newsId = extractContentId(currentUrl)
            ?: currentDocument.selectFirst("#serie-data")?.attr("data-newsid")?.takeIf(String::isNotBlank)
        val fromApi = if (seriesTag != null && newsId != null) {
            val apiUrl = "$mainUrl/engine/ajax/get_seasons.php" +
                "?serie_tag=${URLEncoder.encode(seriesTag, "UTF-8")}" +
                "&news_id=${URLEncoder.encode(newsId, "UTF-8")}"
            runCatching { org.json.JSONArray(safeGet(apiUrl).text) }
                .getOrNull()
                ?.let { FrenchStreamMetadata.seasonRefs(it, mainUrl, canonicalTitle) }
                ?: emptyList()
        } else {
            emptyList()
        }
        val discovered = if (fromApi.isNotEmpty()) {
            fromApi
        } else {
            val searchUrl = "$mainUrl/?do=search&subaction=search&story=${URLEncoder.encode(canonicalTitle, "UTF-8")}"
            runCatching { safeGet(searchUrl).document }
                .getOrNull()
                ?.let { FrenchStreamMetadata.seasonRefs(it, canonicalTitle) }
                ?: emptyList()
        }
        val current = FrenchStreamSeasonRef(currentSeason, currentTitle, currentUrl)
        return (discovered + current).associateBy { it.season }.values.sortedBy { it.season }
    }

    private suspend fun loadSiteEpisodes(
        ref: FrenchStreamSeasonRef,
        currentUrl: String,
        currentDocument: Document
    ): List<SiteEpisode> {
        val document = if (ref.url == currentUrl) currentDocument else {
            runCatching { safeGet(ref.url).document }.getOrNull() ?: return emptyList()
        }
        val newsId = document.selectFirst("#serie-config, #sv-cfg")?.attr("data-news-id")?.takeIf { it.isNotBlank() }
            ?: extractContentId(ref.url)
            ?: return emptyList()
        val root = runCatching { JSONObject(safeGet("$mainUrl/ep-data.php?id=$newsId").text) }
            .getOrNull()
            ?: return emptyList()
        return FrenchStreamMetadata.episodeLinks(root).map { (episode, links) ->
            SiteEpisode(
                season = ref.season,
                episode = episode,
                data = FrenchStreamMetadata.mergeEpisodePayload(episode, links)
            )
        }
    }

    private suspend fun tmdbEpisodes(
        details: JSONObject?,
        seasons: List<Int>
    ): Map<Pair<Int, Int>, JSONObject> {
        val id = details?.optInt("id")?.takeIf { it > 0 } ?: return emptyMap()
        val result = mutableMapOf<Pair<Int, Int>, JSONObject>()
        for (batch in seasons.distinct().chunked(4)) {
            val seasonData = coroutineScope {
                batch.map { season -> async { season to FrenchStreamTmdbClient.season(id, season) } }.awaitAll()
            }
            seasonData.forEach { (season, root) ->
                val episodes = root?.optJSONArray("episodes") ?: return@forEach
                (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }.forEach { episode ->
                    val number = episode.optInt("episode_number").takeIf { it > 0 } ?: return@forEach
                    result[season to number] = episode
                }
            }
        }
        return result
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = safeGet(url).document
        val siteTitle = doc.selectFirst("h1#s-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"
        val canonicalTitle = FrenchStreamMetadata.normalizeTitle(siteTitle)

        var poster = fixUrlNull(doc.selectFirst("div.fposter img")?.attr("src"))
        if (poster == null) {
            poster = Regex("""url\((https?://\S+)\)""").find(doc.toString())?.groupValues?.get(1)
        }

        val description = doc.selectFirst("div.fdesc")?.text()
            ?: doc.selectFirst("#s-desc")?.ownText()
            ?: ""
        val siteYear = doc.selectFirst("ul.flist-col li")?.text()?.toIntOrNull()
            ?: doc.selectFirst("span.release")?.text()?.substringBefore("-")?.trim()?.toIntOrNull()
        val siteTags = doc.select("ul.flist-col li a").map { it.text() }.filter { it.isNotBlank() }
        val isSeries = siteTitle.contains("saison", ignoreCase = true)
            || doc.select("div.episodes-wrapper").isNotEmpty()
            || doc.select("#serie-config, #sv-cfg").isNotEmpty()
        val details = FrenchStreamTmdbClient.details(canonicalTitle, siteYear, isSeries)
        val tmdbTags = details?.optJSONArray("genres")?.let { genres ->
            (0 until genres.length()).mapNotNull { genres.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
        } ?: emptyList()
        val tags = (siteTags + tmdbTags).distinct()
        val tmdbPoster = FrenchStreamTmdbClient.image(details?.optString("poster_path"))
        val background = FrenchStreamTmdbClient.image(details?.optString("backdrop_path"), "w1280")
        val tmdbYear = details?.optString(if (isSeries) "first_air_date" else "release_date")
            ?.take(4)?.toIntOrNull()
        val tmdbId = details?.optInt("id")?.takeIf { it > 0 }?.toString()
        val imdbId = if (isSeries) {
            details?.optJSONObject("external_ids")?.optString("imdb_id")
        } else {
            details?.optString("imdb_id")?.ifBlank {
                details.optJSONObject("external_ids")?.optString("imdb_id").orEmpty()
            }
        }?.takeIf { it.isNotBlank() }

        if (!isSeries) {
            val contentId = extractContentId(url) ?: url
            val apiUrl = "$mainUrl/engine/ajax/film_api.php?id=$contentId"
            return newMovieLoadResponse(canonicalTitle, url, TvType.Movie, apiUrl) {
                posterUrl = poster ?: tmdbPoster
                backgroundPosterUrl = background
                logoUrl = logo(details)
                plot = description.ifBlank { details?.optString("overview").orEmpty() }
                year = siteYear ?: tmdbYear
                this.tags = tags
                duration = details?.optInt("runtime")?.takeIf { it > 0 }
                score = Score.from10(details?.optDouble("vote_average")?.takeIf { it > 0.0 })
                contentRating = details?.let(FrenchStreamMetadata::movieContentRating)
                recommendations = siteRecommendations(doc)
                addActors(actors(details))
                addTrailer(details?.let(FrenchStreamMetadata::trailerUrl))
                addImdbId(imdbId)
                addTMDbId(tmdbId)
            }
        }

        val currentSeason = FrenchStreamMetadata.seasonNumber(siteTitle) ?: 1
        val refs = discoverSeasonRefs(canonicalTitle, siteTitle, url, currentSeason, doc)
        val siteEpisodes = refs.flatMap { loadSiteEpisodes(it, url, doc) }
            .distinctBy { it.season to it.episode }
            .sortedWith(compareBy<SiteEpisode> { it.season }.thenBy { it.episode })
        val tmdbEpisodes = tmdbEpisodes(details, siteEpisodes.map { it.season })
        val episodes = siteEpisodes.map { item ->
            val metadata = tmdbEpisodes[item.season to item.episode]
            newEpisode(item.data) {
                name = metadata?.optString("name")?.takeIf { it.isNotBlank() } ?: "Épisode ${item.episode}"
                season = item.season
                episode = item.episode
                this.description = metadata?.optString("overview")?.takeIf { it.isNotBlank() }
                posterUrl = FrenchStreamTmdbClient.image(metadata?.optString("still_path")) ?: poster ?: tmdbPoster
                score = Score.from10(metadata?.optDouble("vote_average")?.takeIf { it > 0.0 })
                runTime = metadata?.optInt("runtime")?.takeIf { it > 0 }
                date = parseDate(metadata?.optString("air_date"))
            }
        }

        return newTvSeriesLoadResponse(canonicalTitle, url, TvType.TvSeries, episodes) {
            posterUrl = poster ?: tmdbPoster
            backgroundPosterUrl = background
            logoUrl = logo(details)
            plot = description.ifBlank { details?.optString("overview").orEmpty() }
            year = siteYear ?: tmdbYear
            this.tags = tags
            duration = details?.optJSONArray("episode_run_time")?.let { runtimes ->
                (0 until runtimes.length()).map { runtimes.optInt(it) }.filter { it > 0 }.average()
                    .takeIf { !it.isNaN() }?.toInt()
            }
            score = Score.from10(details?.optDouble("vote_average")?.takeIf { it > 0.0 })
            showStatus = FrenchStreamMetadata.showStatus(details?.optString("status"))
            contentRating = details?.let(FrenchStreamMetadata::tvContentRating)
            recommendations = siteRecommendations(doc)
            addActors(actors(details))
            addTrailer(details?.let(FrenchStreamMetadata::trailerUrl))
            addImdbId(imdbId)
            addTMDbId(tmdbId)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val groupedLinks = linkedMapOf<String?, MutableList<String>>()
        val payload = FrenchStreamMetadata.parseEpisodePayload(data)

        if (payload != null) {
            payload.links.forEach { (language, links) ->
                groupedLinks.getOrPut(language) { mutableListOf() }.addAll(links)
            }
        } else if (data.contains("film_api.php")) {
            val response = safeGet(data).text
            val json = JSONObject(response)
            FrenchStreamMetadata.movieLinks(json).forEach { (language, links) ->
                groupedLinks.getOrPut(language) { mutableListOf() }.addAll(links)
            }
        } else if (data.contains("ep-data.php")) {
            val parts = data.split("|")
            val apiUrl = parts[0]
            val epNum = parts.find { it.startsWith("ep=") }?.substringAfter("ep=") ?: return false
            val lang = parts.find { it.startsWith("lang=") }?.substringAfter("lang=") ?: "vf"

            val response = safeGet(apiUrl).text
            val json = JSONObject(response)
            if (json.has(lang) && !json.isNull(lang)) {
                val langObj = json.getJSONObject(lang)
                if (langObj.has(epNum)) {
                    val epObj = langObj.getJSONObject(epNum)
                    epObj.keys().forEach { hoster ->
                        val url = epObj.getString(hoster)
                        if (url.isNotBlank()) {
                            groupedLinks.getOrPut(lang.uppercase()) { mutableListOf() }.add(url)
                        }
                    }
                }
            }
        }

        if (groupedLinks.values.all { it.isEmpty() }) return false

        var found = false
        groupedLinks.forEach { (language, links) ->
            links.distinct()
                .sortedBy { extractorPriority(it) }
                .forEach { link ->
                    if (loadHostedLink(link, language, subtitleCallback, callback)) {
                        found = true
                    }
                }
        }
        return found
    }
}
