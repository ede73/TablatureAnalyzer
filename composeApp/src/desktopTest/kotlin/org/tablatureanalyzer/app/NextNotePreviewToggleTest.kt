package org.tablatureanalyzer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test

class NextNotePreviewToggleTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun previewToggleIsVisibleAndCanBeTurnedOffAndOn() = runDesktopComposeUiTest {
        val preferenceKey = "upcomingNoteZoomEnabled"
        val previousPreference = PlatformPreferences.get(preferenceKey)
        PlatformPreferences.put(preferenceKey, "true")
        try {
            setContent { MaterialTheme { TablatureApp() } }

            val previewOption = onNodeWithContentDescription("Preview next note")
            previewOption.assertIsDisplayed()
            previewOption.assertIsSelected()
            previewOption.performClick()
            previewOption.assertIsNotSelected()
            previewOption.performClick()
            previewOption.assertIsSelected()
        } finally {
            PlatformPreferences.put(preferenceKey, previousPreference ?: "true")
        }
    }
}
