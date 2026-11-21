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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MovixProvider : MainAPI() {
    @Volatile
    override var mainUrl = "https://movix.tokyo"
    override var name = "Movix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val healthUrl = "https://movix.online"
    private val mirrors = listOf(
        "https://movix.tokyo",
        "https://movix.miami",
        "https://movix.date",
        "https://movix.show",
        "https://movix.cash"
    )
    private val movixApiBases = listOf("https://api.movix.show", "https://api.movix.miami")
    @Volatile
    private var movixApiUrl = movixApiBases.first()
    private val healthMutex = Mutex()
    @Volatile
    private var healthCheckedAt = 0L

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "f3d757824f08ea2cff45eb8f47ca3a1e"
    private val imageBase = "https://image.tmdb.org/t/p"
    private val movixHeaders get() = mapOf(
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "User-Agent" to USER_AGENT
    )

    private data class QualityCacheEntry(val quality: SearchQuality?, val expiresAt: Long)
    private val qualityCache = ConcurrentHashMap<Int, QualityCacheEntry>()
    private data class TheatersCacheEntry(val inTheaters: Boolean, val expiresAt: Long)
    private val theatersCache = ConcurrentHashMap<Int, TheatersCacheEntry>()
    private data class TmdbCacheEntry(val json: JSONObject, val expiresAt: Long)
    private val tmdbCache = ConcurrentHashMap<String, TmdbCacheEntry>()

    override val mainPage = mainPageOf(
        "movie/popular" to "Films populaires",
        "movie/trending" to "Films tendance",
        "movie/top_rated" to "Films les mieux notés",
        "tv/popular" to "Séries populaires",
        "tv/trending" to "Séries tendance",
        "tv/on_the_air" to "Séries en cours de diffusion",
        "tv/top_rated" to "Séries les mieux notées",
        "tv/provider/8" to "Nouveautés Netflix",
        "tv/provider/337" to "Nouveautés Disney+",
        "tv/provider/119" to "Nouveautés Prime Video",
        "tv/provider/350" to "Nouveautés Apple TV+",
        "tv/anime" to "Animes populaires",
        "movie/genre/28" to "Films · Action",
        "movie/genre/12" to "Films · Aventure",
        "movie/genre/16" to "Films · Animation",
        "movie/genre/35" to "Films · Comédie",
        "movie/genre/80" to "Films · Crime",
        "movie/genre/99" to "Films · Documentaire",
        "movie/genre/18" to "Films · Drame",
        "movie/genre/10751" to "Films · Famille",
        "movie/genre/14" to "Films · Fantastique",
        "movie/genre/36" to "Films · Histoire",
        "movie/genre/27" to "Films · Horreur",
        "movie/genre/10402" to "Films · Musique",
        "movie/genre/9648" to "Films · Mystère",
        "movie/genre/10749" to "Films · Romance",
        "movie/genre/878" to "Films · Science-Fiction",
        "movie/genre/53" to "Films · Thriller",
        "movie/genre/10752" to "Films · Guerre",
        "movie/genre/37" to "Films · Western",
        "tv/genre/10759" to "Séries · Action & Aventure",
        "tv/genre/16" to "Séries · Animation",
        "tv/genre/35" to "Séries · Comédie",
        "tv/genre/80" to "Séries · Crime",
        "tv/genre/99" to "Séries · Documentaire",
        "tv/genre/18" to "Séries · Drame",
        "tv/genre/10751" to "Séries · Famille",
        "tv/genre/10762" to "Séries · Jeunesse",
        "tv/genre/9648" to "Séries · Mystère",
        "tv/genre/10763" to "Séries · Actualités",
        "tv/genre/10764" to "Séries · Télé-réalité",
        "tv/genre/10765" to "Séries · Sci-Fi & Fantastique",
        "tv/genre/10768" to "Séries · Guerre & Politique",
        "tv/genre/37" to "Séries · Western"
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

    /** Vérification de domaine mise en cache 30 min (évite ~80 appels HTTP inutiles à l'ouverture). */
    private suspend fun ensureMainUrl(): String {
        val now = System.currentTimeMillis()
        if (now - healthCheckedAt < HEALTH_CACHE_MS) return mainUrl
        return healthMutex.withLock {
            val freshNow = System.currentTimeMillis()
            if (freshNow - healthCheckedAt < HEALTH_CACHE_MS) return@withLock mainUrl
            val response = withTimeoutOrNull(4_000L) {
                runCatching { app.get(healthUrl, timeout = 4L) }.getOrNull()
            }
            val discovered = if (response?.isSuccessful == true) {
                Regex("""(?:https://)?movix\.[a-z0-9.-]+""", RegexOption.IGNORE_CASE)
                    .findAll(response.text)
                    .map { it.value.trimEnd('/', '.', '"', '\'') }
                    .map { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
                    .firstOrNull {
                        !it.equals(healthUrl, ignoreCase = true) && !it.endsWith(".png", ignoreCase = true)
                    }
            } else null

            if (!discovered.isNullOrBlank() && isReachable(discovered)) {
                mainUrl = discovered
            } else {
                mirrors.firstOrNull { isReachable(it) }?.let { mainUrl = it }
            }
            healthCheckedAt = System.currentTimeMillis()
            mainUrl
        }
    }

    private suspend fun isReachable(url: String): Boolean {
        return withTimeoutOrNull(4_000L) {
            runCatching { app.get(url, timeout = 4L).isSuccessful }.getOrDefault(false)
        } ?: false
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
        val requestUrl = tmdbUrl(path, page, extra)
        val now = System.currentTimeMillis()
        tmdbCache[requestUrl]?.takeIf { it.expiresAt > now }?.let { return it.json }
        val json = JSONObject(app.get(requestUrl, timeout = 8L).text)
        if (tmdbCache.size >= MAX_TMDB_CACHE) {
            tmdbCache.entries.removeIf { it.value.expiresAt <= now }
            if (tmdbCache.size >= MAX_TMDB_CACHE) tmdbCache.clear()
        }
        tmdbCache[requestUrl] = TmdbCacheEntry(json, now + TMDB_CACHE_MS)
        return json
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split('/')
        val type = parts.getOrNull(0) ?: "movie"
        val section = parts.getOrNull(1) ?: "popular"
        val param = parts.getOrNull(2)

        val json = when (section) {
            "trending" -> getTmdb("trending/$type/week", page)
            "top_rated" -> getTmdb("$type/top_rated", page)
            "on_the_air" -> getTmdb("tv/on_the_air", page)
            "anime" -> getTmdb(
                "discover/tv",
                page,
                mapOf(
                    "with_genres" to "16",
                    "with_original_language" to "ja",
                    "sort_by" to "popularity.desc",
                    "include_adult" to "false"
                )
            )
            "provider" -> getTmdb(
                "discover/$type",
                page,
                mapOf(
                    "with_watch_providers" to (param ?: ""),
                    "watch_region" to "FR",
                    "sort_by" to "popularity.desc",
                    "include_adult" to "false"
                )
            )
            "genre" -> getTmdb(
                "discover/$type",
                page,
                mapOf(
                    "with_genres" to (param ?: ""),
                    "sort_by" to "popularity.desc",
                    "include_adult" to "false",
                    "include_video" to "false"
                )
            )
            else -> getTmdb("$type/popular", page)
        }

        val rawItems = json.optJSONArray("results")
            ?.toJsonObjects()
            ?.map { if (!it.has("media_type")) it.put("media_type", type) else it }
            ?.mapNotNull { toSearchResponse(it) }
            ?: emptyList()
        val filtered = filterInTheaters(rawItems)
        enrichMovieQualities(filtered)
        val items = filtered.filterNot { item ->
            item.type == TvType.Movie && item.quality?.let(MovixMetadata::isCamera) == true
        }

        val totalPages = json.optInt("total_pages", page)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = page < totalPages && rawItems.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = searchInternal(query, enrich = true)

    /** Recherche instantanée : saute les enrichissements lourds pour répondre dès la frappe. */
    override suspend fun quickSearch(query: String): List<SearchResponse> =
        searchInternal(query, enrich = false).take(20)

    private suspend fun searchInternal(query: String, enrich: Boolean): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val json = getTmdb(
            "search/multi",
            1,
            mapOf("query" to query, "include_adult" to "false")
        )
        val raw = json.optJSONArray("results")
            ?.toJsonObjects()
            ?.mapNotNull { toSearchResponse(it) }
            ?.distinctBy { it.url }
            ?: emptyList()
        if (!enrich) return raw
        val results = filterInTheaters(raw)
        enrichMovieQualities(results)
        return results.filterNot { item ->
            item.type == TvType.Movie && item.quality?.let(MovixMetadata::isCamera) == true
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = parseData(url)
        val type = parts.getOrNull(0) ?: "movie"
        val id = parts.getOrNull(1)?.toIntOrNull() ?: throw ErrorLoadingException("URL Movix invalide")

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
            posterUrl = image(details.optString("poster_path"), "w780")
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
        val poster = image(details.optString("poster_path"), "w780")
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
                                    name = episodeJson.optString("name").ifBlank { "Épisode $episodeNumber" }
                                    season = seasonNumber
                                    episode = episodeNumber
                                    description = episodeJson.optString("overview").takeIf { it.isNotBlank() }
                                    score = Score.from10(episodeJson.optDouble("vote_average").takeIf { it > 0.0 })
                                    runTime = episodeJson.optInt("runtime").takeIf { it > 0 }
                                    posterUrl = image(episodeJson.optString("still_path"), "w780") ?: fallbackPoster
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
            Actor(cast.name, image(cast.profilePath, "w342")) to cast.character
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

    private suspend fun filterInTheaters(items: List<SearchResponse>): List<SearchResponse> {
        val movies = items.filter { it.type == TvType.Movie && it.id != null }
        if (movies.isEmpty()) return items
        val currentYear = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.YEAR)
        val recent = movies.filter { (it as? MovieSearchResponse)?.year?.let { y -> y >= currentYear } == true }
        if (recent.isEmpty()) return items
        val inTheaters = Collections.synchronizedSet(HashSet<Int>())
        withTimeoutOrNull(3_000L) {
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
        withTimeoutOrNull(2_200L) {
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
        ensureMainUrl()
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

        if (loadSourceLinks(primaryLinks, subtitleCallback, callback)) return true

        val fallbackLinks = if (type == "tv") {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return false
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return false
            wiflixTvLinks(id, season, episode)
        } else {
            coroutineScope {
                val custom = async { customMovieLinks(id) }
                val wiflix = async { wiflixMovieLinks(id) }
                custom.await() + wiflix.await()
            }
        }

        return loadSourceLinks(fallbackLinks, subtitleCallback, callback)
    }

    private data class ResolvedMovixLink(val language: String?, val link: ExtractorLink)

    private suspend fun loadSourceLinks(
        entries: List<MovixSourceLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (entries.isEmpty()) return false
        val byUrl = entries.associateBy { it.url }
        val resolved = Collections.synchronizedList(mutableListOf<ResolvedMovixLink>())

        MovixExtractorPipeline.load<ResolvedMovixLink>(
            links = entries.map { it.url },
            loader = { link, emit ->
                val meta = byUrl[link]
                val direct = directMediaLink(link, meta?.language)
                if (direct != null) {
                    emit(ResolvedMovixLink(meta?.language, direct))
                    true
                } else {
                    var ok = false
                    loadExtractor(link, "$mainUrl/", subtitleCallback) { extracted ->
                        if (MovixPackedPlayerParser.isPlayableMediaUrl(extracted.url)) {
                            ok = true
                            emit(ResolvedMovixLink(meta?.language, withLanguage(extracted, meta?.language)))
                        }
                    }
                    ok
                }
            },
            callback = { resolved += it }
        )

        val sorted = resolved
            .distinctBy { "${it.language.orEmpty()}|${it.link.url}" }
            .sortedWith(
                compareBy<ResolvedMovixLink> { MovixLinkParser.languagePriority(it.language) }
                    .thenByDescending { it.link.quality }
                    .thenBy { it.link.source }
            )
        sorted.forEach { callback(it.link) }
        return sorted.isNotEmpty()
    }

    @Suppress("DEPRECATION")
    private fun withLanguage(link: ExtractorLink, language: String?): ExtractorLink {
        val label = language?.uppercase()?.takeIf { it.isNotBlank() }
        val detectedQuality = link.quality.takeIf { it > Qualities.Unknown.value }
            ?: MovixPackedPlayerParser.inferQuality(link.url)
            ?: link.quality
        if (label == null && detectedQuality == link.quality) return link
        val sourceText = if (label != null) "$label · ${link.source}" else link.source
        val nameText = if (label != null) "$label · ${link.name}" else link.name
        return ExtractorLink(
            source = sourceText,
            name = nameText,
            url = link.url,
            referer = link.referer,
            quality = detectedQuality,
            headers = link.headers,
            extractorData = link.extractorData,
            type = link.type,
            audioTracks = link.audioTracks
        )
    }

    private suspend fun directMediaLink(url: String, language: String?): ExtractorLink? {
        if (!MovixPackedPlayerParser.isPlayableMediaUrl(url)) return null
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
        val isM3u8 = path.endsWith(".m3u8", ignoreCase = true) || url.contains(".m3u8?", ignoreCase = true)
        val isMp4 = path.endsWith(".mp4", ignoreCase = true) || url.contains(".mp4?", ignoreCase = true)
        if (!isM3u8 && !isMp4) return null
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("Movix")
            .substringBefore('.').replaceFirstChar { it.uppercase() }
        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val quality = MovixPackedPlayerParser.inferQuality(url) ?: Qualities.Unknown.value
        val display = if (!language.isNullOrBlank()) "${language.uppercase()} · $host" else host
        return newExtractorLink("Movix", display, url, type) {
            this.quality = quality
        }
    }

    private suspend fun getMovixApi(path: String, timeoutSeconds: Long = 10L): JSONObject? {
        val bases = (listOf(movixApiUrl) + movixApiBases).distinct()
        for (base in bases) {
            val json = withTimeoutOrNull(timeoutSeconds * 1000L + 1000L) {
                runCatching {
                    val response = app.get(
                        "$base/${path.trimStart('/')}",
                        headers = movixHeaders,
                        timeout = timeoutSeconds
                    )
                    if (!response.isSuccessful) return@runCatching null
                    JSONObject(response.text)
                }.getOrNull()
            }
            if (json != null) {
                movixApiUrl = base
                return json
            }
        }
        return null
    }

    private suspend fun fstreamMovieLinks(id: Int): List<MovixSourceLink> {
        return getMovixApi("api/fstream/movie/$id")
            ?.let(MovixLinkParser::fstreamMovieEntries)
            ?: emptyList()
    }

    private suspend fun fstreamTvLinks(id: Int, season: Int, episode: Int): List<MovixSourceLink> {
        return getMovixApi("api/fstream/tv/$id/season/$season")
            ?.let { MovixLinkParser.fstreamTvEntries(it, episode) }
            ?: emptyList()
    }

    private suspend fun customMovieLinks(id: Int): List<MovixSourceLink> {
        return getMovixApi("api/links/movie/$id")
            ?.let(MovixLinkParser::customMovieEntries)
            ?: emptyList()
    }

    private suspend fun wiflixMovieLinks(id: Int): List<MovixSourceLink> {
        return getMovixApi("api/wiflix/movie/$id")
            ?.let(MovixLinkParser::wiflixMovieEntries)
            ?: emptyList()
    }

    private suspend fun wiflixTvLinks(id: Int, season: Int, episode: Int): List<MovixSourceLink> {
        return getMovixApi("api/wiflix/tv/$id/$season")
            ?.let { MovixLinkParser.wiflixTvEntries(it, episode) }
            ?: emptyList()
    }

    private companion object {
        const val HEALTH_CACHE_MS = 30 * 60 * 1000L
        const val TMDB_CACHE_MS = 15 * 60 * 1000L
        const val MAX_TMDB_CACHE = 300
    }
}
