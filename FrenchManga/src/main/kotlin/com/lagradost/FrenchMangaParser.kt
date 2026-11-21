package com.lagradost

import com.lagradost.cloudstream3.utils.JsUnpacker
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer

internal data class FrenchMangaCategory(val path: String, val label: String)

internal data class FrenchMangaCard(
    val title: String,
    val seasonTitle: String,
    val url: String,
    val posterUrl: String?,
    val isMovie: Boolean,
    val season: Int?,
    val year: Int?,
    val language: String?
)

internal data class FrenchMangaActor(
    val name: String,
    val role: String?,
    val imageUrl: String?
)

internal data class FrenchMangaDetail(
    val newsId: String,
    val title: String,
    val seasonTitle: String,
    val season: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val plot: String?,
    val genres: List<String>,
    val tags: List<String>,
    val trailerId: String?,
    val actors: List<FrenchMangaActor>,
    val isMovie: Boolean
)

internal data class FrenchMangaSeason(
    val number: Int,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val newsId: String? = null
)

internal data class FrenchMangaSource(
    val language: String,
    val host: String,
    val url: String
)

internal data class FrenchMangaEpisodeData(
    val number: Int,
    val title: String,
    val synopsis: String?,
    val posterUrl: String?,
    val sources: List<FrenchMangaSource>
)

internal data class FrenchMangaEpisodePayload(val pageUrl: String, val episode: Int)

internal object FrenchMangaParser {
    val categories = listOf(
        FrenchMangaCategory("/manga-streaming-1/", "Récemment mises à jour"),
        FrenchMangaCategory("/manga-streaming-1/coups-de-cur/", "Coups de Cœur"),
        FrenchMangaCategory("/xfsearch/tagz/shounen/", "Shonen"),
        FrenchMangaCategory("/xfsearch/tagz/seinen/", "Seinen"),
        FrenchMangaCategory("/xfsearch/tagz/dark+fantasy/", "Dark Fantasy"),
        FrenchMangaCategory("/xfsearch/tagz/gore/", "Gore"),
        FrenchMangaCategory("/xfsearch/tagz/isekai/", "Isekai"),
        FrenchMangaCategory("/xfsearch/manga_genre/Thriller/", "Thriller"),
        FrenchMangaCategory("/xfsearch/manga_genre/Aventure/", "Aventure"),
        FrenchMangaCategory("/xfsearch/manga_genre/Action+%26+Adventure/", "Action & Aventure"),
        FrenchMangaCategory(
            "/xfsearch/manga_genre/Science-Fiction+%26+Fantastique/",
            "Science-Fiction & Fantastique"
        ),
        FrenchMangaCategory("/xfsearch/serie-statut/Film/", "Films d'animation"),
        FrenchMangaCategory("/xfsearch/serie-statut/En+cours/", "Animes en cours"),
        FrenchMangaCategory("/xfsearch/serie-statut/Termin%C3%A9/", "Animes terminés"),
        FrenchMangaCategory("/mangas-vf/", "Animes VF"),
        FrenchMangaCategory("/mangas-vostfr/", "Animes VOSTFR"),
        FrenchMangaCategory("/xfsearch/manga_genre/Com%C3%A9die/", "Comédie"),
        FrenchMangaCategory("/xfsearch/manga_genre/Drame/", "Drame"),
        FrenchMangaCategory("/xfsearch/manga_genre/Romance/", "Romance"),
        FrenchMangaCategory("/xfsearch/manga_genre/Myst%C3%A8re/", "Mystère"),
        FrenchMangaCategory("/xfsearch/tagz/sports/", "Sport"),
        FrenchMangaCategory("/xfsearch/tagz/martial+arts/", "Arts martiaux")
    )

    private val seasonRegex = Regex("""\bSaison\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val seasonSuffixRegex = Regex(
        """\s*[-\u2013\u2014]?\s*Saison\s+\d+.*$""",
        RegexOption.IGNORE_CASE
    )
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val backdropRegex = Regex("""url\(\s*['\"]?([^)'\"]+)""", RegexOption.IGNORE_CASE)
    private val actorDataRegex = Regex(
        """window\.actorData\s*=\s*(\[[\s\S]*?])\s*;""",
        RegexOption.IGNORE_CASE
    )
    private val searchUrlRegex = Regex("""location\.href\s*=\s*['\"]([^'\"]+)""", RegexOption.IGNORE_CASE)

    fun cards(document: Document): List<FrenchMangaCard> {
        return document.select("div.short").mapNotNull { card ->
            val anchor = card.selectFirst("a.short-poster[href]") ?: return@mapNotNull null
            val url = anchor.absUrl("href").takeIf(::isHttpUrl) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val displayedTitle = card.selectFirst(".short-title")?.text()?.trim()
            val imageTitle = image?.attr("alt")?.trim()?.removeSuffix(" affiche")
            val seasonTitle = displayedTitle?.takeIf { it.isNotBlank() }
                ?: imageTitle?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val type = card.select(".mli-type, .film-quality, .short-qual").text()
            val isMovie = type.contains("Film", ignoreCase = true) &&
                !type.contains("Anime", ignoreCase = true)
            FrenchMangaCard(
                title = if (isMovie) seasonTitle else baseTitle(seasonTitle),
                seasonTitle = seasonTitle,
                url = url,
                posterUrl = posterUrl(image?.absUrl("src"), image?.absUrl("data-src")),
                isMovie = isMovie,
                season = seasonNumber(seasonTitle),
                year = yearFrom(url),
                language = card.selectFirst(".film-version")?.text()?.trim()?.takeIf { it.isNotBlank() }
            )
        }.distinctBy { it.url }
    }

    fun searchResults(document: Document): List<FrenchMangaCard> {
        return document.select(".search-item").mapNotNull { item ->
            val onclick = item.attr("onclick")
            val relativeUrl = searchUrlRegex.find(onclick)?.groupValues?.getOrNull(1)
                ?: item.selectFirst("a[href]")?.attr("href")
                ?: return@mapNotNull null
            val url = resolveUrl(document.baseUri(), relativeUrl) ?: return@mapNotNull null
            val rawLabel = item.selectFirst(".search-title")?.text()?.trim().orEmpty()
            // The live search HTML uses single-quoted attributes even for titles containing
            // apostrophes. The visible title is therefore the reliable source.
            val title = rawLabel.replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").trim()
                .takeIf { it.isNotBlank() }
                ?: item.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val isMovie = seasonNumber(title) == null &&
                Regex("""\b(film|movie)\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)
            val image = item.selectFirst("img")
            FrenchMangaCard(
                title = if (isMovie) title else baseTitle(title),
                seasonTitle = title,
                url = url,
                posterUrl = posterUrl(image?.absUrl("src"), image?.absUrl("data-src")),
                isMovie = isMovie,
                season = seasonNumber(title),
                year = yearRegex.find(rawLabel)?.value?.toIntOrNull() ?: yearFrom(url),
                language = null
            )
        }.distinctBy { it.url }
    }

    fun groupCards(cards: List<FrenchMangaCard>): List<FrenchMangaCard> {
        val groups = LinkedHashMap<String, MutableList<FrenchMangaCard>>()
        cards.forEach { card ->
            val key = if (card.isMovie) "movie|${normalize(card.title)}|${card.url}" else "anime|${normalize(card.title)}"
            groups.getOrPut(key) { mutableListOf() }.add(card)
        }
        return groups.values.map { items ->
            val selected = items.maxWithOrNull(
                compareBy<FrenchMangaCard> { it.season ?: 0 }.thenBy { it.year ?: 0 }
            ) ?: items.first()
            selected.copy(title = if (selected.isMovie) selected.title else baseTitle(selected.seasonTitle))
        }
    }

    fun detail(document: Document): FrenchMangaDetail? {
        val data = document.selectFirst("#manga-data") ?: return null
        val seasonTitle = data.attr("data-title").trim().takeIf { it.isNotBlank() } ?: return null
        val isMovie = document.select(".short-meta.short-qual, .mli-type, .film-quality")
            .text().contains("Film", ignoreCase = true)
        val poster = data.attr("data-affiche").trim().takeIf(::isHttpUrl)?.let(::highQualityImage)
        val backdrop = backdropRegex.find(
            document.selectFirst(".hero-backdrop")?.attr("style").orEmpty()
        )?.groupValues?.getOrNull(1)?.replace("&amp;", "&")?.takeIf(::isHttpUrl)
        val tags = data.attr("data-tagz").split(',').map(String::trim).filter(String::isNotBlank)
        val yearText = document.selectFirst(".facts .release, .release")?.text().orEmpty()
        return FrenchMangaDetail(
            newsId = data.attr("data-newsid").trim(),
            title = if (isMovie) seasonTitle else baseTitle(seasonTitle),
            seasonTitle = seasonTitle,
            season = seasonNumber(seasonTitle),
            posterUrl = poster,
            backdropUrl = backdrop,
            year = yearRegex.find(yearText)?.value?.toIntOrNull() ?: yearFrom(document.baseUri()),
            plot = document.selectFirst(".flist .fdesc, .fdesc")?.text()?.trim()?.takeIf { it.isNotBlank() },
            genres = document.select(".facts .genres a, .genres a").map { it.text().trim() }
                .filter(String::isNotBlank).distinct(),
            tags = tags,
            trailerId = data.attr("data-trailer").trim().takeIf { it.isNotBlank() },
            actors = actors(document),
            isMovie = isMovie
        )
    }

    fun seasons(json: String, baseUrl: String): List<FrenchMangaSeason> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val number = item.optInt("season_number", -1).takeIf { it in 1..998 }
                ?: return@mapNotNull null
            val url = resolveUrl(baseUrl, item.optString("full_url")) ?: return@mapNotNull null
            FrenchMangaSeason(
                number = number,
                url = url,
                posterUrl = item.optString("affiche").trim().takeIf(::isHttpUrl),
                year = item.optString("serie_anne").toIntOrNull(),
                newsId = item.optString("id").trim().takeIf { it.isNotBlank() }
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    fun episodes(json: String): List<FrenchMangaEpisodeData> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val info = root.optJSONObject("info")
        val numbers = sortedSetOf<Int>()
        listOf("vf", "vostfr", "info").forEach { section ->
            root.optJSONObject(section)?.keys()?.forEach { it.toIntOrNull()?.let(numbers::add) }
        }
        return numbers.map { number ->
            val metadata = info?.optJSONObject(number.toString())
            val sources = buildList {
                listOf("vf" to "VF", "vostfr" to "VOSTFR").forEach { (section, language) ->
                    root.optJSONObject(section)?.optJSONObject(number.toString())?.let { hosts ->
                        hosts.keys().forEach { host ->
                            hosts.optString(host).trim().takeIf(::isHttpUrl)?.let { url ->
                                add(FrenchMangaSource(language, host, url))
                            }
                        }
                    }
                }
            }
            FrenchMangaEpisodeData(
                number = number,
                title = metadata?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Épisode $number",
                synopsis = metadata?.optString("synopsis")?.trim()?.takeIf { it.isNotBlank() },
                posterUrl = metadata?.optString("poster")?.trim()?.takeIf(::isHttpUrl),
                sources = sources
            )
        }
    }

    fun episodePayload(pageUrl: String, episodeNumber: Int): String {
        return JSONObject().put("url", pageUrl).put("episode", episodeNumber).toString()
    }

    fun episodePayload(payload: String): FrenchMangaEpisodePayload? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val url = root.optString("url").trim().takeIf(::isHttpUrl) ?: return null
        val episode = root.optInt("episode", -1).takeIf { it > 0 } ?: return null
        return FrenchMangaEpisodePayload(url, episode)
    }

    fun baseTitle(title: String): String = title.replace(seasonSuffixRegex, "").trim()

    fun seasonNumber(title: String): Int? {
        return seasonRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun newsId(url: String): String? {
        return Regex("""[?&]newsid=(\d+)""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/(\d+)-[^/]+\.html""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/(\d+)-""").find(url)?.groupValues?.getOrNull(1)
    }

    fun sameSeries(left: String, right: String): Boolean = normalize(baseTitle(left)) == normalize(baseTitle(right))

    private fun actors(document: Document): List<FrenchMangaActor> {
        val json = actorDataRegex.find(document.toString())?.groupValues?.getOrNull(1) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optString(index).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val imageSeparator = entry.lastIndexOf(" - http")
            val credit = if (imageSeparator >= 0) entry.substring(0, imageSeparator).trim() else entry
            val image = if (imageSeparator >= 0) entry.substring(imageSeparator + 3).trim().takeIf(::isHttpUrl) else null
            val roleStart = credit.indexOf(" (")
            if (roleStart < 0 || !credit.endsWith(')')) {
                FrenchMangaActor(credit, null, image)
            } else {
                FrenchMangaActor(
                    name = credit.substring(0, roleStart).trim(),
                    role = credit.substring(roleStart + 2, credit.length - 1).trim().takeIf { it.isNotBlank() },
                    imageUrl = image
                )
            }
        }.distinctBy { it.name to it.role }
    }

    private val tmdbSizeRegex = Regex("""(/t/p/)(?:w|h)\d+(/)""", RegexOption.IGNORE_CASE)

    private fun highQualityImage(url: String?): String? {
        return url?.let { tmdbSizeRegex.replace(it, "$1w780$2") }
    }

    private fun posterUrl(src: String?, lazySrc: String?): String? {
        return listOf(src, lazySrc)
            .firstOrNull { it != null && isHttpUrl(it) && !it.startsWith("data:") }
            ?.let(::highQualityImage)
    }

    private fun yearFrom(value: String): Int? = yearRegex.findAll(value).lastOrNull()?.value?.toIntOrNull()

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun resolveUrl(baseUrl: String, value: String): String? {
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()?.takeIf(::isHttpUrl)
    }

    private fun isHttpUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")
}

internal object FrenchMangaPackedPlayerParser {
    private val mediaRegex = Regex(
        """https?://[^\s\"'\\<>]+?\.(?:m3u8|mp4)(?:\?[^\s\"'\\<>]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val fsvidXorSourceRegex = Regex(
        """var\s+k\s*=\s*\[([0-9,\s]+)]\s*,\s*b\s*=\s*atob\(s\).*?\}\)\(\s*["']([A-Za-z0-9+/_=-]+)["']\s*\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
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

    fun extractMediaUrls(html: String): List<String> {
        val candidates = buildList {
            org.jsoup.Jsoup.parse(html).select("script").forEach { script ->
                val content = script.data().ifBlank { script.html() }
                runCatching { JsUnpacker(content).takeIf { it.detect() }?.unpack() }
                    .getOrNull()?.let(::add)
            }
            runCatching { JsUnpacker(html).takeIf { it.detect() }?.unpack() }
                .getOrNull()?.let(::add)
            add(html)
        }
        return candidates.flatMap { content ->
            val decoded = listOfNotNull(decodeFsvidSource(content))
            val direct = mediaRegex.findAll(content.replace("\\/", "/")).map { match ->
                match.value.replace("&amp;", "&")
            }.toList()
            decoded + direct
        }.distinct().filter(::isPlayableMediaUrl)
    }

    private val resolutionRegex = Regex(
        """(?<!\d)(2160|1440|1080|720|576|540|480|360|240)(?:p)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

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

    fun languagePriority(language: String?): Int {
        return when (language?.uppercase()) {
            "VF", "VFF" -> 0
            "VFQ" -> 1
            "VOSTFR", "VO" -> 2
            else -> 3
        }
    }

    /** Paliers stricts : VF/VFF (0) -> VFQ (1) -> VOSTFR/autres (2). */
    fun languageTier(language: String?): Int {
        return when (language?.uppercase()) {
            "VF", "VFF" -> 0
            "VFQ" -> 1
            else -> 2
        }
    }

    fun hostPriority(url: String): Int {
        val lower = url.lowercase()
        return when {
            "uqload." in lower -> 0
            "luluvdo." in lower || "luluvid" in lower || "lulustream." in lower || "lplaygo." in lower -> 1
            "voe." in lower -> 2
            "vidoza." in lower -> 3
            "sibnet." in lower || "sendvid." in lower -> 4
            "vidmoly." in lower -> 5
            "dood" in lower || "goodstream." in lower -> 6
            "fsvid." in lower || "vidzy." in lower -> 80
            else -> 20
        }
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
