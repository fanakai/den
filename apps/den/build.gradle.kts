plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "ai.fanak.den.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.fanak.den"
        minSdk = 28
        targetSdk = 36
        // From den.version: 1.2.3 is version code 10203.
        val version = property("den.version") as String
        versionName = version
        versionCode = version.substringBefore('-').split('.').map { it.toInt() }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key lives outside the repository. ~/.gradle/gradle.properties names it:
    //   den.release.storeFile, den.release.keyAlias, den.release.passwordFile (a file holding the password).
    // Without them (CI, other machines), or with -Pden.release.storeFile= , release builds use the debug key.
    val store = providers.gradleProperty("den.release.storeFile").orNull?.takeIf { it.isNotBlank() }
    val passwordFile = providers.gradleProperty("den.release.passwordFile").orNull
    signingConfigs {
        if (store != null && passwordFile != null) {
            create("release") {
                storeFile = file(store)
                // The first line, as keytool reads it with -storepass:file.
                storePassword = file(passwordFile).readLines().first()
                keyAlias = providers.gradleProperty("den.release.keyAlias").getOrElse("den")
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("Den app: no release key configured, release build signed with the debug key")
            }
        }
    }

    // With the Hexagon NPU (den.hexagon), native libraries are extracted: the DSP reads its skel from that folder.
    packaging { jniLibs { useLegacyPackaging = !providers.gradleProperty("den.hexagon").orNull.isNullOrBlank() } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":sdk:kotlin"))
    implementation(project(":backends:llamacpp"))
    implementation(project(":backends:litertlm"))
    implementation(project(":backends:openai"))
    implementation(project(":service"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}
