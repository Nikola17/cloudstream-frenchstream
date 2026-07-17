package com.lagradost

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

internal object MovixPackedPlayerParser {
    private val mediaRegex = Regex(
        """https?://[^\s"'\\]+\.(?:m3u8|mp4)(?:\?[^\s"'\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun extractMediaUrls(html: String): List<String> {
        return Jsoup.parse(html)
            .select("script")
            .mapNotNull { script ->
                val packed = script.data().ifBlank { script.html() }
                JsUnpacker(packed).takeIf { it.detect() }?.unpack()
            }
            .flatMap { unpacked -> mediaRegex.findAll(unpacked).map { it.value }.toList() }
            .distinct()
    }
}

internal object MovixExtractorPipeline {
    suspend fun <T> load(
        links: List<String>,
        loader: suspend (String, (T) -> Unit) -> Boolean,
        callback: (T) -> Unit
    ): Boolean {
        var emitted = false

        links.distinct().forEach { link ->
            runCatching {
                loader(link) { item ->
                    emitted = true
                    callback(item)
                }
            }
        }

        return emitted
    }
}

internal abstract class MovixPackedPlayerExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            referer = referer ?: "https://movix.show/",
            headers = mapOf("User-Agent" to USER_AGENT)
        )
        val streamHeaders = mapOf(
            "Referer" to url,
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        MovixPackedPlayerParser.extractMediaUrls(response.text).forEach { streamUrl ->
            val type = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(name, name, streamUrl, type) {
                    this.referer = url
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

internal class MovixVidzyExtractor : MovixPackedPlayerExtractor() {
    override val name = "Vidzy"
    override val mainUrl = "https://vidzy.org"
}

internal class MovixUqloadExtractor : MovixPackedPlayerExtractor() {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.is"
}
