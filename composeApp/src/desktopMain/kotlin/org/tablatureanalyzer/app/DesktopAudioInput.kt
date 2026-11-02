package org.tablatureanalyzer.app

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** Try common Windows device formats instead of requiring one mono 44.1 kHz mode. */
internal object DesktopAudioInput {
    val formats: List<AudioFormat> = listOf(44_100, 48_000, 32_000, 16_000, 22_050, 8_000).flatMap { rate ->
        listOf(1, 2).map { channels -> AudioFormat(rate.toFloat(), 16, channels, true, false) }
    }

    fun deviceNames(): List<String> = AudioSystem.getMixerInfo().filter { mixer ->
        formats.any { AudioSystem.getMixer(mixer).isLineSupported(DataLine.Info(TargetDataLine::class.java, it)) }
    }.map { it.name }

    fun defaultAvailable(): Boolean = formats.any { format ->
        runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format)) }.getOrDefault(false)
    }

    fun open(deviceName: String?): TargetDataLine {
        val mixer = deviceName?.let { name ->
            val info = AudioSystem.getMixerInfo().firstOrNull { it.name == name }
                ?: error("Audio input “$name” is no longer available. Refresh inputs.")
            AudioSystem.getMixer(info)
        }
        var lastFailure: Exception? = null
        for (format in formats) {
            var line: TargetDataLine? = null
            try {
                val info = DataLine.Info(TargetDataLine::class.java, format)
                if (mixer != null && !mixer.isLineSupported(info)) continue
                if (mixer == null && !AudioSystem.isLineSupported(info)) continue
                line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
                line.open(format, 16_384 * format.frameSize)
                return line
            } catch (failure: Exception) {
                line?.close()
                lastFailure = failure
            }
        }
        throw IllegalStateException("Could not open ${deviceName ?: "the default microphone"}: ${lastFailure?.message ?: "no supported capture format"}", lastFailure)
    }

    /** Choose the strongest channel; stereo microphone channels can be silent or out of phase. */
    fun decode(bytes: ByteArray, count: Int, channels: Int): FloatArray {
        require(channels > 0 && count in 0..bytes.size)
        val frames = count / (channels * 2)
        fun sample(frame: Int, channel: Int): Float {
            val index = (frame * channels + channel) * 2
            return ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 255)).toShort().toFloat() / 32768f
        }
        val strongest = (0 until channels).maxByOrNull { channel ->
            (0 until frames).sumOf { frame -> val value = sample(frame, channel); (value * value).toDouble() }
        } ?: 0
        return FloatArray(frames) { sample(it, strongest) }
    }
}
