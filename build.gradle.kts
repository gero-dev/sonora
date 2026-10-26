// Корневой build-файл. AGP 9 включает встроенную поддержку Kotlin,
// поэтому отдельный плагин org.jetbrains.kotlin.android не применяется.
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
