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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import javax.swing.SwingUtilities
import kotlin.math.sqrt
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import java.util.Properties

private const val DEFAULT_INPUT = "Default input"
private const val NO_INPUT = "No microphone input detected"

@Composable
actual fun GuitarInputPanel(
    mode: GuitarInputMode,
    tuning: List<Int>,
    naming: ChordNamingSystem,
    onDetectedPitches: (List<Int>, ChordSymbol?) -> Unit
) {
    var devices by remember { mutableStateOf(availableInputDevices()) }
    var selectedDevice by remember { mutableStateOf(PlatformPreferences.get("guitarInputDevice")?.takeIf { it in devices } ?: devices.firstOrNull() ?: NO_INPUT) }
    var inputGain by remember(selectedDevice) { mutableStateOf(PlatformPreferences.get("guitarInputGain_${selectedDevice.hashCode()}")?.toFloatOrNull()?.coerceIn(0.25f, 64f) ?: 1f) }
    var inputNoise by remember(selectedDevice) { mutableStateOf(PlatformPreferences.get("guitarInputNoise_${selectedDevice.hashCode()}")?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f) }
    var deviceMenu by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var liveNotes by remember { mutableStateOf(emptyList<Int>()) }
    var liveRms by remember { mutableStateOf(0f) }
    var liveSpectrum by remember { mutableStateOf(emptyList<Float>()) }
    var sampleSaveStatus by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf<DesktopGuitarCapture?>(null) }
    var calibrationDialog by remember { mutableStateOf(false) }
    var calibrationStep by remember { mutableStateOf(0) }
    var calibrationStartedAt by remember { mutableStateOf(0L) }
    val quietCalibration = remember { mutableListOf<Float>() }
    val playingCalibration = remember { mutableListOf<Float>() }
    var calibrationResult by remember { mutableStateOf("") }
    val chordRanker = remember { AudioChordRanker() }
    var rankedChords by remember { mutableStateOf(emptyList<AudioChordGuess>()) }
    var confirmedChord by remember { mutableStateOf<ChordSymbol?>(null) }
    var lastChordSent by remember { mutableStateOf<String?>(null) }
    var chordLevelReference by remember { mutableStateOf(0f) }
    var belowChordGateFrames by remember { mutableStateOf(0) }
    var quietInputFrames by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        onDispose { capture?.stop() }
    }

    fun toggleListening() {
                    if (listening) {
                        capture?.stop()
                        capture = null
                        listening = false
                        chordRanker.clear()
                        lastChordSent = null
                        chordLevelReference = 0f
                        belowChordGateFrames = 0
                        quietInputFrames = 0
                        calibrationStep = 0
                        calibrationDialog = false
                        status = "Stopped"
                    } else {
                        val next = DesktopGuitarCapture(
                            deviceName = selectedDevice.takeUnless { it == DEFAULT_INPUT },
                            initialGain = inputGain,
                            initialNoiseFloor = inputNoise,
                            singleNoteMode = mode == GuitarInputMode.TAB_RECORD,
                            minMidi = tuning.minOrNull() ?: 35,
                            maxMidi = minOf(88, (tuning.maxOrNull() ?: 64) + 24),
                            onFrame = { pitches, rms, spectrum ->
                                SwingUtilities.invokeLater {
                                    if (listening || capture != null) {
                                        liveNotes = pitches
                                        liveRms = rms
                                        liveSpectrum = spectrum
                                        if (calibrationStep != 0) {
                                            val now = System.currentTimeMillis()
                                            if (calibrationStep == 1) {
                                                quietCalibration += rms
                                                if (now - calibrationStartedAt >= 2_000L) {
                                                    calibrationStep = 2
                                                    calibrationStartedAt = now
                                                    playingCalibration.clear()
                                                    status = "Strum a chord at your usual playing volume"
                                                }
                                            } else {
                                                playingCalibration += rms
                                                if (now - calibrationStartedAt >= 3_000L) {
                                                    fun median(values: List<Float>): Float = values.sorted().let { sorted -> sorted[sorted.size / 2] }
                                                    val quiet = quietCalibration.takeIf { it.isNotEmpty() }?.let(::median) ?: 0f
                                                    val playing = playingCalibration.takeIf { it.isNotEmpty() }?.let(::median) ?: 0f
                                                    fun dbfs(value: Float) = if (value > 0f) (20.0 * ln(value.toDouble()) / ln(10.0)).roundToInt() else -96
                                                    val ambientDb = dbfs(quiet)
                                                    val signalToNoiseDb = if (quiet > 0f && playing > 0f) (20.0 * ln((playing / quiet).toDouble()) / ln(10.0)).roundToInt() else 96
                                                    if (signalToNoiseDb < 9 || playing < 0.00005f) {
                                                        calibrationResult = "Too noisy: ambient ${ambientDb} dBFS; guitar only ${signalToNoiseDb} dB above it. Move closer, use a pickup, or calibrate somewhere quieter."
                                                        status = calibrationResult
                                                    } else {
                                                        val gain = (0.07f / playing).coerceIn(0.25f, 64f)
                                                        val noiseGate = quiet * 2.5f
                                                        inputGain = gain
                                                        inputNoise = noiseGate
                                                        capture?.setInputProfile(gain, noiseGate)
                                                        val profileKey = selectedDevice.hashCode()
                                                        PlatformPreferences.put("guitarInputGain_$profileKey", gain.toString())
                                                        PlatformPreferences.put("guitarInputNoise_$profileKey", noiseGate.toString())
                                                        calibrationResult = "Saved ×${"%.1f".format(gain)} · ambient ${ambientDb} dBFS · guitar +${signalToNoiseDb} dB · $selectedDevice"
                                                        status = calibrationResult
                                                    }
                                                    calibrationStep = 0
                                                    calibrationDialog = false
                                                }
                                            }
                                        }
                                        if (mode == GuitarInputMode.CHORD_GUESS) {
                                            // Keep raw pitch guesses flowing independently from the
                                            // slower, stabilized chord-name updates.
                                            onDetectedPitches(pitches, null)
                                            status = if (pitches.isEmpty()) "Listening… play a chord" else "Listening"
                                            chordLevelReference = if (rms > chordLevelReference) rms else chordLevelReference * 0.995f
                                            val inputThreshold = capture?.inputThreshold ?: 0.0015f
                                            if (rms < inputThreshold) {
                                                quietInputFrames++
                                                if (quietInputFrames >= 8) chordLevelReference = 0f
                                            } else quietInputFrames = 0
                                            val trustedLevel = rms >= maxOf(inputThreshold, chordLevelReference * 0.18f)
                                            if (trustedLevel) {
                                                belowChordGateFrames = 0
                                                val preferredRoot = confirmedChord?.root
                                                val frameCandidates = rankAudioChords(pitches, preferredRoot)
                                                val ranking = chordRanker.update(pitches, preferredRoot)
                                                if (frameCandidates.isNotEmpty()) rankedChords = ranking
                                                val winner = ranking.firstOrNull()
                                                val currentEvidence = confirmedChord?.let { current ->
                                                    ranking.firstOrNull { it.chord.root == current.root && it.chord.quality.suffix == current.quality.suffix }
                                                }
                                                val isSameChord = winner?.chord?.let { candidate ->
                                                    candidate.root == confirmedChord?.root && candidate.quality.suffix == confirmedChord?.quality?.suffix
                                                } == true
                                                val replacesCurrent = currentEvidence == null || (
                                                    winner != null && winner.score >= currentEvidence.score * 1.2 && winner.support >= currentEvidence.support
                                                )
                                                // Keep the strongest confirmed guess. A replacement must persist and
                                                // beat the current chord's recent evidence by a clear margin.
                                                if (frameCandidates.isNotEmpty() && winner != null && winner.support >= 4 &&
                                                    (confirmedChord == null || isSameChord || replacesCurrent) &&
                                                    winner.chord.name(naming) != lastChordSent
                                                ) {
                                                    confirmedChord = winner.chord
                                                    logAudioUiEvent("chord", pitches, winner.chord.name)
                                                    onDetectedPitches(pitches, winner.chord)
                                                    lastChordSent = winner.chord.name(naming)
                                                }
                                            } else {
                                                belowChordGateFrames++
                                                if (belowChordGateFrames == 6) chordRanker.clear()
                                            }
                                        }
                                    }
                                }
                            },
                            onAttack = { pitches ->
                                SwingUtilities.invokeLater {
                                    if (mode == GuitarInputMode.TAB_RECORD && (listening || capture != null)) {
                                        logAudioUiEvent("note", pitches, "")
                                        onDetectedPitches(pitches, null)
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

    }

    // Local opt-in hot-reload harness: no external player or system setting changes.
    val currentToggle by rememberUpdatedState(::toggleListening)
    val currentProfile by rememberUpdatedState<(Float, Float) -> Unit>({ gain, noise ->
        inputGain = gain
        inputNoise = noise
        capture?.setInputProfile(gain, noise)
    })
    androidx.compose.runtime.LaunchedEffect("audio-test-control-v2") {
        if (System.getProperty("compose.reload.argfile") == null) return@LaunchedEffect
        val command = File("build/audio-test.properties")
        var lastCommand = ""
        while (true) {
            if (command.isFile) {
                val properties = Properties().apply { command.inputStream().use(::load) }
                val id = properties.getProperty("id", "")
                val requestedPage = properties.getProperty("page")?.toIntOrNull()
                val pageMatches = requestedPage == null ||
                    (requestedPage == 0 && mode == GuitarInputMode.TAB_RECORD) ||
                    (requestedPage == 2 && mode == GuitarInputMode.CHORD_GUESS)
                if (id.isNotBlank() && id != lastCommand && pageMatches) {
                    lastCommand = id
                    if (listening) currentToggle()
                    selectedDevice = properties.getProperty("input", selectedDevice)
                    delay(200)
                    currentProfile(properties.getProperty("gain", "1").toFloat().coerceIn(0.25f, 64f),
                        properties.getProperty("noise", "0").toFloat().coerceAtLeast(0f))
                    val log = File("build/reports/audio/live-$id.log").apply { parentFile.mkdirs() }
                    log.writeText("START millis=${System.currentTimeMillis()} input=$selectedDevice gain=${properties.getProperty("gain")} mode=$mode\n")
                    currentToggle()
                    delay(1000)
                    val wave = DesktopWavePlayback(File(properties.getProperty("wav"))) { failure ->
                        log.appendText("PLAYBACK_DONE error=${failure?.message}\n")
                    }
                    try {
                        wave.start()
                        log.appendText("PLAYBACK_STARTED\n")
                        for (seconds in listOf(14, 21, 33)) {
                            delay(if (seconds == 14) 14000L else if (seconds == 21) 7000L else 12000L)
                            val saved = capture?.saveDiagnosticSample()
                            log.appendText("$seconds s notes=$liveNotes chord=${confirmedChord?.name} rms=$liveRms status=$status sample=${saved?.absolutePath}\n")
                        }
                        delay(3000)
                    } finally {
                        wave.stop()
                        if (listening) currentToggle()
                        log.appendText("STOPPED\n")
                    }
                }
            }
            delay(500)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (mode == GuitarInputMode.CHORD_GUESS) "Guess chord" else "Record from guitar", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Button(enabled = devices.isNotEmpty(), onClick = ::toggleListening) { Text(if (listening) "Stop" else "Listen") }
                Box {
                    OutlinedButton(onClick = {
                        devices = availableInputDevices()
                        if (selectedDevice !in devices) selectedDevice = devices.firstOrNull() ?: NO_INPUT
                        deviceMenu = true
                    }, enabled = !listening) { Text("Input: ${selectedDevice.take(24)} ▾", fontSize = 11.sp) }
                    DropdownMenu(deviceMenu, onDismissRequest = { deviceMenu = false }) {
                        devices.forEach { device ->
                            DropdownMenuItem(text = { Text(device) }, onClick = { selectedDevice = device; PlatformPreferences.put("guitarInputDevice", device); deviceMenu = false })
                        }
                    }
                }
                OutlinedButton(onClick = { calibrationDialog = true }, enabled = listening) { Text("Calibrate input", fontSize = 11.sp) }
                if (listening) {
                    OutlinedButton(onClick = {
                        sampleSaveStatus = runCatching {
                            capture?.saveDiagnosticSample() ?: error("Microphone is not recording")
                        }.fold(
                            onSuccess = { "Saved ${it.name}" },
                            onFailure = { "Could not save sample: ${it.message ?: "unknown error"}" }
                        )
                    }) { Text("Save last 10 s", fontSize = 11.sp) }
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WavePlaybackButton()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val gainDb = (20.0 * ln(inputGain.toDouble()) / ln(10.0)).toFloat()
                    Text("Input gain ${"%+.0f".format(gainDb)} dB · ×${"%.1f".format(inputGain)}", fontSize = 12.sp)
                    Slider(value = gainDb.coerceIn(-12f, 36f), valueRange = -12f..36f, onValueChange = { db ->
                        inputGain = kotlin.math.exp(db / 20.0 * ln(10.0)).toFloat().coerceIn(0.25f, 64f)
                        capture?.setInputProfile(inputGain, inputNoise)
                    }, onValueChangeFinished = {
                        PlatformPreferences.put("guitarInputGain_${selectedDevice.hashCode()}", inputGain.toString())
                    })
                }
                Column(Modifier.weight(1f)) {
                    val noiseDb = if (inputNoise > 0f) (20.0 * ln(inputNoise.toDouble()) / ln(10.0)).toFloat() else -90f
                    val effectiveDb = (20.0 * ln(guitarInputThreshold(inputGain, inputNoise).toDouble()) / ln(10.0)).roundToInt()
                    Text("Noise gate · effective $effectiveDb dBFS", fontSize = 12.sp)
                    Slider(value = noiseDb.coerceIn(-90f, -30f), valueRange = -90f..-30f, onValueChange = { db ->
                        inputNoise = if (db <= -89.9f) 0f else kotlin.math.exp(db / 20.0 * ln(10.0)).toFloat()
                        capture?.setInputProfile(inputGain, inputNoise)
                    }, onValueChangeFinished = {
                        PlatformPreferences.put("guitarInputNoise_${selectedDevice.hashCode()}", inputNoise.toString())
                    })
                }
                TextButton(onClick = {
                    inputGain = 1f
                    inputNoise = 0f
                    capture?.setInputProfile(inputGain, inputNoise)
                    PlatformPreferences.put("guitarInputGain_${selectedDevice.hashCode()}", "1.0")
                    PlatformPreferences.put("guitarInputNoise_${selectedDevice.hashCode()}", "0.0")
                }) { Text("Reset input") }
            }
            if (calibrationResult.isNotEmpty()) {
                Text(calibrationResult, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            if (mode == GuitarInputMode.CHORD_GUESS && (listening || liveNotes.isNotEmpty() || rankedChords.isNotEmpty() || confirmedChord != null)) {
                val chord = confirmedChord ?: rankedChords.firstOrNull()?.chord ?: identifyAudioChord(liveNotes)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Chord guess", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    Text(
                        chord?.name(naming) ?: "Waiting for a stable chord",
                        color = if (chord != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Text("FFT pitch candidates", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    val pitches = liveNotes.sorted().joinToString(" · ") { "${midiNoteName(it, naming)} (MIDI $it)" }
                    Text(
                        pitches.ifEmpty { "No pitches above the input gate" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp
                    )
                    val alternatives = rankedChords.take(3).mapIndexed { index, guess ->
                        "${index + 1}. ${guess.chord.name(naming)} · ${guess.support}/16 frames"
                    }.joinToString("   ·   ")
                    if (alternatives.isNotEmpty()) {
                        Text("Other chord candidates  ·  $alternatives", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
            if (listening) {
                val db = if (liveRms > 0f) (20.0 * ln(liveRms.toDouble()) / ln(10.0)).roundToInt() else -96
                val detected = liveNotes.takeIf { it.isNotEmpty() }?.joinToString(" · ") { midiNoteName(it, naming) } ?: "no pitch yet"
                Text("Mic ${db} dBFS  ·  $detected", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("FFT spectrum · 55 Hz–2.2 kHz", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                SpectrumGraph(liveSpectrum)
                if (sampleSaveStatus.isNotEmpty()) {
                    Text(sampleSaveStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
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
    if (calibrationDialog) {
        AlertDialog(
            onDismissRequest = {
                calibrationDialog = false
                calibrationStep = 0
            },
            title = { Text("Calibrate input") },
            text = {
                Text(
                    when (calibrationStep) {
                        1 -> "Measuring ambient noise for 2 seconds. Then the app will ask you to strum."
                        2 -> "Strum a chord at your usual playing volume for 3 seconds."
                        else -> "Use the selected mic in its normal position. The app measures ambient noise first, then compares it with a normal strum. It saves a level profile for this input only when the guitar is clearly louder than the background. Rerun after moving the mic or changing setup."
                    }
                )
            },
            confirmButton = {
                if (calibrationStep == 0) TextButton(enabled = listening, onClick = {
                    quietCalibration.clear()
                    playingCalibration.clear()
                    calibrationStartedAt = System.currentTimeMillis()
                    calibrationStep = 1
                    calibrationResult = ""
                    status = "Calibration: keep quiet for 2 seconds"
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = {
                calibrationDialog = false
                calibrationStep = 0
            }) { Text("Cancel") } }
        )
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

private fun logAudioUiEvent(kind: String, pitches: List<Int>, chord: String) {
    if (System.getProperty("compose.reload.argfile") == null) return
    val command = File("build/audio-test.properties")
    if (!command.isFile) return
    val properties = Properties().apply { command.inputStream().use(::load) }
    val id = properties.getProperty("id", "")
    if (id.isBlank()) return
    val log = File("build/reports/audio/live-$id-events.csv")
    log.parentFile.mkdirs()
    if (!log.exists()) log.writeText("millis,kind,midi,chord\n")
    log.appendText("${System.currentTimeMillis()},$kind,${pitches.joinToString(" ")},$chord\n")
}

private fun availableInputDevices(): List<String> {
    val inputs = runCatching { DesktopAudioInput.deviceNames() }.getOrDefault(emptyList())
    val defaultAvailable = DesktopAudioInput.defaultAvailable()
    return (if (defaultAvailable) listOf(DEFAULT_INPUT) else emptyList()) + inputs
}

private class DesktopGuitarCapture(
    private val deviceName: String?,
    initialGain: Float,
    initialNoiseFloor: Float,
    private val singleNoteMode: Boolean,
    private val minMidi: Int,
    private val maxMidi: Int,
    private val onFrame: (List<Int>, Float, List<Float>) -> Unit,
    private val onAttack: (List<Int>) -> Unit,
    private val onReady: (String) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var inputGain = initialGain.coerceIn(0.25f, 64f)
    @Volatile private var noiseFloor = initialNoiseFloor.coerceAtLeast(0f)
    private var thread: Thread? = null
    @Volatile private var diagnosticBuffer = DiagnosticAudioBuffer(SAMPLE_RATE * DIAGNOSTIC_SECONDS, SAMPLE_RATE)

    val inputThreshold: Float get() = guitarInputThreshold(inputGain, noiseFloor)

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

    fun saveDiagnosticSample(): File = diagnosticBuffer.save()

    fun setInputProfile(gain: Float, noise: Float) {
        inputGain = gain.coerceIn(0.25f, 64f)
        noiseFloor = noise.coerceAtLeast(0f)
    }

    private fun captureLoop() {
        try {
            val target = DesktopAudioInput.open(deviceName)
            val format = target.format
            val sampleRate = format.sampleRate.toInt()
            diagnosticBuffer = DiagnosticAudioBuffer(sampleRate * DIAGNOSTIC_SECONDS, sampleRate)
            line = target
            target.start()
            onReady("Listening on ${deviceName ?: "default input"} · $sampleRate Hz · ${format.channels} ch")
            val hop = if (singleNoteMode) (sampleRate * 1024L / 44_100).toInt().coerceAtLeast(128) else READ_SAMPLES
            val bytes = ByteArray(hop * format.frameSize)
            val noteRecorder = SingleNoteRecorder()
            var samplesRead = 0L
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
                val decoded = DesktopAudioInput.decode(bytes, count, format.channels)
                val readSamples = decoded.size
                samplesRead += readSamples
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
                // Keep software gain linear. Clamping here is a hard limiter that creates
                // extra harmonics, which the chord detector can mistake for chord tones.
                val analyzed = FloatArray(FFT_SIZE) { index -> ring[index] * inputGain }
                val threshold = inputThreshold
                val notes = if (rms >= threshold) {
                    if (singleNoteMode) GuitarFftAnalyzer.detectMidiNote(analyzed, sampleRate)?.let(::listOf) ?: emptyList()
                    else GuitarFftAnalyzer.detectMidiNotes(analyzed, sampleRate, minMidi, maxMidi)
                } else emptyList()
                val attackLevel = sqrt(decoded.sumOf { (it * it).toDouble() } / decoded.size).toFloat()
                val recordedNote = if (singleNoteMode) {
                    noteRecorder.update(notes.singleOrNull(), attackLevel, threshold, samplesRead * 1000 / sampleRate)
                } else null
                diagnosticBuffer.append(decoded, rms, notes, threshold, attackLevel, recordedNote)
                val spectrum = GuitarFftAnalyzer.spectrumForDisplay(analyzed, sampleRate)
                onFrame(notes, rms, spectrum)
                if (singleNoteMode) {
                    recordedNote?.let { onAttack(listOf(it)) }
                    continue
                }
                if (notes == stableNotes) stableFrames++ else { stableNotes = notes; stableFrames = 1 }
                val now = System.currentTimeMillis()
                val changed = stableFrames >= 2 && notes.isNotEmpty() && notes != previousNotes
                val newAttack = rms > maxOf(threshold, previousRms * 1.28f) && now - lastAttackAt > ATTACK_COOLDOWN_MS
                if (stableFrames >= 2 && notes.isNotEmpty() && now - lastAttackAt > ATTACK_COOLDOWN_MS && (changed || newAttack)) {
                    onAttack(notes)
                    previousNotes = notes
                    lastAttackAt = now
                }
                if (rms < threshold) previousNotes = emptyList()
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
        const val ATTACK_COOLDOWN_MS = 260L
        const val DIAGNOSTIC_SECONDS = 10
    }
}

/** Keeps only a short in-memory tail until the user explicitly saves a diagnostic sample. */
private class DiagnosticAudioBuffer(private val maxSamples: Int, private val sampleRate: Int) {
    private val lock = Any()
    private val samples = ShortArray(maxSamples)
    private val frames = ArrayDeque<Pair<Long, String>>()
    private var writeIndex = 0
    private var sampleCount = 0
    private val captureStartedAt = System.currentTimeMillis()

    fun append(input: FloatArray, rms: Float, notes: List<Int>, threshold: Float, attackLevel: Float, recordedNote: Int?) = synchronized(lock) {
        val now = System.currentTimeMillis()
        input.forEach { sample ->
            samples[writeIndex] = (sample * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            writeIndex = (writeIndex + 1) % maxSamples
            sampleCount = (sampleCount + 1).coerceAtMost(maxSamples)
        }
        val db = if (rms > 0f) 20.0 * ln(rms.toDouble()) / ln(10.0) else -96.0
        frames.addLast(now to "${now - captureStartedAt},$rms,$db,${notes.joinToString(" ")},$threshold,$attackLevel,${recordedNote ?: ""}")
        while (frames.isNotEmpty() && now - frames.first.first > DIAGNOSTIC_WINDOW_MS) {
            frames.removeFirst()
        }
    }

    fun save(): File = synchronized(lock) {
        check(sampleCount > 0) { "No microphone samples captured yet" }
        val directory = File(System.getProperty("user.home"), "Documents/TablatureAnalyzer-audio-debug")
        check(directory.mkdirs() || directory.isDirectory) { "Could not create ${directory.absolutePath}" }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val waveFile = File(directory, "guitar-$stamp.wav")
        val traceFile = File(directory, "guitar-$stamp.csv")
        writeWave(waveFile)
        traceFile.writeText(buildString {
            appendLine("elapsed_ms,rms,dbfs,midi_candidates,gate_rms,attack_rms,recorded_midi")
            frames.forEach { appendLine(it.second) }
        })
        waveFile
    }

    private fun writeWave(file: File) {
        val dataBytes = sampleCount * 2
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.writeAscii("RIFF")
            output.writeLittleEndianInt(36 + dataBytes)
            output.writeAscii("WAVEfmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianInt(sampleRate)
            output.writeLittleEndianInt(sampleRate * 2)
            output.writeLittleEndianShort(2)
            output.writeLittleEndianShort(16)
            output.writeAscii("data")
            output.writeLittleEndianInt(dataBytes)
            val first = (writeIndex - sampleCount + maxSamples) % maxSamples
            repeat(sampleCount) { offset ->
                val value = samples[(first + offset) % maxSamples].toInt()
                output.write(value and 0xff)
                output.write((value ushr 8) and 0xff)
            }
        }
    }

    private fun OutputStream.writeAscii(value: String) = write(value.encodeToByteArray())
    private fun OutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }
    private fun OutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val DIAGNOSTIC_WINDOW_MS = 10_000L
    }
}

private fun midiNoteName(midi: Int, naming: ChordNamingSystem): String {
    val octave = midi / 12 - 1
    return "${noteName(midi, naming)}$octave"
}
