package org.tablatureanalyzer.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFollowTest {
    @Test
    fun bringsPlayingSystemBackIntoViewAfterUserScrollsAway() {
        // The same system can go offscreen after the user manually scrolls while
        // playback continues. Rechecking on each playing event must bring it back.
        assertEquals(
            380,
            playbackFollowTargetScroll(
                currentScroll = 500,
                systemTopInViewport = -120f,
                systemHeight = 220,
                viewportHeight = 600,
                bottomClearance = 24,
                maxScroll = 2_000
            )
        )
    }

    @Test
    fun scrollsDownWhenPlayingSystemFallsBelowViewport() {
        assertEquals(
            224,
            playbackFollowTargetScroll(
                currentScroll = 100,
                systemTopInViewport = 480f,
                systemHeight = 220,
                viewportHeight = 600,
                bottomClearance = 24,
                maxScroll = 2_000
            )
        )
    }

    @Test
    fun leavesScrollAloneWhenPlayingSystemIsVisible() {
        assertEquals(
            100,
            playbackFollowTargetScroll(
                currentScroll = 100,
                systemTopInViewport = 120f,
                systemHeight = 220,
                viewportHeight = 600,
                bottomClearance = 24,
                maxScroll = 2_000
            )
        )
    }
}
