package com.lagradost

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI

/**
 * WITV : source de chaînes live pour French-Stream TV. Le domaine officiel (`witv.channel`)
 * est interrogé en priorité, puis les miroirs de secours ; le premier catalogue valide l'emporte.
 */
internal class WitvClient(private val player: BzhSportClient) {

    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)
    private val mutex = Mutex()

    @Volatile
    var baseUrl: String = WitvParser.knownBases.first()
        private set

    @Volatile
    private var cachedChannels: Pair<Long, List<WitvChannel>>? = null

    fun isSiteUrl(url: String): Boolean {
        val urlHost = host(url) ?: return false
        return WitvParser.knownBases.any { base -> host(base)?.equals(urlHost, ignoreCase = true) == true }
    }

    /** Catalogue complet des chaînes (pagination incluse), mis en cache quelques minutes. */
    suspend fun channels(): List<WitvChannel> = mutex.withLock {
        cachedChannels?.takeIf { System.currentTimeMillis() - it.first < CHANNELS_CACHE_MS }?.let { return it.second }
        val bases = (listOf(baseUrl) + WitvParser.knownBases).distinct()
        for (base in bases) {
            val all = collectChannels(WitvParser.channelsUrl(base))
            if (all.isEmpty()) continue
            baseUrl = base
            cachedChannels = System.currentTimeMillis() to all
            return all
        }
        return cachedChannels?.second.orEmpty()
    }

    /** Chaînes d'une catégorie WITV (sport, cinema, generaliste…). */
    suspend fun categoryChannels(category: String): List<WitvChannel> {
        val bases = (listOf(baseUrl) + WitvParser.knownBases).distinct()
        for (base in bases) {
            val channels = collectChannels(WitvParser.categoryUrl(base, category), paginate = true)
            if (channels.isNotEmpty()) {
                baseUrl = base
                return channels
            }
        }
        return emptyList()
    }

    suspend fun findChannel(url: String): WitvChannel? =
        WitvParser.findChannelByUrl(channels(), url)

    suspend fun pageTitle(url: String): String? {
        val document = fetchDocument(url) ?: return null
        return WitvParser.pageTitle(document)
    }

    /** Titre + logo d'une page chaîne, en un seul chargement. */
    suspend fun pageInfo(url: String): Pair<String?, String?>? {
        val document = fetchDocument(url) ?: return null
        return WitvParser.pageTitle(document) to WitvParser.pagePoster(document)
    }

    /**
     * Résout les serveurs embarqués dans la page d'une chaîne jusqu'aux flux HLS ;
     * [onStream] reçoit chaque flux dès qu'il est trouvé.
     */
    suspend fun streamsForPage(
        pageUrl: String,
        label: String,
        onStream: suspend (BzhStream) -> Unit = {}
    ): List<BzhStream> {
        val document = fetchDocument(pageUrl) ?: return emptyList()
        val links = WitvParser.playerLinks(document).take(MAX_PLAYERS)
        if (links.isEmpty()) return emptyList()
        val pageReferer = document.baseUri().ifBlank { pageUrl }
        val found = mutableListOf<BzhStream>()
        val emitMutex = Mutex()

        coroutineScope {
            links.forEachIndexed { index, link ->
                launch {
                    val resolved = resolveWitvLink(link, pageReferer)
                        .map { (url, referer) -> BzhStream("$label · Source ${index + 1}", url, referer) }
                    emitMutex.withLock {
                        resolved.filter { s -> found.none { it.url == s.url } }.forEach { stream ->
                            found.add(stream)
                            onStream(stream)
                        }
                    }
                }
            }
        }
        return found
    }

    /** Rapprochement par nom de chaîne (secours pour les anciens favoris FSTV). */
    suspend fun channelStreams(channelName: String): List<BzhStream> {
        val channel = WitvParser.findChannel(channels(), channelName) ?: return emptyList()
        return streamsForPage(channel.url, channel.name)
    }

    /**
     * Résout un lien lecteur WITV :
     * - si c'est un lecteur interne `/player/…`, on l'ouvre avec les en-têtes iframe same-origin
     *   puis on extrait soit le flux HLS direct `_wtPayload`, soit les miroirs (`server-btn` / iframe) ;
     * - sinon on délègue directement au résolveur d'embeds + WebView en dernier recours.
     */
    private suspend fun resolveWitvLink(link: String, pageReferer: String): List<Pair<String, String>> {
        if (link.contains(".m3u8", ignoreCase = true)) {
            return if (link.contains("vc=notice", ignoreCase = true)) emptyList() else listOf(link to pageReferer)
        }

        if (isSiteUrl(link) && link.contains("/player/", ignoreCase = true)) {
            val fetched = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    val response = app.get(
                        link,
                        headers = browserHeaders + mapOf(
                            "Referer" to pageReferer,
                            "Sec-Fetch-Dest" to "iframe",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "same-origin"
                        ),
                        timeout = REQUEST_TIMEOUT
                    )
                    if (!response.isSuccessful) null else response.text to response.url.ifBlank { link }
                }.getOrNull()
            }
            if (fetched != null) {
                val (html, finalUrl) = fetched
                val extracted = WitvParser.extractPlayerStreams(html, finalUrl)
                if (extracted.directHls.isNotEmpty()) {
                    return extracted.directHls.map { it to finalUrl }
                }
                if (extracted.embedUrls.isNotEmpty()) {
                    val mirrorStreams = coroutineScope {
                        extracted.embedUrls.take(MAX_MIRRORS_PER_SOURCE).map { embedUrl ->
                            async {
                                runCatching { player.resolveEmbed(embedUrl, finalUrl) }.getOrNull().orEmpty()
                            }
                        }.awaitAll().flatten()
                    }
                    if (mirrorStreams.isNotEmpty()) return mirrorStreams
                    // Secours WebView sur le premier miroir si aucun m3u8 n'était lisible en HTML statique.
                    val browser = runCatching {
                        player.browserEmbed(extracted.embedUrls.first(), finalUrl)
                    }.getOrNull()
                    if (browser != null) return listOf(browser)
                }
            }
        }

        return runCatching { player.resolveEmbed(link, pageReferer) }.getOrNull().orEmpty()
            .ifEmpty { listOfNotNull(runCatching { player.browserEmbed(link, pageReferer) }.getOrNull()) }
    }

    private suspend fun collectChannels(listUrl: String, paginate: Boolean = true): List<WitvChannel> {
        var currentDoc = fetchDocument(listUrl) ?: return emptyList()
        val first = WitvParser.channels(currentDoc)
        if (first.isEmpty()) return emptyList()
        if (!paginate) return first

        val all = mutableListOf<WitvChannel>().apply { addAll(first) }
        for (page in 2..MAX_PAGES) {
            val nextUrl = WitvParser.nextPageUrl(currentDoc, listUrl, page)
                ?: if (listUrl.contains("witv.online", ignoreCase = true)) "$listUrl?page=$page" else break
            val nextDoc = fetchDocument(nextUrl) ?: break
            val added = WitvParser.channels(nextDoc).filter { c -> all.none { it.url == c.url } }
            if (added.isEmpty()) break
            all += added
            currentDoc = nextDoc
        }
        return all
    }

    private suspend fun fetchDocument(url: String): Document? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val response = app.get(url, headers = browserHeaders, timeout = REQUEST_TIMEOUT)
                if (!response.isSuccessful) return@runCatching null
                response.document.also { it.setBaseUri(response.url.ifBlank { url }) }
                    .takeIf(WitvParser::isSiteDocument)
            }.getOrNull()
        }
    }

    private fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()

    private companion object {
        const val REQUEST_TIMEOUT = 8L
        const val REQUEST_TIMEOUT_MS = 9_000L
        const val CHANNELS_CACHE_MS = 5 * 60 * 1000L
        const val MAX_PAGES = 5
        const val MAX_PLAYERS = 4
        const val MAX_MIRRORS_PER_SOURCE = 4
    }
}
