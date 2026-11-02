package org.tablatureanalyzer.app

/** Threshold in raw microphone units; software gain applies to the signal threshold only. */
internal fun guitarInputThreshold(gain: Float, noiseFloor: Float): Float =
    maxOf(0.0015f / gain.coerceIn(0.25f, 64f), noiseFloor.coerceAtLeast(0f))
