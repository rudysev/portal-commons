import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// Coordinates used by consuming apps via Gradle composite build (`includeBuild`) dependency
// substitution: an app declares `implementation("com.portal:commons")` and Gradle wires it to this
// project. See the project README.
group = "com.portal"
version = "0.1.0"

// Target Java 11 bytecode so the artifact is consumable by the Portal apps (AGP compileOptions 11),
// regardless of the JDK that runs the build. No toolchain download required.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
