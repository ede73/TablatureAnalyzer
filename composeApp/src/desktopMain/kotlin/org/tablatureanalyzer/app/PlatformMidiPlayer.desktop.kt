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
actual fun PlatformMidiPlayerButton(document: TabDocument, replayEventIndex: Int?, durationPreviewRequestId: Int, playheadEventIndex: Int, selectedPlaybackRange: IntRange?, playbackResetRequestId: Int, onPlayingEvent: (Int?) -> Unit) {
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
    LaunchedEffect(playbackResetRequestId) {
        if (playbackResetRequestId > 0) {
            DesktopMidiPlayback.stop()
            playbackState = PlaybackState.STOPPED
            playbackKind = PlaybackKind.FULL_TAB
            eventCallback(null)
        }
    }
    LaunchedEffect(document.parts.map { it.id to it.midiProgram }) {
        DesktopMidiPlayback.updatePrograms(document.parts)
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

private object DesktopNoteAudition {
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "tab-note-audition").apply { isDaemon = true }
    }
    private var output: DesktopMidiOutput? = null
    private var pendingOff: java.util.concurrent.ScheduledFuture<*>? = null
    private var soundingPitch: Int? = null

    @Synchronized
    fun play(pitch: Int) {
        if (pitch !in 0..127) return
        runCatching {
            val receiver = (output ?: DesktopMidiOutput.open().also { output = it }).receiver
            pendingOff?.cancel(false)
            soundingPitch?.let { send(receiver, ShortMessage.NOTE_OFF, it, 0) }
            send(receiver, ShortMessage.PROGRAM_CHANGE, 24, 0)
            send(receiver, ShortMessage.NOTE_ON, pitch, 88)
            soundingPitch = pitch
            pendingOff = scheduler.schedule({
                synchronized(this) {
                    val active = soundingPitch ?: return@synchronized
                    runCatching { output?.receiver?.let { send(it, ShortMessage.NOTE_OFF, active, 0) } }
                    soundingPitch = null
                }
            }, 450, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun send(receiver: javax.sound.midi.Receiver, command: Int, data1: Int, data2: Int) {
        val message = ShortMessage()
        message.setMessage(command, 1, data1, data2)
        receiver.send(message, -1)
    }
}

actual fun PlatformAuditionNote(midiPitch: Int) = DesktopNoteAudition.play(midiPitch)

private enum class PlaybackState { STOPPED, PLAYING, PAUSED }
private enum class PlaybackKind { FULL_TAB, PREVIEW, SELECTION_LOOP }

private fun selectedNoteContextRange(document: TabDocument, selectedEvent: Int): IntRange? {
    val noteEvents = document.events.indices.filter { document.events[it].restDurationUnits == null && document.events[it].frets.any { fret -> fret != null } }
    if (noteEvents.isEmpty()) return null
    val selectedPosition = noteEvents.indexOfFirst { it >= selectedEvent }
        .let { if (it < 0) noteEvents.lastIndex else it }
    val start = minOf(selectedEvent, noteEvents[(selectedPosition - 4).coerceAtLeast(0)])
    val end = maxOf(selectedEvent, noteEvents[(selectedPosition + 4).coerceAtMost(noteEvents.lastIndex)])
    return start..end
}

private object DesktopMidiPlayback {
    private const val PPQ = 480
    private var sequencer: javax.sound.midi.Sequencer? = null
    private var output: DesktopMidiOutput? = null
    private var channelByPartId: Map<String, Int> = emptyMap()
    private var programByPartId: Map<String, Int> = emptyMap()

    private data class ScheduledSlot(val eventIndex: Int, val startTick: Long, val endTick: Long)

    @Synchronized
    fun play(document: TabDocument, speedPercent: Int, onEvent: (Int?) -> Unit, eventRange: IntRange? = null, loopCount: Int = 0) {
        stop()
        val nextSequencer = MidiSystem.getSequencer(false) ?: error("No MIDI sequencer is available.")
        val sequence = Sequence(Sequence.PPQ, PPQ)
        val track = sequence.createTrack()
        require(document.parts.count { !it.isPercussion } <= 15) { "MIDI playback supports up to 15 pitched instrument parts." }
        val melodicChannels = (0..15).filter { it != 9 }.iterator()
        val channels = document.parts.map { part -> if (part.isPercussion) 9 else melodicChannels.next() }
        channelByPartId = document.parts.mapIndexed { index, part -> part.id to channels[index] }.toMap()
        programByPartId = document.parts.associate { it.id to it.midiProgram.coerceIn(0, 127) }
        document.parts.forEachIndexed { index, part ->
            addShort(track, ShortMessage.PROGRAM_CHANGE, channels[index], part.midiProgram.coerceIn(0, 127), 0, 0L)
        }

        // Parts have independent event lists. Schedule each from tick zero so a
        // guitar and bass line can have different note counts without truncation.
        val timelinePart = document.activePart
        val schedules = document.parts.map(::schedulePart)
        val activePartIndex = document.parts.indexOfFirst { it.id == timelinePart.id }.coerceAtLeast(0)
        val activeSchedule = schedules[activePartIndex]
        val selectedSlots = eventRange?.let { range -> activeSchedule.filter { it.eventIndex in range } }
        val rangeStartTick = selectedSlots?.firstOrNull()?.startTick ?: 0L
        val rangeEndTick = if (eventRange != null) {
            selectedSlots?.lastOrNull()?.endTick ?: 0L
        } else {
            schedules.maxOfOrNull { it.lastOrNull()?.endTick ?: 0L } ?: 0L
        }

        eventRange?.let { range ->
            val priorTempo = activeSchedule.lastOrNull { it.eventIndex < range.first }
                ?.let { slot -> timelinePart.tempoChanges.lastOrNull { it.eventIndex == slot.eventIndex } }
                ?: timelinePart.tempoChanges.filter { it.eventIndex < range.first }.maxByOrNull { it.eventIndex }
            priorTempo?.let { addTempo(track, it.beatsPerMinute, 0L) }
            val priorSignature = activeSchedule.lastOrNull { it.eventIndex < range.first }
                ?.let { slot -> timelinePart.timeSignatures.lastOrNull { it.eventIndex == slot.eventIndex } }
                ?: timelinePart.timeSignatures.filter { it.eventIndex < range.first }.maxByOrNull { it.eventIndex }
            priorSignature?.let { addTimeSignature(track, it.beats, it.beatType, 0L) }
        }

        schedules.forEachIndexed { partIndex, schedule ->
            val part = document.parts[partIndex]
            schedule.filter { slot -> slot.startTick < rangeEndTick && slot.endTick > rangeStartTick }
                .forEach { slot ->
                    val event = part.events[slot.eventIndex]
                    val eventStart = maxOf(slot.startTick, rangeStartTick)
                    if (partIndex == activePartIndex && (eventRange == null || eventRange.contains(slot.eventIndex))) {
                        addMarker(track, "TAB_EVENT:${slot.eventIndex}", eventStart - rangeStartTick)
                    }
                    if (event.restDurationUnits != null) return@forEach
                    event.frets.indices.forEach { string ->
                        val fret = event.frets[string] ?: return@forEach
                        val midiPitch = part.tuning.getOrNull(string)?.plus(fret)?.coerceIn(0, 127) ?: return@forEach
                        val units = event.durationUnits.getOrNull(string)
                            ?: event.lengthCodes.getOrNull(string)?.let(::legacyDurationUnits)
                            ?: part.divisions.coerceAtLeast(1)
                        val durationTicks = (units.toDouble() * PPQ / part.divisions.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
                        val noteStart = maxOf(slot.startTick, rangeStartTick) - rangeStartTick
                        val noteEnd = minOf(slot.startTick + durationTicks, rangeEndTick) - rangeStartTick
                        if (noteEnd > noteStart) {
                            addShort(track, ShortMessage.NOTE_ON, channels[partIndex], midiPitch, 88, noteStart)
                            addShort(track, ShortMessage.NOTE_OFF, channels[partIndex], midiPitch, 0, noteEnd)
                        }
                    }
                }
        }

        val activeWindow = activeSchedule.filter { slot -> slot.startTick < rangeEndTick && slot.endTick > rangeStartTick }
        activeWindow.forEach { slot ->
            val offsetTick = maxOf(slot.startTick, rangeStartTick) - rangeStartTick
            timelinePart.tempoChanges.filter { it.eventIndex == slot.eventIndex }.forEach { addTempo(track, it.beatsPerMinute, offsetTick) }
            timelinePart.timeSignatures.filter { it.eventIndex == slot.eventIndex }.forEach { addTimeSignature(track, it.beats, it.beatType, offsetTick) }
        }
        val tick = (rangeEndTick - rangeStartTick).coerceAtLeast(1L)
        timelinePart.tempoChanges.lastOrNull { it.eventIndex >= timelinePart.events.size }?.let { addTempo(track, it.beatsPerMinute, tick) }
        timelinePart.timeSignatures.lastOrNull { it.eventIndex >= timelinePart.events.size }?.let { addTimeSignature(track, it.beats, it.beatType, tick) }
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
                            output?.close()
                            output = null
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
        try {
            nextSequencer.open()
            output = DesktopMidiOutput.open()
            nextSequencer.transmitter.receiver = output!!.receiver
        } catch (failure: Exception) {
            nextSequencer.close()
            output?.close()
            output = null
            throw failure
        }
        nextSequencer.tempoFactor = speedPercent.coerceIn(25, 200) / 100f
        if (loopCount != 0) {
            nextSequencer.loopStartPoint = 0L
            nextSequencer.loopEndPoint = sequence.tickLength
            nextSequencer.loopCount = loopCount
        }
        sequencer = nextSequencer
        nextSequencer.start()
    }

    private fun schedulePart(part: TabPart): List<ScheduledSlot> {
        var tick = 0L
        return playbackEventIndices(part.events).map { eventIndex ->
            val event = part.events[eventIndex]
            val duration = if (event.restDurationUnits != null) {
                (event.restDurationUnits.toDouble() * PPQ / part.divisions.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
            } else {
                event.frets.indices.mapNotNull { string ->
                    if (event.frets[string] == null) return@mapNotNull null
                    val units = event.durationUnits.getOrNull(string)
                        ?: event.lengthCodes.getOrNull(string)?.let(::legacyDurationUnits)
                        ?: part.divisions.coerceAtLeast(1)
                    (units.toDouble() * PPQ / part.divisions.coerceAtLeast(1)).toLong().coerceAtLeast(1L)
                }.maxOrNull() ?: PPQ.toLong()
            }
            ScheduledSlot(eventIndex, tick, tick + duration).also { tick += duration }
        }
    }

    @Synchronized
    fun setSpeed(speedPercent: Int) {
        sequencer?.tempoFactor = speedPercent.coerceIn(25, 200) / 100f
    }

    @Synchronized
    fun updatePrograms(parts: List<TabPart>) {
        val receiver = output?.receiver ?: return
        parts.forEach { part ->
            val channel = channelByPartId[part.id] ?: return@forEach
            val program = part.midiProgram.coerceIn(0, 127)
            if (programByPartId[part.id] == program) return@forEach
            val change = ShortMessage()
            change.setMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0)
            receiver.send(change, -1)
            programByPartId = programByPartId + (part.id to program)
        }
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
        output?.let { runCatching { it.close() } }
        output = null
        channelByPartId = emptyMap()
        programByPartId = emptyMap()
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
