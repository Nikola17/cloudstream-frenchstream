package com.lagradost

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object FrenchStreamTmdbClient {
    private const val API_URL = "https://api.themoviedb.org/3"
    private const val API_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private const val IMAGE_URL = "https://image.tmdb.org/t/p"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L
    private const val CATALOG_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val HBO_MAX_PROVIDER_ID = 1899
    private const val HBO_MAX_API_PAGES_PER_CATALOG_PAGE = 1

    private data class MatchCacheEntry(val value: JSONObject?, val expiresAt: Long)
    private data class CatalogCacheEntry(val value: List<FrenchStreamCatalogItem>, val expiresAt: Long)

    private val matchCache = ConcurrentHashMap<String, MatchCacheEntry>()
    private val hboMaxCache = ConcurrentHashMap<Int, CatalogCacheEntry>()

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

    suspend fun hboMaxReleases(page: Int): List<FrenchStreamCatalogItem> {
        val safePage = page.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        hboMaxCache[safePage]?.takeIf { it.expiresAt > now }?.let { return it.value }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = dateFormat.format(Date())
        val earliestPopularDate = Calendar.getInstance().apply {
            add(Calendar.YEAR, -2)
        }.let { dateFormat.format(it.time) }
        val common = mapOf(
            "watch_region" to "FR",
            "with_watch_providers" to HBO_MAX_PROVIDER_ID.toString(),
            "with_watch_monetization_types" to "flatrate",
            "include_adult" to "false"
        )
        suspend fun discover(path: String, extra: Map<String, String>): JSONArray = coroutineScope {
            val pages = hboMaxApiPages(safePage).map { apiPage ->
                async {
                    runCatching {
                        JSONObject(
                            app.get(
                                url("discover/$path", common + extra + ("page" to apiPage.toString()))
                            ).text
                        ).optJSONArray("results")
                    }.getOrNull() ?: JSONArray()
                }
            }.awaitAll()
            JSONArray().also { merged ->
                pages.forEach { results ->
                    for (index in 0 until results.length()) merged.put(results.opt(index))
                }
            }
        }

        val datasets = coroutineScope {
            listOf(
                async {
                    discover(
                        "movie",
                        mapOf("sort_by" to "primary_release_date.desc", "release_date.lte" to today)
                    )
                },
                async {
                    discover(
                        "tv",
                        mapOf(
                            "sort_by" to "first_air_date.desc",
                            "first_air_date.lte" to today,
                            "include_null_first_air_dates" to "false"
                        )
                    )
                },
                async {
                    discover(
                        "movie",
                        mapOf(
                            "sort_by" to "popularity.desc",
                            "release_date.gte" to earliestPopularDate,
                            "release_date.lte" to today
                        )
                    )
                },
                async {
                    discover(
                        "tv",
                        mapOf(
                            "sort_by" to "popularity.desc",
                            "first_air_date.gte" to earliestPopularDate,
                            "first_air_date.lte" to today,
                            "include_null_first_air_dates" to "false"
                        )
                    )
                }
            ).awaitAll()
        }
        val releases = FrenchStreamMetadata.hboMaxCatalogCandidates(
            recentMovies = datasets[0],
            recentSeries = datasets[1],
            popularMovies = datasets[2],
            popularSeries = datasets[3],
            earliestPopularDate = earliestPopularDate
        )
        if (releases.isNotEmpty()) {
            hboMaxCache[safePage] = CatalogCacheEntry(releases, now + CATALOG_CACHE_TTL_MS)
        }
        return releases
    }

    fun hboMaxApiPages(catalogPage: Int): IntRange {
        val safePage = catalogPage.coerceAtLeast(1)
        val first = (safePage - 1) * HBO_MAX_API_PAGES_PER_CATALOG_PAGE + 1
        return first until first + HBO_MAX_API_PAGES_PER_CATALOG_PAGE
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
