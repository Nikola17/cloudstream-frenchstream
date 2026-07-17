package com.lagradost

import org.json.JSONArray
import org.json.JSONObject

internal object MovixLinkParser {
    private val preferredGroups = listOf(
        "VFQ",
        "VFF",
        "VF",
        "VOSTFR",
        "VOENG",
        "Default",
        "vf",
        "vostfr"
    )

    fun fstreamMovie(root: JSONObject): List<String> {
        return linksFromGroups(
            root.optJSONObject("players"),
            listOf("VFQ", "VFF", "VOSTFR", "Default")
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

        return groupNames
            .flatMap { linksFromArray(groups.optJSONArray(it)) }
            .distinct()
    }

    private fun linksFromArray(items: JSONArray?): List<String> {
        if (items == null) return emptyList()

        return (0 until items.length())
            .mapNotNull { index ->
                when (val item = items.opt(index)) {
                    is String -> item
                    is JSONObject -> item.optString("url")
                    else -> null
                }?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            .distinct()
    }
}
