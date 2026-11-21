package com.lagradost

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal sealed class MovixHlsCheck {
    data object Invalid : MovixHlsCheck()
    data class Media(val quality: Int?, val firstSegmentUrl: String) : MovixHlsCheck()
    data class Master(val quality: Int?, val variantUrl: String) : MovixHlsCheck()
}

internal data class MovixVerifiedStream(
    val quality: Int
)

/**
 * Vérifie qu'un flux HLS ou MP4 répond en HTTP 2xx avec un manifeste valide (`#EXTM3U` + `#EXTINF:`
 * sans boucle `#EXT-X-STREAM-INF`) et un premier segment lisible. Élimine ExoPlayer 3002 et 2004.
 */
internal object MovixStreamVerifier {
    private const val PROBE_TIMEOUT_S = 4L
    private const val PROBE_TIMEOUT_MS = 4_500L
    private const val CACHE_TTL_MS = 15_000L
    private const val MAX_CACHE_ENTRIES = 200
    private val resolutionAttrRegex = Regex("""RESOLUTION\s*=\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    private data class CacheEntry(val result: MovixVerifiedStream?, val expiresAt: Long)
    private val probeCache = ConcurrentHashMap<String, CacheEntry>()

    fun inspectHlsManifest(body: String, baseUrl: String): MovixHlsCheck {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("#EXTM3U")) return MovixHlsCheck.Invalid

        val hasExtInf = trimmed.contains("#EXTINF:", ignoreCase = true)
        val hasStreamInf = trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true)

        if (hasExtInf && !hasStreamInf) {
            val firstSegment = pickFirstSegmentUrl(trimmed, baseUrl) ?: return MovixHlsCheck.Invalid
            return MovixHlsCheck.Media(MovixPackedPlayerParser.inferQuality(baseUrl), firstSegment)
        }
        if (hasStreamInf) {
            val highestQuality = MovixPackedPlayerParser.highestHlsQuality(trimmed)
            val bestVariant = pickBestVariantUrl(trimmed, baseUrl) ?: return MovixHlsCheck.Invalid
            return MovixHlsCheck.Master(highestQuality, bestVariant)
        }
        return MovixHlsCheck.Invalid
    }

    fun isValidMediaPlaylist(body: String): Boolean {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return trimmed.startsWith("#EXTM3U") &&
            trimmed.contains("#EXTINF:", ignoreCase = true) &&
            !trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true)
    }

    fun pickFirstSegmentUrl(manifest: String, baseUrl: String): String? {
        var seenExtInf = false
        for (rawLine in manifest.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                seenExtInf = true
            } else if (seenExtInf && !line.startsWith("#")) {
                return resolveUrl(baseUrl, line)
            }
        }
        return null
    }

    fun pickBestVariantUrl(manifest: String, baseUrl: String): String? {
        val lines = manifest.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var bestScore = -1
        var bestUrl: String? = null
        var pendingScore = 0
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val res = resolutionAttrRegex.find(line)
                pendingScore = if (res != null) {
                    val w = res.groupValues[1].toIntOrNull() ?: 0
                    val h = res.groupValues[2].toIntOrNull() ?: 0
                    MovixPackedPlayerParser.normalizeQuality(w, h)
                } else 0
            } else if (!line.startsWith("#")) {
                val resolved = resolveUrl(baseUrl, line)
                if (resolved != null && pendingScore >= bestScore) {
                    bestScore = pendingScore
                    bestUrl = resolved
                }
                pendingScore = 0
            }
        }
        return bestUrl
    }

    fun isHtmlOrErrorContentType(contentType: String?): Boolean {
        val ct = contentType?.lowercase()?.trim().orEmpty()
        if (ct.isBlank()) return false
        return ct.startsWith("text/html") ||
            ct.startsWith("application/xhtml") ||
            ct.startsWith("application/json") ||
            ct.startsWith("text/plain")
    }

    suspend fun verifyUrl(url: String, headers: Map<String, String>, isHls: Boolean): MovixVerifiedStream? {
        if (!MovixPackedPlayerParser.isPlayableMediaUrl(url)) return null
        val now = System.currentTimeMillis()
        val cacheKey = "${if (isHls) "hls" else "vid"}|$url|${headers["Referer"].orEmpty()}"
        probeCache[cacheKey]?.takeIf { it.expiresAt > now }?.let { return it.result }

        val effectiveHeaders = buildMap {
            put("User-Agent", USER_AGENT)
            putAll(headers)
        }
        val result = if (isHls) verifyHls(url, effectiveHeaders) else verifyDirectVideo(url, effectiveHeaders)
        if (probeCache.size >= MAX_CACHE_ENTRIES) {
            probeCache.entries.removeIf { it.value.expiresAt <= now }
            if (probeCache.size >= MAX_CACHE_ENTRIES) probeCache.clear()
        }
        probeCache[cacheKey] = CacheEntry(result, now + CACHE_TTL_MS)
        return result
    }

    suspend fun verifyLink(link: ExtractorLink): MovixVerifiedStream? {
        if (!MovixPackedPlayerParser.isPlayableMediaUrl(link.url)) return null
        val isHls = link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8", ignoreCase = true)
        val headers = buildMap {
            put("User-Agent", USER_AGENT)
            if (link.referer.isNotBlank()) put("Referer", link.referer)
            putAll(link.headers)
        }
        return verifyUrl(link.url, headers, isHls)
    }

    private suspend fun verifyHls(url: String, headers: Map<String, String>): MovixVerifiedStream? {
        val masterResp = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { app.get(url, headers = headers, timeout = PROBE_TIMEOUT_S) }.getOrNull()
        } ?: return null
        val (masterOk, masterText, finalUrl) = try {
            if (!masterResp.isSuccessful) Triple(false, "", url)
            else Triple(true, masterResp.text, masterResp.url.ifBlank { url })
        } finally {
            runCatching { masterResp.okhttpResponse.close() }
        }
        if (!masterOk) return null

        return when (val check = inspectHlsManifest(masterText, finalUrl)) {
            is MovixHlsCheck.Invalid -> null
            is MovixHlsCheck.Media -> {
                if (!verifySegment(check.firstSegmentUrl, headers)) return null
                MovixVerifiedStream(check.quality ?: MovixPackedPlayerParser.inferQuality(url) ?: Qualities.Unknown.value)
            }
            is MovixHlsCheck.Master -> {
                val subResp = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    runCatching { app.get(check.variantUrl, headers = headers, timeout = PROBE_TIMEOUT_S) }.getOrNull()
                } ?: return null
                val (subOk, subText, subFinalUrl) = try {
                    if (!subResp.isSuccessful) Triple(false, "", check.variantUrl)
                    else Triple(true, subResp.text, subResp.url.ifBlank { check.variantUrl })
                } finally {
                    runCatching { subResp.okhttpResponse.close() }
                }
                if (!subOk || !isValidMediaPlaylist(subText)) return null
                val firstSegment = pickFirstSegmentUrl(subText, subFinalUrl) ?: return null
                if (!verifySegment(firstSegment, headers)) return null
                MovixVerifiedStream(check.quality ?: MovixPackedPlayerParser.inferQuality(url) ?: Qualities.Unknown.value)
            }
        }
    }

    private suspend fun verifySegment(url: String, headers: Map<String, String>): Boolean {
        val resp = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                app.get(url, headers = headers + ("Range" to "bytes=0-511"), timeout = PROBE_TIMEOUT_S)
            }.getOrNull()
        } ?: return false
        return try {
            when {
                resp.isSuccessful || resp.code == 206 -> true
                resp.code == 416 || resp.code == 400 || resp.code == 501 -> {
                    val plain = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        runCatching { app.get(url, headers = headers, timeout = PROBE_TIMEOUT_S) }.getOrNull()
                    } ?: return false
                    try {
                        plain.isSuccessful
                    } finally {
                        runCatching { plain.okhttpResponse.close() }
                    }
                }
                else -> false
            }
        } finally {
            runCatching { resp.okhttpResponse.close() }
        }
    }

    private suspend fun verifyDirectVideo(url: String, headers: Map<String, String>): MovixVerifiedStream? {
        val resp = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                app.get(url, headers = headers + ("Range" to "bytes=0-1023"), timeout = PROBE_TIMEOUT_S)
            }.getOrNull()
        } ?: return null
        return try {
            if (!resp.isSuccessful && resp.code != 206) return null
            if (isHtmlOrErrorContentType(resp.headers["Content-Type"])) return null
            MovixVerifiedStream(MovixPackedPlayerParser.inferQuality(url) ?: Qualities.Unknown.value)
        } finally {
            runCatching { resp.okhttpResponse.close() }
        }
    }

    private fun resolveUrl(baseUrl: String, value: String): String? {
        val trimmed = value.trim().takeIf { it.isNotBlank() } ?: return null
        val absolute = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        return runCatching { URI(baseUrl).resolve(absolute).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
