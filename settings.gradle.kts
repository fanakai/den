pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "den"

include(":core")
include(":backends:llamacpp")
include(":sdk:kotlin")
include(":apps:testapp")
