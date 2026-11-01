package org.tablatureanalyzer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import javax.swing.SwingUtilities
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.roundToInt

private const val DEFAULT_INPUT = "Default input"
private const val NO_INPUT = "No microphone input detected"

@Composable
actual fun GuitarInputPanel(
    mode: GuitarInputMode,
    tuning: List<Int>,
    naming: ChordNamingSystem,
    onDetectedPitches: (List<Int>) -> Unit
) {
    val devices = remember { availableInputDevices() }
    var selectedDevice by remember(devices) { mutableStateOf(devices.firstOrNull() ?: NO_INPUT) }
    var deviceMenu by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var liveNotes by remember { mutableStateOf(emptyList<Int>()) }
    var liveRms by remember { mutableStateOf(0f) }
    var liveSpectrum by remember { mutableStateOf(emptyList<Float>()) }
    var capture by remember { mutableStateOf<DesktopGuitarCapture?>(null) }
    DisposableEffect(Unit) {
        onDispose { capture?.stop() }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (mode == GuitarInputMode.CHORD_GUESS) "Guess chord" else "Record from guitar", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Button(enabled = devices.isNotEmpty(), onClick = {
                    if (listening) {
                        capture?.stop()
                        capture = null
                        listening = false
                        status = "Stopped"
                    } else {
                        val next = DesktopGuitarCapture(
                            deviceName = selectedDevice.takeUnless { it == DEFAULT_INPUT },
                            singleNoteMode = mode == GuitarInputMode.TAB_RECORD,
                            onFrame = { pitches, rms, spectrum ->
                                SwingUtilities.invokeLater {
                                    if (listening || capture != null) {
                                        liveNotes = pitches
                                        liveRms = rms
                                        liveSpectrum = spectrum
                                        if (mode == GuitarInputMode.CHORD_GUESS) {
                                            status = if (pitches.isEmpty()) "Listening… play a chord" else "Listening"
                                        }
                                    }
                                }
                            },
                            onAttack = { pitches ->
                                SwingUtilities.invokeLater {
                                    if (mode == GuitarInputMode.TAB_RECORD && (listening || capture != null)) {
                                        onDetectedPitches(pitches)
                                        status = "Added ${pitches.size} ${if (pitches.size == 1) "note" else "notes"} to tab"
                                    }
                                }
                            },
                            onReady = { message -> SwingUtilities.invokeLater { status = message; listening = true } },
                            onFailure = { message -> SwingUtilities.invokeLater { status = message; listening = false; capture = null } }
                        )
                        capture = next
                        try {
                            next.start()
                            status = "Opening microphone…"
                        } catch (failure: Exception) {
                            capture = null
                            listening = false
                            status = failure.message ?: "No usable audio input found"
                        }
                    }
                }) { Text(if (listening) "Stop" else "Listen") }
                Box {
                    OutlinedButton(onClick = { deviceMenu = true }, enabled = devices.isNotEmpty()) { Text("Input: ${selectedDevice.take(24)} ▾", fontSize = 11.sp) }
                    DropdownMenu(deviceMenu, onDismissRequest = { deviceMenu = false }) {
                        devices.forEach { device ->
                            DropdownMenuItem(text = { Text(device) }, onClick = { selectedDevice = device; deviceMenu = false })
                        }
                    }
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
            if (mode == GuitarInputMode.CHORD_GUESS && liveNotes.isNotEmpty()) {
                val pitchClasses = liveNotes.map { ((it % 12) + 12) % 12 }.toSet()
                val chord = identifyChord(pitchClasses)
                val noteText = liveNotes.sorted().joinToString(" · ") { midiNoteName(it, naming) }
                Text("${chord?.name(naming) ?: "Chord not identified"}  ·  $noteText", color = if (chord != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            if (listening) {
                val db = if (liveRms > 0f) (20.0 * ln(liveRms.toDouble()) / ln(10.0)).roundToInt() else -96
                val detected = liveNotes.takeIf { it.isNotEmpty() }?.joinToString(" · ") { midiNoteName(it, naming) } ?: "no pitch yet"
                Text("Mic ${db} dBFS  ·  $detected", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("FFT spectrum · 55 Hz–2.2 kHz", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                SpectrumGraph(liveSpectrum)
            }
            if (mode == GuitarInputMode.TAB_RECORD) {
                Text(
                    if (devices.isEmpty()) "Connect a microphone or guitar interface, then restart the app. Each detected attack will add a tab position."
                    else "Play one note; each detected pluck adds one tab position.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SpectrumGraph(values: List<Float>) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val spectrumColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val top = 4f
        val bottom = size.height - 4f
        for (step in 0..4) {
            val y = top + (bottom - top) * step / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (values.size > 1) {
            val path = Path()
            values.forEachIndexed { index, level ->
                val x = size.width * index / (values.lastIndex)
                val y = bottom - (bottom - top) * level
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, spectrumColor, style = Stroke(width = 2f))
        }
    }
}

private fun availableInputDevices(): List<String> {
    val format = AudioFormat(44_100f, 16, 1, true, false)
    val info = DataLine.Info(TargetDataLine::class.java, format)
    val inputs = AudioSystem.getMixerInfo().filter { mixerInfo ->
        runCatching { AudioSystem.getMixer(mixerInfo).isLineSupported(info) }.getOrDefault(false)
    }.map { it.name }
    val defaultAvailable = runCatching { AudioSystem.getLine(info) is TargetDataLine }.getOrDefault(false)
    return (if (defaultAvailable) listOf(DEFAULT_INPUT) else emptyList()) + inputs
}

private class DesktopGuitarCapture(
    private val deviceName: String?,
    private val singleNoteMode: Boolean,
    private val onFrame: (List<Int>, Float, List<Float>) -> Unit,
    private val onAttack: (List<Int>) -> Unit,
    private val onReady: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var line: TargetDataLine? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ captureLoop() }, "guitar-microphone-input").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        line?.stop()
        line?.close()
        line = null
    }

    private fun captureLoop() {
        try {
            val format = AudioFormat(44_100f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val target = if (deviceName == null) {
                AudioSystem.getLine(info) as TargetDataLine
            } else {
                val mixerInfo = AudioSystem.getMixerInfo().firstOrNull { it.name == deviceName }
                    ?: error("Audio input “$deviceName” is no longer available")
                val mixer = AudioSystem.getMixer(mixerInfo)
                if (!mixer.isLineSupported(info)) error("$deviceName does not provide microphone input")
                mixer.getLine(info) as TargetDataLine
            }
            line = target
            target.open(format, FFT_SIZE * 2)
            target.start()
            onReady("Listening on ${deviceName ?: "default input"}")
            val bytes = ByteArray(READ_SAMPLES * 2)
            val ring = FloatArray(FFT_SIZE)
            var filled = 0
            var previousRms = 0f
            var previousNotes = emptyList<Int>()
            var stableNotes = emptyList<Int>()
            var stableFrames = 0
            var lastAttackAt = 0L
            while (running.get()) {
                val count = target.read(bytes, 0, bytes.size)
                if (count <= 0) continue
                val readSamples = count / 2
                val decoded = FloatArray(readSamples) { index ->
                    val lo = bytes[index * 2].toInt() and 0xff
                    val hi = bytes[index * 2 + 1].toInt()
                    ((hi shl 8) or lo).toShort().toInt() / 32768f
                }
                if (readSamples >= FFT_SIZE) {
                    decoded.copyInto(ring, 0, readSamples - FFT_SIZE)
                    filled = FFT_SIZE
                } else {
                    val shift = readSamples
                    ring.copyInto(ring, 0, shift, FFT_SIZE)
                    decoded.copyInto(ring, FFT_SIZE - shift)
                    filled = (filled + shift).coerceAtMost(FFT_SIZE)
                }
                if (filled < FFT_SIZE) continue
                val rms = sqrt(ring.sumOf { (it * it).toDouble() } / FFT_SIZE).toFloat()
                val notes = if (rms >= MIN_RMS) {
                    if (singleNoteMode) GuitarFftAnalyzer.detectMidiNote(ring, SAMPLE_RATE)?.let(::listOf) ?: emptyList()
                    else GuitarFftAnalyzer.detectMidiNotes(ring, SAMPLE_RATE)
                } else emptyList()
                val spectrum = GuitarFftAnalyzer.spectrumForDisplay(ring, SAMPLE_RATE)
                onFrame(notes, rms, spectrum)
                if (notes == stableNotes) stableFrames++ else { stableNotes = notes; stableFrames = 1 }
                val now = System.currentTimeMillis()
                val changed = stableFrames >= 2 && notes.isNotEmpty() && notes != previousNotes
                val newAttack = rms > maxOf(MIN_RMS, previousRms * 1.28f) && now - lastAttackAt > ATTACK_COOLDOWN_MS
                if (stableFrames >= 2 && notes.isNotEmpty() && now - lastAttackAt > ATTACK_COOLDOWN_MS && (changed || newAttack)) {
                    onAttack(notes)
                    previousNotes = notes
                    lastAttackAt = now
                }
                if (rms < MIN_RMS) previousNotes = emptyList()
                previousRms = rms
            }
        } catch (failure: Exception) {
            if (running.get()) onFailure(failure.message ?: "Could not open microphone input")
        } finally {
            running.set(false)
            line?.close()
            line = null
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FFT_SIZE = 16_384
        const val READ_SAMPLES = 4_096
        const val MIN_RMS = 0.0015f
        const val ATTACK_COOLDOWN_MS = 260L
    }
}

private fun midiNoteName(midi: Int, naming: ChordNamingSystem): String {
    val octave = midi / 12 - 1
    return "${noteName(midi, naming)}$octave"
}
