package org.tablatureanalyzer.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformMidiPlayerButton(document: TabDocument, replayEventIndex: Int?, durationPreviewRequestId: Int, playheadEventIndex: Int, selectedPlaybackRange: IntRange?, onPlayingEvent: (Int?) -> Unit) {
    OutlinedButton(onClick = {}, enabled = false) { Text("MIDI playback is desktop-only") }
}

actual fun PlatformAuditionNote(midiPitch: Int) {
    if (midiPitch !in 0..127) return
    Thread({
        runCatching {
            val sampleRate = 22_050
            val sampleCount = sampleRate * 45 / 100
            val pcm = ByteArray(sampleCount * 2)
            val frequency = 440.0 * Math.pow(2.0, (midiPitch - 69) / 12.0)
            for (i in 0 until sampleCount) {
                val t = i.toDouble() / sampleRate
                val envelope = kotlin.math.exp(-5.0 * t) * (1.0 - kotlin.math.exp(-90.0 * t))
                val tone = kotlin.math.sin(2.0 * Math.PI * frequency * t) * 0.68 +
                    kotlin.math.sin(2.0 * Math.PI * frequency * 2.01 * t) * 0.22 +
                    kotlin.math.sin(2.0 * Math.PI * frequency * 3.0 * t) * 0.10
                val sample = (tone * envelope * Short.MAX_VALUE * 0.7).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                pcm[i * 2] = sample.toByte()
                pcm[i * 2 + 1] = (sample.toInt() shr 8).toByte()
            }
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(470)
            } finally {
                track.release()
            }
        }
    }, "tab-note-audition").apply { isDaemon = true }.start()
}
