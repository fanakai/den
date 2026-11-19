plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ai.fanak.den.testapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.fanak.den.testapp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Debug builds the native code without optimization (~3x slower decode); test with release.
    buildTypes {
        release { signingConfig = signingConfigs.getByName("debug") }
    }

    // Bundle the repository recipes, as the SDK will.
    sourceSets["main"].assets.srcDir(rootProject.file("recipes"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":backends:llamacpp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
