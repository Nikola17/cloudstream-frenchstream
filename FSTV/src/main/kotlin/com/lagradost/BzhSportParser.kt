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
    val iconUrl: String?,
    val isLive: Boolean,
    val position: Int
)

internal data class BzhChannel(val name: String, val url: String)

internal data class BzhEmbed(val label: String, val url: String)

internal object BzhSportParser {
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
                iconUrl = cells.firstOrNull()?.selectFirst("img")?.absUrl("src")?.takeIf(::isImageUrl),
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

    private fun isImageUrl(value: String): Boolean {
        return isHttpUrl(value) && Regex("""\.(png|jpe?g|gif|webp|svg)(\?.*)?$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://") || value.startsWith("http://")
    }
}
