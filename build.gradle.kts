plugins {
  kotlin("jvm") version "2.2.10" apply false
  // Applied by the :commons-android subproject (the shared Android mic shell). Pure :commons stays JVM-only.
  // AGP 9 brings built-in Kotlin support, so no standalone kotlin-android plugin is needed.
  id("com.android.library") version "9.2.1" apply false
  // ktlint via kotlinter (ADR 0001). 5.7.0 bundles ktlint 1.8.0, the version .githooks/pre-commit requires.
  id("org.jmailen.kotlinter") version "5.7.0"
  // Dependency advice (`./gradlew buildHealth`), run as its own CI job and not part of `check`.
  // Each subproject applies it too (no auto-apply from the root). Stay on 3.18.x while Kotlin is 2.2.x: 3.19's
  // kotlin-bom 2.4.0 upgrades kotlin-daemon-client past KGP 2.2.10, and the Kotlin daemon fails (NoSuchMethodError).
  id("com.autonomousapps.dependency-analysis") version "3.18.0"
  base
}

// kotlinter's lintKotlin only covers source sets, so lint the Gradle scripts here (the pre-commit hook does too).
val lintKotlinScripts by tasks.registering(org.jmailen.gradle.kotlinter.tasks.LintTask::class) {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
tasks.check { dependsOn(lintKotlinScripts) }

dependencyAnalysis {
  issues {
    all {
      onAny { severity("fail") }
    }
  }
}
