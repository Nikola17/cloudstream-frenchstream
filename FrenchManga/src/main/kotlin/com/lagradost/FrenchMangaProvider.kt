package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class FrenchMangaProvider : MainAPI() {
    @Volatile
    override var mainUrl = "https://w16.french-manga.net"
    override var name = "French-Manga"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var sequentialMainPage = false

    private val mirrors = listOf("https://w16.french-manga.net", "https://french-manga.net")
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    private data class EpisodeCacheEntry(val items: List<FrenchMangaEpisodeData>, val expiresAt: Long)
    private val episodeCache = ConcurrentHashMap<String, EpisodeCacheEntry>()

    override val mainPage = FrenchMangaParser.categories.map { category ->
        MainPageData(category.label, category.path)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getSiteDocument(categoryUrl(request.data, page))
        val rawCards = FrenchMangaParser.cards(document)
        val results = FrenchMangaParser.groupCards(rawCards).map(::toSearchResponse)
        return newHomePageResponse(request, results, hasNext = rawCards.size >= PAGE_SIZE)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val document = searchDocument(query)
        return FrenchMangaParser.groupCards(FrenchMangaParser.searchResults(document))
            .map(::toSearchResponse)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query).take(20)

    override suspend fun load(url: String): LoadResponse {
        val document = getSiteDocument(url)
        val detail = FrenchMangaParser.detail(document)
            ?: throw ErrorLoadingException("Fiche French-Manga introuvable")
        val related = FrenchMangaParser.groupCards(FrenchMangaParser.cards(document))
            .filterNot { sameUrl(it.url, document.baseUri()) }
            .take(15)
            .map(::toSearchResponse)

        if (detail.isMovie) {
            val episode = loadEpisodeData(url, detail.newsId).firstOrNull()
                ?: throw ErrorLoadingException("Aucune source disponible")
            return newMovieLoadResponse(
                detail.title,
                document.baseUri(),
                TvType.AnimeMovie,
                FrenchMangaParser.episodePayload(document.baseUri(), episode.number)
            ) {
                applyMetadata(detail, related)
            }
        }

        val seasonRefs = discoverSeasons(detail, document.baseUri())
        val episodes = loadAllSeasons(seasonRefs, document, detail).flatMap { seasonPage ->
            seasonPage.episodes.map { item ->
                newEpisode(FrenchMangaParser.episodePayload(seasonPage.url, item.number)) {
                    name = item.title
                    season = seasonPage.number
                    episode = item.number
                    description = item.synopsis
                    posterUrl = item.posterUrl ?: seasonPage.posterUrl ?: detail.posterUrl
                }
            }
        }.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episode })

        if (episodes.isEmpty()) throw ErrorLoadingException("Aucun épisode disponible")
        return newTvSeriesLoadResponse(detail.title, document.baseUri(), TvType.Anime, episodes) {
            applyMetadata(detail, related)
        }
    }

    private data class ResolvedMangaLink(val language: String, val link: ExtractorLink)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = FrenchMangaParser.episodePayload(data) ?: return false
        val newsId = FrenchMangaParser.newsId(payload.pageUrl) ?: runCatching {
            FrenchMangaParser.detail(getSiteDocument(payload.pageUrl))?.newsId
        }.getOrNull() ?: return false
        val episode = loadEpisodeData(payload.pageUrl, newsId)
            .firstOrNull { it.number == payload.episode } ?: return false

        val tieredSources = episode.sources
            .groupBy { FrenchMangaPackedPlayerParser.languageTier(it.language) }
            .toSortedMap()

        for ((_, tierSources) in tieredSources) {
            val orderedSources = tierSources.sortedBy { FrenchMangaPackedPlayerParser.hostPriority(it.url) }
            val resolvedAll = mutableListOf<ResolvedMangaLink>()
            orderedSources.chunked(SOURCE_BATCH_SIZE).forEach { batch ->
                resolvedAll += coroutineScope {
                    batch.map { source ->
                        async {
                            val collected = mutableListOf<ResolvedMangaLink>()
                            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                val directOk = loadSource(source, payload.pageUrl) { link ->
                                    collected += ResolvedMangaLink(source.language, link)
                                }
                                val lowerUrl = source.url.lowercase()
                                if (!directOk && "fsvid." !in lowerUrl && "vidzy." !in lowerUrl) {
                                    val extracted = mutableListOf<ExtractorLink>()
                                    runCatching {
                                        loadExtractor(source.url, payload.pageUrl, subtitleCallback) { link ->
                                            if (FrenchMangaPackedPlayerParser.isPlayableMediaUrl(link.url)) {
                                                extracted += link
                                            }
                                        }
                                    }
                                    for (link in extracted) {
                                        val verified = FrenchMangaStreamVerifier.verifyLink(link) ?: continue
                                        collected += ResolvedMangaLink(
                                            source.language,
                                            withLanguage(link, source.language, verified.quality)
                                        )
                                    }
                                }
                            }
                            collected
                        }
                    }.awaitAll().flatten()
                }
            }

            val sorted = resolvedAll
                .distinctBy { "${it.language}|${it.link.url}" }
                .sortedWith(
                    compareBy<ResolvedMangaLink> { FrenchMangaPackedPlayerParser.languagePriority(it.language) }
                        .thenByDescending { it.link.quality }
                        .thenBy { it.link.name }
                )
            if (sorted.isNotEmpty()) {
                sorted.forEach { callback(it.link) }
                return true
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun withLanguage(
        link: ExtractorLink,
        language: String,
        verifiedQuality: Int = Qualities.Unknown.value
    ): ExtractorLink {
        val label = language.uppercase()
        val finalQuality = maxOf(
            link.quality.takeIf { it > Qualities.Unknown.value }
                ?: FrenchMangaPackedPlayerParser.inferQuality(link.url)
                ?: link.quality,
            verifiedQuality
        )
        val displayName = if (link.name.startsWith(label, ignoreCase = true)) link.name else "$label · ${link.name}"
        if (displayName == link.name && finalQuality == link.quality) return link
        return ExtractorLink(
            source = name,
            name = displayName,
            url = link.url,
            referer = link.referer,
            quality = finalQuality,
            headers = link.headers,
            extractorData = link.extractorData,
            type = link.type,
            audioTracks = link.audioTracks
        )
    }

    private fun toSearchResponse(card: FrenchMangaCard): SearchResponse {
        return if (card.isMovie) {
            newMovieSearchResponse(card.title, card.url, TvType.AnimeMovie) {
                posterUrl = card.posterUrl
                year = card.year
            }
        } else {
            newTvSeriesSearchResponse(card.title, card.url, TvType.Anime) {
                posterUrl = card.posterUrl
                year = card.year
            }
        }
    }

    private suspend fun LoadResponse.applyMetadata(
        detail: FrenchMangaDetail,
        related: List<SearchResponse>
    ) {
        posterUrl = detail.posterUrl
        backgroundPosterUrl = detail.backdropUrl
        plot = detail.plot
        year = detail.year
        tags = (detail.genres + detail.tags).distinct()
        if (related.isNotEmpty()) recommendations = related
        addActors(detail.actors.map { actor -> Actor(actor.name, actor.imageUrl) to actor.role })
        addTrailer(detail.trailerId?.let { "https://www.youtube.com/watch?v=$it" })
    }

    private data class SeasonPage(
        val number: Int,
        val url: String,
        val posterUrl: String?,
        val episodes: List<FrenchMangaEpisodeData>
    )

    private suspend fun discoverSeasons(
        detail: FrenchMangaDetail,
        selectedUrl: String
    ): List<FrenchMangaSeason> {
        val apiUrl = "$mainUrl/engine/ajax/get_seasons.php" +
            "?serie_tag=${encode(detail.tags.joinToString(","))}" +
            "&news_id=${encode(detail.newsId)}" +
            "&title_base=${encode(detail.title)}"
        val apiSeasons = FrenchMangaParser.seasons(getSiteText(apiUrl, selectedUrl), "$mainUrl/")
        val fallbackSeasons = if (apiSeasons.isEmpty()) {
            try {
                FrenchMangaParser.searchResults(searchDocument(detail.title))
                    .filter { !it.isMovie && it.season != null && FrenchMangaParser.sameSeries(it.title, detail.title) }
                    .map {
                        FrenchMangaSeason(
                            it.season!!,
                            it.url,
                            it.posterUrl,
                            it.year,
                            FrenchMangaParser.newsId(it.url)
                        )
                    }
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
        val current = FrenchMangaSeason(
            detail.season ?: 1,
            selectedUrl,
            detail.posterUrl,
            detail.year,
            detail.newsId
        )
        return (apiSeasons + fallbackSeasons + current)
            .groupBy { it.number }
            .map { (_, entries) -> entries.firstOrNull { sameUrl(it.url, selectedUrl) } ?: entries.first() }
            .sortedBy { it.number }
    }

    private suspend fun loadAllSeasons(
        seasons: List<FrenchMangaSeason>,
        selectedDocument: Document,
        selectedDetail: FrenchMangaDetail
    ): List<SeasonPage> {
        val selectedUrl = selectedDocument.baseUri()
        return seasons.chunked(SEASON_BATCH_SIZE).flatMap { batch ->
            coroutineScope {
                batch.map { season ->
                    async {
                        runCatching {
                            val isSelected = sameUrl(season.url, selectedUrl)
                            val document = if (isSelected || season.newsId != null) null else getSiteDocument(season.url)
                            val detail = when {
                                isSelected -> selectedDetail
                                document != null -> FrenchMangaParser.detail(document)
                                else -> null
                            }
                            val newsId = season.newsId
                                ?: detail?.newsId?.takeIf { it.isNotBlank() }
                                ?: FrenchMangaParser.newsId(season.url)
                                ?: return@runCatching null
                            val pageUrl = if (isSelected) selectedDocument.baseUri() else document?.baseUri() ?: season.url
                            SeasonPage(
                                number = season.number,
                                url = pageUrl,
                                posterUrl = season.posterUrl ?: detail?.posterUrl,
                                episodes = loadEpisodeData(pageUrl, newsId)
                            )
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
        }.sortedBy { it.number }
    }

    private suspend fun loadEpisodeData(pageUrl: String, newsId: String): List<FrenchMangaEpisodeData> {
        val now = System.currentTimeMillis()
        episodeCache[newsId]?.takeIf { it.expiresAt > now }?.let { return it.items }
        val apiUrl = "$mainUrl/engine/ajax/manga_episodes_api.php?id=${encode(newsId)}"
        val items = FrenchMangaParser.episodes(getSiteText(apiUrl, pageUrl))
        if (items.isNotEmpty()) {
            if (episodeCache.size >= MAX_EPISODE_CACHE) episodeCache.clear()
            episodeCache[newsId] = EpisodeCacheEntry(items, now + EPISODE_CACHE_MS)
        }
        return items
    }

    private suspend fun loadSource(
        source: FrenchMangaSource,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourceOrigin = origin(source.url)
        val html = runCatching {
            val first = app.get(source.url, headers = browserHeaders, referer = pageUrl, timeout = 8L)
            when {
                first.isSuccessful -> first.text
                first.code == 403 && sourceOrigin != null -> app.get(
                    source.url,
                    headers = browserHeaders,
                    referer = "$sourceOrigin/",
                    timeout = 8L
                ).takeIf { it.isSuccessful }?.text
                else -> null
            }
        }.getOrNull() ?: return false
        val mediaUrls = FrenchMangaPackedPlayerParser.extractMediaUrls(html)
        var emitted = false
        for (mediaUrl in mediaUrls) {
            val streamHeaders = browserHeaders + mapOf("Referer" to source.url) +
                sourceOrigin?.let { mapOf("Origin" to it) }.orEmpty()
            val isHls = mediaUrl.contains(".m3u8", ignoreCase = true)
            val verified = FrenchMangaStreamVerifier.verifyUrl(mediaUrl, streamHeaders, isHls) ?: continue
            val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val host = source.host.replaceFirstChar { it.uppercase() }
            callback(newExtractorLink(name, "${source.language} · $host", mediaUrl, type) {
                referer = source.url
                headers = streamHeaders
                this.quality = verified.quality
            })
            emitted = true
        }
        return emitted
    }

    private suspend fun searchDocument(query: String): Document {
        var lastError: Throwable? = null
        for (base in (listOf(mainUrl) + mirrors).distinct()) {
            val response = runCatching {
                app.post(
                    "$base/engine/ajax/search.php",
                    data = mapOf("query" to query, "page" to "1"),
                    headers = browserHeaders,
                    referer = "$base/",
                    timeout = 10L
                )
            }.onFailure { lastError = it }.getOrNull() ?: continue
            if (!response.isSuccessful) continue
            val document = Jsoup.parse(response.text, "$base/")
            mainUrl = base
            return document
        }
        throw ErrorLoadingException(lastError?.message ?: "Recherche French-Manga indisponible")
    }

    private suspend fun getSiteDocument(url: String): Document {
        for ((candidate, base) in mirrorCandidates(url)) {
            val response = runCatching {
                app.get(candidate, headers = browserHeaders, timeout = 10L)
            }.getOrNull() ?: continue
            if (!response.isSuccessful) continue
            val document = response.document
            document.setBaseUri(candidate)
            if (document.select("div.short, #manga-data").isNotEmpty()) {
                mainUrl = base
                return document
            }
        }
        throw ErrorLoadingException("French-Manga est indisponible")
    }

    private suspend fun getSiteText(url: String, referer: String): String {
        for ((candidate, base) in mirrorCandidates(url)) {
            val response = runCatching {
                app.get(
                    candidate,
                    headers = browserHeaders,
                    referer = replaceOrigin(referer, base),
                    timeout = 10L
                )
            }.getOrNull() ?: continue
            if (response.isSuccessful && response.text.isNotBlank()) {
                mainUrl = base
                return response.text
            }
        }
        return "{}"
    }

    private fun mirrorCandidates(url: String): List<Pair<String, String>> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url to mainUrl)
        val sourceOrigin = "${uri.scheme}://${uri.authority}"
        val knownHosts = mirrors.mapNotNull { runCatching { URI(it).host }.getOrNull() }
        if (uri.host !in knownHosts) return listOf(url to sourceOrigin)
        val suffix = uri.rawPath.orEmpty() + uri.rawQuery?.let { "?$it" }.orEmpty()
        return (listOf(sourceOrigin, mainUrl) + mirrors).distinct().map { "$it$suffix" to it }
    }

    private fun categoryUrl(path: String, page: Int): String {
        return if (page <= 1) "$mainUrl$path" else "$mainUrl${path.trimEnd('/')}/page/$page/"
    }

    private fun sameUrl(left: String, right: String): Boolean {
        return left.trimEnd('/') == right.trimEnd('/')
    }

    private fun replaceOrigin(url: String, newOrigin: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$newOrigin$path$query"
    }

    private fun origin(url: String): String? {
        return runCatching { URI(url) }.getOrNull()?.let { "${it.scheme}://${it.authority}" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val PAGE_SIZE = 18
        const val SEASON_BATCH_SIZE = 4
        const val SOURCE_BATCH_SIZE = 6
        const val SOURCE_TIMEOUT_MS = 9_000L
        const val EPISODE_CACHE_MS = 5 * 60 * 1000L
        const val MAX_EPISODE_CACHE = 80
    }
}
