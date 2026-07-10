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
    // On-device openWakeWord (ONNX Runtime) — free, keyless, offline, no GMS.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
    // JVM ONNX Runtime for integration tests that exercise bundled assets on the dev/CI host.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}
