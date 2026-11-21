package com.lagradost

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal object FrenchStreamTmdbClient {
    private const val API_URL = "https://api.themoviedb.org/3"
    private const val API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private const val IMAGE_URL = "https://image.tmdb.org/t/p"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 250
    private const val REQUEST_TIMEOUT_S = 6L
    private const val REQUEST_TIMEOUT_MS = 7_000L

    private data class CacheEntry(val value: JSONObject?, val expiresAt: Long)

    private val matchCache = ConcurrentHashMap<String, CacheEntry>()
    private val detailsCache = ConcurrentHashMap<String, CacheEntry>()
    private val seasonCache = ConcurrentHashMap<String, CacheEntry>()

    fun image(path: String?, size: String = "w500"): String? {
        return path?.trim()?.takeIf { it.isNotBlank() }?.let { "$IMAGE_URL/$size$it" }
    }

    suspend fun find(title: String, year: Int?, isSeries: Boolean): JSONObject? {
        val type = if (isSeries) "tv" else "movie"
        val effectiveYear = if (isSeries) null else year
        val cacheKey = "$type|${FrenchStreamMetadata.normalizeTitle(title).lowercase()}|${effectiveYear ?: 0}"
        cached(matchCache, cacheKey)?.let { return it.value }

        val params = mutableMapOf(
            "query" to FrenchStreamMetadata.normalizeTitle(title),
            "include_adult" to "false"
        )
        effectiveYear?.let { params["year"] = it.toString() }
        val root = fetchJson(url("search/$type", params)) ?: return null
        val match = root.optJSONArray("results")?.let {
            FrenchStreamMetadata.tmdbResult(it, title, effectiveYear, isSeries)
        }
        putCache(matchCache, cacheKey, match)
        return match
    }

    suspend fun details(title: String, year: Int?, isSeries: Boolean): JSONObject? {
        val match = find(title, year, isSeries) ?: return null
        val id = match.optInt("id").takeIf { it > 0 } ?: return null
        val type = if (isSeries) "tv" else "movie"
        val cacheKey = "$type|$id"
        cached(detailsCache, cacheKey)?.let { return it.value }

        val append = if (isSeries) {
            "credits,videos,images,external_ids,content_ratings"
        } else {
            "credits,videos,images,external_ids,release_dates"
        }
        val details = fetchJson(
            url(
                "$type/$id",
                mapOf(
                    "append_to_response" to append,
                    "include_image_language" to "fr,en,null"
                )
            )
        )
        if (details != null) putCache(detailsCache, cacheKey, details)
        return details
    }

    suspend fun season(seriesId: Int, season: Int): JSONObject? {
        val cacheKey = "$seriesId|$season"
        cached(seasonCache, cacheKey)?.let { return it.value }
        val result = fetchJson(url("tv/$seriesId/season/$season"))
        if (result != null) putCache(seasonCache, cacheKey, result)
        return result
    }

    private suspend fun fetchJson(requestUrl: String): JSONObject? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching { JSONObject(app.get(requestUrl, timeout = REQUEST_TIMEOUT_S).text) }.getOrNull()
        }
    }

    private fun cached(cache: ConcurrentHashMap<String, CacheEntry>, key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        return entry.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    private fun putCache(cache: ConcurrentHashMap<String, CacheEntry>, key: String, value: JSONObject?) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            val now = System.currentTimeMillis()
            cache.entries.removeIf { it.value.expiresAt <= now }
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        }
        cache[key] = CacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    private fun url(path: String, extra: Map<String, String> = emptyMap()): String {
        val params = linkedMapOf("api_key" to API_KEY, "language" to "fr-FR")
        params.putAll(extra)
        return "$API_URL/${path.trimStart('/')}?" + params.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
