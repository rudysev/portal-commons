import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  // Android Lint on a pure-JVM module, so :commons gets the same gate as :commons-android.
  id("com.android.lint")
  id("com.autonomousapps.dependency-analysis") // `buildHealth` advice; version pinned in the root build
  id("org.jmailen.kotlinter")
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
    // Keep the build warning-free: any compiler warning fails compilation.
    allWarningsAsErrors.set(true)
  }
}

// Any lint warning fails the build. No baseline: suppress inline only, with a reason.
lint {
  warningsAsErrors = true
  abortOnError = true
  checkReleaseBuilds = true
  // Dependency-freshness checks query Maven/Google at lint time, so the result changes whenever upstream ships a
  // release, not when this code changes. Dependency updates are deliberate (Renovate/Dependabot are out of scope).
  disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
}

dependencies {
  testImplementation("junit:junit:4.13.2")
}

tasks.test {
  useJUnit()
  testLogging { events("passed", "skipped", "failed") }
}
