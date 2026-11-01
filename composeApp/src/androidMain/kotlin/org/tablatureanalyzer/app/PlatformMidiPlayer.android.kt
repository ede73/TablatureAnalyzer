package org.tablatureanalyzer.app

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformMidiPlayerButton(document: TabDocument, replayEventIndex: Int?, durationPreviewRequestId: Int, onPlayingEvent: (Int?) -> Unit) {
    OutlinedButton(onClick = {}, enabled = false) { Text("MIDI playback is desktop-only") }
}
