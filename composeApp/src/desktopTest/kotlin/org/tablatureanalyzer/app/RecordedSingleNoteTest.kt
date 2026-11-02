package org.tablatureanalyzer.app

import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordedSingleNoteTest {
    @Test
    fun windowsLaptopOpenStringsAndMelody() {
        val resource = requireNotNull(javaClass.getResource("/audio/SingleNotes.wav"))
        val samples = AudioSystem.getAudioInputStream(resource).use { input ->
            check(input.format.sampleRate == 44_100f && input.format.channels == 1)
            val bytes = input.readBytes()
            FloatArray(bytes.size / 2) { i ->
                (((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 255)).toShort()).toFloat() / 32768f
            }
        }
        val rows = mutableListOf("seconds,dbfs,midi")
        val frames = mutableListOf<Pair<Double, Int?>>()
        for (end in 16_384..samples.size step 4_096) {
            val frame = samples.copyOfRange(end - 16_384, end)
            val rms = sqrt(frame.sumOf { (it * it).toDouble() } / frame.size).toFloat()
            val note = if (rms >= guitarInputThreshold(1f, 0f)) GuitarFftAnalyzer.detectMidiNote(frame, 44_100) else null
            frames += end / 44100.0 to note
            rows += "${end / 44100.0},${20 * log10(rms.coerceAtLeast(1e-10f))},${note ?: ""}"
        }
        val output = File("build/reports/audio/SingleNotes.csv")
        output.parentFile.mkdirs()
        output.writeText(rows.joinToString("\n"))
        assertTrue(samples.size > 32 * 44_100)
        fun expectNote(start: Double, end: Double, midi: Int, minimumFraction: Double) {
            val window = frames.filter { it.first >= start && it.first < end }
            val matches = window.count { it.second == midi }
            assertTrue(window.isNotEmpty() && matches.toDouble() / window.size >= minimumFraction,
                "$start–$end s: expected MIDI $midi, got $matches/${window.size} frames")
        }
        expectNote(8.0, 14.0, 40, 0.9)
        expectNote(15.0, 21.0, 45, 0.95)
        assertTrue(frames.filter { it.first < 7.0 }.all { it.second == null }, "Background must not write notes")
        val tuning = listOf(64, 59, 55, 50, 45, 40)
        assertEquals(listOf(null, null, null, null, null, 0), tabEventForPitches(tuning, listOf(40))?.frets)
        assertEquals(listOf(null, null, null, null, 0, null), tabEventForPitches(tuning, listOf(45))?.frets)

        // Run the actual live cadence and event trigger, not just pitch snapshots.
        val recorder = SingleNoteRecorder()
        val events = mutableListOf<Pair<Double, Int>>()
        for (end in 16_384..samples.size step 1024) {
            val frame = samples.copyOfRange(end - 16_384, end)
            val rms = sqrt(frame.sumOf { (it * it).toDouble() } / frame.size).toFloat()
            val attackLevel = sqrt(samples.copyOfRange(end - 1024, end).sumOf { (it * it).toDouble() } / 1024).toFloat()
            val threshold = guitarInputThreshold(1f, 0f)
            val note = if (rms >= threshold) GuitarFftAnalyzer.detectMidiNote(frame, 44_100) else null
            recorder.update(note, attackLevel, threshold, end * 1000L / 44_100)?.let {
                events += end / 44100.0 to it
            }
        }
        File(output.parentFile, "SingleNotes-events.csv").writeText("seconds,midi\n" +
            events.joinToString("\n") { "${it.first},${it.second}" })
        val melody = events.filter { it.first in 21.5..32.1 }
        assertTrue(melody.size >= 15, "Live event trigger lost the melody: ${melody.size} events")
        assertTrue(events.none { it.first < 7.0 }, "Noise must not insert notes")
    }
}
