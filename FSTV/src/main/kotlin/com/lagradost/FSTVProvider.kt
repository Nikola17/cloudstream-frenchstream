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
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

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
    private val bzhSport = BzhSportClient()

    override val mainPage = listOf(
        MainPageData(name = "🔴 Événements sport en direct", data = EVENTS_LIVE, horizontalImages = true),
        MainPageData(name = "Événements sport aujourd'hui", data = EVENTS_TODAY, horizontalImages = true),
        MainPageData(name = "Événements sport demain", data = EVENTS_TOMORROW, horizontalImages = true)
    ) + FSTVParser.categories.map {
        MainPageData(name = it.label, data = it.slug, horizontalImages = true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        if (request.data.startsWith(EVENTS_PREFIX)) {
            val events = eventsFor(request.data).map(::toEventSearchResponse)
            return newHomePageResponse(request, events, hasNext = false)
        }
        val document = getFstvDocument(categoryUrl(request.data))
        val channels = FSTVParser.channels(document).map(::toSearchResponse)
        return newHomePageResponse(request, channels, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val events = runCatching { bzhSport.events() }.getOrNull().orEmpty()
            .filter { event ->
                normalize(listOfNotNull(event.title, event.competition).joinToString(" "))
                    .contains(normalizedQuery)
            }
            .let(BzhSportParser::ranked)
            .map(::toEventSearchResponse)

        val channels = FSTVParser.categories.chunked(3).flatMap { batch ->
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
        return channels + events
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query).take(20)
    }

    override suspend fun load(url: String): LoadResponse {
        if (bzhSport.isSiteUrl(url)) return loadBzhPage(url)

        val document = getFstvDocument(url)
        val config = FSTVParser.playerConfig(document)
            ?: throw ErrorLoadingException("Chaîne TV indisponible")
        val program = loadProgram(config.name, config.streamUrl)
        val linkData = FSTVParser.linkData(document.baseUri(), config.name)

        return newLiveStreamLoadResponse(config.name, document.baseUri(), linkData) {
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
        val linkData = FSTVParser.parseLinkData(data)
        if (bzhSport.isSiteUrl(linkData.url)) {
            val found = AtomicBoolean(false)
            withTimeoutOrNull(BZH_EVENT_TIMEOUT_MS) {
                runCatching {
                    bzhSport.streams(linkData.url) { stream ->
                        emitBzhStreams(listOf(stream), callback)
                        found.set(true)
                    }
                }
            }
            return found.get()
        }

        return coroutineScope {
            // BZH-Sport sert de secours quand les flux French-Stream TV ne fonctionnent pas.
            val knownFallback = linkData.name?.let { channelName ->
                async { bzhStreams { bzhSport.channelStreams(channelName) } }
            }
            val fstvChannel = loadFstvLinks(linkData.url, callback)
            val fallback = knownFallback?.await()
                ?: fstvChannel?.let { channelName -> bzhStreams { bzhSport.channelStreams(channelName) } }
                    .orEmpty()
            val hasFallback = emitBzhStreams(fallback, callback)
            fstvChannel != null || hasFallback
        }
    }

    /** Envoie les liens French-Stream TV et renvoie le nom de la chaîne, ou null si aucun lien. */
    private suspend fun loadFstvLinks(url: String, callback: (ExtractorLink) -> Unit): String? {
        val document = runCatching { getFstvDocument(url) }.getOrNull() ?: return null
        val config = FSTVParser.playerConfig(document) ?: return null
        val origin = origin(config.streamUrl) ?: return null
        val streamHeaders = browserHeaders + mapOf(
            "Referer" to document.baseUri(),
            "Origin" to origin
        )

        val (primaryQuality, alternatives) = coroutineScope {
            val qualityRequest = async {
                withTimeoutOrNull(4_000L) {
                    runCatching {
                        FSTVParser.highestHlsQuality(
                            app.get(config.streamUrl, headers = streamHeaders, timeout = 4L).text
                        )
                    }.getOrNull()
                } ?: Qualities.Unknown.value
            }
            val sourceRequest = async { loadAlternativeSources(config.name, origin) }
            qualityRequest.await() to sourceRequest.await()
        }

        FSTVParser.playbackSources(config, primaryQuality, alternatives).forEach { source ->
            callback(newExtractorLink(name, source.label, source.url, ExtractorLinkType.M3U8) {
                referer = document.baseUri()
                headers = streamHeaders
                quality = source.quality
            })
        }
        return config.name
    }

    private suspend fun bzhStreams(block: suspend () -> List<BzhStream>): List<BzhStream> {
        return withTimeoutOrNull(BZH_TIMEOUT_MS) { runCatching { block() }.getOrNull() }.orEmpty()
    }

    private suspend fun emitBzhStreams(streams: List<BzhStream>, callback: (ExtractorLink) -> Unit): Boolean {
        streams.forEach { stream ->
            val streamHeaders = browserHeaders + listOfNotNull(
                "Referer" to stream.referer,
                origin(stream.referer)?.let { "Origin" to it }
            )
            callback(
                newExtractorLink(
                    BzhSportClient.SOURCE_NAME,
                    "${BzhSportClient.SOURCE_NAME} · ${stream.label}",
                    stream.url,
                    ExtractorLinkType.M3U8
                ) {
                    referer = stream.referer
                    headers = streamHeaders
                    quality = Qualities.Unknown.value
                }
            )
        }
        return streams.isNotEmpty()
    }

    private suspend fun loadBzhPage(url: String): LoadResponse {
        // Les infos de l'accueil suffisent pour la fiche : la page de l'événement, lente à
        // charger, n'est récupérée qu'au lancement de la lecture.
        val event = runCatching { bzhSport.events() }.getOrNull().orEmpty()
            .firstOrNull { samePath(it.url, url) }
        val document = if (event == null) {
            bzhSport.page(url) ?: throw ErrorLoadingException("BZH-Sport est indisponible")
        } else null
        val pageUrl = event?.url ?: document?.baseUri() ?: url
        val title = event?.title
            ?: document?.let(BzhSportParser::pageTitle)
            ?: BzhSportClient.SOURCE_NAME

        return newLiveStreamLoadResponse(title, pageUrl, pageUrl) {
            posterUrl = event?.let { BzhSportParser.posterUrl(it.sport) }
            posterHeaders = browserHeaders
            plot = event?.let(::eventDescription)
                ?: "Chaîne en direct via ${BzhSportClient.SOURCE_NAME}"
            tags = listOfNotNull(
                "Sport en direct",
                event?.sport?.let(BzhSportParser.sportLabels::get),
                event?.competition
            ).distinct()
        }
    }

    private suspend fun eventsFor(section: String): List<BzhEvent> {
        val events = runCatching { bzhSport.events() }.getOrNull().orEmpty()
        val selected = when (section) {
            EVENTS_LIVE -> events.filter { it.isLive }
            EVENTS_TODAY -> events.filter { !it.isLive && it.day == BzhEventDay.TODAY }
            EVENTS_TOMORROW -> events.filter { it.day == BzhEventDay.TOMORROW }
            else -> emptyList()
        }
        return BzhSportParser.ranked(selected).take(MAX_EVENTS_PER_SECTION)
    }

    private fun toEventSearchResponse(event: BzhEvent): SearchResponse {
        val name = when {
            event.isLive -> "🔴 ${event.title}"
            event.time != null -> "${event.time} · ${event.title}"
            else -> event.title
        }
        return newLiveSearchResponse(name, event.url) {
            posterUrl = BzhSportParser.posterUrl(event.sport)
            posterHeaders = browserHeaders
        }
    }

    private fun eventDescription(event: BzhEvent): String {
        val schedule = listOfNotNull(event.dayLabel.takeIf { it.isNotBlank() }, event.time).joinToString(" à ")
        return buildList {
            add(if (event.isLive) "🔴 En direct" else "Programmé : $schedule")
            event.competition?.let { add(it) }
            if (!event.isLive) add("Les liens apparaissent généralement peu avant le début.")
        }.joinToString("\n")
    }

    private fun samePath(first: String, second: String): Boolean {
        val firstPath = runCatching { URI(first).rawPath }.getOrNull() ?: return false
        val secondPath = runCatching { URI(second).rawPath }.getOrNull() ?: return false
        return firstPath.equals(secondPath, ignoreCase = true)
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val streamHost = host(extractorLink.url) ?: return null
        val providerHost = host(mainUrl) ?: return null
        if (!streamHost.equals(providerHost, ignoreCase = true)) return null

        return Interceptor { chain ->
            val request = chain.request()
            if (request.url.host.equals(providerHost, ignoreCase = true)) {
                executeFstvRequest(chain, request)
            } else {
                chain.proceed(request)
            }
        }
    }

    private fun executeFstvRequest(chain: Interceptor.Chain, request: Request): Response {
        var response = chain.proceed(requestForAttempt(request))
        var proxyAttempts = 1
        if (isTransientGatewayError(response.code)) {
            FSTVParser.directSegmentUrl(request.url.toString())?.let { directUrl ->
                response.close()
                val directResponse = runCatching {
                    chain.proceed(request.newBuilder().url(directUrl).build())
                }.getOrNull()
                if (directResponse?.isSuccessful == true) return directResponse
                directResponse?.close()
                response = chain.proceed(requestForAttempt(request))
                proxyAttempts++
            }
        }
        while (isTransientGatewayError(response.code) && proxyAttempts < 3) {
            response.close()
            response = chain.proceed(requestForAttempt(request))
            proxyAttempts++
        }
        return patchPlaylistResponse(response)
    }

    private fun requestForAttempt(request: Request): Request {
        if (!FSTVParser.isProxiedMediaPlaylist(request.url.toString())) return request
        val freshUrl = request.url.newBuilder()
            .setQueryParameter("cs_no_cache", System.nanoTime().toString())
            .build()
        return request.newBuilder()
            .url(freshUrl)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
    }

    private fun isTransientGatewayError(code: Int): Boolean {
        return code == 502 || code == 503 || code == 504
    }

    private fun patchPlaylistResponse(response: Response): Response {
        val body = response.body
        val contentType = body.contentType()
        if (!contentType.toString().contains("mpegurl", ignoreCase = true)) return response
        val manifest = body.string()
        return response.newBuilder()
            .removeHeader("Content-Length")
            .body(FSTVParser.normalizeHlsManifest(manifest).toResponseBody(contentType))
            .build()
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

    private fun host(url: String): String? {
        return runCatching { URI(url).host }.getOrNull()
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

    companion object {
        private const val EVENTS_PREFIX = "bzh-events:"
        private const val EVENTS_LIVE = "${EVENTS_PREFIX}live"
        private const val EVENTS_TODAY = "${EVENTS_PREFIX}today"
        private const val EVENTS_TOMORROW = "${EVENTS_PREFIX}tomorrow"
        private const val MAX_EVENTS_PER_SECTION = 60
        private const val BZH_TIMEOUT_MS = 15_000L
        private const val BZH_EVENT_TIMEOUT_MS = 30_000L
    }
}
