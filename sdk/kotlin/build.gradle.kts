plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "ai.fanak.den"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// Recipes ship inside the SDK under assets/den/recipes, away from the app's own assets.
abstract class CopyRecipes : DefaultTask() {
    @get:InputDirectory
    abstract val recipes: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun run() {
        fs.sync {
            from(recipes) { include("**/*.yaml") }
            into(outputDir.dir("den/recipes"))
        }
    }
}

val copyRecipes = tasks.register<CopyRecipes>("copyRecipes") {
    recipes.set(rootProject.layout.projectDirectory.dir("recipes"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyRecipes, CopyRecipes::outputDir)
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":backends:llamacpp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(kotlin("test"))
}
