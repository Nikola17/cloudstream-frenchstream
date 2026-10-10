package com.lagradost

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import java.net.URI

internal data class BzhStream(
    val label: String,
    val url: String,
    val referer: String
)

/**
 * BZH-Sport (ex Volkastream / OhQuelbut) : source secondaire de French-Stream TV.
 * Le domaine change souvent : les miroirs connus sont essayés, puis l'adresse à jour
 * est récupérée sur rentry.co/alpagang ou sur le Chatango du site.
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

    fun isSiteUrl(url: String): Boolean {
        val urlHost = host(url) ?: return false
        return knownHosts.any { it.equals(urlHost, ignoreCase = true) } || BzhSportParser.isSitePath(url)
    }

    suspend fun events(): List<BzhEvent> = home()?.let(BzhSportParser::events).orEmpty()

    suspend fun channels(): List<BzhChannel> = home()?.let(BzhSportParser::channels).orEmpty()

    suspend fun page(url: String): Document? {
        fetchValidDocument(url)?.let { return it }
        val path = pathOf(url)
        val requestedBase = originOf(url)
        if (requestedBase != baseUrl) fetchValidDocument("$baseUrl$path")?.let { return it }
        return candidateBases().filterNot { it == requestedBase }.firstNotNullOfOrNull { base ->
            fetchValidDocument("$base$path")?.also { useBase(base) }
        }
    }

    suspend fun streams(pageUrl: String): List<BzhStream> {
        val document = page(pageUrl) ?: return emptyList()
        val embeds = BzhSportParser.embeds(document).take(MAX_EMBEDS)
        return coroutineScope {
            embeds.map { embed ->
                async {
                    runCatching { resolve(embed.url, document.baseUri(), 0) }.getOrNull().orEmpty()
                        .map { (url, referer) -> BzhStream(embed.label, url, referer) }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.url }
    }

    suspend fun channelStreams(channelName: String): List<BzhStream> {
        val channel = BzhSportParser.findChannel(channels(), channelName) ?: return emptyList()
        return streams(channel.url).map { it.copy(label = channel.name) }
    }

    private suspend fun resolve(url: String, referer: String, depth: Int): List<Pair<String, String>> {
        val response = runCatching {
            app.get(url, headers = browserHeaders + mapOf("Referer" to referer), timeout = REQUEST_TIMEOUT)
        }.getOrNull() ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        val html = response.text
        val pageUrl = response.url.ifBlank { url }

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
        val document = page("$baseUrl/")
        if (document != null) cachedHome = System.currentTimeMillis() to document
        document ?: cachedHome?.second
    }

    private suspend fun candidateBases(): List<String> {
        val mirrors = knownBases.filterNot { it == baseUrl }
        return mirrors + discoverBases().filterNot { it == baseUrl || it in mirrors }
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

    private suspend fun fetchValidDocument(url: String): Document? {
        val response = runCatching {
            app.get(url, headers = browserHeaders, timeout = REQUEST_TIMEOUT)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val document = response.document
        document.setBaseUri(response.url.ifBlank { url })
        return document.takeIf(BzhSportParser::isSiteDocument)
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

    private fun originOf(url: String): String? {
        return runCatching { URI(url) }.getOrNull()?.let { "${it.scheme}://${it.authority}" }
    }

    private fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()

    companion object {
        val knownBases = listOf("https://bzh.lat", "https://volkamax.club")
        const val RENTRY_URL = "https://rentry.co/alpagang"
        const val CHATANGO_URL = "https://ewenethan22bzh.chatango.com/"
        const val SOURCE_NAME = "BZH-Sport"
        private const val REQUEST_TIMEOUT = 8L
        private const val HOME_CACHE_MS = 2 * 60 * 1000L
        private const val MAX_EMBEDS = 6
        private const val MAX_FRAMES_PER_PAGE = 3
        private const val MAX_FRAME_DEPTH = 3
    }
}
