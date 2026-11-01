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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Track

@Composable
actual fun PlatformMidiPlayerButton(document: TabDocument, replayEventIndex: Int?, durationPreviewRequestId: Int, playheadEventIndex: Int, selectedPlaybackRange: IntRange?, onPlayingEvent: (Int?) -> Unit) {
    var playbackState by remember { mutableStateOf(PlaybackState.STOPPED) }
    var playbackKind by remember { mutableStateOf(PlaybackKind.FULL_TAB) }
    var error by remember { mutableStateOf<String?>(null) }
    var speedPercent by remember { mutableIntStateOf(100) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    val eventCallback by rememberUpdatedState(onPlayingEvent)
    fun playDurationContext() {
        val eventIndex = replayEventIndex ?: return
        val previewRange = selectedNoteContextRange(document, eventIndex) ?: return
        error = null
        runCatching {
            playbackKind = PlaybackKind.PREVIEW
            DesktopMidiPlayback.play(document, speedPercent, onEvent = { playedEvent ->
                EventQueue.invokeLater {
                    eventCallback(playedEvent)
                    if (playedEvent == null) {
                        playbackState = PlaybackState.STOPPED
                        playbackKind = PlaybackKind.FULL_TAB
                    }
                }
            }, eventRange = previewRange, loopCount = 1)
            playbackState = PlaybackState.PLAYING
        }.onFailure {
            playbackState = PlaybackState.STOPPED
            playbackKind = PlaybackKind.FULL_TAB
            error = it.message ?: "No MIDI output device is available."
            eventCallback(null)
        }
    }
    LaunchedEffect(durationPreviewRequestId) {
        if (durationPreviewRequestId > 0) playDurationContext()
    }
    DisposableEffect(Unit) {
        onDispose {
            DesktopMidiPlayback.stop()
            eventCallback(null)
        }
    }
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Button(onClick = {
            error = null
            if (playbackState == PlaybackState.PLAYING && playbackKind != PlaybackKind.PREVIEW) {
                DesktopMidiPlayback.pause()
                playbackState = PlaybackState.PAUSED
            } else if (playbackState == PlaybackState.PAUSED && playbackKind != PlaybackKind.PREVIEW) {
                DesktopMidiPlayback.resume()
                playbackState = PlaybackState.PLAYING
            } else {
                runCatching {
                    val safeSelection = selectedPlaybackRange?.let { range ->
                        range.first.coerceAtLeast(0)..range.last.coerceAtMost(document.events.lastIndex)
                    }?.takeIf { document.events.isNotEmpty() && it.first <= it.last }
                    val startRange = safeSelection ?: if (document.events.isNotEmpty()) {
                        playheadEventIndex.coerceIn(0, document.events.lastIndex)..document.events.lastIndex
                    } else null
                    playbackKind = if (safeSelection == null) PlaybackKind.FULL_TAB else PlaybackKind.SELECTION_LOOP
                    DesktopMidiPlayback.play(document, speedPercent, onEvent = { eventIndex ->
                        EventQueue.invokeLater {
                            eventCallback(eventIndex)
                            if (eventIndex == null) {
                                playbackState = PlaybackState.STOPPED
                                playbackKind = PlaybackKind.FULL_TAB
                            }
                        }
                    }, eventRange = startRange, loopCount = if (safeSelection == null) 0 else Sequencer.LOOP_CONTINUOUSLY)
                    playbackState = PlaybackState.PLAYING
                }.onFailure {
                    playbackState = PlaybackState.STOPPED
                    playbackKind = PlaybackKind.FULL_TAB
                    error = it.message ?: "No MIDI output device is available."
                    eventCallback(null)
                }
            }
        }, modifier = Modifier.semantics {
            contentDescription = when {
                playbackState == PlaybackState.PLAYING && playbackKind == PlaybackKind.SELECTION_LOOP -> "Pause selected passage loop"
                playbackState == PlaybackState.PLAYING && playbackKind == PlaybackKind.FULL_TAB -> "Pause tab playback"
                selectedPlaybackRange != null -> "Play selected passage in a loop"
                playheadEventIndex > 0 -> "Play from the selected tab position to the end"
                else -> "Play full tab from the beginning"
            }
        }) { Text(if (playbackState == PlaybackState.PLAYING && playbackKind != PlaybackKind.PREVIEW) "Ⅱ Pause" else "▶ Play") }
        OutlinedButton(onClick = {
            DesktopMidiPlayback.stop()
            playbackState = PlaybackState.STOPPED
            playbackKind = PlaybackKind.FULL_TAB
            eventCallback(null)
        }, enabled = playbackState != PlaybackState.STOPPED) { Text("■ Stop") }
        Box {
            OutlinedButton(
                onClick = { showSpeedMenu = true },
                modifier = Modifier.semantics { contentDescription = "Playback speed $speedPercent percent" }
            ) { Text("🏃 ${speedPercent}% ▾") }
            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                listOf(
                    200 to "2× tempo",
                    150 to "1.5× tempo",
                    100 to "original tempo",
                    75 to "75% tempo",
                    50 to "half tempo",
                    25 to "quarter tempo"
                ).forEach { (speed, description) ->
                    DropdownMenuItem(text = { Text("$speed% · $description") }, onClick = {
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

private enum class PlaybackState { STOPPED, PLAYING, PAUSED }
private enum class PlaybackKind { FULL_TAB, PREVIEW, SELECTION_LOOP }

private fun selectedNoteContextRange(document: TabDocument, selectedEvent: Int): IntRange? {
    val noteEvents = document.events.indices.filter { document.events[it].frets.any { fret -> fret != null } }
    val selectedPosition = noteEvents.indexOf(selectedEvent)
    if (selectedPosition < 0) return null
    val start = noteEvents[(selectedPosition - 4).coerceAtLeast(0)]
    val end = noteEvents[(selectedPosition + 4).coerceAtMost(noteEvents.lastIndex)]
    return start..end
}

private object DesktopMidiPlayback {
    private const val PPQ = 480
    private var sequencer: javax.sound.midi.Sequencer? = null

    @Synchronized
    fun play(document: TabDocument, speedPercent: Int, onEvent: (Int?) -> Unit, eventRange: IntRange? = null, loopCount: Int = 0) {
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

        // A playback range starts its MIDI sequence at tick zero, so carry forward
        // the most recent tempo change that happened before the range begins.
        eventRange?.first?.let { rangeStart ->
            document.tempoChanges
                .filter { it.eventIndex in 0 until rangeStart }
                .maxByOrNull { it.eventIndex }
                ?.let { addTempo(track, it.beatsPerMinute, tick) }
        }

        playbackEventIndices(document.events).filter { eventIndex -> eventRange == null || eventIndex in eventRange }.forEach { eventIndex ->
            val event = document.events[eventIndex]
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
                    val endedCurrentSequence = synchronized(DesktopMidiPlayback) {
                        if (sequencer === nextSequencer) {
                            nextSequencer.stop()
                            nextSequencer.close()
                            sequencer = null
                            true
                        } else {
                            false
                        }
                    }
                    if (endedCurrentSequence) onEvent(null)
                }
            }
        }
        nextSequencer.open()
        nextSequencer.tempoFactor = speedPercent.coerceIn(25, 200) / 100f
        if (loopCount != 0) {
            nextSequencer.loopStartPoint = 0L
            nextSequencer.loopEndPoint = sequence.tickLength
            nextSequencer.loopCount = loopCount
        }
        sequencer = nextSequencer
        nextSequencer.start()
    }

    @Synchronized
    fun setSpeed(speedPercent: Int) {
        sequencer?.tempoFactor = speedPercent.coerceIn(25, 200) / 100f
    }

    @Synchronized
    fun pause() {
        sequencer?.stop()
    }

    @Synchronized
    fun resume() {
        sequencer?.start()
    }

    @Synchronized
    fun stop() {
        sequencer?.let {
            runCatching { it.stop() }
            runCatching { it.tickPosition = 0L }
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
