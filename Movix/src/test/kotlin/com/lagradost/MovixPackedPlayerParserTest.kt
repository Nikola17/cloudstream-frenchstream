package com.lagradost

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MovixPackedPlayerParserTest {
    @Test
    fun extractsStreamFromPackedVidzyOrUqloadScript() {
        val html = """
            <html><body><script>
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0("1").2({3:[{4:"5"}]});',6,6,'jwplayer|vplayer|setup|sources|file|https://cdn.example/master.m3u8?token=abc'.split('|'),0,{}))
            </script></body></html>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example/master.m3u8?token=abc"),
            MovixPackedPlayerParser.extractMediaUrls(html)
        )
    }

    @Test
    fun ignoresMatchedExtractorsThatEmitNoLinksAndContinues() {
        val attempted = mutableListOf<String>()
        val emitted = mutableListOf<String>()

        val found = runBlocking {
            MovixExtractorPipeline.load(
                links = listOf("https://empty.example/embed", "https://working.example/embed"),
                loader = { link: String, callback: (String) -> Unit ->
                    attempted += link
                    if (link.contains("working")) callback("https://cdn.example/video.m3u8")
                    true
                },
                callback = { emitted += it }
            )
        }

        assertEquals(true, found)
        assertEquals(
            listOf("https://empty.example/embed", "https://working.example/embed"),
            attempted
        )
        assertEquals(listOf("https://cdn.example/video.m3u8"), emitted)
    }

    @Test
    fun reportsNoLinkWhenExtractorsOnlyReportDomainMatches() {
        val found = runBlocking {
            MovixExtractorPipeline.load<String>(
                links = listOf("https://empty.example/embed"),
                loader = { _, _ -> true },
                callback = { error("No callback should be emitted") }
            )
        }

        assertEquals(false, found)
    }
}
