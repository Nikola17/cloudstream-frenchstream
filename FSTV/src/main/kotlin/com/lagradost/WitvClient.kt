package com.lagradost

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import java.net.URI

/**
 * WITV : troisième source de chaînes live pour French-Stream TV, après FSTV et BZH-Sport.
 * Le domaine change souvent (witv.online, witv.channel, witv.team, witv.football, witv.space…) :
 * les miroirs connus sont essayés en tête, et le premier qui sert un catalogue valide l'emporte.
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

    suspend fun channels(): List<WitvChannel> = mutex.withLock {
        cachedChannels?.takeIf { System.currentTimeMillis() - it.first < CHANNELS_CACHE_MS }?.let { return it.second }
        val bases = (listOf(baseUrl) + WitvParser.knownBases).distinct()
        for (base in bases) {
            val document = fetchDocument("$base/chaines-live") ?: continue
            val firstPage = WitvParser.channels(document)
            if (firstPage.isEmpty()) continue
            baseUrl = base
            // Pagination ?page=2… tant qu'elle rapporte des chaînes nouvelles.
            val all = mutableListOf<WitvChannel>().apply { addAll(firstPage) }
            for (page in 2..MAX_PAGES) {
                val next = fetchDocument("$base/chaines-live?page=$page") ?: break
                val added = WitvParser.channels(next).filter { c -> all.none { it.url == c.url } }
                if (added.isEmpty()) break
                all += added
            }
            cachedChannels = System.currentTimeMillis() to all
            return all
        }
        return cachedChannels?.second.orEmpty()
    }

    suspend fun channelStreams(channelName: String): List<BzhStream> {
        val channel = WitvParser.findChannel(channels(), channelName) ?: return emptyList()
        val document = fetchDocument(channel.url) ?: return emptyList()
        val links = WitvParser.playerLinks(document).take(MAX_PLAYERS)
        if (links.isEmpty()) return emptyList()
        val found = mutableListOf<BzhStream>()
        coroutineScope {
            links.mapIndexed { index, link ->
                async {
                    val resolved = runCatching { player.resolveEmbed(link, document.baseUri()) }.getOrNull().orEmpty()
                        .ifEmpty { listOfNotNull(runCatching { player.browserEmbed(link, document.baseUri()) }.getOrNull()) }
                    resolved.map { (url, referer) ->
                        BzhStream("${channel.name} · Source ${index + 1}", url, referer)
                    }
                }
            }.awaitAll().flatten().forEach { stream ->
                if (found.none { it.url == stream.url }) found.add(stream)
            }
        }
        return found
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
        const val MAX_PLAYERS = 6
    }
}
