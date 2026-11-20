package com.lagradost

import org.json.JSONObject

internal data class FSTVLinkData(
    val url: String,
    val name: String? = null
)

internal object FSTVParser {

    fun linkData(url: String, channelName: String): String {
        return JSONObject().put("url", url).put("name", channelName).toString()
    }

    /** Les anciens favoris ne contiennent que l'URL de la chaîne. */
    fun parseLinkData(data: String): FSTVLinkData {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return FSTVLinkData(data)
        val url = json.optString("url").trim().takeIf { it.isNotBlank() } ?: return FSTVLinkData(data)
        return FSTVLinkData(url, json.optString("name").trim().takeIf { it.isNotBlank() })
    }
}
