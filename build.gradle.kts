import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io") // NewPipe Extractor
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("uk.co.caprica:vlcj:4.8.3")                       // audio playback (needs VLC installed)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.6") // YouTube Music search + stream URLs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "TuneDesk"
            packageVersion = "1.0.0"
            // Needed so the bundled runtime includes java.net.http (lyrics, YouTube account API).
            includeAllModules = true
            windows {
                console = true // shows a console window with errors; set to false once everything works
                shortcut = true
                menuGroup = "TuneDesk"
                dirChooser = true
                upgradeUuid = "b6f2d1a4-3c58-4e0e-9a41-7d2f6c1e8a90"
            }
        }
    }
}
