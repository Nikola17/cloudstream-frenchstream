package com.lagradost

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URI

internal object MovixPackedPlayerParser {
    private val mediaRegex = Regex(
        """https?://[^\s"'\\<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'\\<>]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val fsvidXorSourceRegex = Regex(
        """var\s+k\s*=\s*\[([0-9,\s]+)]\s*,\s*b\s*=\s*atob\(s\).*?\}\)\(\s*["']([A-Za-z0-9+/_=-]+)["']\s*\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val resolutionRegex = Regex(
        """(?<!\d)(2160|1440|1080|720|576|540|480|360|240)(?:p)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    fun isPlayableMediaUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        return host != "fstream.top" && !host.endsWith(".fstream.top") && "/troll/" !in path
    }

    fun decodeFsvidSource(script: String): String? {
        val match = fsvidXorSourceRegex.find(script) ?: return null
        val key = match.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
        if (key.isEmpty()) return null
        val encrypted = decodeBase64(match.groupValues[2]) ?: return null
        val source = buildString(encrypted.size) {
            encrypted.forEachIndexed { index, byte ->
                append(((byte.toInt() and 0xFF) xor key[index % key.size]).toChar())
            }
        }.trim()
        return source.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /**
     * Extrait les URLs média jouables depuis :
     * 1. Scripts packés (p,a,c,k,e,d) + déchiffrement XOR fsvid.lol ;
     * 2. HTML/JS non packé (vidzy.org / vidzy.live / uqload direct).
     * Les fausses vidéos `/troll/` et `fstream.top` sont toujours filtrées.
     */
    fun extractMediaUrls(html: String): List<String> {
        val found = mutableListOf<String>()

        fun collect(text: String) {
            decodeFsvidSource(text)?.let(found::add)
            val cleaned = text.replace("\\/", "/").replace("&amp;", "&")
            mediaRegex.findAll(cleaned).forEach { found.add(it.value) }
        }

        Jsoup.parse(html)
            .select("script")
            .forEach { script ->
                val raw = script.data().ifBlank { script.html() }
                runCatching { JsUnpacker(raw).takeIf { it.detect() }?.unpack() }
                    .getOrNull()
                    ?.let(::collect)
            }
        runCatching { JsUnpacker(html).takeIf { it.detect() }?.unpack() }
            .getOrNull()
            ?.let(::collect)
        collect(html)

        return found.distinct().filter(::isPlayableMediaUrl)
    }

    fun inferQuality(url: String): Int? {
        return resolutionRegex.findAll(url)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
    }

    fun highestHlsQuality(manifest: String): Int? {
        return Regex("""RESOLUTION\s*=\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .mapNotNull { match ->
                val width = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val height = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                when {
                    width >= 3000 || height >= 1500 -> 2160
                    height >= 1100 -> 1440
                    width >= 1600 || height >= 800 -> 1080
                    width >= 1100 || height >= 600 -> 720
                    width >= 700 || height >= 400 -> 480
                    width >= 600 || height >= 300 -> 360
                    else -> height
                }
            }
            .maxOrNull()
    }

    private fun decodeBase64(input: String): ByteArray? {
        val clean = input.trim().replace('-', '+').replace('_', '/').filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return null
        val out = ByteArray((clean.length * 3) / 4 + 3)
        var outLen = 0
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outLen)
    }
}

internal object MovixExtractorPipeline {
    private const val CONCURRENCY_BATCH = 6
    private const val LINK_TIMEOUT_MS = 9_000L

    /**
     * Résout les liens en parallèle par lots de 6 (avec timeout par hébergeur pour qu'un serveur mort
     * ne bloque jamais la lecture), puis émet les flux trouvés dans l'ordre de priorité d'origine.
     */
    suspend fun <T> load(
        links: List<String>,
        loader: suspend (String, (T) -> Unit) -> Boolean,
        callback: (T) -> Unit
    ): Boolean {
        val unique = links.distinct()
        if (unique.isEmpty()) return false
        var emitted = false

        unique.chunked(CONCURRENCY_BATCH).forEach { batch ->
            val batchResults = coroutineScope {
                batch.map { link ->
                    async {
                        val collected = mutableListOf<T>()
                        withTimeoutOrNull(LINK_TIMEOUT_MS) {
                            runCatching {
                                loader(link) { item -> collected += item }
                            }
                        }
                        collected
                    }
                }.awaitAll()
            }
            batchResults.flatten().forEach { item ->
                emitted = true
                callback(item)
            }
        }

        return emitted
    }
}

internal abstract class MovixPackedPlayerExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = runCatching { URI(url) }.getOrNull()?.let { "${it.scheme}://${it.authority}" } ?: mainUrl
        val headers = mapOf("User-Agent" to USER_AGENT)
        val html = withTimeoutOrNull(8_000L) {
            runCatching {
                val first = app.get(url, referer = referer ?: "$origin/", headers = headers, timeout = 7L)
                when {
                    first.isSuccessful -> first.text
                    first.code == 403 -> app.get(url, referer = "$origin/", headers = headers, timeout = 7L)
                        .takeIf { it.isSuccessful }?.text
                    else -> null
                }
            }.getOrNull()
        } ?: return

        val streamHeaders = mapOf(
            "Referer" to url,
            "Origin" to origin,
            "User-Agent" to USER_AGENT
        )

        MovixPackedPlayerParser.extractMediaUrls(html).forEach { streamUrl ->
            val type = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            val quality = resolveQuality(streamUrl, streamHeaders)
            callback(
                newExtractorLink(name, name, streamUrl, type) {
                    this.referer = url
                    this.headers = streamHeaders
                    this.quality = quality
                }
            )
        }
    }

    private suspend fun resolveQuality(streamUrl: String, headers: Map<String, String>): Int {
        val inferred = MovixPackedPlayerParser.inferQuality(streamUrl)
        if (!streamUrl.contains(".m3u8", ignoreCase = true)) return inferred ?: Qualities.Unknown.value
        val manifest = withTimeoutOrNull(3_500L) {
            runCatching { app.get(streamUrl, headers = headers, timeout = 3L).text }.getOrNull()
        }
        return manifest?.let(MovixPackedPlayerParser::highestHlsQuality)
            ?: inferred
            ?: Qualities.Unknown.value
    }
}

internal class MovixVidzyExtractor : MovixPackedPlayerExtractor() {
    override val name = "Vidzy"
    override val mainUrl = "https://vidzy.org"
}

internal class MovixUqloadExtractor : MovixPackedPlayerExtractor() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.is"
}

/** Lecteur « packed » / direct générique : couvre les hébergeurs fréquents de Movix/FStream. */
internal class MovixDomainExtractor(
    override val name: String,
    override val mainUrl: String
) : MovixPackedPlayerExtractor()

internal object MovixDomainExtractors {
    val domains = listOf(
        "fsvid.lol", "vidzy.live", "kokoflix.lol", "trakx.lol", "playmogo.com",
        "sandratableother.com", "bysebuho.com", "neocine.embedseek.com", "embedorigin.cc",
        "vidara.to", "luluvido.com", "lplaygo.com", "playmate.to", "flemmix.upns.pro",
        "savefiles.com", "morencius.com", "hanerix.com", "anonmp4.art", "firestream.to",
        "uqload.vc", "uqload.cx", "uqload.net", "vidmoly.org", "vidmoly.me", "vidmoly.to",
        "jeremyparticipantanything.com"
    )

    fun create(): List<ExtractorApi> = domains.map { domain ->
        MovixDomainExtractor(
            name = domain.substringBefore('.').replaceFirstChar { it.uppercase() },
            mainUrl = "https://$domain"
        )
    }
}
