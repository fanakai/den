// A backend for OpenAI-compatible servers: llama-server, Lemonade or Ollama on the LAN, and cloud providers. Plain
// Kotlin (JVM), so CI tests it against Den's own server.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(com.vanniktech.maven.publish.KotlinJvm(com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaHtml")))
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation(project(":service"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
