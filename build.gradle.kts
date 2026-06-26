plugins {
    kotlin("jvm") version "2.2.10" apply false
    // Applied by the :commons-android subproject (the shared Android mic shell). Pure :commons stays JVM-only.
    // AGP 9 brings built-in Kotlin support, so no standalone kotlin-android plugin is needed.
    id("com.android.library") version "9.2.1" apply false
}
