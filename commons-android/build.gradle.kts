import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 has built-in Kotlin support (the apps likewise apply no standalone kotlin-android plugin).
    id("com.android.library")
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
        // Use the bundled org.json (testImplementation) instead of android.jar's throwing stubs, so the
        // Vosk-JSON parser (WakeRecognizer.parseResult) can be exercised in plain JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":commons")) // PcmDevice seam + PcmCaptureFormat
    // On-device wake-word recognition (WakeRecognizer/WakeMicEngine) — free, keyless, offline, no GMS.
    implementation("com.alphacephei:vosk-android:0.3.75")
    // openWakeWord: ONNX Runtime for Android (self-contained native lib, no GMS) for the shared neural KWS.
    // Both apps get it transitively — it is the routing wake detector on gen1 (portal-wake) and gen2 (assistant).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for the WakeRecognizer.parseResult test
}
