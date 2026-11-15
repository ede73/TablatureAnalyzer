package org.tablatureanalyzer.app

import kotlin.math.roundToInt

/** Returns the scroll offset that keeps the active tablature system inside its viewport. */
internal fun playbackFollowTargetScroll(
    currentScroll: Int,
    systemTopInViewport: Float,
    systemHeight: Int,
    viewportHeight: Int,
    bottomClearance: Int,
    maxScroll: Int
): Int {
    val systemBottomInViewport = systemTopInViewport + systemHeight
    val delta = when {
        systemTopInViewport < 0f -> systemTopInViewport
        systemBottomInViewport > viewportHeight -> systemBottomInViewport - viewportHeight + bottomClearance
        else -> 0f
    }
    return (currentScroll + delta.roundToInt()).coerceIn(0, maxScroll)
}
