package com.lagradost

import org.json.JSONArray
import org.json.JSONObject

internal data class MovixSourceLink(
    val url: String,
    val language: String? = null,
    val qualityLabel: String? = null
)

internal object MovixLinkParser {
    // VF d'abord (VFF = doublage FR, puis VF, puis VFQ), puis VOSTFR ; la meilleure qualité d'abord dans chaque groupe.
    private val preferredGroups = listOf(
        "VFF",
        "VF",
        "VFQ",
        "VOSTFR",
        "VOENG",
        "Default",
        "vf",
        "vostfr"
    )

    fun fstreamMovieEntries(root: JSONObject): List<MovixSourceLink> {
        return entriesFromGroups(
            root.optJSONObject("players"),
            listOf("VFF", "VF", "VFQ", "VOSTFR", "Default")
        )
    }

    fun fstreamTvEntries(root: JSONObject, episode: Int): List<MovixSourceLink> {
        val languages = root.optJSONObject("episodes")
            ?.optJSONObject(episode.toString())
            ?.optJSONObject("languages")
        return entriesFromGroups(languages)
    }

    fun customMovieEntries(root: JSONObject): List<MovixSourceLink> {
        return linksFromArray(root.optJSONObject("data")?.optJSONArray("links"))
            .sortedByDescending { it.rank }
            .distinctBy { it.url }
            .map { MovixSourceLink(it.url, null, it.qualityLabel) }
    }

    fun wiflixMovieEntries(root: JSONObject): List<MovixSourceLink> {
        return entriesFromGroups(root.optJSONObject("players"), listOf("vf", "vostfr"))
    }

    fun wiflixTvEntries(root: JSONObject, episode: Int): List<MovixSourceLink> {
        val groups = root.optJSONObject("episodes")?.optJSONObject(episode.toString())
        return entriesFromGroups(groups, listOf("vf", "vostfr"))
    }

    fun fstreamMovie(root: JSONObject): List<String> = fstreamMovieEntries(root).map { it.url }

    fun fstreamTv(root: JSONObject, episode: Int): List<String> = fstreamTvEntries(root, episode).map { it.url }

    fun customMovie(root: JSONObject): List<String> = customMovieEntries(root).map { it.url }

    fun wiflixMovie(root: JSONObject): List<String> = wiflixMovieEntries(root).map { it.url }

    fun wiflixTv(root: JSONObject, episode: Int): List<String> = wiflixTvEntries(root, episode).map { it.url }

    fun languagePriority(language: String?): Int {
        return when (language?.uppercase()) {
            "VFF" -> 0
            "VF" -> 1
            "VFQ" -> 2
            "VOSTFR" -> 3
            "VOENG", "VO" -> 4
            else -> 5
        }
    }

    /**
     * Paliers stricts de langue : VFF/VF (0) -> VFQ & liens par défaut non tagués (1) -> VOSTFR/VO (2).
     * Garantit que la VF est toujours choisie en priorité si au moins un flux VF valide existe.
     */
    fun languageTier(language: String?): Int {
        return when (language?.uppercase()?.takeIf { it.isNotBlank() }) {
            "VFF", "VF" -> 0
            "VFQ", null -> 1
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
            "vidmoly." in lower -> 4
            "vidara." in lower || "bysebuho." in lower || "embedorigin." in lower -> 5
            "dood" in lower || "goodstream." in lower -> 6
            "fsvid." in lower || "vidzy." in lower -> 80
            else -> 20
        }
    }

    private data class RankedLink(val url: String, val qualityLabel: String?, val rank: Int)

    private fun entriesFromGroups(
        groups: JSONObject?,
        requestedGroups: List<String>? = null
    ): List<MovixSourceLink> {
        if (groups == null) return emptyList()

        val available = groups.keys().asSequence().toList()
        val groupNames = requestedGroups ?: (
            preferredGroups.filter { it in available } + available.filterNot { it in preferredGroups }
        )

        val seen = mutableSetOf<String>()
        return groupNames.flatMap { group ->
            val normalizedLang = group.uppercase().takeUnless { it == "DEFAULT" }
            linksFromArray(groups.optJSONArray(group))
                .sortedByDescending { it.rank }
                .filter { seen.add(it.url) }
                .map { MovixSourceLink(it.url, normalizedLang, it.qualityLabel) }
        }
    }

    private fun linksFromArray(items: JSONArray?): List<RankedLink> {
        if (items == null) return emptyList()

        return (0 until items.length())
            .mapNotNull { index ->
                val item = items.opt(index)
                val url = when (item) {
                    is String -> item
                    is JSONObject -> item.optString("url")
                    else -> null
                }?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
                val label = (item as? JSONObject)?.optString("quality")?.trim()?.takeIf { it.isNotBlank() }
                RankedLink(url, label, MovixMetadata.qualityRankOf(label.orEmpty()))
            }
    }
}
