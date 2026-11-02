package org.tablatureanalyzer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuitarInputGateTest {
    @Test
    fun calibrationAllowsQuietCleanGuitarThrough() {
        val quiet = 0.0001f
        val playing = 0.0008f
        val gain = (0.07f / playing).coerceIn(0.25f, 16f)
        val threshold = guitarInputThreshold(gain, quiet * 2.5f)
        assertTrue(playing >= threshold)
        assertTrue(quiet < threshold)
    }

    @Test
    fun gainDoesNotLowerMeasuredAmbientGate() {
        assertEquals(0.003f, guitarInputThreshold(16f, 0.003f))
        assertEquals(0.0015f, guitarInputThreshold(1f, 0f))
    }
}
