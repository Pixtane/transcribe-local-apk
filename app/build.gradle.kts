plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Minutes since 2026-01-01: always increasing, so Play accepts every new upload.
val autoVersionCode = ((System.currentTimeMillis() / 60_000L) - 29_453_760L).toInt().coerceAtLeast(1)

// Google Play upload key: provided via environment (GitHub secrets), never committed.
val uploadKeystore = System.getenv("HOLOS_UPLOAD_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.pixtane.holos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pixtane.holos"
        minSdk = 26
        targetSdk = 36
        versionCode = autoVersionCode
        versionName = "1.1.$buildNumber"
        // Nothing Phone 3a (and every modern phone) is arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
        // Number of the GitHub Actions build that produced this APK; the updater compares it
        // with the latest release tag (v1.1.<build>).
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
    }

    signingConfigs {
        create("release") {
            // Fixed key so new builds install as updates over old ones. CI provides it from the
            // HOLOS_RELEASE_KEYSTORE_B64 secret; the committed keystore is only a fallback.
            storeFile = System.getenv("HOLOS_RELEASE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
                ?: file("holos-release.jks")
            storePassword = System.getenv("HOLOS_RELEASE_STORE_PASSWORD") ?: "holos-local"
            keyAlias = System.getenv("HOLOS_RELEASE_KEY_ALIAS") ?: "holos"
            keyPassword = System.getenv("HOLOS_RELEASE_KEY_PASSWORD") ?: "holos-local"
        }
        if (uploadKeystore != null) {
            create("play") {
                storeFile = uploadKeystore
                storePassword = System.getenv("HOLOS_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("HOLOS_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("HOLOS_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("play") ?: signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // sherpa-onnx (k2-fsa) prebuilt Android library; downloaded by CI / scripts/fetch-sherpa.sh
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Document import: PDF text extraction and (X)HTML parsing for EPUB / MOBI.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.jsoup:jsoup:1.18.1")
}
