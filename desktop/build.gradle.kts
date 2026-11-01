import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// Version shown in the app and used for updates: 1.0.<CI run number>.
val buildNumber = (System.getenv("HOLOS_DESKTOP_BUILD") ?: "0").toInt()
val appVersion = "1.0.$buildNumber"

kotlin { jvmToolchain(21) }

/*
 * Code shared with the Android app is compiled straight from app/src/main: languages, engine
 * catalogue, sentence splitting, Ukrainian stress + IPA, EPUB/MOBI import, chapters, model
 * downloads, the engine pool, recordings, and the library / player screens. Only the parts
 * that talk to the platform live in this module (plus tiny stand-ins for a few Android types).
 */
val sharedFiles = listOf(
    "I18n.kt", "Lang.kt",
    "data/Recording.kt", "data/RecordingRepository.kt",
    "importer/EpubReader.kt", "importer/HtmlText.kt", "importer/ImportedDoc.kt", "importer/MobiReader.kt",
    "tts/Dsp.kt", "tts/Ecclesiastical.kt", "tts/EngineCatalog.kt", "tts/EnginePool.kt", "tts/ModelManager.kt",
    "tts/TextSegmenter.kt", "tts/uk/UkFrontend.kt", "tts/uk/UkIpa.kt", "tts/uk/UkNumbers.kt",
    "playback/PlayerBus.kt",
    "ui/CrashDialog.kt", "ui/Format.kt", "ui/theme/Theme.kt", "ui/components/Components.kt",
    "ui/screens/ChaptersSheet.kt", "ui/screens/LanguageSheet.kt", "ui/screens/LibraryScreen.kt", "ui/screens/PlayerScreen.kt",
)
val sharedDir = layout.buildDirectory.dir("shared-src")
val syncShared by tasks.registering(Sync::class) {
    from("../app/src/main/java/com/pixtane/holos") { include(sharedFiles) }
    into(sharedDir.map { it.dir("com/pixtane/holos") })
}
sourceSets.main {
    kotlin.srcDir(syncShared.map { sharedDir.get() })
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // sherpa-onnx Kotlin API (the classes from the Android AAR; natives come from the JNI build).
    implementation(files("libs/sherpa-onnx-classes.jar"))
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.apache.pdfbox:pdfbox:2.0.32")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("de.sciss:jump3r:1.0.5")
}

val genVersion by tasks.registering {
    val out = layout.buildDirectory.dir("gen-version")
    inputs.property("v", appVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/pixtane/holos/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package com.pixtane.holos\n\nobject BuildInfo {\n" +
                "    const val VERSION = \"$appVersion\"\n    const val BUILD = $buildNumber\n}\n"
        )
    }
}
sourceSets.main { kotlin.srcDir(genVersion) }

compose.desktop {
    application {
        mainClass = "com.pixtane.holos.MainKt"
        jvmArgs += listOf("-Xss2m", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
        // sherpa-onnx loads its JNI library by name, so it must be on the library path.
        jvmArgs += "-Djava.library.path=\$APPDIR/resources"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Holos"
            packageVersion = appVersion
            description = "Offline AI text-to-speech"
            vendor = "Pixtane"
            copyright = "© 2026 Pixtane"
            // Native engine libraries (sherpa-onnx + ONNX Runtime) go to app/resources.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("native"))
            // jdeps-suggested modules plus ones it cannot see: windows-1251 (stress dictionary and
            // old Cyrillic text files), TLS EC ciphers (model downloads) and Ukrainian locale data.
            modules("java.instrument", "java.prefs", "java.sql", "jdk.unsupported", "jdk.charsets", "jdk.crypto.ec", "jdk.localedata", "jdk.accessibility")
            windows {
                iconFile.set(project.file("icons/holos.ico"))
                menu = true
                menuGroup = "Holos"
                shortcut = true
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "8c8be169-8fcf-4122-b5d4-dfc6b6d7532f"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
