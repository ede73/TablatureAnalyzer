package org.tablatureanalyzer.app

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuitarFftAnalyzerTest {
    @Test
    fun windowsCaptureSampleRatesPreservePitch() {
        for (rate in listOf(8_000, 16_000, 32_000, 44_100, 48_000)) {
            for (midi in listOf(40, 45, 49, 52, 55, 64, 76, 88)) {
                val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
                val input = FloatArray(16_384) { i ->
                    (0.1 * sin(2 * PI * frequency * i / rate) +
                        0.03 * sin(4 * PI * frequency * i / rate)).toFloat()
                }
                assertEquals(midi, GuitarFftAnalyzer.detectMidiNote(input, rate), "$rate Hz, MIDI $midi")
            }
        }
    }

    @Test
    fun singleNoteDetectorRecognizesEveryGuitarFretWithHarmonics() {
        for (midi in 40..76) {
            assertEquals(midi, GuitarFftAnalyzer.detectMidiNote(signal(listOf(midi)), 44_100), "MIDI $midi")
        }
    }

    @Test
    fun singleNoteDetectorFollowsNewNoteInsideChordSizedBuffer() {
        for ((old, current) in listOf(40 to 45, 45 to 48, 48 to 51, 51 to 55)) {
            val samples = signal(listOf(old))
            val recent = signal(listOf(current))
            recent.copyInto(samples, samples.size - 8192, recent.size - 8192)
            assertEquals(current, GuitarFftAnalyzer.detectMidiNote(samples, 44_100), "$old -> $current")
        }
    }

    @Test
    fun dominantSecondHarmonicDoesNotTurnOpenAIntoAnOctave() {
        val samples = FloatArray(16_384) { i ->
            (0.025 * sin(2 * PI * 110 * i / 44_100) + 0.2 * sin(4 * PI * 110 * i / 44_100)).toFloat()
        }
        assertEquals(45, GuitarFftAnalyzer.detectMidiNote(samples, 44_100))
        assertEquals(null, GuitarFftAnalyzer.detectMidiNote(FloatArray(16_384), 44_100))
    }

    private fun signal(notes: List<Int>, dc: Float = 0f): FloatArray = FloatArray(16_384) { i ->
        dc + notes.sumOf { midi ->
            val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
            0.1 * sin(2 * PI * frequency * i / 44_100) +
                0.04 * sin(4 * PI * frequency * i / 44_100) +
                0.02 * sin(6 * PI * frequency * i / 44_100)
        }.toFloat()
    }

    @Test
    fun harmonicsDoNotInventLowerFundamentals() {
        val pitches = GuitarFftAnalyzer.detectMidiNotes(signal(listOf(57)), 44_100)
        assertTrue(57 in pitches)
        assertTrue(pitches.none { it < 57 }, "A3 must not invent lower notes: $pitches")
    }

    @Test
    fun cleanMajorAndMinorTriadsKeepTheirPitchClasses() {
        for (notes in listOf(listOf(45, 48, 52), listOf(43, 47, 50), listOf(48, 52, 55), listOf(40, 43, 47))) {
            val pitches = GuitarFftAnalyzer.detectMidiNotes(signal(notes), 44_100)
            assertEquals(notes.map { it % 12 }.toSet(), pitches.map { it % 12 }.toSet(), "$notes -> $pitches")
        }
    }

    @Test
    fun dcOffsetDoesNotChangePitchCandidates() {
        val notes = listOf(45, 48, 52)
        assertEquals(
            GuitarFftAnalyzer.detectMidiNotes(signal(notes), 44_100),
            GuitarFftAnalyzer.detectMidiNotes(signal(notes, dc = 0.6f), 44_100)
        )
    }
}
