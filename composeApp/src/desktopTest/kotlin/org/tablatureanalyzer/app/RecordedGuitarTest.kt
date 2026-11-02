package org.tablatureanalyzer.app

import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class RecordedGuitarTest {
    private data class Frame(val seconds: Double, val rollingChord: String)

    @Test
    fun windowsLaptopMicrophoneRecording() {
        val resource = requireNotNull(javaClass.getResource("/audio/AmGCEm.wav"))
        val samples = AudioSystem.getAudioInputStream(resource).use { input ->
            check(input.format.sampleRate == 44_100f && input.format.channels == 1)
            val bytes = input.readBytes()
            FloatArray(bytes.size / 2) { i ->
                (((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 255)).toShort()).toFloat() / 32768f
            }
        }
        val ranker = AudioChordRanker()
        val rows = mutableListOf("seconds,dbfs,pitches,frame_chord,rolling_chord")
        val frames = mutableListOf<Frame>()
        for (end in 16_384..samples.size step 4_096) {
            val frame = samples.copyOfRange(end - 16_384, end)
            val rms = sqrt(frame.sumOf { (it * it).toDouble() } / frame.size).toFloat()
            val notes = if (rms >= guitarInputThreshold(1f, 0f)) {
                GuitarFftAnalyzer.detectMidiNotes(frame, 44_100, 40, 88)
            } else emptyList()
            val frameChord = identifyAudioChord(notes)?.name.orEmpty()
            val rolling = ranker.update(notes).firstOrNull()?.chord?.name.orEmpty()
            frames += Frame(end / 44100.0, rolling)
            rows += "${end / 44100.0},${20 * log10(rms.coerceAtLeast(1e-10f))},${notes.joinToString(" ")},$frameChord,$rolling"
        }
        val output = File("build/reports/audio/AmGCEm.csv")
        output.parentFile.mkdirs()
        output.writeText(rows.joinToString("\n"))
        assertTrue(samples.size > 20 * 44_100, "Fixture must contain the annotated progression")
        // Interior windows avoid using the owner's approximate chord-change times
        // as exact labels. These exercise the real detector and rolling ranker.
        fun expectChord(start: Double, end: Double, chord: String, minimumFraction: Double) {
            val window = frames.filter { it.seconds >= start && it.seconds < end }
            val correct = window.count { it.rollingChord == chord }
            assertTrue(window.isNotEmpty() && correct.toDouble() / window.size >= minimumFraction,
                "$start–$end s: expected $chord in >= $minimumFraction of frames; got $correct/${window.size}")
        }
        expectChord(7.5, 10.0, "Am", 0.8)
        expectChord(10.8, 13.0, "G", 0.9)
        expectChord(13.5, 14.5, "C", 0.9)
        expectChord(17.0, 18.8, "Am", 0.9)
    }
}
