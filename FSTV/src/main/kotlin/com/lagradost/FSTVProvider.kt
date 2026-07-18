package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer

class FSTVProvider : MainAPI() {
    override var mainUrl = "https://fstv.rest"
    override var name = "French-Stream TV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Live)
    override var sequentialMainPage = true
    override val hasDownloadSupport = false

    private val portalUrl = "https://fstream.info/"
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = FSTVParser.categories.map {
        MainPageData(name = it.label, data = it.slug, horizontalImages = true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        val document = getFstvDocument(categoryUrl(request.data))
        val channels = FSTVParser.channels(document).map(::toSearchResponse)
        return newHomePageResponse(request, channels, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return FSTVParser.categories.chunked(3).flatMap { batch ->
            coroutineScope {
                batch.map { category ->
                    async {
                        runCatching { getFstvDocument(categoryUrl(category.slug)) }
                            .getOrNull()
                            ?.let(FSTVParser::channels)
                            .orEmpty()
                    }
                }.awaitAll().flatten()
            }
        }.distinctBy { it.url }
            .filter { normalize(it.name).contains(normalizedQuery) }
            .map(::toSearchResponse)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query).take(20)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getFstvDocument(url)
        val config = FSTVParser.playerConfig(document)
            ?: throw ErrorLoadingException("Chaîne TV indisponible")
        val program = loadProgram(config.name, config.streamUrl)

        return newLiveStreamLoadResponse(config.name, document.baseUri(), document.baseUri()) {
            posterUrl = config.posterUrl
            posterHeaders = browserHeaders
            plot = program?.let(::programDescription)
            tags = listOfNotNull("TV en direct", program?.category)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { getFstvDocument(data) }.getOrNull() ?: return false
        val config = FSTVParser.playerConfig(document) ?: return false
        val origin = origin(config.streamUrl) ?: return false
        val streamHeaders = browserHeaders + mapOf(
            "Referer" to document.baseUri(),
            "Origin" to origin
        )

        val primaryQuality = withTimeoutOrNull(4_000L) {
            runCatching {
                FSTVParser.highestHlsQuality(
                    app.get(config.streamUrl, headers = streamHeaders, timeout = 4L).text
                )
            }.getOrNull()
        } ?: Qualities.Unknown.value

        callback(newExtractorLink(name, "Direct", config.streamUrl, ExtractorLinkType.M3U8) {
            referer = document.baseUri()
            headers = streamHeaders
            quality = primaryQuality
        })

        loadAlternativeSources(config.name, origin).take(12).forEach { source ->
            val sourceUrl = "$origin/live.php?id=${encode(source.id)}"
            callback(newExtractorLink(name, source.label.ifBlank { "Source TV" }, sourceUrl, ExtractorLinkType.M3U8) {
                referer = document.baseUri()
                headers = streamHeaders
                quality = source.quality.takeIf { it > 0 } ?: Qualities.Unknown.value
            })
        }
        return true
    }

    private fun toSearchResponse(channel: FSTVChannel): SearchResponse {
        return newLiveSearchResponse(channel.name, channel.url) {
            posterUrl = channel.posterUrl
            posterHeaders = browserHeaders
        }
    }

    private suspend fun loadProgram(channelName: String, streamUrl: String): FSTVProgram? {
        val origin = origin(streamUrl) ?: return null
        return withTimeoutOrNull(3_000L) {
            runCatching {
                FSTVParser.program(
                    app.get(
                        "$origin/live.php?epg=${encode(channelName)}",
                        headers = browserHeaders,
                        timeout = 3L
                    ).text
                )
            }.getOrNull()
        }
    }

    private suspend fun loadAlternativeSources(channelName: String, origin: String): List<FSTVSource> {
        return withTimeoutOrNull(4_000L) {
            runCatching {
                FSTVParser.sources(
                    app.get(
                        "$origin/live.php?q=1&sources=${encode(channelName)}",
                        headers = browserHeaders,
                        timeout = 4L
                    ).text
                )
            }.getOrNull()
        }.orEmpty()
    }

    private suspend fun getFstvDocument(url: String): Document {
        fetchValidDocument(url)?.let { return it }

        val discoveredBase = discoverCurrentBase()
            ?: throw ErrorLoadingException("French-Stream TV est indisponible")
        mainUrl = discoveredBase
        val retryUrl = replaceOrigin(url, discoveredBase)
        return fetchValidDocument(retryUrl)
            ?: throw ErrorLoadingException("French-Stream TV est indisponible")
    }

    private suspend fun fetchValidDocument(url: String): Document? {
        val response = runCatching {
            app.get(url, headers = browserHeaders, timeout = 10L)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val document = response.document
        document.setBaseUri(url)
        return document.takeIf(::isFstvDocument)
    }

    private suspend fun discoverCurrentBase(): String? {
        val portal = runCatching {
            app.get(portalUrl, headers = browserHeaders, timeout = 10L)
        }.getOrNull() ?: return null
        if (!portal.isSuccessful) return null
        return FSTVParser.portalTvBaseUrl(portal.document)
    }

    private fun isFstvDocument(document: Document): Boolean {
        val html = document.toString()
        return document.select("div.short").isNotEmpty() ||
            html.contains("window.FSTV_NAME", ignoreCase = true) ||
            html.contains("French Stream TV", ignoreCase = true)
    }

    private fun categoryUrl(slug: String): String {
        return "$mainUrl/index.php?category=${encode(slug)}&do=cat"
    }

    private fun replaceOrigin(url: String, newOrigin: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$newOrigin$path$query$fragment"
    }

    private fun origin(url: String): String? {
        return runCatching { URI(url) }.getOrNull()?.let { uri ->
            "${uri.scheme}://${uri.authority}"
        }
    }

    private fun programDescription(program: FSTVProgram): String {
        val remaining = program.remainingSeconds
            ?.let { seconds -> ((seconds + 59) / 60).takeIf { it > 0 } }
            ?.let { minutes -> " ($minutes min restantes)" }
            .orEmpty()
        return buildList {
            add("En direct : ${program.currentTitle}$remaining")
            program.category?.let { add(it) }
            program.nextTitle?.let { add("À suivre : $it") }
        }.joinToString("\n")
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
