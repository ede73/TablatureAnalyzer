package org.tablatureanalyzer.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lightweight FFT-based pitch candidates for guitar fundamentals and their harmonics. */
object GuitarFftAnalyzer {
    fun detectMidiNotes(
        samples: FloatArray,
        sampleRate: Int,
        minMidi: Int = 35,
        maxMidi: Int = 88
    ): List<Int> {
        val magnitude = magnitudeSpectrum(samples) ?: return emptyList()
        if (sampleRate <= 0) return emptyList()
        val size = samples.size
        val peak = magnitude.maxOrNull() ?: return emptyList()
        if (peak <= 0.00001f) return emptyList()

        val lowestNote = minMidi.coerceIn(35, 88)
        val highestNote = maxMidi.coerceIn(lowestNote, 88)
        val scores = (lowestNote..highestNote).associateWith { midi ->
            val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
            var score = 0.0
            for (harmonic in 1..6) {
                val bin = (frequency * harmonic * size / sampleRate).roundToInt()
                if (bin !in 1 until magnitude.size - 1) break
                val local = maxOf(magnitude[bin - 1], magnitude[bin], magnitude[bin + 1])
                score += local / harmonic.toDouble().pow(0.82)
            }
            // A real fundamental matters; this prevents an overtone from routinely
            // being mistaken for a separate low note.
            val fundamentalBin = (frequency * size / sampleRate).roundToInt().coerceIn(1, magnitude.lastIndex)
            score * (0.55 + 0.45 * magnitude[fundamentalBin] / peak)
        }
        val best = scores.values.maxOrNull() ?: return emptyList()
        if (best <= peak * 0.035) return emptyList()
        val ranked = scores.entries.sortedByDescending { it.value }
        val chosen = mutableListOf<Int>()
        for ((midi, score) in ranked) {
            if (score < best * 0.12 || chosen.size == 8) break
            // Neighboring semitones often share FFT bins at low guitar frequencies.
            // Keep only the stronger estimate in a one-semitone cluster.
            if (chosen.any { kotlin.math.abs(midi - it) <= 1 }) continue
            val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
            // Suppress likely octave/fifth harmonics of an already accepted note.
            val explained = chosen.any { lower ->
                val ratio = frequency / (440.0 * 2.0.pow((lower - 69) / 12.0))
                (2..6).any { harmonic -> kotlin.math.abs(12.0 * ln(ratio / harmonic) / ln(2.0)) < 0.45 }
            }
            if (!explained) chosen += midi
        }
        return chosen.sorted()
    }

    /** Estimates one played note for tab recording, where overtones must not become extra frets. */
    fun detectMidiNote(samples: FloatArray, sampleRate: Int): Int? {
        if (samples.size < 2048 || sampleRate <= 0) return null

        // YIN's difference function is inexpensive after 4:1 decimation and is robust to
        // guitar harmonics that the chord-oriented spectral peak detector reports separately.
        val decimation = 4
        val reduced = FloatArray(samples.size / decimation) { index ->
            val start = index * decimation
            (samples[start] + samples[start + 1] + samples[start + 2] + samples[start + 3]) * 0.25f
        }
        val reducedRate = sampleRate / decimation
        val mean = reduced.average().toFloat()
        for (index in reduced.indices) reduced[index] -= mean

        val minFrequency = 55.0
        val maxFrequency = 1_200.0
        val minLag = (reducedRate / maxFrequency).toInt().coerceAtLeast(2)
        val maxLag = (reducedRate / minFrequency).toInt().coerceAtMost(reduced.size / 2)
        if (maxLag <= minLag) return null

        val difference = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0.0
            val limit = reduced.size - lag
            for (index in 0 until limit) {
                val delta = reduced[index] - reduced[index + lag]
                sum += delta * delta
            }
            difference[lag] = sum / (reduced.size - lag)
        }

        var cumulative = 0.0
        val normalized = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            cumulative += difference[lag]
            normalized[lag] = if (cumulative > 0.0) difference[lag] * lag / cumulative else 1.0
        }

        // Select the first convincing periodicity dip, then follow it to its local minimum.
        var selectedLag = -1
        for (lag in minLag..maxLag) {
            if (normalized[lag] < 0.18) {
                selectedLag = lag
                while (selectedLag < maxLag && normalized[selectedLag + 1] < normalized[selectedLag]) selectedLag++
                break
            }
        }
        if (selectedLag < 0) {
            selectedLag = (minLag..maxLag).minByOrNull { normalized[it] } ?: return null
            if (normalized[selectedLag] > 0.32) return null
        }

        val frequency = reducedRate.toDouble() / selectedLag
        val midi = (69.0 + 12.0 * ln(frequency / 440.0) / ln(2.0)).roundToInt()
        return midi.takeIf { it in 35..88 }
    }

    /** Log-frequency spectrum, normalized to its strongest bin and compressed to -48..0 dB. */
    fun spectrumForDisplay(samples: FloatArray, sampleRate: Int, points: Int = 120): List<Float> {
        if (sampleRate <= 0 || points < 2) return emptyList()
        val magnitude = magnitudeSpectrum(samples) ?: return List(points) { 0f }
        val maxMagnitude = magnitude.maxOrNull()?.takeIf { it > 0f } ?: return List(points) { 0f }
        val minFrequency = 55.0
        val maxFrequency = minOf(2_200.0, sampleRate / 2.0)
        return List(points) { point ->
            val lowFrequency = minFrequency * (maxFrequency / minFrequency).pow(point.toDouble() / points)
            val highFrequency = minFrequency * (maxFrequency / minFrequency).pow((point + 1).toDouble() / points)
            val lowBin = (lowFrequency * samples.size / sampleRate).toInt().coerceIn(1, magnitude.lastIndex)
            val highBin = (highFrequency * samples.size / sampleRate).toInt().coerceIn(lowBin + 1, magnitude.size)
            var bandPeak = 0f
            for (bin in lowBin until highBin) bandPeak = maxOf(bandPeak, magnitude[bin])
            val decibels = 20.0 * ln((bandPeak / maxMagnitude).coerceAtLeast(0.00001f).toDouble()) / ln(10.0)
            ((decibels + 48.0) / 48.0).coerceIn(0.0, 1.0).toFloat()
        }
    }

    private fun magnitudeSpectrum(samples: FloatArray): FloatArray? {
        val size = samples.size
        if (size < 2048 || size and (size - 1) != 0) return null
        val real = samples.copyOf()
        val imaginary = FloatArray(size)
        for (i in real.indices) {
            val window = (0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))).toFloat()
            real[i] *= window
        }
        fft(real, imaginary)
        return FloatArray(size / 2) { bin ->
            val re = real[bin]
            val im = imaginary[bin]
            kotlin.math.sqrt(re * re + im * im)
        }
    }

    private fun fft(real: FloatArray, imaginary: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()
            for (start in 0 until n step length) {
                var wr = 1f
                var wi = 0f
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val tr = wr * real[odd] - wi * imaginary[odd]
                    val ti = wr * imaginary[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imaginary[odd] = imaginary[even] - ti
                    real[even] += tr
                    imaginary[even] += ti
                    val nextWr = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal
                    wr = nextWr
                }
            }
            length = length shl 1
        }
    }

    private fun Double.pow(power: Double): Double = kotlin.math.exp(ln(this) * power)
}

enum class GuitarInputMode { CHORD_GUESS, TAB_RECORD }

@androidx.compose.runtime.Composable
expect fun GuitarInputPanel(
    mode: GuitarInputMode,
    tuning: List<Int>,
    naming: ChordNamingSystem,
    onDetectedPitches: (List<Int>, ChordSymbol?) -> Unit
)
