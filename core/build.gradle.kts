// Plain Kotlin, no Android dependency, so the iOS SDK can reuse it later through KMP.
plugins {
    kotlin("jvm")
}

group = "ai.fanak"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
