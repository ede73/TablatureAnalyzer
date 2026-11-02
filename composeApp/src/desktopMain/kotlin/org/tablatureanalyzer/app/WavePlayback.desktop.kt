package org.tablatureanalyzer.app

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal fun WavePlaybackButton() {
    var player by remember { mutableStateOf<DesktopWavePlayback?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedFile by remember {
        mutableStateOf(PlatformPreferences.get("guitarTestWave")?.let(::File)?.takeIf(File::isFile)
            ?: listOf("src/desktopTest/resources/audio/SingleNotes.wav", "composeApp/src/desktopTest/resources/audio/SingleNotes.wav")
                .map(::File).firstOrNull(File::isFile))
    }
    DisposableEffect(Unit) { onDispose { player?.stop() } }
    OutlinedButton(onClick = {
        if (player != null) {
            player?.stop()
            player = null
        } else {
            val file = selectedFile ?: JFileChooser().apply {
                dialogTitle = "Play a WAV recording through the speakers"
                fileFilter = FileNameExtensionFilter("WAV audio", "wav")
            }.let { if (it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) it.selectedFile else null }
            if (file != null) {
                selectedFile = file
                PlatformPreferences.put("guitarTestWave", file.absolutePath)
                error = null
                lateinit var next: DesktopWavePlayback
                next = DesktopWavePlayback(file) { failure ->
                    SwingUtilities.invokeLater {
                        if (player === next) {
                            player = null
                            error = failure?.message
                        }
                    }
                }
                player = next
                next.start()
            }
        }
    }) { Text(if (player != null) "■ Stop WAV" else "▶ Play WAV") }
    OutlinedButton(onClick = {
        val chooser = JFileChooser(selectedFile?.parentFile).apply {
            dialogTitle = "Choose WAV recording"
            fileFilter = FileNameExtensionFilter("WAV audio", "wav")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.selectedFile
            PlatformPreferences.put("guitarTestWave", chooser.selectedFile.absolutePath)
        }
    }, enabled = player == null) { Text("Choose WAV…") }
    error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
}

/** Streaming PCM playback, independent of MIDI and microphone recording. */
internal class DesktopWavePlayback(private val file: File, private val onFinished: (Exception?) -> Unit) {
    private val running = AtomicBoolean(true)
    @Volatile private var line: SourceDataLine? = null

    fun start() {
        Thread({
            var failure: Exception? = null
            try {
                AudioSystem.getAudioInputStream(file).use { source ->
                    val format = AudioFormat(source.format.sampleRate, 16, source.format.channels, true, false)
                    AudioSystem.getAudioInputStream(format, source).use { audio ->
                        AudioSystem.getSourceDataLine(format).use { output ->
                            line = output
                            output.open(format)
                            if (running.get()) output.start()
                            val bytes = ByteArray(8192)
                            while (running.get()) {
                                val count = audio.read(bytes)
                                if (count < 0) break
                                var offset = 0
                                while (running.get() && offset < count) {
                                    val written = output.write(bytes, offset, count - offset)
                                    if (written <= 0) break
                                    offset += written
                                }
                            }
                            if (running.get()) output.drain()
                        }
                    }
                }
            } catch (error: Exception) {
                if (running.get()) failure = error
            } finally {
                line = null
                onFinished(failure)
            }
        }, "guitar-wav-playback").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        line?.let { output ->
            runCatching { output.stop() }
            runCatching { output.flush() }
            runCatching { output.close() }
        }
    }
}
