package com.lagradost

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI

internal data class FSTVCategory(val slug: String, val label: String)

internal data class FSTVChannel(
    val name: String,
    val url: String,
    val posterUrl: String?
)

internal data class FSTVPlayerConfig(
    val name: String,
    val streamUrl: String,
    val posterUrl: String?
)

internal data class FSTVSource(
    val id: String,
    val quality: Int,
    val label: String
)

internal data class FSTVProgram(
    val currentTitle: String,
    val category: String?,
    val remainingSeconds: Int?,
    val nextTitle: String?
)

internal object FSTVParser {
    val categories = listOf(
        FSTVCategory("sport", "Chaînes Sport"),
        FSTVCategory("cinema", "Chaînes Cinéma"),
        FSTVCategory("generaliste", "Chaînes Généralistes"),
        FSTVCategory("enfants", "Chaînes Jeunesse"),
        FSTVCategory("documentaire", "Chaînes Documentaire"),
        FSTVCategory("musique", "Chaînes Musique"),
        FSTVCategory("information", "Chaînes Info")
    )

    private val playerNameRegex = Regex(
        """window\.FSTV_NAME\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val playerSourceRegex = Regex(
        """window\.FSTV_SRC\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    fun channels(document: Document): List<FSTVChannel> {
        return document.select("div.short").mapNotNull { card ->
            val anchor = card.selectFirst("a.short-poster[href]") ?: return@mapNotNull null
            val name = card.selectFirst(".short-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("alt").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = anchor.absUrl("href").takeIf(::isHttpUrl) ?: return@mapNotNull null
            val image = anchor.selectFirst("img")
            val poster = image?.absUrl("src")?.takeIf(::isHttpUrl)
                ?: image?.absUrl("data-src")?.takeIf(::isHttpUrl)
            FSTVChannel(name, url, poster)
        }.distinctBy { it.url }
    }

    fun playerConfig(document: Document): FSTVPlayerConfig? {
        val html = document.toString()
        val name = playerNameRegex.find(html)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val source = playerSourceRegex.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .lastOrNull()
            ?.replace("&amp;", "&")
            ?: return null
        val streamUrl = resolveUrl(document.baseUri(), source) ?: return null
        val posterElement = document.selectFirst("#posterImage")
        val poster = if (posterElement != null) {
            val image = posterElement
            image.absUrl("src").takeIf(::isHttpUrl)
                ?: image.absUrl("data-src").takeIf(::isHttpUrl)
        } else null
        return FSTVPlayerConfig(name, streamUrl, poster)
    }

    fun sources(json: String): List<FSTVSource> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val qualityLabel = item.optString("q").trim().uppercase()
            val country = item.optString("ct").trim()
            val service = item.optString("s").trim()
            FSTVSource(
                id = id,
                quality = quality(qualityLabel),
                label = listOfNotNull(
                    countryCode(country),
                    qualityLabel.takeIf { it.isNotBlank() && it != "NONE" },
                    serviceLabel(service)
                ).joinToString(" ")
            )
        }.distinctBy { it.id }
            .sortedWith(
                compareBy<FSTVSource> { source ->
                    if (source.label.startsWith("FR ") || source.label == "FR") 0 else 1
                }.thenByDescending { it.quality }
                    .thenBy { it.label }
            )
    }

    fun portalTvBaseUrl(document: Document): String? {
        return document.select("a[href]").asSequence().mapNotNull { anchor ->
            val text = anchor.text().trim()
            val href = anchor.absUrl("href").takeIf(::isHttpUrl) ?: return@mapNotNull null
            if (!text.contains("TV", ignoreCase = true) &&
                !text.contains("Regarder La TV", ignoreCase = true) &&
                !href.contains("fstv", ignoreCase = true)
            ) return@mapNotNull null
            runCatching { URI(href) }.getOrNull()?.let { uri ->
                "${uri.scheme}://${uri.authority}"
            }
        }.firstOrNull()
    }

    fun program(json: String): FSTVProgram? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val current = root.optJSONObject("current") ?: return null
        val title = current.optString("title").trim().takeIf { it.isNotBlank() } ?: return null
        val category = current.optString("category").trim().takeIf { it.isNotBlank() }
        val remaining = current.optInt("remaining_sec", -1).takeIf { it >= 0 }
        val nextTitle = root.optJSONObject("next")?.optString("title")
            ?.trim()?.takeIf { it.isNotBlank() }
        return FSTVProgram(title, category, remaining, nextTitle)
    }

    fun highestHlsQuality(manifest: String): Int? {
        return Regex("""RESOLUTION\s*=\s*\d+x(\d+)""", RegexOption.IGNORE_CASE)
            .findAll(manifest)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
    }

    private fun quality(label: String): Int {
        return when (label) {
            "4K", "UHD" -> 2160
            "FHD", "FULLHD", "FULL HD" -> 1080
            "HD" -> 720
            "SD" -> 480
            else -> 0
        }
    }

    private fun countryCode(country: String): String? {
        return when (country.lowercase()) {
            "france" -> "FR"
            "united kingdom" -> "UK"
            "turkey" -> "TR"
            "spain" -> "ES"
            "italy" -> "IT"
            "germany" -> "DE"
            "belgium" -> "BE"
            "switzerland" -> "CH"
            "canada" -> "CA"
            "qatar" -> "QA"
            "morocco" -> "MA"
            "algeria" -> "DZ"
            "tunisia" -> "TN"
            else -> country.takeIf { it.isNotBlank() }?.take(2)?.uppercase()
        }
    }

    private fun serviceLabel(service: String): String? {
        return when (service.lowercase()) {
            "basic" -> "TNT"
            "satellite" -> "Satellite"
            "cable" -> "Câble"
            else -> service.takeIf { it.isNotBlank() }
        }
    }

    private fun resolveUrl(baseUrl: String, value: String): String? {
        return runCatching { URI(baseUrl).resolve(value).toString() }
            .getOrNull()
            ?.takeIf(::isHttpUrl)
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://") || value.startsWith("http://")
    }
}
