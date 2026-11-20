plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "ai.fanak.den.backend.llamacpp"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Link-time stub only; the vendor libOpenCL.so must be the one loaded on the phone.
    packaging {
        jniLibs {
            excludes += "**/libOpenCL.so"
            // CPU variants kept: armv8.0 (oldest phones), armv8.2_2 (dotprod + fp16), armv8.6_1 (+ i8mm). The others
            // add about 18 MB for little gain on phones (128-bit SVE, same width as NEON); the loader skips them.
            excludes += listOf("**/libggml-cpu-android_armv8.2_1.so", "**/libggml-cpu-android_armv9*.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
