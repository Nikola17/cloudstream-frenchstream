package com.lagradost

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI

internal data class BzhStream(
    val label: String,
    val url: String,
    val referer: String
)

/**
 * BZH-Sport (ex Volkastream / OhQuelbut) : source secondaire de French-Stream TV.
 * Le domaine change souvent : les miroirs connus sont interrogés en parallèle, puis l'adresse
 * à jour est récupérée sur rentry.co/alpagang ou sur le Chatango du site.
 */
internal class BzhSportClient {
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)
    private val homeMutex = Mutex()
    private val knownHosts = knownBases.mapNotNull(::host).toMutableSet()

    @Volatile
    var baseUrl: String = knownBases.first()
        private set

    @Volatile
    private var cachedHome: Pair<Long, Document>? = null
    private val pageCache = LinkedHashMap<String, Pair<Long, Document>>()

    fun isSiteUrl(url: String): Boolean {
        val urlHost = host(url) ?: return false
        return knownHosts.any { it.equals(urlHost, ignoreCase = true) } || BzhSportParser.isSitePath(url)
    }

    suspend fun events(): List<BzhEvent> = home()?.let(BzhSportParser::events).orEmpty()

    suspend fun channels(): List<BzhChannel> = home()?.let(BzhSportParser::channels).orEmpty()

    suspend fun page(url: String): Document? {
        val path = pathOf(url)
        if (path == "/") return home()
        cachedPage(url)?.let { return it }

        // Les pages d'événements ne sont servies que par le domaine principal : un miroir
        // peut rediriger vers son accueil, d'où la vérification du chemin final.
        val document = fetchValidDocument(url, path)
            ?: firstNonNull(knownBases.filterNot { url.startsWith(it) }) { base ->
                fetchValidDocument("$base$path", path)
            }
            ?: firstNonNull(discoverBases().filterNot { url.startsWith(it) || it in knownBases }) { base ->
                fetchValidDocument("$base$path", path)
            }
        document?.let { cachePage(url, it) }
        return document
    }

    /** Résout les lecteurs de la page ; [onStream] reçoit chaque flux dès qu'il est trouvé. */
    suspend fun streams(pageUrl: String, onStream: suspend (BzhStream) -> Unit = {}): List<BzhStream> {
        val document = page(pageUrl) ?: return emptyList()
        val embeds = BzhSportParser.embeds(document).take(MAX_EMBEDS)
        val found = mutableListOf<BzhStream>()
        val mutex = Mutex()
        coroutineScope {
            embeds.forEach { embed ->
                launch {
                    val streams = runCatching { resolve(embed.url, document.baseUri(), 0) }.getOrNull().orEmpty()
                        .map { (url, referer) -> BzhStream(embed.label, url, referer) }
                    mutex.withLock {
                        streams.filter { stream -> found.none { it.url == stream.url } }.forEach { stream ->
                            found.add(stream)
                            onStream(stream)
                        }
                    }
                }
            }
        }
        return found
    }

    suspend fun channelStreams(channelName: String): List<BzhStream> {
        val channel = BzhSportParser.findChannel(channels(), channelName) ?: return emptyList()
        return streams(channel.url).map { it.copy(label = channel.name) }
    }

    private suspend fun resolve(url: String, referer: String, depth: Int): List<Pair<String, String>> {
        val (html, pageUrl) = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val response = app.get(
                    url,
                    headers = browserHeaders + mapOf("Referer" to referer),
                    timeout = REQUEST_TIMEOUT
                )
                if (!response.isSuccessful) null else response.text to response.url.ifBlank { url }
            }.getOrNull()
        } ?: return emptyList()

        val hls = BzhSportParser.hlsUrls(html)
        if (hls.isNotEmpty()) return hls.map { it to pageUrl }
        if (depth >= MAX_FRAME_DEPTH) return emptyList()

        val frames = BzhSportParser.iframeUrls(html, pageUrl).take(MAX_FRAMES_PER_PAGE)
        return coroutineScope {
            frames.map { frame -> async { resolve(frame, pageUrl, depth + 1) } }.awaitAll().flatten()
        }
    }

    private suspend fun home(): Document? = homeMutex.withLock {
        cachedHome?.takeIf { System.currentTimeMillis() - it.first < HOME_CACHE_MS }?.let { return@withLock it.second }
        // Un domaine peut répondre très lentement : le premier miroir valide l'emporte.
        val bases = (listOf(baseUrl) + knownBases).distinct()
        val found = firstNonNull(bases) { base -> fetchValidDocument("$base/", "/")?.let { base to it } }
            ?: firstNonNull(discoverBases().filterNot { it in bases }) { base ->
                fetchValidDocument("$base/", "/")?.let { base to it }
            }
        found?.let { (base, document) ->
            useBase(base)
            cachedHome = System.currentTimeMillis() to document
        }
        found?.second ?: cachedHome?.second
    }

    private suspend fun <T> firstNonNull(items: List<String>, block: suspend (String) -> T?): T? {
        if (items.isEmpty()) return null
        return coroutineScope {
            val result = CompletableDeferred<T?>()
            val jobs = items.map { item -> launch { block(item)?.let { result.complete(it) } } }
            launch {
                jobs.joinAll()
                result.complete(null)
            }
            result.await().also { coroutineContext.cancelChildren() }
        }
    }

    private fun cachedPage(url: String): Document? = synchronized(pageCache) {
        pageCache[url]?.takeIf { System.currentTimeMillis() - it.first < PAGE_CACHE_MS }?.second
    }

    private fun cachePage(url: String, document: Document) = synchronized(pageCache) {
        pageCache[url] = System.currentTimeMillis() to document
        while (pageCache.size > MAX_CACHED_PAGES) pageCache.remove(pageCache.keys.first())
    }

    private suspend fun discoverBases(): List<String> = coroutineScope {
        val rentry = async {
            runCatching {
                app.get(RENTRY_URL, headers = browserHeaders, timeout = REQUEST_TIMEOUT).document
            }.getOrNull()?.let(BzhSportParser::rentryBaseUrls).orEmpty()
        }
        val chatango = async {
            runCatching {
                app.get(CHATANGO_URL, headers = browserHeaders, timeout = REQUEST_TIMEOUT).text
            }.getOrNull()?.let(BzhSportParser::chatangoBaseUrls).orEmpty()
        }
        (chatango.await() + rentry.await()).distinct()
    }

    private suspend fun fetchValidDocument(url: String, expectedPath: String): Document? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val response = app.get(url, headers = browserHeaders, timeout = REQUEST_TIMEOUT)
                val finalUrl = response.url.ifBlank { url }
                val redirectedHome = expectedPath != "/" && pathOf(finalUrl) == "/"
                if (!response.isSuccessful || redirectedHome) return@runCatching null
                response.document.also { it.setBaseUri(finalUrl) }.takeIf(BzhSportParser::isSiteDocument)
            }.getOrNull()
        }
    }

    private fun useBase(base: String) {
        baseUrl = base
        host(base)?.let(knownHosts::add)
    }

    private fun pathOf(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "/"
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$path$query"
    }

    private fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()

    companion object {
        val knownBases = listOf("https://bzh.lat", "https://volkamax.club")
        const val RENTRY_URL = "https://rentry.co/alpagang"
        const val CHATANGO_URL = "https://ewenethan22bzh.chatango.com/"
        const val SOURCE_NAME = "BZH-Sport"
        private const val REQUEST_TIMEOUT = 8L
        private const val REQUEST_TIMEOUT_MS = 9_000L
        private const val HOME_CACHE_MS = 3 * 60 * 1000L
        private const val PAGE_CACHE_MS = 2 * 60 * 1000L
        private const val MAX_CACHED_PAGES = 20
        private const val MAX_EMBEDS = 6
        private const val MAX_FRAMES_PER_PAGE = 3
        private const val MAX_FRAME_DEPTH = 3
    }
}
