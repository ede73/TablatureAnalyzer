package org.tablatureanalyzer.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
actual fun GuitarInputPanel(
    mode: GuitarInputMode,
    tuning: List<Int>,
    naming: ChordNamingSystem,
    onDetectedPitches: (List<Int>, ChordSymbol?) -> Unit
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var inputGain by remember { mutableStateOf(1f) }
    var liveRms by remember { mutableStateOf(0f) }
    var spectrum by remember { mutableStateOf(emptyList<Float>()) }
    var pitches by remember { mutableStateOf(emptyList<Int>()) }
    var candidates by remember { mutableStateOf(emptyList<AudioChordGuess>()) }
    var confirmedChord by remember { mutableStateOf<ChordSymbol?>(null) }
    val ranker = remember { AudioChordRanker() }
    var capture by remember { mutableStateOf<AndroidGuitarCapture?>(null) }
    val currentCallback by rememberUpdatedState(onDetectedPitches)
    val currentMode by rememberUpdatedState(mode)
    val currentNaming by rememberUpdatedState(naming)
    val noteRecorder = remember { SingleNoteRecorder() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) status = "Microphone ready" else status = "Microphone permission denied"
    }

    DisposableEffect(Unit) {
        onDispose {
            capture?.stop()
            capture = null
        }
    }

    fun stopCapture() {
        capture?.stop()
        capture = null
        listening = false
        status = "Stopped"
        ranker.clear()
        confirmedChord = null
        candidates = emptyList()
    }

    fun startCapture() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val recorder = AndroidGuitarCapture(
            singleNoteMode = mode == GuitarInputMode.TAB_RECORD,
            minMidi = (tuning.minOrNull() ?: 35).coerceIn(35, 88),
            maxMidi = minOf(88, (tuning.maxOrNull() ?: 64) + 24),
            initialGain = inputGain,
            onFrame = { frame ->
                liveRms = frame.rms
                spectrum = frame.spectrum
                pitches = frame.pitches
                if (currentMode == GuitarInputMode.CHORD_GUESS) {
                    currentCallback(frame.pitches, null)
                    if (frame.rms >= guitarInputThreshold(inputGain, 0f)) {
                        val ranking = ranker.update(frame.pitches, confirmedChord?.root)
                        if (ranking.isNotEmpty()) candidates = ranking
                        val winner = ranking.firstOrNull()
                        val currentEvidence = confirmedChord?.let { current -> ranking.firstOrNull {
                            it.chord.root == current.root && it.chord.quality.suffix == current.quality.suffix
                        } }
                        val stable = winner != null && winner.support >= 4
                        val beatsCurrent = currentEvidence == null || (winner != null && winner.score >= currentEvidence.score * 1.2)
                        if (stable && (confirmedChord == null || winner!!.chord.name(currentNaming) == confirmedChord?.name(currentNaming) || beatsCurrent)) {
                            confirmedChord = winner!!.chord
                            currentCallback(frame.pitches, winner.chord)
                        }
                    }
                } else {
                    val note = frame.pitches.singleOrNull()
                    val detected = noteRecorder.update(note, frame.rms, guitarInputThreshold(inputGain, 0f), frame.elapsedMillis)
                    if (detected != null) currentCallback(listOf(detected), null)
                }
            },
            onReady = { message -> status = message; listening = true },
            onFailure = { message -> status = message; listening = false; capture = null }
        )
        capture = recorder
        status = "Opening microphone…"
        try {
            recorder.start()
        } catch (failure: Exception) {
            capture = null
            listening = false
            status = failure.message ?: "Could not open microphone"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (listening) {
                    OutlinedButton(onClick = ::stopCapture) { Text("■ Stop") }
                } else {
                    Button(onClick = ::startCapture) { Text(if (permissionGranted) "🎙 Listen" else "Allow microphone") }
                }
                Text(status, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Input gain ${"%.1f".format(inputGain)}×", modifier = Modifier.padding(top = 10.dp), fontSize = 12.sp)
                Slider(value = inputGain, onValueChange = { inputGain = it; capture?.setGain(it) }, valueRange = 0.25f..64f, modifier = Modifier.weight(1f))
            }
            Text("Input ${(if (liveRms > 0f) 20.0 * ln(liveRms.toDouble()) / ln(10.0) else -96.0).roundToInt()} dBFS", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SpectrumGraph(spectrum, Modifier.fillMaxWidth().height(74.dp))
            if (mode == GuitarInputMode.CHORD_GUESS) {
                Text(confirmedChord?.name(naming) ?: if (pitches.isEmpty()) "Play a chord" else "Analyzing chord…", style = MaterialTheme.typography.titleMedium)
                if (pitches.isNotEmpty()) Text("Notes: ${pitches.distinct().sorted().joinToString(" · ") { midiNoteName(it) }}", fontSize = 12.sp)
                candidates.take(4).forEach { Text("${it.chord.name(naming)}  ·  ${it.support} frames", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Text(if (pitches.isEmpty()) "Play one note to add it to the tab" else "Detected: ${pitches.joinToString(" · ") { midiNoteName(it) }}", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SpectrumGraph(values: List<Float>, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val baseline = size.height
        drawLine(outline, androidx.compose.ui.geometry.Offset(0f, baseline), androidx.compose.ui.geometry.Offset(size.width, baseline), 1.dp.toPx())
        if (values.isEmpty()) return@Canvas
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).coerceAtLeast(1)
            val y = baseline - value.coerceIn(0f, 1f) * (baseline - 3.dp.toPx())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, primary, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun midiNoteName(midi: Int): String {
    val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return "${names[((midi % 12) + 12) % 12]}${midi / 12 - 1}"
}
