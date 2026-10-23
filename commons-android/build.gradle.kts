import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 has built-in Kotlin support (the apps likewise apply no standalone kotlin-android plugin).
    id("com.android.library")
    id("com.autonomousapps.dependency-analysis") // `buildHealth` advice; version pinned in the root build
}

// Shared Android-coupled helpers for the Portal apps (the bits that can't live in the pure-JVM :commons).
// Consumed by both apps via Gradle composite build: `implementation("com.portal:commons-android")`.
group = "com.portal"
version = "0.1.0"

android {
    namespace = "com.portal.commons.audio"
    compileSdk = 36

    defaultConfig {
        minSdk = 28 // Android 9 — Portal+ ("aloha"), matches both apps
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // Use the bundled org.json (testRuntimeOnly) instead of android.jar's throwing stubs, so the
        // Vosk detector's JSON parsing can be exercised in plain JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // Keep the build warning-free: any compiler warning fails compilation.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // PcmDevice seam + PcmCaptureFormat. `api`: they appear in this module's public signatures.
    api(project(":commons"))
    // On-device wake-word recognition (VoskWakeDetector/WakeMicEngine) — free, keyless, offline, no GMS.
    implementation("com.alphacephei:vosk-android:0.3.75")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for the VoskWakeDetector tests: main code calls it, the tests never reference it, so it is
    // only needed on the test runtime classpath (in place of android.jar's throwing stubs).
    testRuntimeOnly("org.json:json:20240303")
}
