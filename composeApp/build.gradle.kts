import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.compose.hot-reload")
    id("com.android.library")
}

kotlin {
    androidTarget()
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidMain.dependencies { implementation("androidx.activity:activity-compose:1.10.1") }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs) } }
    }
}

android {
    namespace = "org.tablatureanalyzer.shared"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
}

compose.desktop {
    application {
        mainClass = "org.tablatureanalyzer.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "TablatureAnalyzer"
            packageVersion = "1.0.0"
            vendor = "ede73"
        }
    }
}
