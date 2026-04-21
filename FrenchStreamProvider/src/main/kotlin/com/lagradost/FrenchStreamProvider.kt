package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    private suspend fun safeGet(url: String): NiceResponse {
        val response = app.get(url)
        if (!response.isSuccessful && !isUsingFallback) {
            isUsingFallback = true
            mainUrl = fallbackUrl
            val fallbackFullUrl = url.replace("https://french-stream.pink", fallbackUrl)
            return app.get(fallbackFullUrl)
        }
        return response
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

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override val mainPage = mainPageOf(
        "films" to "Derniers Films",
        "s-tv" to "Dernières Séries",
        "films/top-film" to "Top Films",
        "sries-du-moment" to "Séries du moment"
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
        val title = doc.selectFirst("h1#s-title")?.text()
            ?: doc.selectFirst("h1")?.text()
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
                || doc.select("div.elink").isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }

        val episodeLists = doc.select("div.elink")
        val vfEpisodes = mutableListOf<Episode>()
        val vostfrEpisodes = mutableListOf<Episode>()

        // VF — premier bloc
        episodeLists.firstOrNull()?.select("a")?.forEachIndexed { index, a ->
            val epNum = a.text().substringAfter("Episode").trim().toIntOrNull() ?: (index + 1)
            val data = fixUrl(url).plus("-episodenumber:$epNum")
            vfEpisodes.add(newEpisode(data) {
                name = "Épisode $epNum VF"
                episode = epNum
                season = 1
            })
        }

        // VOSTFR — deuxième bloc s'il existe
        if (episodeLists.size > 1) {
            episodeLists[1].select("a").forEachIndexed { index, a ->
                val epNum = a.text().substringAfter("Episode").trim().toIntOrNull() ?: (index + 1)
                val data = fixUrl(url).plus("-episodenumber:$epNum")
                vostfrEpisodes.add(newEpisode(data) {
                    name = "Épisode $epNum VOSTFR"
                    episode = epNum
                    season = 2
                })
            }
        }

        val allEpisodes = (vfEpisodes + vostfrEpisodes).filter { it.data.isNotBlank() }

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
        val isEpisode = data.contains("-episodenumber:")
        val pageUrl = if (isEpisode) data.split("-episodenumber:")[0] else data
        val doc = safeGet(pageUrl).document

        val links = if (isEpisode) {
            val wantedEpisode = data.split("-episodenumber:")[1]
            val episodeId = if (wantedEpisode == "1") "episode1" else "episode${wantedEpisode.toInt() + 32}"
            val divSelector = if (wantedEpisode == "1") "> div.tabs-sel " else ""
            doc.select("div#$episodeId > div.selink > ul.btnss $divSelector> li a")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .filter { it.isNotBlank() }
        } else {
            doc.select("nav#primary_nav_wrap > ul > li > ul > li > a")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .filter { it.isNotBlank() }
        }

        if (links.isEmpty()) {
            // Fallback de parsing si la structure change légèrement
            val altLinks = doc.select("div.selink a, div.fsctab a, .fplayer a")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .filter { it.isNotBlank() }
            altLinks.forEach { loadExtractor(it, subtitleCallback, callback) }
            return altLinks.isNotEmpty()
        }

        links.forEach { loadExtractor(it, subtitleCallback, callback) }
        return links.isNotEmpty()
    }
}
