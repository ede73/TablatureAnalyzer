package org.tablatureanalyzer.app

import java.util.prefs.Preferences

actual object PlatformPreferences {
    private val preferences = Preferences.userNodeForPackage(PlatformPreferences::class.java)
    actual fun get(key: String): String? = preferences.get(key, null)
    actual fun put(key: String, value: String) { preferences.put(key, value) }
}
