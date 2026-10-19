package com.sanqi.coach.tts

import org.junit.Assert.*
import org.junit.Test

class EdgeProtocolTest {
    @Test fun v2TokensMatchPinnedUpstreamVectors() {
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6", EdgeGec.token(0))
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2", EdgeGec.token(1700000000))
        assertEquals("7028A92AC911F55E-86AF7242F1BBC2AF3FC00BB16A4570B875C336A4B1A508AF2CC8A673C7B5FF5E", EdgeGec.token(1792383000))
        assertEquals(EdgeGec.token(1792383000), EdgeGec.token(1792383001))
    }

    @Test fun extractsOnlyMpegPayload() {
        val headers = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
        val prefix = byteArrayOf((headers.size ushr 8).toByte(), headers.size.toByte())
        assertArrayEquals(byteArrayOf(1, 2, 3), EdgeAudioFrame.decode(prefix + headers + byteArrayOf(1, 2, 3)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedFrame() { EdgeAudioFrame.decode(byteArrayOf(0, 8, 1)) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongContentType() {
        val headers = "Path:audio\r\nContent-Type:text/html\r\n".toByteArray()
        EdgeAudioFrame.decode(byteArrayOf(0, headers.size.toByte()) + headers + byteArrayOf(1))
    }
}
