// The OpenAI-compatible HTTP server. Plain Kotlin (JVM), so it is tested in CI and reused outside Android (#74).
plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    // The official OpenAI Java client, so the tests talk to the server the way real clients do.
    testImplementation("com.openai:openai-java:4.95.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
