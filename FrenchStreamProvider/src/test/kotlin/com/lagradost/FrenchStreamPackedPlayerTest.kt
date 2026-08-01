package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class FrenchStreamPackedPlayerTest {
    @Test
    fun decodesFsvidXorSourceInsteadOfTrollPlaylist() {
        val source = "https://cdn.example/movie/master.m3u8"
        val key = listOf(214, 91, 173, 44, 122, 250, 19, 88)
        val encrypted = source.mapIndexed { index, char ->
            (char.code xor key[index % key.size]).toByte()
        }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        val unpacked = """
            var _fsvHls="https://s1.fsvid.lol/troll/master.m3u8";
            var player=videojs('vjsplayer',{sources:[{src:(function(s){
                var k=[214,91,173,44,122,250,19,88],b=atob(s),r="";
                for(var i=0;i<b.length;i++){r+=String.fromCharCode(b.charCodeAt(i)^k[i%8])}
                return r
            })("$encoded"),type:"application/x-mpegURL"}]});
        """.trimIndent()

        assertEquals(source, FrenchStreamPackedPlayer.decodeFsvidSource(unpacked))
        assertNull(FrenchStreamPackedPlayer.decodeFsvidSource("var source='https://cdn.example/video.mp4';"))
    }
}
