package com.lagradost

import com.lagradost.cloudstream3.utils.ExtractorLink

internal data class FrenchStreamResolvedLink(
    val language: String?,
    val link: ExtractorLink
)

internal object FrenchStreamQuality {
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

    fun bestFirst(sources: List<FrenchStreamResolvedLink>): List<FrenchStreamResolvedLink> {
        return sources
            .distinctBy { "${it.language.orEmpty()}|${it.link.url}" }
            .sortedWith(
                compareBy<FrenchStreamResolvedLink> { languagePriority(it.language) }
                    .thenByDescending { it.link.quality }
                    .thenBy { it.link.source }
            )
    }

    private fun languagePriority(language: String?): Int {
        return when (language?.uppercase()) {
            "VF", "VFF" -> 0
            "VFQ" -> 1
            "VOSTFR", "VO" -> 2
            else -> 3
        }
    }
}
