package org.tablatureanalyzer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.io.File
import java.util.Properties
import kotlinx.coroutines.delay

/** Dev-only navigation for repeatable hot-reload audio tests without desktop input. */
@Composable
internal actual fun PlatformAudioTestControl(onPage: (Int) -> Unit) {
    val updatePage by rememberUpdatedState(onPage)
    LaunchedEffect(Unit) {
        if (System.getProperty("compose.reload.argfile") == null) return@LaunchedEffect
        val file = File("build/audio-test.properties")
        while (true) {
            if (file.isFile) {
                val properties = Properties().apply { file.inputStream().use(::load) }
                properties.getProperty("page")?.toIntOrNull()?.takeIf { it == 0 || it == 2 }?.let(updatePage)
            }
            delay(500)
        }
    }
}
