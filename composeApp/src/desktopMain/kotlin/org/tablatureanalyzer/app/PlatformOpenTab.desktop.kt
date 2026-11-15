package org.tablatureanalyzer.app

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun PlatformOpenTabButton(onBytes: (ByteArray) -> Unit) {
    OutlinedButton(onClick = {
        val picker = JFileChooser().apply {
            dialogTitle = "Open tab, MusicXML, or editable project"
            val supportedFormats = FileNameExtensionFilter(
                "Tab / PowerTab / MusicXML / editable project (*.tab, *.ptb, *.ptab, *.txt, *.musicxml, *.xml, *.ta)",
                "tab", "ptb", "ptab", "txt", "musicxml", "xml", "ta"
            )
            addChoosableFileFilter(supportedFormats)
            fileFilter = supportedFormats
        }
        if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onBytes(File(picker.selectedFile.toURI()).readBytes())
    }) { Text("Open") }
}

@Composable
actual fun PlatformSaveMusicXmlButton(contents: String) {
    Button(onClick = {
        val picker = JFileChooser().apply {
            dialogTitle = "Save MusicXML score"
            selectedFile = File("tablature.musicxml")
            fileFilter = FileNameExtensionFilter("MusicXML score (*.musicxml, *.xml)", "musicxml", "xml")
        }
        if (picker.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val selected = picker.selectedFile
            val output = if (selected.extension.isBlank()) File(selected.parentFile, "${selected.name}.musicxml") else selected
            output.writeText(contents)
        }
    }) { Text("Save MusicXML") }
}

@Composable
actual fun PlatformSaveProjectButton(contents: String) {
    OutlinedButton(onClick = {
        val picker = JFileChooser().apply {
            dialogTitle = "Save editable TablatureAnalyzer project"
            selectedFile = File("tablature.ta")
            fileFilter = FileNameExtensionFilter("TablatureAnalyzer project (*.ta)", "ta")
        }
        if (picker.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val selected = picker.selectedFile
            val output = if (selected.extension.isBlank()) File(selected.parentFile, "${selected.name}.ta") else selected
            output.writeText(contents)
        }
    }) { Text("Save editable") }
}
