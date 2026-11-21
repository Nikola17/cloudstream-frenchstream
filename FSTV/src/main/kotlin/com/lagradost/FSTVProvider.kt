package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI

/**
 * French-Stream TV : chaînes live + événements sportifs.
 * Les événements et matchs viennent de BZH-Sport (bzzh.lat, ex Volkastream / OhQuelbut) ;
 * les chaînes TV viennent de WITV (witv.online, witv.channel, …).
 */
class FSTVProvider : MainAPI() {
    override var mainUrl = WitvParser.knownBases.first()
    override var name = "French-Stream TV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Live)
    override var sequentialMainPage = false
    override val hasDownloadSupport = false

    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    private val bzhSport = BzhSportClient()
    private val witv = WitvClient(bzhSport)

    // Sections numérotées : l'ordre affiché reste 1) matchs BZH-Sport, 2) chaînes WITV,
    // même si l'app trie les lignes par nom.
    override val mainPage = listOf(
        MainPageData(name = "01. 🔴 Matchs en direct", data = EVENTS_LIVE, horizontalImages = true),
        MainPageData(name = "02. Matchs aujourd'hui", data = EVENTS_TODAY, horizontalImages = true),
        MainPageData(name = "03. Matchs demain", data = EVENTS_TOMORROW, horizontalImages = true),
        MainPageData(name = "04. 📅 Matchs à venir", data = EVENTS_UPCOMING, horizontalImages = true),
        MainPageData(name = "05. 📺 Toutes les chaînes", data = WITV_ALL, horizontalImages = true)
    ) + WitvParser.channelCategories.mapIndexed { index, category ->
        MainPageData(
            name = "%02d. %s".format(index + 6, category.label),
            data = "${WITV_PREFIX}${category.slug}",
            horizontalImages = true
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)

        if (request.data.startsWith(EVENTS_PREFIX)) {
            val events = eventsFor(request.data).map(::toEventSearchResponse)
            return newHomePageResponse(request, events, hasNext = false)
        }

        val channels = if (request.data == WITV_ALL) {
            witv.channels()
        } else {
            witv.categoryChannels(request.data.removePrefix(WITV_PREFIX))
        }
        return newHomePageResponse(request, channels.map(::toSearchResponse), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val events = runCatching { bzhSport.events() }.getOrNull().orEmpty()
            .filter { event ->
                val searchable = listOfNotNull(
                    event.title,
                    event.competition,
                    BzhSportParser.sportLabels[event.sport],
                    event.sport
                ).joinToString(" ")
                normalize(searchable).contains(normalizedQuery)
            }
            .let(BzhSportParser::ranked)
            .map(::toEventSearchResponse)

        val channels = witv.channels()
            .filter { normalize(it.name).contains(normalizedQuery) }
            .map(::toSearchResponse)
        return channels + events
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query).take(20)
    }

    override suspend fun load(url: String): LoadResponse {
        if (bzhSport.isSiteUrl(url)) return loadBzhPage(url)
        if (witv.isSiteUrl(url)) return loadWitvPage(url)
        throw ErrorLoadingException("Chaîne TV indisponible")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = FSTVParser.parseLinkData(data)

        if (bzhSport.isSiteUrl(linkData.url)) return loadBzhLinks(linkData.url, callback)

        if (witv.isSiteUrl(linkData.url)) {
            val label = linkData.name ?: runCatching { witv.pageTitle(linkData.url) }.getOrNull() ?: "Chaîne TV"
            val found = AtomicBoolean(false)
            withTimeoutOrNull(WITV_TIMEOUT_MS) {
                runCatching {
                    witv.streamsForPage(linkData.url, label) { stream ->
                        if (emitStreams(WITV_SOURCE_NAME, listOf(stream), callback)) {
                            found.set(true)
                        }
                    }
                }
            }
            if (found.get()) return true
            // Secours BZH-Sport si aucun flux WITV valide n'a répondu pour cette chaîne.
            val bzhFallback = withTimeoutOrNull(BZH_TIMEOUT_MS) {
                runCatching { bzhSport.channelStreams(label) }.getOrNull()
            }.orEmpty()
            return emitStreams(BzhSportClient.SOURCE_NAME, bzhFallback, callback)
        }

        // Anciens favoris FSTV : l'URL d'origine ne répond plus, on retente par nom de chaîne.
        val name = linkData.name ?: return false
        val bzh = withTimeoutOrNull(BZH_TIMEOUT_MS) {
            runCatching { bzhSport.channelStreams(name) }.getOrNull()
        }.orEmpty()
        if (emitStreams(BzhSportClient.SOURCE_NAME, bzh, callback)) return true
        val witvStreams = withTimeoutOrNull(WITV_TIMEOUT_MS) {
            runCatching { witv.channelStreams(name) }.getOrNull()
        }.orEmpty()
        return emitStreams(WITV_SOURCE_NAME, witvStreams, callback)
    }

    /** Matchs et événements BZH-Sport : envoi des flux vérifiés dès qu'ils sont trouvés. */
    private suspend fun loadBzhLinks(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val found = AtomicBoolean(false)
        withTimeoutOrNull(BZH_EVENT_TIMEOUT_MS) {
            runCatching {
                bzhSport.streams(url) { stream ->
                    if (emitStreams(BzhSportClient.SOURCE_NAME, listOf(stream), callback)) {
                        found.set(true)
                    }
                }
            }
        }
        return found.get()
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

        return newLiveStreamLoadResponse(title, pageUrl, FSTVParser.linkData(pageUrl, title)) {
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

    private suspend fun loadWitvPage(url: String): LoadResponse {
        val channel = runCatching { witv.findChannel(url) }.getOrNull()
        // Si la liste n'avait pas le logo, on le cherche sur la page de la chaîne.
        val info = if (channel == null || channel.posterUrl == null) {
            runCatching { witv.pageInfo(url) }.getOrNull()
        } else null
        val name = channel?.name ?: info?.first ?: "Chaîne TV"
        val poster = channel?.posterUrl ?: info?.second

        return newLiveStreamLoadResponse(name, url, FSTVParser.linkData(url, name)) {
            posterUrl = poster
            posterHeaders = browserHeaders
            plot = "Chaîne en direct via WITV"
            tags = listOf("TV en direct")
        }
    }

    private suspend fun emitStreams(sourceName: String, streams: List<BzhStream>, callback: (ExtractorLink) -> Unit): Boolean {
        if (streams.isEmpty()) return false
        val verified = coroutineScope {
            streams.map { stream ->
                async {
                    val streamHeaders = browserHeaders + listOfNotNull(
                        "Referer" to stream.referer,
                        origin(stream.referer)?.let { "Origin" to it }
                    )
                    val check = FSTVStreamVerifier.verifyHls(stream.url, streamHeaders) ?: return@async null
                    Triple(stream, streamHeaders, check.quality)
                }
            }.awaitAll().filterNotNull()
        }.sortedByDescending { it.third }

        verified.forEach { (stream, streamHeaders, verifiedQuality) ->
            callback(
                newExtractorLink(
                    sourceName,
                    "$sourceName · ${stream.label}",
                    stream.url,
                    ExtractorLinkType.M3U8
                ) {
                    referer = stream.referer
                    headers = streamHeaders
                    quality = verifiedQuality
                }
            )
        }
        return verified.isNotEmpty()
    }

    private suspend fun eventsFor(section: String): List<BzhEvent> {
        val events = runCatching { bzhSport.events() }.getOrNull().orEmpty()
        val selected = when (section) {
            EVENTS_LIVE -> events.filter { it.isLive }
            EVENTS_TODAY -> events.filter { !it.isLive && it.day == BzhEventDay.TODAY }
            EVENTS_TOMORROW -> events.filter { it.day == BzhEventDay.TOMORROW }
            EVENTS_UPCOMING -> events.filter { it.day == BzhEventDay.LATER }
            else -> emptyList()
        }
        return BzhSportParser.ranked(selected).take(MAX_EVENTS_PER_SECTION)
    }

    private fun toEventSearchResponse(event: BzhEvent): SearchResponse {
        val name = when {
            event.isLive && event.time != null -> "🔴 ${event.time} · ${event.title}"
            event.isLive -> "🔴 ${event.title}"
            event.time != null -> "${event.time} · ${event.title}"
            else -> event.title
        }
        return newLiveSearchResponse(name, event.url) {
            posterUrl = BzhSportParser.posterUrl(event.sport)
            posterHeaders = browserHeaders
        }
    }

    private fun toSearchResponse(channel: WitvChannel): SearchResponse {
        return newLiveSearchResponse(channel.name, channel.url) {
            posterUrl = channel.posterUrl
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

    private fun origin(url: String): String? {
        return runCatching { URI(url) }.getOrNull()?.let { uri ->
            "${uri.scheme}://${uri.authority}"
        }
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()
    }

    companion object {
        private const val EVENTS_PREFIX = "bzh-events:"
        private const val EVENTS_LIVE = "${EVENTS_PREFIX}live"
        private const val EVENTS_TODAY = "${EVENTS_PREFIX}today"
        private const val EVENTS_TOMORROW = "${EVENTS_PREFIX}tomorrow"
        private const val EVENTS_UPCOMING = "${EVENTS_PREFIX}upcoming"
        private const val WITV_PREFIX = "witv:"
        private const val WITV_ALL = "${WITV_PREFIX}all"
        const val WITV_SOURCE_NAME = "WITV"
        private const val MAX_EVENTS_PER_SECTION = 60
        private const val BZH_TIMEOUT_MS = 15_000L
        private const val BZH_EVENT_TIMEOUT_MS = 45_000L
        private const val WITV_TIMEOUT_MS = 30_000L
    }
}
