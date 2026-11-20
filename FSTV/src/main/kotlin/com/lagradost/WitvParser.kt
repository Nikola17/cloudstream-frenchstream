package com.lagradost

import org.jsoup.nodes.Document
import java.net.URI
import java.text.Normalizer

internal data class WitvCategory(val slug: String, val label: String)

internal data class WitvChannel(val name: String, val url: String, val posterUrl: String? = null)

/**
 * WITV (witv.online / witv.channel / witv.team / witv.football / witv.space) : annuaire de
 * chaînes live. Chaque chaîne a une page `/chaines-live/{slug}` (ou `{id}-{slug}.html`) qui
 * liste des serveurs `/player/sourceN/?id=…` ; chaque serveur embarque le flux (iframe ou HLS).
 */
internal object WitvParser {
    val knownBases = listOf(
        "https://witv.online",
        "https://witv.channel",
        "https://witv.team",
        "https://witv.football",
        "https://witv.space"
    )

    val channelCategories = listOf(
        WitvCategory("sport", "Chaînes Sport"),
        WitvCategory("cinema", "Chaînes Cinéma"),
        WitvCategory("documentaire", "Chaînes Documentaires"),
        WitvCategory("generaliste", "Chaînes Généralistes"),
        WitvCategory("enfants", "Chaînes Jeunesse"),
        WitvCategory("info", "Chaînes Info"),
        WitvCategory("musique", "Chaînes Musique"),
        WitvCategory("belgique", "Chaînes Belgique"),
        WitvCategory("suisse", "Chaînes Suisse")
    )

    private val categorySlugs = channelCategories.map { it.slug }.toSet() +
        setOf("chaines", "live", "toutes-les-chaines", "chaines-live")

    // /chaines-live/tf1  ou  /chaines-live/27-tf1.html
    private val channelPathRegex = Regex(
        """^/chaines-live/(?:(\d+)-)?([a-z0-9][a-z0-9-]*?)(?:\.html)?$""",
        RegexOption.IGNORE_CASE
    )

    fun isSiteDocument(document: Document): Boolean {
        val html = document.toString()
        return html.contains("/chaines-live", ignoreCase = true) ||
            document.title().contains("witv", ignoreCase = true)
    }

    fun categoryUrl(base: String, slug: String): String = "$base/chaines-live/$slug/"

    fun channelsUrl(base: String): String = "$base/chaines-live"

    fun channels(document: Document): List<WitvChannel> {
        val seen = mutableSetOf<String>()
        return document.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").takeIf(::isHttpUrl) ?: return@mapNotNull null
            val path = runCatching { URI(href).rawPath }.getOrNull()?.trimEnd('/') ?: return@mapNotNull null
            val match = channelPathRegex.matchEntire(path) ?: return@mapNotNull null
            val slug = match.groupValues[2].lowercase()
            if (slug in categorySlugs || !seen.add(slug)) return@mapNotNull null
            val ownText = anchor.text().trim()
            val genericWords = setOf(
                "live", "regarder", "voir", "stream", "direct", "gratuit", "hd", "fhd", "uhd",
                "tv", "chaine", "chaîne", "en", "la", "le", "les", "cliquez", "ici", "play"
            )
            val isGenericCta = ownText.isNotBlank() &&
                ownText.lowercase().split(Regex("""\s+""")).all { it in genericWords }
            val name = ownText.takeIf { it.isNotBlank() && !isGenericCta }
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: anchor.parent()?.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: anchor.parent()?.ownText()?.trim()?.takeIf { it.isNotBlank() }
                ?: slug.replace('-', ' ')
            val poster = (anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img"))
                ?.absUrl("src")?.takeIf(::isHttpUrl)
            WitvChannel(name, href, poster)
        }
    }

    /** Titre d'une page chaîne : « TF1 - Live Tv Streaming » → « TF1 ». */
    fun pageTitle(document: Document): String? {
        val raw = document.selectFirst("h1")?.text()
            ?: document.selectFirst("h2")?.text()
            ?: document.title()
        return cleanTitle(raw)
    }

    private fun cleanTitle(value: String): String? {
        val title = value.trim()
            .substringBefore(" - Live Tv Streaming")
            .substringBefore(" – Live Tv Streaming")
            .trim()
        return title.takeIf { it.isNotBlank() && !it.equals("chaines", ignoreCase = true) }
    }

    /** Liens de lecteurs d'une page chaîne : hrefs /player/sourceN/?id=… et iframes directes. */
    fun playerLinks(document: Document): List<String> {
        val fromHrefs = document.select("a[href]").mapNotNull { anchor ->
            val raw = anchor.attr("href")
            if (!raw.contains("/player/", ignoreCase = true)) return@mapNotNull null
            resolveUrl(document.baseUri(), raw)
        }
        val fromHtml = Regex("""/player/[a-z0-9_-]+/\?[^"'\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(document.toString())
            .mapNotNull { resolveUrl(document.baseUri(), it.value) }
        val frames = document.select("iframe[src]").mapNotNull { frame ->
            resolveUrl(document.baseUri(), frame.attr("src"))
        }
        return (fromHrefs + fromHtml + frames)
            .filterNotNull()
            .filter(BzhSportParser::usableFrame)
            .distinct()
    }

    fun findChannel(channels: List<WitvChannel>, name: String): WitvChannel? {
        val key = BzhSportParser.channelKey(name).takeIf { it.isNotBlank() } ?: return null
        return channels.firstOrNull { BzhSportParser.channelKey(it.name) == key }
            ?: channels.firstOrNull { BzhSportParser.channelKey(slugOf(it.url)) == key }
    }

    fun findChannelByUrl(channels: List<WitvChannel>, url: String): WitvChannel? {
        val path = runCatching { URI(url).rawPath?.trimEnd('/') }.getOrNull() ?: return null
        return channels.firstOrNull { channel ->
            runCatching { URI(channel.url).rawPath?.trimEnd('/') }.getOrNull() == path
        }
    }

    fun slugOf(url: String): String {
        val path = runCatching { URI(url).rawPath }.getOrNull()?.trimEnd('/') ?: return url
        return channelPathRegex.matchEntire(path)?.groupValues?.get(2) ?: path.substringAfterLast('/')
    }

    private fun resolveUrl(baseUrl: String, value: String): String? {
        val trimmed = value.trim().takeIf { it.isNotBlank() } ?: return null
        val absolute = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        return runCatching {
            if (baseUrl.isBlank()) URI(absolute).toString() else URI(baseUrl).resolve(absolute).toString()
        }.getOrNull()?.takeIf(::isHttpUrl)
    }

    private fun isHttpUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")
}
