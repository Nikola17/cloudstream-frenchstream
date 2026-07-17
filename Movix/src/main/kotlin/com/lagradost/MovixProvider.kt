package com.lagradost

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class MovixProvider : MainAPI() {
    override var mainUrl = "https://movix.date"
    override var name = "Movix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val healthUrl = "https://movix.online"
    private val mirrors = listOf("https://movix.date", "https://movix.show", "https://movix.cash")
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private val imageBase = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "movie/popular" to "Films populaires",
        "movie/trending" to "Films tendance",
        "movie/top_rated" to "Films les mieux notes",
        "tv/popular" to "Series populaires",
        "tv/trending" to "Series tendance",
        "tv/top_rated" to "Series les mieux notees",
        "movie/genre/28" to "Films - Action",
        "movie/genre/12" to "Films - Aventure",
        "movie/genre/16" to "Films - Animation",
        "movie/genre/35" to "Films - Comedie",
        "movie/genre/80" to "Films - Crime",
        "movie/genre/99" to "Films - Documentaire",
        "movie/genre/18" to "Films - Drame",
        "movie/genre/10751" to "Films - Famille",
        "movie/genre/14" to "Films - Fantastique",
        "movie/genre/36" to "Films - Histoire",
        "movie/genre/27" to "Films - Horreur",
        "movie/genre/10402" to "Films - Musique",
        "movie/genre/9648" to "Films - Mystere",
        "movie/genre/10749" to "Films - Romance",
        "movie/genre/878" to "Films - Science-Fiction",
        "movie/genre/53" to "Films - Thriller",
        "movie/genre/10752" to "Films - Guerre",
        "movie/genre/37" to "Films - Western",
        "tv/genre/10759" to "Series - Action & Aventure",
        "tv/genre/16" to "Series - Animation",
        "tv/genre/35" to "Series - Comedie",
        "tv/genre/80" to "Series - Crime",
        "tv/genre/99" to "Series - Documentaire",
        "tv/genre/18" to "Series - Drame",
        "tv/genre/10751" to "Series - Famille",
        "tv/genre/10762" to "Series - Kids",
        "tv/genre/9648" to "Series - Mystere",
        "tv/genre/10763" to "Series - News",
        "tv/genre/10764" to "Series - Reality",
        "tv/genre/10765" to "Series - Sci-Fi & Fantasy",
        "tv/genre/10766" to "Series - Soap",
        "tv/genre/10767" to "Series - Talk",
        "tv/genre/10768" to "Series - Guerre & Politique",
        "tv/genre/37" to "Series - Western"
    )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun tmdbUrl(path: String, page: Int? = null, extra: Map<String, String> = emptyMap()): String {
        val params = mutableMapOf(
            "api_key" to tmdbKey,
            "language" to "fr-FR"
        )
        if (page != null) params["page"] = page.toString()
        params.putAll(extra)
        return "$tmdbApi/${path.trimStart('/')}?" + params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
    }

    private fun image(path: String?, size: String = "w500"): String? {
        return path?.takeIf { it.isNotBlank() }?.let { "$imageBase/$size$it" }
    }

    private fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()

    private fun JSONArray.toJsonObjects(): List<JSONObject> {
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun mediaUrl(type: String, id: Int): String = "$mainUrl/$type/$id"

    private fun episodeData(id: Int, season: Int, episode: Int): String = "$mainUrl/tv/$id/$season/$episode"

    private fun parseData(data: String): List<String> {
        val path = if (data.contains("movix://")) {
            data.substringAfter("movix://")
        } else {
            runCatching { URI(data).path ?: data }.getOrDefault(data)
        }
        return path.trim('/').split('/').filter { it.isNotBlank() }
    }

    private suspend fun refreshMainUrlFromHealth(): String? {
        val response = try {
            app.get(healthUrl)
        } catch (_: Exception) {
            return refreshMainUrlFromMirrors()
        }
        if (!response.isSuccessful) return refreshMainUrlFromMirrors()

        val discovered = Regex("""(?:https://)?movix\.[a-z0-9.-]+""", RegexOption.IGNORE_CASE)
            .findAll(response.text)
            .map { it.value.trimEnd('/', '.', '"', '\'') }
            .map { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
            .firstOrNull {
                !it.equals(healthUrl, ignoreCase = true) &&
                    !it.endsWith(".png", ignoreCase = true)
            }

        if (!discovered.isNullOrBlank() && isReachable(discovered)) {
            mainUrl = discovered
        } else {
            return refreshMainUrlFromMirrors()
        }
        return mainUrl
    }

    private suspend fun refreshMainUrlFromMirrors(): String? {
        return mirrors.firstOrNull { isReachable(it) }?.also { mainUrl = it }
    }

    private suspend fun isReachable(url: String): Boolean {
        return try {
            app.get(url).isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    private fun toSearchResponse(item: JSONObject): SearchResponse? {
        val mediaType = item.optString("media_type").ifBlank {
            when {
                item.has("title") -> "movie"
                item.has("name") -> "tv"
                else -> ""
            }
        }
        if (mediaType != "movie" && mediaType != "tv") return null

        val id = item.optInt("id").takeIf { it > 0 } ?: return null
        val title = if (mediaType == "movie") item.optString("title") else item.optString("name")
        if (title.isBlank()) return null

        val poster = image(item.optString("poster_path"))
        val releaseYear = year(
            if (mediaType == "movie") item.optString("release_date") else item.optString("first_air_date")
        )

        return if (mediaType == "movie") {
            newMovieSearchResponse(title, mediaUrl("movie", id), TvType.Movie) {
                posterUrl = poster
                this.year = releaseYear
            }
        } else {
            newTvSeriesSearchResponse(title, mediaUrl("tv", id), TvType.TvSeries) {
                posterUrl = poster
                this.year = releaseYear
            }
        }
    }

    private suspend fun getTmdb(path: String, page: Int? = null, extra: Map<String, String> = emptyMap()): JSONObject {
        return JSONObject(app.get(tmdbUrl(path, page, extra)).text)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        refreshMainUrlFromHealth()

        val parts = request.data.split('/')
        val type = parts.getOrNull(0) ?: "movie"
        val section = parts.getOrNull(1) ?: "popular"
        val genre = parts.getOrNull(2)

        val json = when (section) {
            "trending" -> getTmdb("trending/$type/week", page)
            "top_rated" -> getTmdb("$type/top_rated", page)
            "genre" -> getTmdb(
                "discover/$type",
                page,
                mapOf(
                    "with_genres" to (genre ?: ""),
                    "sort_by" to "popularity.desc",
                    "include_adult" to "false",
                    "include_video" to "false"
                )
            )
            else -> getTmdb("$type/popular", page)
        }

        val items = json.optJSONArray("results")
            ?.toJsonObjects()
            ?.map { if (!it.has("media_type")) it.put("media_type", type) else it }
            ?.mapNotNull { toSearchResponse(it) }
            ?: emptyList()

        val totalPages = json.optInt("total_pages", page)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = page < totalPages && items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        refreshMainUrlFromHealth()

        val json = getTmdb(
            "search/multi",
            1,
            mapOf(
                "query" to query,
                "include_adult" to "false"
            )
        )
        return json.optJSONArray("results")
            ?.toJsonObjects()
            ?.mapNotNull { toSearchResponse(it) }
            ?.distinctBy { it.url }
            ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = parseData(url)
        val type = parts.getOrNull(0) ?: "movie"
        val id = parts.getOrNull(1)?.toIntOrNull() ?: throw ErrorLoadingException("URL Movix invalide")
        refreshMainUrlFromHealth()

        return if (type == "tv") {
            loadTv(id)
        } else {
            loadMovie(id)
        }
    }

    private suspend fun loadMovie(id: Int): LoadResponse {
        val details = getTmdb("movie/$id", extra = mapOf("append_to_response" to "external_ids"))
        val title = details.optString("title").ifBlank { details.optString("original_title").ifBlank { "Movix" } }
        val genres = details.optJSONArray("genres")?.toJsonObjects()?.mapNotNull {
            it.optString("name").takeIf { tag -> tag.isNotBlank() }
        } ?: emptyList()

        return newMovieLoadResponse(title, mediaUrl("movie", id), TvType.Movie, mediaUrl("movie", id)) {
            posterUrl = image(details.optString("poster_path"))
            backgroundPosterUrl = image(details.optString("backdrop_path"), "w1280")
            year = year(details.optString("release_date"))
            plot = details.optString("overview")
            tags = genres
        }
    }

    private suspend fun loadTv(id: Int): LoadResponse {
        val details = getTmdb("tv/$id", extra = mapOf("append_to_response" to "external_ids"))
        val title = details.optString("name").ifBlank { details.optString("original_name").ifBlank { "Movix" } }
        val genres = details.optJSONArray("genres")?.toJsonObjects()?.mapNotNull {
            it.optString("name").takeIf { tag -> tag.isNotBlank() }
        } ?: emptyList()

        val episodes = mutableListOf<Episode>()
        val seasons = details.optJSONArray("seasons")?.toJsonObjects() ?: emptyList()
        for (seasonJson in seasons) {
            val seasonNumber = seasonJson.optInt("season_number")
            val episodeCount = seasonJson.optInt("episode_count")
            if (seasonNumber > 0 && episodeCount > 0) {
                for (episodeNumber in 1..episodeCount) {
                    episodes.add(newEpisode(episodeData(id, seasonNumber, episodeNumber)) {
                        name = "Episode $episodeNumber"
                        season = seasonNumber
                        episode = episodeNumber
                    })
                }
            }
        }

        return newTvSeriesLoadResponse(title, "$mainUrl/tv/$id", TvType.TvSeries, episodes) {
            posterUrl = image(details.optString("poster_path"))
            backgroundPosterUrl = image(details.optString("backdrop_path"), "w1280")
            year = year(details.optString("first_air_date"))
            plot = details.optString("overview")
            tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = parseData(data)
        val type = parts.getOrNull(0) ?: return false
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return false

        val links = if (type == "tv") {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return false
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return false
            frembedTvLinks(id, season, episode)
        } else {
            frembedMovieLinks(id)
        }

        var found = false
        links.distinct().forEach { link ->
            if (loadExtractor(link, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    private suspend fun frembedMovieLinks(id: Int): List<String> {
        val result = JSONObject(app.get("https://frembed.click/api/public/v1/movies/$id").text)
            .optJSONObject("result")
            ?: return emptyList()
        if (result.optInt("total", 0) <= 0) return emptyList()

        val publicLink = result.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optString("link")
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

        return listOfNotNull(
            publicLink,
            "https://frembed.click/embed/movie/$id",
            "https://frembed.click/api/film.php?id=$id"
        )
    }

    private suspend fun frembedTvLinks(id: Int, season: Int, episode: Int): List<String> {
        val result = JSONObject(app.get("https://frembed.click/api/public/v1/tv/$id?sa=$season&epi=$episode").text)
            .optJSONObject("result")
            ?: return emptyList()
        if (result.optInt("totalItems", 0) <= 0) return emptyList()

        val publicLink = result.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optString("link")
            ?.takeIf { it.startsWith("http", ignoreCase = true) }

        return listOfNotNull(
            publicLink,
            "https://frembed.click/embed/serie/$id?sa=$season&epi=$episode",
            "https://frembed.click/api/serie.php?id=$id&sa=$season&epi=$episode"
        )
    }
}
