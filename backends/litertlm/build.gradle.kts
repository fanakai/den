// The LiteRT-LM backend: .litertlm files on the CPU and the GPU, through Google's runtime from Google Maven.
plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

val litertlm = "0.20.0"

mavenPublishing {
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = false))
}
val javadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}
publishing.publications.withType<MavenPublication>().configureEach { artifact(javadocJar) }

android {
    namespace = "ai.fanak.den.backend.litertlm"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "LITERTLM_VERSION", "\"$litertlm\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

android.testOptions.unitTests.all {
    it.testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    api(project(":core"))
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertlm")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test-junit"))
}
