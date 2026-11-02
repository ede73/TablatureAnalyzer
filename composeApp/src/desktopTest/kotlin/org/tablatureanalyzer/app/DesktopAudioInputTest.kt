package org.tablatureanalyzer.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class DesktopAudioInputTest {
    @Test
    fun decodesSignedPcmAndIgnoresIncompleteFrames() {
        assertContentEquals(floatArrayOf(-1f, 32767f / 32768f, 0f),
            DesktopAudioInput.decode(byteArrayOf(0, -128, -1, 127, 0, 0, 42), 7, 1))
    }

    @Test
    fun keepsGuitarWhenOnlyOneStereoChannelHasSignal() {
        assertContentEquals(floatArrayOf(0.5f, -0.5f),
            DesktopAudioInput.decode(byteArrayOf(0, 0, 0, 64, 0, 0, 0, -64), 8, 2))
    }

    @Test
    fun oppositePhaseChannelsDoNotCancelTheGuitar() {
        val decoded = DesktopAudioInput.decode(byteArrayOf(0, 64, 0, -64, 0, -64, 0, 64), 8, 2)
        assertTrue(decoded.all { kotlin.math.abs(it) == 0.5f })
        assertTrue(DesktopAudioInput.formats.any { it.sampleRate == 48000f && it.channels == 2 })
        assertTrue(DesktopAudioInput.formats.any { it.sampleRate == 16000f && it.channels == 1 })
    }
}
