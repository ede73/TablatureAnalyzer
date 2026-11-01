package org.tablatureanalyzer.app

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun PlatformOpenTabButton(onText: (String) -> Unit) {
    OutlinedButton(onClick = {
        val picker = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Tab / MusicXML / editable project (*.tab, *.txt, *.musicxml, *.xml, *.ta)", "tab", "txt", "musicxml", "xml", "ta") }
        if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onText(File(picker.selectedFile.toURI()).readText())
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
