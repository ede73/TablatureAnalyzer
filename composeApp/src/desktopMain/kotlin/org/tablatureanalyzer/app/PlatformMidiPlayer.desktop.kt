package org.tablatureanalyzer.app

import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import javax.sound.midi.Track

@Composable
actual fun PlatformMidiPlayerButton(document: TabDocument, onPlayingEvent: (Int?) -> Unit) {
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var speedPercent by remember { mutableIntStateOf(100) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    val eventCallback by rememberUpdatedState(onPlayingEvent)
    DisposableEffect(Unit) {
        onDispose {
            DesktopMidiPlayback.stop()
            eventCallback(null)
        }
    }
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = {
            if (playing) {
                DesktopMidiPlayback.stop()
                playing = false
                eventCallback(null)
            } else {
                error = null
                runCatching {
                    DesktopMidiPlayback.play(document, speedPercent) { eventIndex ->
                        EventQueue.invokeLater {
                            eventCallback(eventIndex)
                            if (eventIndex == null) playing = false
                        }
                    }
                    playing = true
                }.onFailure {
                    playing = false
                    error = it.message ?: "No MIDI output device is available."
                    eventCallback(null)
                }
            }
        }) { Text(if (playing) "Stop MIDI" else "Play MIDI") }
        Box {
            OutlinedButton(onClick = { showSpeedMenu = true }) { Text("${speedPercent}%") }
            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                listOf(100, 50, 25).forEach { speed ->
                    DropdownMenuItem(text = { Text("$speed% speed") }, onClick = {
                        speedPercent = speed
                        DesktopMidiPlayback.setSpeed(speed)
                        showSpeedMenu = false
                    })
                }
            }
        }
        error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
    }
}

private object DesktopMidiPlayback {
    private const val PPQ = 480
    private var sequencer: javax.sound.midi.Sequencer? = null

    @Synchronized
    fun play(document: TabDocument, speedPercent: Int, onEvent: (Int?) -> Unit) {
        stop()
        val nextSequencer = MidiSystem.getSequencer() ?: error("No MIDI sequencer is available.")
        val sequence = Sequence(Sequence.PPQ, PPQ)
        val track = sequence.createTrack()
        addShort(track, ShortMessage.PROGRAM_CHANGE, 0, 24, 0, 0L) // Nylon-string guitar

        val tempoByEvent = document.tempoChanges.filter { it.eventIndex >= 0 }
            .groupBy { it.eventIndex.coerceAtMost(document.events.size) }
        val signatureByEvent = document.timeSignatures.filter { it.eventIndex >= 0 }
            .groupBy { it.eventIndex.coerceAtMost(document.events.size) }
        var tick = 0L

        document.events.forEachIndexed { eventIndex, event ->
            addMarker(track, "TAB_EVENT:$eventIndex", tick)
            tempoByEvent[eventIndex].orEmpty().forEach { addTempo(track, it.beatsPerMinute, tick) }
            signatureByEvent[eventIndex].orEmpty().forEach { addTimeSignature(track, it.beats, it.beatType, tick) }
            val soundedDurations = event.frets.indices.mapNotNull { string ->
                val fret = event.frets[string] ?: return@mapNotNull null
                val midiPitch = document.tuning.getOrNull(string)?.plus(fret)?.coerceIn(0, 127) ?: return@mapNotNull null
                val units = event.durationUnits.getOrNull(string)
                    ?: event.lengthCodes.getOrNull(string)?.let(::legacyDurationUnits)
                    ?: document.divisions.coerceAtLeast(1)
                val durationTicks = (units.toDouble() * PPQ / document.divisions.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
                addShort(track, ShortMessage.NOTE_ON, 0, midiPitch, 88, tick)
                addShort(track, ShortMessage.NOTE_OFF, 0, midiPitch, 0, tick + durationTicks)
                durationTicks
            }
            val restTicks = event.restDurationUnits?.let { (it.toDouble() * PPQ / document.divisions.coerceAtLeast(1)).toLong().coerceAtLeast(1L) }
            tick += soundedDurations.maxOrNull() ?: restTicks ?: PPQ.toLong()
        }
        tempoByEvent[document.events.size].orEmpty().forEach { addTempo(track, it.beatsPerMinute, tick) }
        signatureByEvent[document.events.size].orEmpty().forEach { addTimeSignature(track, it.beats, it.beatType, tick) }
        val endMarker = MetaMessage()
        val markerText = "End of tab".encodeToByteArray()
        endMarker.setMessage(0x06, markerText, markerText.size)
        track.add(MidiEvent(endMarker, tick))

        nextSequencer.sequence = sequence
        nextSequencer.addMetaEventListener { message ->
            if (message.type == 0x06) {
                val marker = message.data.decodeToString()
                if (marker.startsWith("TAB_EVENT:")) {
                    marker.substringAfter(':').toIntOrNull()?.let(onEvent)
                }
            } else if (message.type == 0x2F) {
                EventQueue.invokeLater {
                    synchronized(DesktopMidiPlayback) {
                        if (sequencer === nextSequencer) {
                            nextSequencer.stop()
                            nextSequencer.close()
                            sequencer = null
                        }
                    }
                    onEvent(null)
                }
            }
        }
        nextSequencer.open()
        nextSequencer.tempoFactor = speedPercent.coerceIn(25, 100) / 100f
        sequencer = nextSequencer
        nextSequencer.start()
    }

    @Synchronized
    fun setSpeed(speedPercent: Int) {
        sequencer?.tempoFactor = speedPercent.coerceIn(25, 100) / 100f
    }

    @Synchronized
    fun stop() {
        sequencer?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        sequencer = null
    }

    private fun addShort(track: Track, command: Int, channel: Int, data1: Int, data2: Int, tick: Long) {
        val message = ShortMessage()
        message.setMessage(command, channel, data1.coerceIn(0, 127), data2.coerceIn(0, 127))
        track.add(MidiEvent(message, tick.coerceAtLeast(0L)))
    }

    private fun addTempo(track: Track, bpm: Int, tick: Long) {
        val micros = (60_000_000 / bpm.coerceIn(1, 1000)).coerceIn(1, 0xFFFFFF)
        val message = MetaMessage()
        message.setMessage(0x51, byteArrayOf((micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte()), 3)
        track.add(MidiEvent(message, tick))
    }

    private fun addMarker(track: Track, text: String, tick: Long) {
        val bytes = text.encodeToByteArray()
        val message = MetaMessage()
        message.setMessage(0x06, bytes, bytes.size)
        track.add(MidiEvent(message, tick))
    }

    private fun addTimeSignature(track: Track, beats: Int, beatType: Int, tick: Long) {
        val denominatorPower = (0..7).minByOrNull { power -> kotlin.math.abs((1 shl power) - beatType.coerceAtLeast(1)) } ?: 2
        val message = MetaMessage()
        message.setMessage(0x58, byteArrayOf(beats.coerceIn(1, 255).toByte(), denominatorPower.toByte(), 24, 8), 4)
        track.add(MidiEvent(message, tick))
    }

    private fun legacyDurationUnits(code: Int): Int = when (code) {
        1 -> 1920
        2 -> 2880
        3 -> 960
        4 -> 1440
        5 -> 480
        6 -> 720
        7 -> 240
        8 -> 360
        9 -> 120
        10 -> 180
        11 -> 60
        12 -> 90
        else -> 480
    }
}
