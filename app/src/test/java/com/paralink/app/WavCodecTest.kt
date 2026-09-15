package com.paralink.app

import com.paralink.app.core.language.WavCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WavCodecTest {

    @Test
    fun pcmRoundTrip() {
        val pcm = ByteArray(320)
        for (i in pcm.indices) pcm[i] = (i * 7).toByte()
        val wav = WavCodec.encodePcmToWav(pcm)
        val info = WavCodec.parseWav(wav)
        assertEquals(16000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(pcm.size, info.pcmLength)
        assertEquals(320L * 1000 / 32000, info.durationMillis)
        assertArrayEquals(pcm, WavCodec.extractPcm(wav))
    }

    @Test
    fun rejectedHeaderIsEmpty() {
        val info = WavCodec.parseWav(ByteArray(32))
        assertEquals(0, info.pcmLength)
    }

    @Test
    fun durationComputed() {
        assertEquals(1000L, WavCodec.durationMillis(8000, 16000, 1, 16))
    }
}