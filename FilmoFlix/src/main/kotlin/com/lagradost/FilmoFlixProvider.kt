package com.lagradost

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmoFlixProvider : MainAPI() {
    override var mainUrl = "https://filmoflix.support"
    override var name = "FilmoFlix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val portalUrl = "https://portail.lol"

    private val mirrors = listOf(
        "https://filmoflix.support",
        "https://filmoflix.delivery",
        "https://filmoflix.markets",
        "https://filmoflix.money",
        "https://filmoflix.lifestyle"
    )

    override val mainPage = mainPageOf(
        "film/" to "Films",
        "serie/" to "Series",
        "film/action/" to "Films - Action",
        "film/animation/" to "Films - Animation",
        "film/aventure/" to "Films - Aventure",
        "film/biopic/" to "Films - Biopic",
        "film/comedie/" to "Films - Comedie",
        "film/drame/" to "Films - Drame",
        "film/documentaire/" to "Films - Documentaire",
        "film/epouvante-horreur/" to "Films - Epouvante-horreur",
        "film/espionnage/" to "Films - Espionnage",
        "film/famille/" to "Films - Famille",
        "film/fantastique/" to "Films - Fantastique",
        "film/guerre/" to "Films - Guerre",
        "film/historique/" to "Films - Historique",
        "film/policier/" to "Films - Policier",
        "film/romance/" to "Films - Romance",
        "film/science-fiction/" to "Films - Science fiction",
        "film/thriller/" to "Films - Thriller",
        "film/western/" to "Films - Western",
        "serie/action-s/" to "Series - Action",
        "serie/animation-s/" to "Series - Animation",
        "serie/aventure-s/" to "Series - Aventure",
        "serie/biopic-s/" to "Series - Biopic",
        "serie/comedie-s/" to "Series - Comedie",
        "serie/documentaire-s/" to "Series - Documentaire",
        "serie/drame-s/" to "Series - Drame",
        "serie/famille-s/" to "Series - Famille",
        "serie/fantastique-s/" to "Series - Fantastique",
        "serie/guerre-s/" to "Series - Guerre",
        "serie/historique-s/" to "Series - Historique",
        "serie/horreur-s/" to "Series - Horreur",
        "serie/judiciare-s/" to "Series - Judiciaire",
        "serie/musical-s/" to "Series - Musical",
        "serie/policier-s/" to "Series - Policier",
        "serie/romance-s/" to "Series - Romance",
        "serie/science-fiction-s/" to "Series - Science fiction",
        "serie/thriller-s/" to "Series - Thriller",
        "serie/western-s/" to "Series - Western"
    )

    private fun rebaseUrl(url: String, base: String = mainUrl): String {
        val fixed = when {
            url.startsWith("http", ignoreCase = true) -> url
            url.startsWith("/") -> "$base$url"
            else -> "$base/$url"
        }
        return fixed.replace(Regex("""https://filmoflix\.[^/]+"""), base)
    }

    private suspend fun refreshMainUrlFromPortal(): String? {
        val portal = try {
            app.get(portalUrl)
        } catch (_: Exception) {
            return null
        }
        if (!portal.isSuccessful) return null

        val href = portal.document.select("a[href*=filmoflix.]")
            .mapNotNull { it.attr("href").takeIf { link -> link.startsWith("http", ignoreCase = true) } }
            .firstOrNull()
            ?: Regex("""https://filmoflix\.[a-z0-9.-]+""", RegexOption.IGNORE_CASE)
                .find(portal.text)
                ?.value

        val discovered = href?.trimEnd('/')
        val resolved = discovered?.let { candidate ->
            try {
                val probe = app.get("$candidate/film/")
                Regex("""https://filmoflix\.[a-z0-9.-]+""", RegexOption.IGNORE_CASE)
                    .find(probe.text)
                    ?.value
                    ?.trimEnd('/')
                    ?: candidate
            } catch (_: Exception) {
                candidate
            }
        }
        if (!resolved.isNullOrBlank()) {
            mainUrl = resolved
        }
        return resolved
    }

    private suspend fun safeGet(url: String) = try {
        app.get(rebaseUrl(url))
    } catch (_: Exception) {
        null
    }.let { response ->
        if (response?.isSuccessful == true) {
            response
        } else {
            val fallback = refreshMainUrlFromPortal()
                ?: mirrors.firstOrNull { it != mainUrl }
                ?: mainUrl
            mainUrl = fallback
            app.get(rebaseUrl(url, fallback))
        }
    }

    private fun pagedUrl(path: String, page: Int): String {
        val normalized = path.trimStart('/')
        return if (page <= 1) {
            "$mainUrl/$normalized"
        } else {
            "$mainUrl/${normalized.trimEnd('/')}/page/$page/"
        }
    }

    private fun cleanPoster(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.contains("loading.gif")) return null
        val tmdbIndex = raw.indexOf("https://image.tmdb.org")
        val cleaned = if (tmdbIndex >= 0) raw.substring(tmdbIndex) else raw
        return fixUrlNull(cleaned)
    }

    private fun isSeriesUrl(url: String): Boolean {
        return url.contains("/serie/", ignoreCase = true)
    }

    private fun titleFromFullPage(doc: Document): String {
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (!ogTitle.isNullOrBlank()) return ogTitle

        return doc.selectFirst("h1")?.text()
            ?.replace(Regex("""^\s*Voir\s+""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s+(film|serie|série)\s+en\s+streaming.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: "FilmoFlix"
    }

    private fun pageYear(doc: Document): Int? {
        return doc.select("div.short-info").firstOrNull {
            it.selectFirst("span")?.text()?.contains("Date de sortie", ignoreCase = true) == true
        }?.ownText()?.trim()?.toIntOrNull()
    }

    private fun pageTags(doc: Document): List<String> {
        return doc.select("div.short-info").firstOrNull {
            it.selectFirst("span")?.text()?.contains("Genre", ignoreCase = true) == true
        }?.select("a")?.mapNotNull {
            it.text().trim().takeIf { tag ->
                tag.isNotBlank() && !tag.equals("Films", true) && !tag.equals("Séries", true)
            }
        } ?: emptyList()
    }

    private fun pagePoster(doc: Document): String? {
        return cleanPoster(doc.selectFirst("div.fposter img")?.attr("data-src"))
            ?: cleanPoster(doc.selectFirst("meta[property=og:image]")?.attr("content"))
    }

    private fun toResult(element: Element): SearchResponse? {
        val href = rebaseUrl(element.attr("href"))
        if (!href.endsWith(".html") || href.contains("-saison/") || href.contains("-episode.html")) return null

        val title = element.selectFirst(".th-title")?.text()?.trim()
            ?: element.selectFirst(".searchheading")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: element.attr("title").trim()
        if (title.isBlank()) return null

        val poster = cleanPoster(element.selectFirst("img")?.attr("data-src"))
            ?: cleanPoster(element.selectFirst("img")?.attr("src"))

        return if (isSeriesUrl(href)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = safeGet(pagedUrl(request.data, page)).document
        val items = doc.select("a.th-in[href], a[href] .searchheading").mapNotNull { element ->
            val anchor = if (element.`is`("a")) element else element.parent()
            anchor?.let { toResult(it) }
        }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val home = safeGet(mainUrl)
        val hash = Regex("""dle_login_hash\s*=\s*'([^']+)'""").find(home.text)?.groupValues?.getOrNull(1)
        val results = if (!hash.isNullOrBlank()) {
            app.post(
                "$mainUrl/engine/ajax/controller.php?mod=search",
                data = mapOf("query" to query, "user_hash" to hash),
                referer = mainUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document.select("a[href]").mapNotNull { toResult(it) }
        } else {
            emptyList()
        }

        if (results.isNotEmpty()) return results.distinctBy { it.url }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = safeGet("$mainUrl/index.php?do=search&subaction=search&story=$encoded").document
        return doc.select("a.th-in[href]").mapNotNull { toResult(it) }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = rebaseUrl(url)
        val doc = safeGet(fixedUrl).document
        val title = titleFromFullPage(doc)
        val poster = pagePoster(doc)
        val year = pageYear(doc)
        val tags = pageTags(doc)
        val plot = doc.selectFirst("div.fdesc")?.text()?.trim()

        if (!isSeriesUrl(fixedUrl)) {
            return newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }

        val episodes = loadEpisodes(doc, fixedUrl)
        return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    private suspend fun loadEpisodes(doc: Document, seriesUrl: String): List<Episode> {
        val seasonUrls = doc.select("a[href*='-saison.html']").mapNotNull {
            rebaseUrl(it.attr("href")).takeIf { href -> href.contains(seriesUrl.removeSuffix(".html")) }
        }.distinct()

        val urlsToParse = if (seasonUrls.isNotEmpty()) seasonUrls else listOf(seriesUrl)
        return urlsToParse.flatMap { seasonUrl ->
            val seasonDoc = if (seasonUrl == seriesUrl) doc else safeGet(seasonUrl).document
            seasonDoc.select("a[href*='-episode.html']").map { episodeAnchor ->
                rebaseUrl(episodeAnchor.attr("href"))
            }.distinct().map { episodeUrl ->
                val match = Regex("""/(\d+)-saison/(\d+)-episode\.html""").find(episodeUrl)
                val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                newEpisode(episodeUrl) {
                    name = "Episode ${episode ?: ""}".trim()
                    this.season = season
                    this.episode = episode
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val primary = loadLinksFromPage(data, subtitleCallback, callback)
        if (primary) return true

        val liveUrl = refreshMainUrlFromPortal() ?: return false
        return loadLinksFromPage(rebaseUrl(data, liveUrl), subtitleCallback, callback)
    }

    private suspend fun loadLinksFromPage(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = rebaseUrl(data)
        val response = safeGet(fixedUrl)
        val html = response.text
        val hash = Regex("""dle_login_hash\s*=\s*'([^']+)'""").find(html)?.groupValues?.getOrNull(1)
        if (hash.isNullOrBlank()) return false

        val entries = Regex("""getxfield\(this,\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'\)""")
            .findAll(html)
            .map { it.groupValues }
            .filter { it[2] != "trailer" }
            .distinctBy { "${it[1]}:${it[2]}:${it[3]}" }
            .toList()

        var found = false
        var captchaBlocked = false
        entries.forEach { values ->
            val body = app.post(
                "$mainUrl/engine/ajax/controller.php?mod=getxfield",
                data = mapOf(
                    "id" to values[1],
                    "xfield" to values[2],
                    "type" to values[3],
                    "user_hash" to hash
                ),
                referer = fixedUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            if (body.trim() == "captcha_error") {
                captchaBlocked = true
                return@forEach
            }

            Regex("""(?:src|data-src)=["']([^"']+)["']""").findAll(body).forEach { match ->
                val link = match.groupValues[1]
                if (link.startsWith("http", ignoreCase = true)) {
                    loadExtractor(link, subtitleCallback, callback)
                    found = true
                }
            }
        }

        if (!found && captchaBlocked) {
            throw ErrorLoadingException("FilmoFlix demande maintenant un captcha Turnstile pour afficher les lecteurs.")
        }

        return found
    }
}
