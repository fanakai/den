// Kotlin Multiplatform, no Android dependency: the router, recipes and catalog run on the JVM (Android) and on native
// targets later (iOS #19, Linux #74, a C library #75). linuxX64 keeps the common code free of the JVM; downloads,
// files and hub catalogs are in jvmMain.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "ai.fanak"

kotlin {
    jvmToolchain(17)
    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("com.charleskorn.kaml:kaml:0.68.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            // IOException in common code; on the JVM it is java.io.IOException.
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.5.4")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    systemProperty("den.recipes", rootProject.file("recipes").absolutePath)
    systemProperty("den.models", rootProject.file("models").absolutePath)
}
