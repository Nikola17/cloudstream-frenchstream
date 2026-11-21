package com.lagradost

import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.JsUnpacker

internal object FrenchStreamPackedPlayer {
    private val fsvidXorSourceRegex = Regex(
        """var\s+k\s*=\s*\[([0-9,\s]+)]\s*,\s*b\s*=\s*atob\(s\).*?\}\)\(\s*["']([A-Za-z0-9+/_=-]+)["']\s*\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val packedScriptRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\).*?</script>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val directVideoRegex = Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun decodeFsvidSource(script: String): String? {
        val match = fsvidXorSourceRegex.find(script) ?: return null
        val key = match.groupValues[1]
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        if (key.isEmpty()) return null

        val encrypted = runCatching { base64Decode(match.groupValues[2]) }.getOrNull() ?: return null
        val source = buildString(encrypted.length) {
            encrypted.forEachIndexed { index, char ->
                append((char.code xor key[index % key.size]).toChar())
            }
        }.trim()

        return source.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /**
     * Extrait toutes les URLs média candidates (XOR fsvid, scripts packés, puis HTML brut),
     * en ignorant les leurres `/troll/` et `fstream.top` (ex: Vidzy met un leurre avant le vrai flux).
     */
    fun extractPlayableMediaUrls(html: String): List<String> {
        val candidates = mutableListOf<String>()

        fun collectFrom(text: String) {
            decodeFsvidSource(text)?.let(candidates::add)
            val cleaned = text.replace("\\/", "/").replace("&amp;", "&")
            directVideoRegex.findAll(cleaned).forEach { candidates.add(it.value) }
        }

        packedScriptRegex.findAll(html).forEach { match ->
            runCatching { JsUnpacker(match.value).takeIf { it.detect() }?.unpack() }
                .getOrNull()
                ?.let(::collectFrom)
        }
        runCatching { JsUnpacker(html).takeIf { it.detect() }?.unpack() }
            .getOrNull()
            ?.let(::collectFrom)
        collectFrom(html)

        return candidates.distinct().filter(FrenchStreamQuality::isPlayableMediaUrl)
    }

    fun hasAnyMediaCandidate(html: String): Boolean {
        val cleaned = html.replace("\\/", "/")
        return directVideoRegex.containsMatchIn(cleaned) || fsvidXorSourceRegex.containsMatchIn(html)
    }
}
