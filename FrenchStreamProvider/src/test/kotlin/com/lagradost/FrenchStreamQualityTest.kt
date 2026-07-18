package com.lagradost

import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FrenchStreamQualityTest {
    @Test
    fun keepsVfFirstAndSortsEachLanguageByResolution() = runBlocking {
        val sources = listOf(
            resolved("VOSTFR", "vo-2160", 2160),
            resolved("VF", "vf-720", 720),
            resolved("VFQ", "vfq-2160", 2160),
            resolved("VF", "vf-1080", 1080),
            resolved("VF", "vf-1080", 1080)
        )

        assertEquals(
            listOf("vf-1080", "vf-720", "vfq-2160", "vo-2160"),
            FrenchStreamQuality.bestFirst(sources).map { it.link.name }
        )
    }

    @Test
    fun infersResolutionFromDirectVideoUrl() {
        assertEquals(2160, FrenchStreamQuality.inferQuality("https://cdn.example/movie.2160p.mp4"))
        assertEquals(1080, FrenchStreamQuality.inferQuality("https://cdn.example/video_1920x1080.mp4"))
        assertEquals(720, FrenchStreamQuality.inferQuality("https://cdn.example/stream-720.mp4?token=abc"))
        assertEquals(null, FrenchStreamQuality.inferQuality("https://cdn.example/video.mp4"))
    }

    @Test
    fun normalizesCinematicResolutionsToStandardQualityTiers() {
        assertEquals(2160, FrenchStreamQuality.normalizeQuality(3840, 1600))
        assertEquals(1080, FrenchStreamQuality.normalizeQuality(2560, 1080))
        assertEquals(1080, FrenchStreamQuality.normalizeQuality(1920, 802))
        assertEquals(720, FrenchStreamQuality.normalizeQuality(1280, 640))
        assertEquals(480, FrenchStreamQuality.normalizeQuality(768, 432))
        assertEquals(360, FrenchStreamQuality.normalizeQuality(640, 360))
    }

    @Test
    fun findsHighestStandardQualityInHlsMaster() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x640
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x802
            1080.m3u8
        """.trimIndent()

        assertEquals(1080, FrenchStreamQuality.highestHlsQuality(manifest))
    }

    private suspend fun resolved(language: String, name: String, quality: Int): FrenchStreamResolvedLink {
        return FrenchStreamResolvedLink(
            language,
            newExtractorLink("French-Stream", name, "https://cdn.example/$name.m3u8") {
                this.quality = quality
            }
        )
    }
}
