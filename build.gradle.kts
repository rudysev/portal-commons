plugins {
  kotlin("jvm") version "2.4.21" apply false
  // Applied by the :commons-android subproject (the shared Android mic shell). Pure :commons stays JVM-only.
  // AGP 9 brings built-in Kotlin support, so no standalone kotlin-android plugin is needed.
  id("com.android.library") version "9.4.1" apply false
  // Android Lint for the pure-JVM :commons (same AGP version as com.android.library).
  id("com.android.lint") version "9.4.1" apply false
  // ktlint via kotlinter (ADR 0001). 5.7.0 bundles ktlint 1.8.0, the version .githooks/pre-commit requires.
  id("org.jmailen.kotlinter") version "5.7.0"
  // Dependency advice (`./gradlew buildHealth`), run as its own CI job and not part of `check`.
  // Each subproject applies it too (no auto-apply from the root). 3.19's kotlin-bom 2.4.0 sets the build's
  // kotlin-daemon-client to at least 2.4.0, so KGP must stay >= 2.4.0 or the Kotlin daemon fails (NoSuchMethodError).
  id("com.autonomousapps.dependency-analysis") version "3.19.2"
  base
}

// kotlinter's lintKotlin only covers source sets, so lint the Gradle scripts here (the pre-commit hook does too).
val lintKotlinScripts = tasks.register<org.jmailen.gradle.kotlinter.tasks.LintTask>("lintKotlinScripts") {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
tasks.check { dependsOn(lintKotlinScripts) }
val formatKotlinScripts = tasks.register<org.jmailen.gradle.kotlinter.tasks.FormatTask>("formatKotlinScripts") {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
// The root has no formatKotlin of its own, so `./gradlew formatKotlin` also fixes the Gradle scripts.
tasks.register("formatKotlin") { dependsOn(formatKotlinScripts) }

dependencyAnalysis {
  issues {
    all {
      onAny { severity("fail") }
    }
  }
}
