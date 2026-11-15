package org.tablatureanalyzer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilDoesNotExist
import kotlin.test.Test
import kotlin.test.assertEquals

class GuitarInputPanelCollapseTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun toggleHidesAndRestoresInputControlsAndPersistsSelection() = runDesktopComposeUiTest {
        val preferenceKey = "guitarInputPanelExpanded_${GuitarInputMode.TAB_RECORD.name}"
        val previousPreference = PlatformPreferences.get(preferenceKey)
        PlatformPreferences.put(preferenceKey, "true")
        try {
            setContent {
                MaterialTheme {
                    GuitarInputPanel(GuitarInputMode.TAB_RECORD, listOf(64, 59, 55, 50, 45, 40), ChordNamingSystem.US) { _, _ -> }
                }
            }

            onNodeWithText("Input gain", substring = true).assertIsDisplayed()
            onNodeWithContentDescription("Collapse guitar input").assertIsDisplayed().performClick()
            onNodeWithContentDescription("Expand guitar input").assertIsDisplayed()
            waitUntilDoesNotExist(hasText("Input gain", substring = true))
            assertEquals("false", PlatformPreferences.get(preferenceKey))

            onNodeWithContentDescription("Expand guitar input").performClick()
            onNodeWithContentDescription("Collapse guitar input").assertIsDisplayed()
            onNodeWithText("Input gain", substring = true).assertIsDisplayed()
            assertEquals("true", PlatformPreferences.get(preferenceKey))
        } finally {
            PlatformPreferences.put(preferenceKey, previousPreference ?: "true")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun scalesTabOpensWithoutRuntimeFailure() = runDesktopComposeUiTest {
        setContent { MaterialTheme { TablatureApp() } }
        onNodeWithText("Scales").performClick()
        onNodeWithText("Find the shape across your fretboard").assertIsDisplayed()
    }
}
