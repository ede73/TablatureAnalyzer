package org.tablatureanalyzer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.tablatureanalyzer.app.TablatureApp
import org.tablatureanalyzer.app.PlatformPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformPreferences.initialize(this)
        setContent { TablatureApp() }
    }
}
