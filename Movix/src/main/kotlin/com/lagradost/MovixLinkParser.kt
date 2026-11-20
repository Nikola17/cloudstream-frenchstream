package com.lagradost

import org.json.JSONArray
import org.json.JSONObject

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

    fun fstreamMovie(root: JSONObject): List<String> {
        return linksFromGroups(
            root.optJSONObject("players"),
            listOf("VFF", "VF", "VFQ", "VOSTFR", "Default")
        )
    }

    fun fstreamTv(root: JSONObject, episode: Int): List<String> {
        val languages = root.optJSONObject("episodes")
            ?.optJSONObject(episode.toString())
            ?.optJSONObject("languages")
        return linksFromGroups(languages)
    }

    fun customMovie(root: JSONObject): List<String> {
        return linksFromArray(root.optJSONObject("data")?.optJSONArray("links"))
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun wiflixMovie(root: JSONObject): List<String> {
        return linksFromGroups(root.optJSONObject("players"), listOf("vf", "vostfr"))
    }

    fun wiflixTv(root: JSONObject, episode: Int): List<String> {
        val groups = root.optJSONObject("episodes")?.optJSONObject(episode.toString())
        return linksFromGroups(groups, listOf("vf", "vostfr"))
    }

    private fun linksFromGroups(
        groups: JSONObject?,
        requestedGroups: List<String>? = null
    ): List<String> {
        if (groups == null) return emptyList()

        val available = groups.keys().asSequence().toList()
        val groupNames = requestedGroups ?: (
            preferredGroups.filter { it in available } + available.filterNot { it in preferredGroups }
        )

        // Chaque groupe de langue garde son ordre de priorité (VFF/VF avant VOSTFR…),
        // et dans chaque groupe la meilleure qualité passe en premier.
        val seen = mutableSetOf<String>()
        return groupNames.flatMap { group ->
            linksFromArray(groups.optJSONArray(group))
                .sortedByDescending { it.second }
                .map { it.first }
                .filter { seen.add(it) }
        }
    }

    /** (url, rang de qualité) : les liens les mieux notés passent en premier dans chaque groupe de langue. */
    private fun linksFromArray(items: JSONArray?): List<Pair<String, Int>> {
        if (items == null) return emptyList()

        return (0 until items.length())
            .mapNotNull { index ->
                val item = items.opt(index)
                val url = when (item) {
                    is String -> item
                    is JSONObject -> item.optString("url")
                    else -> null
                }?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
                val label = (item as? JSONObject)?.optString("quality")?.trim().orEmpty()
                url to MovixMetadata.qualityRankOf(label)
            }
    }
}
