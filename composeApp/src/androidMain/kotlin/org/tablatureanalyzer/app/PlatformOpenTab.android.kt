package org.tablatureanalyzer.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun PlatformOpenTabButton(onText: (String) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let(onText)
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/octet-stream", "application/xml", "application/vnd.recordare.musicxml+xml")) }) { Text("Choose file") }
}

@Composable
actual fun PlatformSaveMusicXmlButton(contents: String) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.recordare.musicxml+xml")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(contents) }
        }
    }
    Button(onClick = { save.launch("tablature.musicxml") }) { Text("Save MusicXML") }
}
