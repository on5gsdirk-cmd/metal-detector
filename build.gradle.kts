// De Android Gradle Plugin wordt in app/build.gradle.kts zelf geladen, zodat de
// pure Kotlin-module :core ook zonder Android SDK gebouwd en getest kan worden.
plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
