package com.lagradost

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer

internal enum class BzhEventDay { TODAY, TOMORROW, LATER }

internal data class BzhEvent(
    val title: String,
    val competition: String?,
    val dayLabel: String,
    val day: BzhEventDay,
    val time: String?,
    val url: String,
    val sport: String,
    val isLive: Boolean,
    val position: Int
)

internal data class BzhChannel(val name: String, val url: String)

internal data class BzhEmbed(val label: String, val url: String)

internal object BzhSportParser {
    private const val SPORT_POSTER_BASE =
        "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/main/assets/sports"
    const val DEFAULT_SPORT = "sport"

    val sportLabels = mapOf(
        "foot" to "Football", "basket" to "Basket", "tennis" to "Tennis", "rugby" to "Rugby",
        "handball" to "Handball", "cyclisme" to "Cyclisme", "f1" to "Formule 1", "moto" to "Moto",
        "footus" to "Football US", "hockey" to "Hockey", "boxe" to "Boxe", "mma" to "MMA",
        "combat" to "Sports de combat", "judo" to "Judo", "athletisme" to "Athlétisme",
        "biathlon" to "Biathlon", "hiver" to "Sports d'hiver", "flechettes" to "Fléchettes",
        "golf" to "Golf", "rallye" to "Rallye", "motocross" to "Motocross", "auto" to "Sport auto",
        "billard" to "Billard", "volley" to "Volley", "padel" to "Padel", "squash" to "Squash",
        "pingpong" to "Tennis de table", "baseball" to "Baseball", "natation" to "Natation",
        "tir" to "Tir", "magazine" to "Émission", DEFAULT_SPORT to "Sport"
    )

    // Identifiants des catégories du site (icône assets/uploads/cat/<id>.png).
    private val sportsByCategory = mapOf(
        1 to "foot", 2 to "basket", 3 to "tennis", 4 to "rugby", 5 to "handball", 6 to "cyclisme",
        7 to "f1", 8 to "moto", 9 to "footus", 10 to "hockey", 11 to "boxe", 12 to "mma",
        13 to "magazine", 14 to "athletisme", 15 to "biathlon", 16 to "hiver", 17 to "flechettes",
        18 to "golf", 19 to "rallye", 20 to "motocross", 21 to "billard", 22 to "combat",
        24 to "volley", 25 to "padel", 27 to "squash", 32 to "judo", 34 to "pingpong",
        37 to "auto", 39 to "baseball", 59 to "tir"
    )

    // Secours quand la catégorie est inconnue : premier mot-clé trouvé.
    private val sportKeywords = listOf(
        listOf("natation", "nage libre", "brasse", "papillon", "dos crawl") to "natation",
        listOf("formule 1", "grand prix de f1") to "f1",
        listOf("moto gp", "motogp", "moto 2", "moto 3", "superbike") to "moto",
        listOf("nba", "euroleague", "betclic elite", "liga endesa", "basket") to "basket",
        listOf("top 14", "pro d2", "rugby", "six nations", "champions cup") to "rugby",
        listOf("nfl", "ncaa") to "footus",
        listOf("nhl", "ligue magnus", "hockey") to "hockey",
        listOf("ufc", "pfl", "ksw", "mma") to "mma",
        listOf("boxe") to "boxe",
        listOf("judo") to "judo",
        listOf("taekwondo", "karate", "kick-boxing", "lutte") to "combat",
        listOf("starligue", "ehf", "handball") to "handball",
        listOf("atp", "wta", "masters 1000", "roland-garros", "wimbledon", "tennis") to "tennis",
        listOf("tour de", "cyclisme", "vtt") to "cyclisme",
        listOf("athletisme", "marathon", "100m", "200m") to "athletisme",
        listOf("biathlon") to "biathlon",
        listOf("ski", "snowboard") to "hiver",
        listOf("golf") to "golf",
        listOf("rallye", "wrc") to "rallye",
        listOf("volley") to "volley",
        listOf("padel") to "padel",
        listOf("squash") to "squash",
        listOf("tennis de table", "wtt") to "pingpong",
        listOf("mlb", "baseball") to "baseball",
        listOf("magazine") to "magazine",
        listOf(
            "ligue", "league", "liga", "serie a", "bundesliga", "eredivisie", "premiership",
            "super lig", "mls", "coupe", "cup", "championship"
        ) to "foot"
    )
    private val categoryRegex = Regex("""/cat/(\d+)\.""")
    private val embedRegex = Regex("""embeds\[(\d+)]\s*=\s*'((?:\\'|[^'])*)'""")
    private val titleRegex = Regex("""titles\[(\d+)]\s*=\s*'((?:\\'|[^'])*)'""")
    private val iframeSourceRegex = Regex(
        """<iframe[^>]*?\ssrc\s*=\s*["']\s*([^"']+?)\s*["']""",
        RegexOption.IGNORE_CASE
    )
    private val hlsRegex = Regex(
        """https?://[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val bareUrlRegex = Regex("""https?://[a-z0-9.-]+\.[a-z]{2,}""", RegexOption.IGNORE_CASE)
    private val liveStatusRegex = Regex("""/live\.(gif|png|webp)""", RegexOption.IGNORE_CASE)
    private val sitePathRegex = Regex(
        """^/(regarder-la-chaine-[^/]+|[^/]+-streaming-direct)/?$""",
        RegexOption.IGNORE_CASE
    )
    private val siteNameRegex = Regex("""ohquelbut|quelbut|volka|bzh""", RegexOption.IGNORE_CASE)
    private val ignoredPortalHosts = listOf(
        "chatango.com", "rentry.co", "rentry.org", "discord", "t.me", "telegram",
        "google.com", "github.io", "opengraphprotocol.org"
    )
    private val ignoredFrameMarkers = listOf("/ads/", "histats", "youtube.com", "youtu.be", "chatango")

    // First match wins, so the more specific competitions come before the broader ones.
    private val competitionScores = listOf(
        listOf("magazine") to 10,
        listOf("ehf", "starligue", "handball") to 45,
        listOf("ligue des champions", "champions league", "multiplex - ldc") to 100,
        listOf("coupe du monde", "ligue des nations", "euro 20", "eliminatoires", "amical international") to 95,
        listOf("ligue 1") to 95,
        listOf("formule 1", "grand prix de f1") to 90,
        listOf("tour de france") to 85,
        listOf("premier league") to 85,
        listOf("laliga 2", "liga 2") to 35,
        listOf("laliga", "liga espagnole") to 80,
        listOf("serie a bresil") to 30,
        listOf("serie a") to 75,
        listOf("bundesliga 2") to 35,
        listOf("bundesliga") to 75,
        listOf("europa league") to 75,
        listOf("top 14", "six nations", "tournoi des 6 nations") to 75,
        listOf("nba") to 75,
        listOf("ufc") to 75,
        listOf("roland-garros", "roland garros", "wimbledon", "us open", "open d australie", "atp finals") to 75,
        listOf("coupe de france") to 70,
        listOf("moto gp", "motogp") to 70,
        listOf("champions cup", "test match") to 65,
        listOf("conference league") to 60,
        listOf("masters 1000") to 60,
        listOf("ligue 2") to 55,
        listOf("nfl") to 55,
        listOf("euroleague", "betclic elite") to 50,
        listOf("tour de lombardie", "paris-roubaix", "paris-tours", "milan-san remo", "liege-bastogne") to 50,
        listOf("pro d2", "challenge cup", "pfl") to 40,
        listOf(
            "championship", "eredivisie", "liga portugal", "pro league", "premiership",
            "super lig", "mls", "saudi pro league", "nhl", "wta", "moto 2", "moto 3", "ligue 3"
        ) to 35
    )
    private val bigTeamRegexes = listOf(
        "psg", "paris saint-germain", "paris sg", "marseille", "real madrid", "fc barcelone", "barcelone",
        "liverpool", "manchester city", "manchester united", "arsenal", "chelsea", "bayern",
        "juventus", "inter", "ac milan", "lyon", "monaco", "lens", "lille", "stade toulousain"
    ).map { Regex("""\b${Regex.escape(it)}\b""") }
    private val franceRegex = Regex("""\bfrance\b""")

    fun isSiteDocument(document: Document): Boolean {
        return document.select("tbody.events tr.event, div.channels a[href], .video-item").isNotEmpty()
    }

    fun isSitePath(url: String): Boolean {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return false
        return sitePathRegex.matches(path)
    }

    fun events(document: Document): List<BzhEvent> {
        return document.select("tbody.events tr.event").mapIndexedNotNull { index, row ->
            val anchor = row.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val url = anchor.absUrl("href").takeIf(::isHttpUrl) ?: return@mapIndexedNotNull null
            val title = row.selectFirst(".event-title")?.text()?.let(::cleanText)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val cells = row.select("td")
            val dayLabel = cells.getOrNull(1)?.text()?.let(::cleanText).orEmpty()
            val competition = row.selectFirst("td.hidden-xs")?.text()?.let(::cleanText)
                ?.takeIf { it.isNotBlank() }
            val status = cells.lastOrNull()?.selectFirst("img")?.attr("src").orEmpty()
            BzhEvent(
                title = title,
                competition = competition,
                dayLabel = dayLabel,
                day = day(dayLabel),
                time = row.selectFirst("td.dt")?.text()?.trim()?.takeIf { it.isNotBlank() },
                url = url,
                sport = sport(
                    cells.firstOrNull()?.selectFirst("img")?.attr("src").orEmpty(),
                    listOfNotNull(competition, title).joinToString(" ")
                ),
                isLive = liveStatusRegex.containsMatchIn(status),
                position = index
            )
        }.distinctBy { it.url }
    }

    fun channels(document: Document): List<BzhChannel> {
        return document.select("div.channels a[href]").mapNotNull { anchor ->
            val name = cleanText(anchor.text()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = anchor.absUrl("href").takeIf(::isHttpUrl) ?: return@mapNotNull null
            BzhChannel(name, url)
        }.distinctBy { it.url }
    }

    fun sport(iconUrl: String, text: String): String {
        categoryRegex.find(iconUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let(sportsByCategory::get)
            ?.let { return it }
        val normalized = normalize(text)
        return sportKeywords.firstOrNull { (keywords, _) -> keywords.any { normalized.contains(it) } }
            ?.second
            ?: DEFAULT_SPORT
    }

    fun posterUrl(sport: String): String {
        val key = sport.takeIf { it in sportLabels } ?: DEFAULT_SPORT
        return "$SPORT_POSTER_BASE/$key.webp"
    }

    fun pageTitle(document: Document): String? {
        return document.selectFirst(".panel-body h1, h1")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }
    }

    fun embeds(document: Document): List<BzhEmbed> {
        val html = document.toString()
        val titles = mutableMapOf<Int, String>()
        titleRegex.findAll(html).forEach { match ->
            val index = match.groupValues[1].toInt()
            val title = cleanText(unescapeJs(match.groupValues[2]))
                .replace(Regex("""^Link\s+(\d+)$""", RegexOption.IGNORE_CASE), "Lecteur $1")
            if (title.isNotBlank() && index !in titles) titles[index] = title
        }
        val tabs = document.select(".video-btn").map { cleanText(it.text()) }
        val scripted = embedRegex.findAll(html).mapNotNull { match ->
            val index = match.groupValues[1].toInt()
            val source = iframeSourceRegex.find(unescapeJs(match.groupValues[2]))
                ?.groupValues?.getOrNull(1)
                ?.let { resolveUrl(document.baseUri(), it) }
                ?: return@mapNotNull null
            val label = titles[index]
                ?: tabs.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: "Lecteur ${index + 1}"
            BzhEmbed(label, source)
        }.toList()
        val inline = document.select(".video iframe[src]").mapIndexedNotNull { index, frame ->
            resolveUrl(document.baseUri(), frame.attr("src"))?.let { BzhEmbed("Lecteur ${index + 1}", it) }
        }
        return (scripted + inline).filter { isUsableFrame(it.url) }.distinctBy { it.url }
    }

    fun hlsUrls(html: String): List<String> {
        val cleaned = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        return hlsRegex.findAll(cleaned).map { it.value }.distinct().toList()
    }

    fun iframeUrls(html: String, baseUrl: String): List<String> {
        return iframeSourceRegex.findAll(html).mapNotNull { match ->
            resolveUrl(baseUrl, match.groupValues[1])
        }.filter(::isUsableFrame).distinct().toList()
    }

    fun rentryBaseUrls(document: Document): List<String> {
        return document.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").takeIf(::isHttpUrl) ?: return@mapNotNull null
            if (!siteNameRegex.containsMatchIn(anchor.text()) && !siteNameRegex.containsMatchIn(href)) {
                return@mapNotNull null
            }
            origin(href)?.takeUnless(::isIgnoredPortal)
        }.distinct()
    }

    fun chatangoBaseUrls(html: String): List<String> {
        val document = Jsoup.parse(html)
        val text = listOf(document.title(), document.select(".group_title").text()).joinToString(" ")
        return bareUrlRegex.findAll(text).mapNotNull { origin(it.value) }
            .filterNot(::isIgnoredPortal)
            .distinct()
            .toList()
    }

    fun channelKey(name: String): String {
        return normalize(name)
            .replace("+", " plus ")
            .replace(Regex("""\bl'"""), " ")
            .replace(Regex("""\bsports\b"""), "sport")
            .replace(Regex("""\b(live tv|la chaine|tv hd|fhd|uhd|hd)\b"""), " ")
            .replace(Regex("""[^a-z0-9]"""), "")
    }

    fun findChannel(channels: List<BzhChannel>, name: String): BzhChannel? {
        val key = channelKey(name).takeIf { it.isNotBlank() } ?: return null
        return channels.firstOrNull { channelKey(it.name) == key }
    }

    fun popularity(event: BzhEvent): Int {
        val text = normalize(listOfNotNull(event.competition, event.title).joinToString(" "))
        val title = normalize(event.title)
        var score = competitionScores.firstOrNull { (keywords, _) ->
            keywords.any { text.contains(it) }
        }?.second ?: 25
        if (score > 10) {
            if (bigTeamRegexes.any { it.containsMatchIn(title) }) score += 15
            if (franceRegex.containsMatchIn(title)) score += 20
            if (title.contains("finale") && !title.contains("de finale")) score += 10
        }
        return score
    }

    fun ranked(events: List<BzhEvent>): List<BzhEvent> {
        return events.sortedWith(
            compareByDescending<BzhEvent> { popularity(it) }.thenBy { it.position }
        )
    }

    private fun day(label: String): BzhEventDay {
        val normalized = normalize(label)
        return when {
            normalized.startsWith("aujourd") -> BzhEventDay.TODAY
            normalized.startsWith("demain") -> BzhEventDay.TOMORROW
            else -> BzhEventDay.LATER
        }
    }

    private fun isUsableFrame(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (!host.contains('.')) return false
        return ignoredFrameMarkers.none { url.contains(it, ignoreCase = true) }
    }

    private fun isIgnoredPortal(url: String): Boolean {
        return ignoredPortalHosts.any { url.contains(it, ignoreCase = true) }
    }

    private fun unescapeJs(value: String): String {
        return value.replace("\\'", "'").replace("\\\"", "\"").replace("\\/", "/")
    }

    private fun cleanText(value: String): String {
        return value.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .trim()
    }

    private fun resolveUrl(baseUrl: String, value: String): String? {
        val trimmed = value.trim().takeIf { it.isNotBlank() } ?: return null
        val absolute = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        return runCatching {
            if (baseUrl.isBlank()) URI(absolute).toString() else URI(baseUrl).resolve(absolute).toString()
        }.getOrNull()?.takeIf(::isHttpUrl)
    }

    private fun origin(url: String): String? {
        return runCatching { URI(url) }.getOrNull()
            ?.takeIf { it.scheme != null && it.authority != null }
            ?.let { "${it.scheme}://${it.authority}" }
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://") || value.startsWith("http://")
    }
}
