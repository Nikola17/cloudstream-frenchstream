package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
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

    /** Extract numeric ID from URLs like /15126665-title.html or /index.php?newsid=15126665 */
    private fun extractContentId(url: String): String? {
        return Regex("""[?&]newsid=([0-9]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/([0-9]+)-[^/]+\.html""").find(url)?.groupValues?.get(1)
    }

    private fun toResult(element: Element): SearchResponse? {
        val anchor = element.selectFirst("a.short-poster") ?: element.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = element.selectFirst(".short-title")?.text()
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: anchor.attr("alt").takeIf { it.isNotBlank() }
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
            hasVOSTFR -> " [VOST]"
            else -> ""
        }

        return if (isSeries) {
            newTvSeriesSearchResponse(title + badge, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title + badge, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
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
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (loadDirectPackedHost(url, callback)) {
            return true
        }

        val normalized = normalizeExtractorUrl(url)
        if (runCatching { loadExtractor(normalized, subtitleCallback, callback) }.getOrDefault(false)) {
            return true
        }
        if (normalized != url && runCatching { loadExtractor(url, subtitleCallback, callback) }.getOrDefault(false)) {
            return true
        }
        if (url.contains("uqload.is", ignoreCase = true)) {
            return loadUqloadDirect(url, callback)
        }
        return false
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

        var found = false
        links.distinct()
            .sortedBy { extractorPriority(it) }
            .forEach { link ->
                if (loadHostedLink(link, subtitleCallback, callback)) {
                    found = true
                }
            }
        return found
    }
}
