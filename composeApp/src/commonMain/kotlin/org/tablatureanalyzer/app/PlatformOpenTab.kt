package org.tablatureanalyzer.app

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformOpenTabButton(onText: (String) -> Unit)

@Composable
expect fun PlatformSaveMusicXmlButton(contents: String)

@Composable
expect fun PlatformMidiPlayerButton(document: TabDocument, onPlayingEvent: (Int?) -> Unit)

expect object PlatformPreferences {
    fun get(key: String): String?
    fun put(key: String, value: String)
}
