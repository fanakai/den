plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("com.android.library") version "8.10.1" apply false
    id("com.android.application") version "8.10.1" apply false
    // The public API of core, the SDK and the backend is dumped to <module>/api/*.api and checked by `./gradlew build`:
    // a change to it fails CI until the dump is updated with `./gradlew apiDump` and reviewed in the pull request.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
}

// The published libraries: ai.fanak:den (the SDK), den-core, den-llamacpp and den-openai, version den.version.
// Release builds pass -Pden.version from the tag; signing needs signingInMemoryKey (CI secrets, docs/releasing.md).
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates("ai.fanak", property("den.artifact") as String, property("den.version") as String)
            publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                name.set(property("den.artifact") as String)
                description.set(property("den.description") as String)
                url.set("https://github.com/fanakai/den")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("fanakai")
                        name.set("The Fanak Den authors")
                        url.set("https://github.com/fanakai")
                    }
                }
                scm {
                    url.set("https://github.com/fanakai/den")
                    connection.set("scm:git:https://github.com/fanakai/den.git")
                    developerConnection.set("scm:git:ssh://git@github.com/fanakai/den.git")
                }
            }
        }
    }
}

apiValidation {
    // Apps, and the Den app's HTTP server: not libraries anyone builds on.
    ignoredProjects += listOf("testapp", "den", "demo", "service")
    // The SDK's engine process: in the same APK as its caller, so it changes in place (docs/aidl.md).
    ignoredClasses += listOf("ai.fanak.den.IEngine", "ai.fanak.den.IEngine\$Default", "ai.fanak.den.IEngine\$Stub")
}
