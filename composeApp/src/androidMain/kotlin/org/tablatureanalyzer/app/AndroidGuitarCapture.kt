package org.tablatureanalyzer.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

internal data class AndroidAudioFrame(
    val samples: FloatArray,
    val sampleRate: Int,
    val rms: Float,
    val pitches: List<Int>,
    val spectrum: List<Float>,
    val elapsedMillis: Long
)

/** Android PCM microphone capture feeding the same shared FFT and note/chord analysis as desktop. */
internal class AndroidGuitarCapture(
    private val singleNoteMode: Boolean,
    private val minMidi: Int,
    private val maxMidi: Int,
    initialGain: Float,
    private val onFrame: (AndroidAudioFrame) -> Unit,
    private val onReady: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var gain = initialGain.coerceIn(0.25f, 64f)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    fun start() {
        check(!running) { "Microphone is already running" }
        val opened = openRecorder()
        recorder = opened
        try {
            opened.startRecording()
            if (opened.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("Android did not start microphone capture")
            running = true
            main.post { onReady("Listening · ${opened.sampleRate} Hz") }
            thread = Thread({ captureLoop(opened) }, "guitar-audio-capture").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            running = false
            opened.release()
            recorder = null
            throw failure
        }
    }

    fun stop() {
        running = false
        runCatching { recorder?.stop() }
        thread?.join(500)
        recorder?.release()
        recorder = null
        thread = null
    }

    fun setGain(value: Float) { gain = value.coerceIn(0.25f, 64f) }

    private fun captureLoop(input: AudioRecord) {
        val fftSize = if (singleNoteMode) 4096 else 16384
        val hopSize = if (singleNoteMode) 1024 else 4096
        val readBuffer = ShortArray(2048)
        val ring = FloatArray(fftSize)
        var write = 0
        var available = 0
        var sinceFrame = 0
        var startedAt = System.currentTimeMillis()
        try {
            while (running) {
                val count = input.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("Microphone read failed (${audioError(count)})")
                for (index in 0 until count) {
                    ring[write] = readBuffer[index] / 32768f
                    write = (write + 1) % fftSize
                }
                available = (available + count).coerceAtMost(fftSize)
                sinceFrame += count
                if (available < fftSize || sinceFrame < hopSize) continue
                sinceFrame %= hopSize
                val raw = FloatArray(fftSize) { offset -> ring[(write + offset) % fftSize] }
                var squareSum = 0.0
                for (sample in raw) squareSum += sample * sample
                val rms = sqrt(squareSum / raw.size).toFloat()
                val currentGain = gain
                val threshold = guitarInputThreshold(currentGain, 0f)
                // Keep analysis gain linear: clipping would add harmonics and false chord tones.
                val amplified = FloatArray(raw.size) { raw[it] * currentGain }
                val notes = if (rms < threshold) emptyList() else if (singleNoteMode) {
                    listOfNotNull(GuitarFftAnalyzer.detectMidiNote(amplified, input.sampleRate))
                } else {
                    GuitarFftAnalyzer.detectMidiNotes(amplified, input.sampleRate, minMidi, maxMidi)
                }
                val frame = AndroidAudioFrame(
                    samples = amplified,
                    sampleRate = input.sampleRate,
                    rms = rms,
                    pitches = notes,
                    spectrum = GuitarFftAnalyzer.spectrumForDisplay(amplified, input.sampleRate),
                    elapsedMillis = System.currentTimeMillis() - startedAt
                )
                main.post { if (running) onFrame(frame) }
            }
        } catch (failure: Exception) {
            if (running) {
                running = false
                runCatching { input.stop() }
                input.release()
                if (recorder === input) recorder = null
                main.post { onFailure(failure.message ?: "Microphone capture failed") }
            }
        }
    }

    private fun openRecorder(): AudioRecord {
        var failure: Exception? = null
        for (rate in listOf(44_100, 48_000, 32_000, 22_050)) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, rate / 2)
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate
                candidate.release()
            } catch (problem: Exception) {
                candidate?.release()
                failure = problem
            }
        }
        throw IllegalStateException("Could not open the Android microphone${failure?.message?.let { ": $it" } ?: "; no supported capture format"}", failure)
    }

    private fun audioError(code: Int): String = when (code) {
        AudioRecord.ERROR_BAD_VALUE -> "invalid audio format"
        AudioRecord.ERROR_INVALID_OPERATION -> "invalid capture state"
        AudioRecord.ERROR_DEAD_OBJECT -> "audio device disconnected"
        else -> "code $code"
    }
}
