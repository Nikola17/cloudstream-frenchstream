package com.lagradost

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

internal sealed class FSTVHlsCheck {
    data object Invalid : FSTVHlsCheck()
    data class Media(val quality: Int?, val firstSegmentUrl: String) : FSTVHlsCheck()
    data class Master(val quality: Int?, val variantUrl: String) : FSTVHlsCheck()
}

internal data class FSTVVerifiedStream(
    val quality: Int
)

/**
 * Vérifie qu'un flux HLS live (WITV / BZH-Sport) répond en HTTP 2xx avec un manifeste `#EXTM3U`
 * valide contenant de vrais segments `#EXTINF:` (sans boucle `#EXT-X-STREAM-INF`) et un premier
 * segment lisible. Élimine ExoPlayer 3002 et 2004, et déclenche automatiquement le secours BZH-Sport
 * si toutes les sources WITV d'une chaîne sont hors ligne.
 */
internal object FSTVStreamVerifier {
    private const val PROBE_TIMEOUT_S = 5L
    private const val PROBE_TIMEOUT_MS = 5_500L
    private val resolutionAttrRegex = Regex("""RESOLUTION\s*=\s*(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val urlResolutionRegex = Regex(
        """(?<!\d)(2160|1440|1080|720|576|540|480|360|240)(?:p)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    fun inspectHlsManifest(body: String, baseUrl: String): FSTVHlsCheck {
        val trimmed = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("#EXTM3U")) return FSTVHlsCheck.Invalid

        val hasExtInf = trimmed.contains("#EXTINF:", ignoreCase = true)
        val hasStreamInf = trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true)

        if (hasExtInf && !hasStreamInf) {
            val firstSegment = pickFirstSegmentUrl(trimmed, baseUrl) ?: return FSTVHlsCheck.Invalid
            return FSTVHlsCheck.Media(inferQuality(baseUrl), firstSegment)
        }
        if (hasStreamInf) {
            val highestQuality = highestHlsQuality(trimmed)
            val bestVariant = pickBestVariantUrl(trimmed, baseUrl) ?: return FSTVHlsCheck.Invalid
            return FSTVHlsCheck.Master(highestQuality, bestVariant)
        }
        return FSTVHlsCheck.Invalid
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
                    normalizeQuality(w, h)
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

    fun highestHlsQuality(manifest: String): Int? {
        return resolutionAttrRegex.findAll(manifest)
            .mapNotNull { match ->
                val width = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val height = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                normalizeQuality(width, height)
            }
            .maxOrNull()
    }

    fun normalizeQuality(width: Int, height: Int): Int {
        return when {
            width >= 3000 || height >= 1500 -> 2160
            height >= 1100 -> 1440
            width >= 1600 || height >= 800 -> 1080
            width >= 1100 || height >= 600 -> 720
            width >= 700 || height >= 400 -> 480
            width >= 600 || height >= 300 -> 360
            else -> height
        }
    }

    fun inferQuality(url: String): Int? {
        return urlResolutionRegex.findAll(url)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
    }

    suspend fun verifyHls(url: String, headers: Map<String, String>): FSTVVerifiedStream? {
        val effectiveHeaders = buildMap {
            put("User-Agent", USER_AGENT)
            putAll(headers)
        }
        val masterResp = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { app.get(url, headers = effectiveHeaders, timeout = PROBE_TIMEOUT_S) }.getOrNull()
        } ?: return null
        val (masterOk, masterText, finalUrl) = try {
            if (!masterResp.isSuccessful) Triple(false, "", url)
            else Triple(true, masterResp.text, masterResp.url.ifBlank { url })
        } finally {
            runCatching { masterResp.okhttpResponse.close() }
        }
        if (!masterOk) return null

        return when (val check = inspectHlsManifest(masterText, finalUrl)) {
            is FSTVHlsCheck.Invalid -> null
            is FSTVHlsCheck.Media -> {
                if (!verifySegment(check.firstSegmentUrl, effectiveHeaders)) return null
                FSTVVerifiedStream(check.quality ?: inferQuality(url) ?: Qualities.Unknown.value)
            }
            is FSTVHlsCheck.Master -> {
                val subResp = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    runCatching { app.get(check.variantUrl, headers = effectiveHeaders, timeout = PROBE_TIMEOUT_S) }.getOrNull()
                } ?: return null
                val (subOk, subText, subFinalUrl) = try {
                    if (!subResp.isSuccessful) Triple(false, "", check.variantUrl)
                    else Triple(true, subResp.text, subResp.url.ifBlank { check.variantUrl })
                } finally {
                    runCatching { subResp.okhttpResponse.close() }
                }
                if (!subOk || !isValidMediaPlaylist(subText)) return null
                val firstSegment = pickFirstSegmentUrl(subText, subFinalUrl) ?: return null
                if (!verifySegment(firstSegment, effectiveHeaders)) return null
                FSTVVerifiedStream(check.quality ?: inferQuality(url) ?: Qualities.Unknown.value)
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

    private fun resolveUrl(baseUrl: String, value: String): String? {
        val trimmed = value.trim().takeIf { it.isNotBlank() } ?: return null
        val absolute = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        return runCatching { URI(baseUrl).resolve(absolute).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
