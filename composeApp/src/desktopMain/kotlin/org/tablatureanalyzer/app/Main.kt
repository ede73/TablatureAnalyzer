package org.tablatureanalyzer.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.File

fun main(args: Array<String>) = application {
    val initialDocument = args.firstOrNull()?.let { path ->
        runCatching { TabCodec.parse(File(path).readText()) }.getOrNull()
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Tablature Analyzer",
        state = rememberWindowState(size = DpSize(1200.dp, 1100.dp))
    ) {
        TablatureApp(initialDocument)
    }
}
