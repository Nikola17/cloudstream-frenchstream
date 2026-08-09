package com.lagradost

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal object FrenchStreamTmdbClient {
    private const val API_URL = "https://api.themoviedb.org/3"
    private const val API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private const val IMAGE_URL = "https://image.tmdb.org/t/p"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    private data class MatchCacheEntry(val value: JSONObject?, val expiresAt: Long)

    private val matchCache = ConcurrentHashMap<String, MatchCacheEntry>()

    fun image(path: String?, size: String = "w500"): String? {
        return path?.trim()?.takeIf { it.isNotBlank() }?.let { "$IMAGE_URL/$size$it" }
    }

    suspend fun find(title: String, year: Int?, isSeries: Boolean): JSONObject? {
        val type = if (isSeries) "tv" else "movie"
        val effectiveYear = if (isSeries) null else year
        val cacheKey = "$type|${FrenchStreamMetadata.normalizeTitle(title).lowercase()}|${effectiveYear ?: 0}"
        val now = System.currentTimeMillis()
        matchCache[cacheKey]?.takeIf { it.expiresAt > now }?.let { return it.value }

        val params = mutableMapOf(
            "query" to FrenchStreamMetadata.normalizeTitle(title),
            "include_adult" to "false"
        )
        effectiveYear?.let { params["year"] = it.toString() }
        val root = runCatching { JSONObject(app.get(url("search/$type", params)).text) }.getOrNull()
            ?: return null
        val match = root.optJSONArray("results")?.let {
            FrenchStreamMetadata.tmdbResult(it, title, effectiveYear, isSeries)
        }
        matchCache[cacheKey] = MatchCacheEntry(match, now + CACHE_TTL_MS)
        return match
    }

    suspend fun details(title: String, year: Int?, isSeries: Boolean): JSONObject? {
        val match = find(title, year, isSeries) ?: return null
        val id = match.optInt("id").takeIf { it > 0 } ?: return null
        val type = if (isSeries) "tv" else "movie"
        val append = if (isSeries) {
            "credits,videos,images,external_ids,content_ratings"
        } else {
            "credits,videos,images,external_ids,release_dates"
        }
        return runCatching {
            JSONObject(
                app.get(
                    url(
                        "$type/$id",
                        mapOf(
                            "append_to_response" to append,
                            "include_image_language" to "fr,en,null"
                        )
                    )
                ).text
            )
        }.getOrNull()
    }

    suspend fun season(seriesId: Int, season: Int): JSONObject? {
        return runCatching { JSONObject(app.get(url("tv/$seriesId/season/$season")).text) }.getOrNull()
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
