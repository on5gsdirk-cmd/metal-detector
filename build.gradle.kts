// De Android Gradle Plugin komt alleen op het classpath als er een Android SDK is
// (zie settings.gradle.kts), zodat :core ook zonder SDK gebouwd en getest kan worden.
// Hij moet in de root-classloader zitten, samen met de Kotlin-plugins.
buildscript {
    val hasSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (hasSdk) classpath(libs.android.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
