package org.tablatureanalyzer.app

import javax.sound.midi.ShortMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class MidiDurationTimelineTest {
    @Test
    fun quarterAndHalfNoteMockKeepsExpectedOnsetsAndLengths() {
        val mock = checkNotNull(javaClass.getResource("/samples/midi-duration-check.ta"))
            .readText()
        val document = TabCodec.parseInternal(mock)
        val track = buildMidiSequence(document).tracks.single()
        val noteOns = mutableListOf<Long>()
        val noteOffs = mutableListOf<Long>()

        repeat(track.size()) { eventIndex ->
            val midiEvent = track.get(eventIndex)
            val message = midiEvent.message as? ShortMessage ?: return@repeat
            when (message.command) {
                ShortMessage.NOTE_ON -> noteOns += midiEvent.tick
                ShortMessage.NOTE_OFF -> noteOffs += midiEvent.tick
            }
        }

        assertEquals(listOf(0L, 480L, 960L, 1440L, 1920L, 2880L), noteOns)
        assertEquals(listOf(480L, 960L, 1440L, 1920L, 2880L, 3840L), noteOffs)
        assertEquals(3840L, track.ticks())
    }

    @Test
    fun durationConversionUsesQuarterNoteDivisions() {
        assertEquals(480L, unitsToMidiTicks(480, 480))
        assertEquals(960L, unitsToMidiTicks(960, 480))
        assertEquals(240L, unitsToMidiTicks(240, 480))
    }
}
