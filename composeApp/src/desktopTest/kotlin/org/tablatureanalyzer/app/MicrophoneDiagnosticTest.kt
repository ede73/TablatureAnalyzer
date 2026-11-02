package org.tablatureanalyzer.app

import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/** Opt-in replay of an actual mic capture, including the same event trigger as live recording. */
class MicrophoneDiagnosticTest {
    @Test
    fun replayActualMicrophoneCapture() {
        val path = System.getenv("TABLATURE_DIAGNOSTIC_WAV")
        assumeTrue("Set TABLATURE_DIAGNOSTIC_WAV to run live-capture diagnostics", path != null)
        val samples = AudioSystem.getAudioInputStream(File(path!!)).use { input ->
            check(input.format.sampleRate == 44100f && input.format.channels == 1)
            val bytes = input.readBytes()
            FloatArray(bytes.size / 2) { i ->
                ((bytes[2*i+1].toInt() shl 8) or (bytes[2*i].toInt() and 255)).toShort().toFloat() / 32768f
            }
        }
        val normal = SingleNoteRecorder()
        val quiet = SingleNoteRecorder()
        val rows = mutableListOf("seconds,rms,ungated_midi,normal_event,quiet_event")
        var normalEvents = 0
        var quietEvents = 0
        for (end in 16384..samples.size step 1024) {
            val frame = samples.copyOfRange(end-16384, end)
            val rms = sqrt(frame.sumOf { (it*it).toDouble() } / frame.size).toFloat()
            val level = sqrt(samples.copyOfRange(end-1024, end).sumOf { (it*it).toDouble() } / 1024).toFloat()
            val pitch = GuitarFftAnalyzer.detectMidiNote(frame, 44100)
            val time = end*1000L/44100
            val normalNote = normal.update(pitch.takeIf { rms >= 0.0015f },level,0.0015f,time)
            val quietNote = quiet.update(pitch.takeIf { rms >= 0.00005f },level,0.00005f,time)
            if (normalNote != null) normalEvents++
            if (quietNote != null) quietEvents++
            rows += "${end/44100.0},$rms,${pitch ?: ""},${normalNote ?: ""},${quietNote ?: ""}"
        }
        File("build/reports/audio/microphone-diagnostic.csv").apply { parentFile.mkdirs();writeText(rows.joinToString("\n")) }
        println("Actual mic replay: normal gate=$normalEvents notes, quiet gate=$quietEvents notes")
    }
}
