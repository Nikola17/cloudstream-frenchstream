package com.lagradost

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal sealed class HlsCheck {
    data object Invalid : HlsCheck()
    data class Media(val quality: Int?, val firstSegmentUrl: String) : HlsCheck()
    data class Master(val quality: Int?, val variantUrl: String) : HlsCheck()
}

internal data class VerifiedStream(
    val quality: Int
)

/**
 * Vérifie qu'un flux HLS ou vidéo directe répond bien en HTTP 2xx avec un manifeste valide,
 * de vrais segments `#EXTINF` (et non une boucle `#EXT-X-STREAM-INF` qui provoque
 * ExoPlayer ERROR_CODE_PARSING_MANIFEST_MALFORMED 3002), et un premier segment lisible
 * (éliminant ExoPlayer ERROR_CODE_IO_BAD_HTTP_STATUS 2004).
 */
internal object FrenchStreamStreamVerifier {
    private const val PROBE_TIMEOUT_S = 4L
    private const val PROBE_TIMEOUT_MS = 4_500L
    private val resolutionAttrRegex = Regex("""RESOLUTION\s*=\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    fun inspectHlsManifest(body: String, baseUrl: String): HlsCheck {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("#EXTM3U")) return HlsCheck.Invalid

        val hasExtInf = trimmed.contains("#EXTINF:", ignoreCase = true)
        val hasStreamInf = trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true)

        if (hasExtInf && !hasStreamInf) {
            val firstSegment = pickFirstSegmentUrl(trimmed, baseUrl) ?: return HlsCheck.Invalid
            return HlsCheck.Media(FrenchStreamQuality.inferQuality(baseUrl), firstSegment)
        }
        if (hasStreamInf) {
            val highestQuality = FrenchStreamQuality.highestHlsQuality(trimmed)
            val bestVariant = pickBestVariantUrl(trimmed, baseUrl) ?: return HlsCheck.Invalid
            return HlsCheck.Master(highestQuality, bestVariant)
        }
        return HlsCheck.Invalid
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
                    FrenchStreamQuality.normalizeQuality(w, h)
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

    suspend fun verifyUrl(url: String, headers: Map<String, String>, isHls: Boolean): VerifiedStream? {
        if (!FrenchStreamQuality.isPlayableMediaUrl(url)) return null
        val effectiveHeaders = buildMap {
            put("User-Agent", USER_AGENT)
            putAll(headers)
        }
        return if (isHls) verifyHls(url, effectiveHeaders) else verifyDirectVideo(url, effectiveHeaders)
    }

    suspend fun verifyLink(link: ExtractorLink): VerifiedStream? {
        if (!FrenchStreamQuality.isPlayableMediaUrl(link.url)) return null
        val isHls = link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8", ignoreCase = true)
        val headers = buildMap {
            put("User-Agent", USER_AGENT)
            if (link.referer.isNotBlank()) put("Referer", link.referer)
            putAll(link.headers)
        }
        return verifyUrl(link.url, headers, isHls)
    }

    private suspend fun verifyHls(url: String, headers: Map<String, String>): VerifiedStream? {
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
            is HlsCheck.Invalid -> null
            is HlsCheck.Media -> {
                if (!verifySegment(check.firstSegmentUrl, headers)) return null
                VerifiedStream(check.quality ?: FrenchStreamQuality.inferQuality(url) ?: Qualities.Unknown.value)
            }
            is HlsCheck.Master -> {
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
                VerifiedStream(check.quality ?: FrenchStreamQuality.inferQuality(url) ?: Qualities.Unknown.value)
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

    private suspend fun verifyDirectVideo(url: String, headers: Map<String, String>): VerifiedStream? {
        val resp = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                app.get(url, headers = headers + ("Range" to "bytes=0-1023"), timeout = PROBE_TIMEOUT_S)
            }.getOrNull()
        } ?: return null
        return try {
            if (!resp.isSuccessful && resp.code != 206) return null
            if (isHtmlOrErrorContentType(resp.headers["Content-Type"])) return null
            VerifiedStream(FrenchStreamQuality.inferQuality(url) ?: Qualities.Unknown.value)
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
