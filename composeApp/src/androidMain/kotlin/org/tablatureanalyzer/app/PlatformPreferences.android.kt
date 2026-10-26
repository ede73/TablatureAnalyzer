package org.tablatureanalyzer.app

import android.content.Context

actual object PlatformPreferences {
    private var appContext: Context? = null
    fun initialize(context: Context) { appContext = context.applicationContext }
    actual fun get(key: String): String? = appContext?.getSharedPreferences("tablature-analyzer", Context.MODE_PRIVATE)?.getString(key, null)
    actual fun put(key: String, value: String) {
        appContext?.getSharedPreferences("tablature-analyzer", Context.MODE_PRIVATE)?.edit()?.putString(key, value)?.apply()
    }
}
