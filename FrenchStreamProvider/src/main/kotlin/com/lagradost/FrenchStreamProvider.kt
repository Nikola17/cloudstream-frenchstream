package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Element

class FrenchStreamProvider : MainAPI() {
    override var mainUrl = "https://french-stream.pink"
    private val fallbackUrl = "https://fstream.info"
    override var name = "French-Stream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var isUsingFallback = false

    private suspend fun safeGet(url: String) = app.get(url).let { response ->
        if (!response.isSuccessful && !isUsingFallback) {
            isUsingFallback = true
            mainUrl = fallbackUrl
            val fallbackFullUrl = url.replace("https://french-stream.pink", fallbackUrl)
            app.get(fallbackFullUrl)
        } else {
            response
        }
    }

    /** Extract numeric ID from URL like /15126665-basic-psych.html */
    private fun extractContentId(url: String): String? {
        return Regex("""/([0-9]+)-[^/]+\.html""").find(url)?.groupValues?.get(1)
    }

    private fun toResult(element: Element): SearchResponse? {
        val anchor = element.selectFirst("a.short-poster") ?: element.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = element.selectFirst(".short-title")?.text()
            ?: anchor.attr("title")
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

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                if (hasVF) {
                    addDubStatus(DubStatus.Dubbed)
                } else if (hasVOSTFR) {
                    addDubStatus(DubStatus.Subbed)
                }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                if (hasVF) {
                    addDubStatus(DubStatus.Dubbed)
                } else if (hasVOSTFR) {
                    addDubStatus(DubStatus.Subbed)
                }
            }
        }
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
        val items = doc.select("div.short").mapNotNull { toResult(it) }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=${query.replace(" ", "+")}"
        val doc = safeGet(url).document
        return doc.select("div.short").mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = safeGet(url).document
        val title = doc.selectFirst("h1#s-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        var poster = fixUrlNull(doc.selectFirst("div.fposter img")?.attr("src"))
        if (poster == null) {
            val posterRegex = Regex("""url\((https?://\S+)\)""")
            poster = posterRegex.find(doc.toString())?.groupValues?.get(1)
        }

        val description = doc.selectFirst("div.fdesc")?.text()
            ?: doc.selectFirst("#s-desc")?.ownText()
            ?: ""

        val year = doc.selectFirst("ul.flist-col li")?.text()?.toIntOrNull()
            ?: doc.selectFirst("span.release")?.text()?.substringBefore("-")?.trim()?.toIntOrNull()

        val tags = doc.select("ul.flist-col li a").mapNotNull { it.text() }

        val isSeries = title.contains("saison", ignoreCase = true)
                || doc.select("div.episodes-wrapper").isNotEmpty()
                || doc.select("#sv-cfg").isNotEmpty()

        if (!isSeries) {
            val contentId = extractContentId(url) ?: url
            val apiUrl = "$mainUrl/engine/ajax/film_api.php?id=$contentId"
            return newMovieLoadResponse(title, url, TvType.Movie, apiUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }

        // Series: fetch episodes from API
        val newsId = doc.selectFirst("#sv-cfg")?.attr("data-news-id")
            ?: extractContentId(url)
            ?: return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }

        val apiUrl = "$mainUrl/ep-data.php?id=$newsId"
        val apiResponse = safeGet(apiUrl).text
        val json = JSONObject(apiResponse)

        val vfEpisodes = mutableListOf<Episode>()
        val vostfrEpisodes = mutableListOf<Episode>()

        if (json.has("vf") && !json.isNull("vf")) {
            val vfObj = json.getJSONObject("vf")
            vfObj.keys().forEach { epNumStr ->
                val epNum = epNumStr.toIntOrNull() ?: 0
                if (epNum > 0) {
                    val data = "$mainUrl/ep-data.php?id=$newsId|ep=$epNum|lang=vf"
                    vfEpisodes.add(newEpisode(data) {
                        name = "Épisode $epNum VF"
                        episode = epNum
                        season = 1
                    })
                }
            }
        }

        if (json.has("vostfr") && !json.isNull("vostfr")) {
            val vostfrObj = json.getJSONObject("vostfr")
            vostfrObj.keys().forEach { epNumStr ->
                val epNum = epNumStr.toIntOrNull() ?: 0
                if (epNum > 0) {
                    val data = "$mainUrl/ep-data.php?id=$newsId|ep=$epNum|lang=vostfr"
                    vostfrEpisodes.add(newEpisode(data) {
                        name = "Épisode $epNum VOSTFR"
                        episode = epNum
                        season = 2
                    })
                }
            }
        }

        val allEpisodes = (vfEpisodes + vostfrEpisodes).sortedBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addSeasonNames(listOf("VF", "VOSTFR"))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = mutableListOf<String>()

        if (data.contains("film_api.php")) {
            // Movie: call film_api.php
            val response = safeGet(data).text
            val json = JSONObject(response)
            if (json.has("players") && !json.isNull("players")) {
                val players = json.getJSONObject("players")
                players.keys().forEach { playerName ->
                    val playerObj = players.getJSONObject(playerName)
                    if (playerObj.has("default")) {
                        val url = playerObj.getString("default")
                        if (url.isNotBlank()) links.add(url)
                    }
                }
            }
        } else if (data.contains("ep-data.php")) {
            // Series: parse data, call ep-data.php, extract episode links
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
                        if (url.isNotBlank()) links.add(url)
                    }
                }
            }
        }

        if (links.isEmpty()) return false

        links.forEach { loadExtractor(it, subtitleCallback, callback) }
        return true
    }
}
