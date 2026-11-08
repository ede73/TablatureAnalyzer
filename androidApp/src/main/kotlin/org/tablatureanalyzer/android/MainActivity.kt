package org.tablatureanalyzer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import org.tablatureanalyzer.app.TablatureApp
import org.tablatureanalyzer.app.PlatformPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appChrome = android.graphics.Color.rgb(18, 23, 21)
        window.statusBarColor = appChrome
        window.navigationBarColor = appChrome
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        PlatformPreferences.initialize(this)
        setContent {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                TablatureApp()
            }
        }
    }
}
