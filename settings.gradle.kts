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
include(":service")
include(":backends:llamacpp")
include(":backends:openai")
include(":backends:litertlm")
include(":sdk:kotlin")
include(":apps:testapp")
include(":apps:den")
include(":apps:demo")
