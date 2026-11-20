package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class MovixProvider : MainAPI() {
    override var mainUrl = "https://movix.date"
    override var name = "Movix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val healthUrl = "https://movix.online"
    private val mirrors = listOf(
        "https://movix.miami",
        "https://movix.date",
        "https://movix.show",
        "https://movix.cash"
    )
    private val movixApiBases = listOf("https://api.movix.show", "https://api.movix.miami")
    @Volatile
    private var movixApiUrl = movixApiBases.first()
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private val imageBase = "https://image.tmdb.org/t/p"
    private val movixHeaders get() = mapOf(
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0"
    )
    private data class QualityCacheEntry(val quality: SearchQuality?, val expiresAt: Long)
    private val qualityCache = ConcurrentHashMap<Int, QualityCacheEntry>()
    private data class TheatersCacheEntry(val inTheaters: Boolean, val expiresAt: Long)
    private val theatersCache = ConcurrentHashMap<Int, TheatersCacheEntry>()

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
                this.id = id
                posterUrl = poster
                this.year = releaseYear
                score = Score.from10(item.optDouble("vote_average").takeIf { it > 0.0 })
            }
        } else {
            newTvSeriesSearchResponse(title, mediaUrl("tv", id), TvType.TvSeries) {
                this.id = id
                posterUrl = poster
                this.year = releaseYear
                score = Score.from10(item.optDouble("vote_average").takeIf { it > 0.0 })
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
            ?.let { filterInTheaters(it) }
            ?: emptyList()
        enrichMovieQualities(items)

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
        val results = json.optJSONArray("results")
            ?.toJsonObjects()
            ?.mapNotNull { toSearchResponse(it) }
            ?.distinctBy { it.url }
            ?.let { filterInTheaters(it) }
            ?: emptyList()
        enrichMovieQualities(results)
        return results
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
        val details = getTmdb(
            "movie/$id",
            extra = mapOf(
                "append_to_response" to "credits,videos,recommendations,images,external_ids,release_dates",
                "include_image_language" to "fr,en,null"
            )
        )
        val title = details.optString("title").ifBlank { details.optString("original_title").ifBlank { "Movix" } }
        val genres = details.optJSONArray("genres")?.toJsonObjects()?.mapNotNull {
            it.optString("name").takeIf { tag -> tag.isNotBlank() }
        } ?: emptyList()
        val imdbId = details.optString("imdb_id").ifBlank {
            details.optJSONObject("external_ids")?.optString("imdb_id").orEmpty()
        }.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(title, mediaUrl("movie", id), TvType.Movie, mediaUrl("movie", id)) {
            posterUrl = image(details.optString("poster_path"))
            backgroundPosterUrl = image(details.optString("backdrop_path"), "w1280")
            logoUrl = logo(details)
            year = year(details.optString("release_date"))
            plot = details.optString("overview")
            tags = genres
            duration = details.optInt("runtime").takeIf { it > 0 }
            score = Score.from10(details.optDouble("vote_average").takeIf { it > 0.0 })
            contentRating = MovixMetadata.movieContentRating(details)
            recommendations = recommendations(details, "movie")
            addActors(actors(details))
            addTrailer(MovixMetadata.trailerUrl(details))
            addImdbId(imdbId)
            addTMDbId(id.toString())
        }
    }

    private suspend fun loadTv(id: Int): LoadResponse {
        val details = getTmdb(
            "tv/$id",
            extra = mapOf(
                "append_to_response" to "credits,videos,recommendations,images,external_ids,content_ratings",
                "include_image_language" to "fr,en,null"
            )
        )
        val title = details.optString("name").ifBlank { details.optString("original_name").ifBlank { "Movix" } }
        val genres = details.optJSONArray("genres")?.toJsonObjects()?.mapNotNull {
            it.optString("name").takeIf { tag -> tag.isNotBlank() }
        } ?: emptyList()
        val poster = image(details.optString("poster_path"))
        val imdbId = details.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }

        val seasonNumbers = details.optJSONArray("seasons")?.toJsonObjects()
            ?.mapNotNull { season -> season.optInt("season_number").takeIf { it > 0 } }
            ?: emptyList()
        val episodes = loadTmdbSeasons(id, seasonNumbers, poster)

        return newTvSeriesLoadResponse(title, "$mainUrl/tv/$id", TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = image(details.optString("backdrop_path"), "w1280")
            logoUrl = logo(details)
            year = year(details.optString("first_air_date"))
            plot = details.optString("overview")
            tags = genres
            duration = details.optJSONArray("episode_run_time")?.let { runtimes ->
                (0 until runtimes.length()).map { runtimes.optInt(it) }.filter { it > 0 }.average()
                    .takeIf { !it.isNaN() }?.toInt()
            }
            score = Score.from10(details.optDouble("vote_average").takeIf { it > 0.0 })
            showStatus = MovixMetadata.showStatus(details.optString("status"))
            contentRating = MovixMetadata.tvContentRating(details)
            recommendations = recommendations(details, "tv")
            addActors(actors(details))
            addTrailer(MovixMetadata.trailerUrl(details))
            addImdbId(imdbId)
            addTMDbId(id.toString())
        }
    }

    private suspend fun loadTmdbSeasons(id: Int, seasons: List<Int>, fallbackPoster: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (batch in seasons.chunked(4)) {
            episodes += coroutineScope {
                batch.map { seasonNumber ->
                    async {
                        runCatching { getTmdb("tv/$id/season/$seasonNumber") }.getOrNull()
                            ?.optJSONArray("episodes")
                            ?.toJsonObjects()
                            ?.mapNotNull { episodeJson ->
                                val episodeNumber = episodeJson.optInt("episode_number").takeIf { it > 0 }
                                    ?: return@mapNotNull null
                                newEpisode(episodeData(id, seasonNumber, episodeNumber)) {
                                    name = episodeJson.optString("name").ifBlank { "Episode $episodeNumber" }
                                    season = seasonNumber
                                    episode = episodeNumber
                                    description = episodeJson.optString("overview").takeIf { it.isNotBlank() }
                                    score = Score.from10(episodeJson.optDouble("vote_average").takeIf { it > 0.0 })
                                    runTime = episodeJson.optInt("runtime").takeIf { it > 0 }
                                    posterUrl = image(episodeJson.optString("still_path")) ?: fallbackPoster
                                    date = parseDate(episodeJson.optString("air_date"))
                                }
                            }
                            ?: emptyList()
                    }
                }.awaitAll().flatten()
            }
        }
        return episodes.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episode })
    }

    private fun actors(details: JSONObject): List<Pair<Actor, String?>> {
        return MovixMetadata.cast(details).take(30).map { cast ->
            Actor(cast.name, image(cast.profilePath, "w185")) to cast.character
        }
    }

    private fun logo(details: JSONObject): String? {
        val logos = details.optJSONObject("images")?.optJSONArray("logos")?.toJsonObjects() ?: return null
        val selected = logos.firstOrNull { it.optString("iso_639_1") == "fr" }
            ?: logos.firstOrNull { it.optString("iso_639_1") == "en" }
            ?: logos.firstOrNull()
        return image(selected?.optString("file_path"), "w500")
    }

    private fun recommendations(details: JSONObject, fallbackType: String): List<SearchResponse> {
        return details.optJSONObject("recommendations")?.optJSONArray("results")?.toJsonObjects()
            ?.mapNotNull { item ->
                if (!item.has("media_type")) item.put("media_type", fallbackType)
                toSearchResponse(item)
            }
            ?: emptyList()
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)?.time }.getOrNull()
    }

    /**
     * Retire les films encore au cinéma (sortie récente sans aucune sortie digitale/physique selon
     * TMDB) : ils ne sont disponibles qu'en version filmée en salle (CAM/TS), illisible.
     */
    private suspend fun filterInTheaters(items: List<SearchResponse>): List<SearchResponse> {
        val movies = items.filter { it.type == TvType.Movie && it.id != null }
        if (movies.isEmpty()) return items
        val currentYear = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.YEAR)
        val recent = movies.filter { (it as? MovieSearchResponse)?.year?.let { y -> y >= currentYear } == true }
        if (recent.isEmpty()) return items
        val inTheaters = Collections.synchronizedSet(HashSet<Int>())
        withTimeoutOrNull(3_500L) {
            for (batch in recent.chunked(8)) {
                coroutineScope {
                    batch.map { item ->
                        async {
                            if (isInTheaters(item.id!!)) inTheaters.add(item.id!!)
                        }
                    }.awaitAll()
                }
            }
        }
        if (inTheaters.isEmpty()) return items
        return items.filterNot { it.id != null && it.id in inTheaters }
    }

    private suspend fun isInTheaters(id: Int): Boolean {
        val now = System.currentTimeMillis()
        theatersCache[id]?.takeIf { it.expiresAt > now }?.let { return it.inTheaters }
        val result = runCatching {
            val root = getTmdb("movie/$id/release_dates")
            val countries = root.optJSONArray("results")?.toJsonObjects() ?: return@runCatching false
            var hasTheatrical = false
            var hasDigitalOrPhysical = false
            for (country in countries) {
                val releases = country.optJSONArray("release_dates") ?: continue
                for (i in 0 until releases.length()) {
                    val type = releases.optJSONObject(i)?.optInt("type") ?: continue
                    if (type in 4..6) hasDigitalOrPhysical = true
                    if (type in 1..3) hasTheatrical = true
                }
            }
            hasTheatrical && !hasDigitalOrPhysical
        }.getOrDefault(false)
        theatersCache[id] = TheatersCacheEntry(result, now + 24 * 60 * 60 * 1000L)
        return result
    }

    private suspend fun enrichMovieQualities(items: List<SearchResponse>) {
        val movies = items.filter { it.type == TvType.Movie && it.id != null }
        withTimeoutOrNull(2_500L) {
            for (batch in movies.chunked(8)) {
                coroutineScope {
                    batch.map { item ->
                        async { item.quality = qualityForMovie(item.id ?: return@async) }
                    }.awaitAll()
                }
            }
        }
    }

    private suspend fun qualityForMovie(id: Int): SearchQuality? {
        val now = System.currentTimeMillis()
        qualityCache[id]?.takeIf { it.expiresAt > now }?.let { return it.quality }
        val quality = getMovixApi("api/j1f/movie/$id", timeoutSeconds = 3L)
            ?.let(MovixMetadata::qualityFromJ1f)
        qualityCache[id] = QualityCacheEntry(quality, now + 30 * 60 * 1000L)
        return quality
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

        val primaryLinks = if (type == "tv") {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return false
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return false
            fstreamTvLinks(id, season, episode)
        } else {
            fstreamMovieLinks(id)
        }

        if (loadExtractorLinks(primaryLinks, subtitleCallback, callback)) return true

        val fallbackLinks = if (type == "tv") {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return false
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return false
            wiflixTvLinks(id, season, episode)
        } else {
            customMovieLinks(id) + wiflixMovieLinks(id)
        }

        return loadExtractorLinks(fallbackLinks, subtitleCallback, callback)
    }

    private suspend fun loadExtractorLinks(
        links: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return MovixExtractorPipeline.load(
            links = links,
            loader = { link, emit ->
                // L'API Movix renvoie parfois directement le média (mp4 CDN, m3u8) : on l'émet tel quel.
                val direct = directMediaLink(link)
                if (direct != null) {
                    emit(direct)
                    true
                } else {
                    loadExtractor(link, subtitleCallback, emit)
                }
            },
            callback = callback
        )
    }

    private suspend fun directMediaLink(url: String): ExtractorLink? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
        val isM3u8 = path.endsWith(".m3u8", ignoreCase = true) || url.contains(".m3u8?", ignoreCase = true)
        val isMp4 = path.endsWith(".mp4", ignoreCase = true) || url.contains(".mp4?", ignoreCase = true)
        if (!isM3u8 && !isMp4) return null
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("Movix")
            .substringBefore('.').replaceFirstChar { it.uppercase() }
        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val quality = Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)(?:p)?(?!\d)""", RegexOption.IGNORE_CASE)
            .findAll(url).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull()
            ?: Qualities.Unknown.value
        return newExtractorLink("Movix", host, url, type) {
            this.quality = quality
        }
    }

    private suspend fun getMovixApi(path: String, timeoutSeconds: Long = 15L): JSONObject? {
        val bases = (listOf(movixApiUrl) + movixApiBases).distinct()
        for (base in bases) {
            val json = runCatching {
                val response = app.get(
                    "$base/${path.trimStart('/')}",
                    headers = movixHeaders,
                    timeout = timeoutSeconds
                )
                if (!response.isSuccessful) return@runCatching null
                JSONObject(response.text)
            }.getOrNull()
            if (json != null) {
                movixApiUrl = base
                return json
            }
        }
        return null
    }

    private suspend fun fstreamMovieLinks(id: Int): List<String> {
        return getMovixApi("api/fstream/movie/$id")
            ?.let(MovixLinkParser::fstreamMovie)
            ?: emptyList()
    }

    private suspend fun fstreamTvLinks(id: Int, season: Int, episode: Int): List<String> {
        return getMovixApi("api/fstream/tv/$id/season/$season")
            ?.let { MovixLinkParser.fstreamTv(it, episode) }
            ?: emptyList()
    }

    private suspend fun customMovieLinks(id: Int): List<String> {
        return getMovixApi("api/links/movie/$id")
            ?.let(MovixLinkParser::customMovie)
            ?: emptyList()
    }

    private suspend fun wiflixMovieLinks(id: Int): List<String> {
        return getMovixApi("api/wiflix/movie/$id")
            ?.let(MovixLinkParser::wiflixMovie)
            ?: emptyList()
    }

    private suspend fun wiflixTvLinks(id: Int, season: Int, episode: Int): List<String> {
        return getMovixApi("api/wiflix/tv/$id/$season")
            ?.let { MovixLinkParser.wiflixTv(it, episode) }
            ?: emptyList()
    }
}
