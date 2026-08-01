package com.lagradost

import com.lagradost.cloudstream3.base64Decode

internal object FrenchStreamPackedPlayer {
    private val fsvidXorSourceRegex = Regex(
        """var\s+k\s*=\s*\[([0-9,\s]+)]\s*,\s*b\s*=\s*atob\(s\).*?\}\)\(\s*["']([A-Za-z0-9+/_=-]+)["']\s*\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
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
}
