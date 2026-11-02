package org.tablatureanalyzer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SingleNoteRecorderTest {
    @Test
    fun actualAudioAtFourNotesPerSecondIncludingUnpluckedTrills() {
        for (pattern in listOf(listOf(45, 49, 51, 52, 54, 56), listOf(49, 51))) {
            val expected = List(24) { pattern[it % pattern.size] }
            val leadIn = 22_050
            val duration = 11025 // Four notes/second at 44.1 kHz.
            val samples = FloatArray(leadIn + duration * expected.size + 8192) { i ->
                if (i < leadIn) 0f else {
                    val note = expected[((i - leadIn) / duration).coerceAtMost(expected.lastIndex)]
                    val frequency = 440.0 * kotlin.math.exp(kotlin.math.ln(2.0) * (note - 69) / 12)
                    (0.1 * sin(2 * PI * frequency * i / 44100) + 0.04 * sin(4 * PI * frequency * i / 44100)).toFloat()
                }
            }
            val recorder = SingleNoteRecorder()
            val events = mutableListOf<Int>()
            for (end in 16_384..samples.size step 1024) {
                val frame = samples.copyOfRange(end - 16_384, end)
                val level = sqrt(samples.copyOfRange(end - 1024, end).sumOf { (it * it).toDouble() } / 1024).toFloat()
                recorder.update(GuitarFftAnalyzer.detectMidiNote(frame, 44100), level, 0.0015f, end * 1000L / 44100)
                    ?.let(events::add)
            }
            assertEquals(expected, events, "Four-note/second pattern $pattern")
        }
    }

    @Test
    fun recordsThirtyNotesAtFiveNotesPerSecond() {
        val recorder = SingleNoteRecorder()
        val expected = List(30) { listOf(45, 49, 51, 52, 54, 56)[it % 6] }
        val events = mutableListOf<Int>()
        for (t in 0L until 6000L step 20L) {
            recorder.update(expected[(t / 200).toInt()], 0.05f, 0.0015f, t)?.let(events::add)
        }
        assertEquals(expected, events)
    }

    @Test
    fun repeatedPlucksAreEventsButSustainAndBriefPitchGlitchesAreNot() {
        val recorder = SingleNoteRecorder()
        val events = mutableListOf<Int>()
        for (t in 0L until 3000L step 20L) {
            val phase = t % 300
            val level = if (phase < 40) 0.08f else 0.02f
            val note = if (phase == 180L) 54 else 40
            recorder.update(note, level, 0.0015f, t)?.let(events::add)
        }
        assertEquals(List(10) { 40 }, events)
        val held = SingleNoteRecorder()
        val heldEvents = (0L..3000L step 20L).mapNotNull { held.update(40, 0.05f, 0.0015f, it) }
        assertEquals(listOf(40), heldEvents)
    }
}
