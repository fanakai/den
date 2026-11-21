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
}

apiValidation {
    // Apps, and the Den app's HTTP server: not libraries anyone builds on.
    ignoredProjects += listOf("testapp", "den", "demo", "service")
    // The SDK's engine process: in the same APK as its caller, so it changes in place (docs/aidl.md).
    ignoredClasses += listOf("ai.fanak.den.IEngine", "ai.fanak.den.IEngine\$Default", "ai.fanak.den.IEngine\$Stub")
}
