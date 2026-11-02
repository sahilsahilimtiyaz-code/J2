buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP 9 has built-in Kotlin support and bundles an older KGP. Declaring the
        // current Kotlin Gradle plugin here keeps the Kotlin compiler, Compose
        // compiler plugin and Kotlin metadata on the same version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    // KSP 2.3.13 is the current release that supports the Kotlin 2.4 line.
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
