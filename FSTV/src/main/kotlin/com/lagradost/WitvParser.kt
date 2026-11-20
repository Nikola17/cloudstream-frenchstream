package com.lagradost

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal data class WitvCategory(val slug: String, val label: String)

internal data class WitvChannel(val name: String, val url: String, val posterUrl: String? = null)

internal data class WitvPlayerExtraction(
    val directHls: List<String> = emptyList(),
    val embedUrls: List<String> = emptyList()
)

/**
 * WITV (witv.channel en priorité — plateforme officielle avec tous les logos et lecteurs actifs,
 * puis miroirs de secours). Chaque chaîne a une page `/chaines-live/{id}-{slug}.html` (ou `{slug}`)
 * contenant les lecteurs (`/player/source2/?id=…` dans `_wt_vmap` ou en liens directs).
 */
internal object WitvParser {
    val knownBases = listOf(
        "https://witv.channel",
        "https://witv.online",
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

    private val backgroundUrlRegex = Regex(
        """url\(\s*['"]?([^'")]+)""",
        RegexOption.IGNORE_CASE
    )

    // Tableau XOR embarqué sur les pages chaînes de witv.channel : var _a=[...],_m=119
    private val vmapXorRegex = Regex("""var\s+_a=\[([0-9,\s]+)]\s*,\s*_m=(\d+)""")
    private val dataVTokenRegex = Regex("""data-v\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    // Flux HLS encodé dans /player/source2/?id=… : var _wtPayload = "…" (base64 inversé)
    private val wtPayloadRegex = Regex("""_wtPayload\s*=\s*["']([^"']+)["']""")
    private val serverBtnUrlRegex = Regex(
        """class\s*=\s*["'][^"']*server-btn[^"']*["'][^>]*?data-url\s*=\s*["'](https?://[^"']+)["']|data-url\s*=\s*["'](https?://[^"']+)["'][^>]*?class\s*=\s*["'][^"']*server-btn""",
        RegexOption.IGNORE_CASE
    )
    private val playerPathRegex = Regex("""/player/[a-z0-9_-]+/\?[^"'\s<>]*""", RegexOption.IGNORE_CASE)

    // Hébergeurs de logos morts/bloqués à remplacer par un logo de secours fiable.
    private val brokenLogoHosts = listOf("media.info")

    private const val TV_LOGOS_UK =
        "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/united-kingdom"
    private const val TV_LOGOS_FR =
        "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france"
    private const val TV_LOGOS_BE =
        "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/belgium"
    private const val TV_LOGOS_CH =
        "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/switzerland"

    /**
     * Logos de secours vérifiés (GitHub tv-logo/tv-logos) pour les chaînes dont l'image source
     * est cassée (ex: media.info 404 pour TNT Sports 2/3 UK) ou absente sur un miroir de secours.
     * Clé normalisée via [BzhSportParser.channelKey].
     */
    private val fallbackLogos: Map<String, String> = mapOf(
        "tntsport1uk" to "$TV_LOGOS_UK/tnt-sports-1-uk.png",
        "tntsport2uk" to "$TV_LOGOS_UK/tnt-sports-2-uk.png",
        "tntsport3uk" to "$TV_LOGOS_UK/tnt-sports-3-uk.png",
        "tntsport4uk" to "$TV_LOGOS_UK/tnt-sports-4-uk.png",
        "ab1" to "$TV_LOGOS_FR/ab1-fr.png",
        "animaux" to "$TV_LOGOS_FR/animaux-fr.png",
        "6ter" to "$TV_LOGOS_FR/6ter-fr.png",
        "arte" to "$TV_LOGOS_FR/arte-fr.png",
        "tf1" to "$TV_LOGOS_FR/tf1-fr.png",
        "france2" to "$TV_LOGOS_FR/france-2-fr.png",
        "france3" to "$TV_LOGOS_FR/france-3-fr.png",
        "france4" to "$TV_LOGOS_FR/france-4-fr.png",
        "france5" to "$TV_LOGOS_FR/france-5-fr.png",
        "m6" to "$TV_LOGOS_FR/m6-fr.png",
        "w9" to "$TV_LOGOS_FR/w9-fr.png",
        "tmc" to "$TV_LOGOS_FR/tmc-fr.png",
        "tfx" to "$TV_LOGOS_FR/tfx-fr.png",
        "cstar" to "$TV_LOGOS_FR/c-star-fr.png",
        "gulli" to "$TV_LOGOS_FR/gulli-fr.png",
        "automoto" to "$TV_LOGOS_FR/automoto-la-chaine-fr.png",
        "rtbflaune" to "$TV_LOGOS_BE/la-une-be.png",
        "rts1" to "$TV_LOGOS_CH/rts-un-ch.png",
        "rtsun" to "$TV_LOGOS_CH/rts-un-ch.png",
        "rts2" to "$TV_LOGOS_CH/rts-deux-ch.png",
        "rtsdeux" to "$TV_LOGOS_CH/rts-deux-ch.png"
    )

    fun isSiteDocument(document: Document): Boolean {
        val html = document.toString()
        return html.contains("/chaines-live", ignoreCase = true) ||
            document.title().contains("witv", ignoreCase = true)
    }

    fun categoryUrl(base: String, slug: String): String = "$base/chaines-live/$slug/"

    fun channelsUrl(base: String): String = "$base/chaines-live/"

    fun nextPageUrl(document: Document, listUrl: String, page: Int): String? {
        val direct = document.select("a[href]").firstOrNull { anchor ->
            val href = anchor.attr("href")
            href.contains("/page/$page/", ignoreCase = true) ||
                href.endsWith("/page/$page", ignoreCase = true) ||
                href.contains("page=$page", ignoreCase = true)
        }?.absUrl("href")?.takeIf(::isHttpUrl)
        if (direct != null) return direct
        return null
    }

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
            val cardLabel = anchor.parent()?.selectFirst(".ann-short_price")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            val name = ownText.takeIf { it.isNotBlank() && !isGenericCta }
                ?: cardLabel
                ?: cardImage(anchor)?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: slug.replace('-', ' ')
            val poster = findCardPoster(anchor) ?: fallbackLogo(name, slug)
            WitvChannel(name, href, poster)
        }
    }

    fun fallbackLogo(name: String, slug: String = ""): String? {
        val byName = BzhSportParser.channelKey(name)
        fallbackLogos[byName]?.let { return it }
        if (slug.isNotBlank()) {
            val bySlug = BzhSportParser.channelKey(slug.replace('-', ' '))
            fallbackLogos[bySlug]?.let { return it }
        }
        return null
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

    /**
     * Logo d'une carte chaîne : on cherche l'image du lien puis des ancêtres proches,
     * mais uniquement quand le bloc ne contient qu'une seule image (sinon on attraperait
     * le logo d'une autre carte), et sinon un background-image CSS sur le bloc.
     */
    private fun findCardPoster(anchor: Element): String? {
        var node: Element? = anchor
        repeat(4) {
            val current = node ?: return@repeat
            cardImage(current)?.let { img -> imageUrlFrom(img)?.let { return it } }
            current.attr("style").takeIf { it.isNotBlank() }?.let { style ->
                backgroundUrlRegex.find(style)?.groupValues?.getOrNull(1)
                    ?.trim()?.takeIf { it.isNotBlank() && isRealImage(it) }
                    ?.let { raw -> resolveUrl(current.baseUri(), raw) }
                    ?.let { return it }
            }
            node = current.parent()
        }
        return null
    }

    /** Image d'un bloc de carte : l'unique img du bloc, en remontant au plus 3 niveaux. */
    private fun cardImage(element: Element): Element? {
        var node: Element? = element
        repeat(4) {
            val current = node ?: return null
            val images = current.select("img")
            if (images.size == 1) return images.first()
            node = current.parent()
        }
        return null
    }

    private fun imageUrlFrom(img: Element): String? {
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("srcset").split(',').firstOrNull()?.trim()?.substringBefore(' ').orEmpty(),
            img.attr("src")
        )
        for (raw in candidates) {
            val value = raw.trim().takeIf { it.isNotBlank() } ?: continue
            if (!isRealImage(value)) continue
            val resolved = resolveUrl(img.baseUri(), value) ?: continue
            if (isRealImage(resolved)) return resolved
        }
        return null
    }

    private fun isRealImage(url: String): Boolean {
        val u = url.lowercase()
        if (u.startsWith("data:") || u.contains("placeholder") ||
            u.contains("no-image") || u.contains("noimage") || u.endsWith(".svg")
        ) return false
        return brokenLogoHosts.none { u.contains(it) }
    }

    /** Logo d'une page chaîne : on privilégie une image /logo, sinon la première image utile. */
    fun pagePoster(document: Document): String? {
        val images = document.select("img")
        val found = images
            .firstOrNull { isRealImage(it.attr("src")) && it.absUrl("src").contains("/logo", ignoreCase = true) }
            ?.let { imageUrlFrom(it) }
            ?: images.firstOrNull { isRealImage(it.attr("src")) || isRealImage(it.attr("data-src")) }
                ?.let { imageUrlFrom(it) }
        return found ?: pageTitle(document)?.let { fallbackLogo(it, slugOf(document.baseUri())) }
    }

    /**
     * Liens de lecteurs d'une page chaîne :
     * 1. Carte `_wt_vmap` de witv.channel (XOR + base64 JSON associée aux conteneurs `data-v`).
     * 2. Liens `/player/sourceN/?id=…` (href ou HTML) et iframes (`src` ou `data-src`).
     */
    fun playerLinks(document: Document): List<String> {
        val base = document.baseUri()
        val html = document.toString()

        val fromVmap = extractVmapPlayerLinks(html, base)
        val fromHrefs = document.select("a[href]").mapNotNull { anchor ->
            val raw = anchor.attr("href")
            if (!raw.contains("/player/", ignoreCase = true)) return@mapNotNull null
            resolveUrl(base, raw)
        }
        val fromHtml = playerPathRegex.findAll(html).mapNotNull { resolveUrl(base, it.value) }.toList()
        val frames = document.select("iframe").flatMap { frame ->
            listOf(frame.attr("src"), frame.attr("data-src"))
                .mapNotNull { resolveUrl(base, it) }
        }
        return (fromVmap + fromHrefs + fromHtml + frames)
            .filterNot { it.contains("/player/*", ignoreCase = true) }
            .filter(BzhSportParser::usableFrame)
            .distinct()
    }

    /** Décode la table `_wt_vmap` de witv.channel en respectant l'ordre des onglets `data-v`. */
    internal fun extractVmapPlayerLinks(html: String, baseUrl: String): List<String> {
        val tokens = dataVTokenRegex.findAll(html).map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
        val directNonWt1 = tokens.filterNot { it.startsWith("wt1_") }
            .mapNotNull { resolveUrl(baseUrl, it) }

        val match = vmapXorRegex.find(html) ?: return directNonWt1.distinct()
        val mask = match.groupValues[2].toIntOrNull() ?: return directNonWt1.distinct()
        val decoded = buildString {
            match.groupValues[1].split(',').forEach { part ->
                val code = part.trim().toIntOrNull() ?: return@forEach
                append((code xor mask).toChar())
            }
        }
        val b64 = decoded.split('|').getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: return directNonWt1.distinct()
        val jsonText = decodeBase64(b64) ?: return directNonWt1.distinct()
        val vmap = runCatching { JSONObject(jsonText) }.getOrNull() ?: return directNonWt1.distinct()

        val ordered = mutableListOf<String>()
        for (token in tokens) {
            val raw = vmap.optString(token).trim()
            if (raw.isNotBlank()) resolveUrl(baseUrl, raw)?.let(ordered::add)
        }
        if (ordered.isEmpty()) {
            vmap.keys().forEach { key ->
                val raw = vmap.optString(key).trim()
                if (raw.isNotBlank()) resolveUrl(baseUrl, raw)?.let(ordered::add)
            }
        }
        return (ordered + directNonWt1).distinct()
    }

    /**
     * Analyse la réponse d'une page lecteur WITV (`/player/source2/?id=…`) :
     * - `_wtPayload` (base64 inversé) → URL HLS directe (ex: `/player/source2/?vp=…&tk=…&vc=…`),
     * - sinon boutons `server-btn[data-url]` et `iframe[src]` vers des miroirs externes.
     * Le leurre anti-hotlink `vc=notice` est toujours ignoré.
     */
    fun extractPlayerStreams(html: String, playerPageUrl: String): WitvPlayerExtraction {
        val directHls = mutableListOf<String>()
        wtPayloadRegex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            decodeBase64(raw.reversed())
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.contains("vc=notice", ignoreCase = true) }
                ?.let { resolveUrl(playerPageUrl, it) }
                ?.let(directHls::add)
        }
        BzhSportParser.hlsUrls(html)
            .filterNot { it.contains("vc=notice", ignoreCase = true) }
            .forEach { directHls.add(it) }

        val embedUrls = mutableListOf<String>()
        serverBtnUrlRegex.findAll(html).forEach { match ->
            val raw = match.groupValues[1].ifBlank { match.groupValues[2] }
            resolveUrl(playerPageUrl, raw)?.takeIf(BzhSportParser::usableFrame)?.let(embedUrls::add)
        }
        BzhSportParser.iframeUrls(html, playerPageUrl)
            .filterNot { it.contains("vc=notice", ignoreCase = true) }
            .forEach(embedUrls::add)

        return WitvPlayerExtraction(
            directHls = directHls.distinct(),
            embedUrls = embedUrls.distinct()
        )
    }

    /** Décodeur Base64 standard + URL-safe pur Kotlin (compatible Android API 21 et tests JVM). */
    internal fun decodeBase64(input: String): String? {
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
        return String(out, 0, outLen, Charsets.UTF_8)
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
        val trimmed = value.trim().replace(" ", "%20").takeIf { it.isNotBlank() } ?: return null
        val absolute = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        return runCatching {
            val resolved = if (baseUrl.isBlank()) URI(absolute) else URI(baseUrl).resolve(absolute)
            resolved.toASCIIString()
        }.getOrNull()?.takeIf(::isHttpUrl)
    }

    private fun isHttpUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")
}
