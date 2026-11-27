plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

// AGP's own javadoc runs an old Dokka that cannot read the sealed classes of core, so Dokka's jar is attached instead.
mavenPublishing {
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = false))
}
val javadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}
publishing.publications.withType<MavenPublication>().configureEach { artifact(javadocJar) }

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

// Recipes ship inside the SDK under assets/den/recipes, away from the app's own assets; the notices of the code in
// the SDK under assets/den/NOTICE and den/licenses, so every app that uses it carries them.
abstract class CopyRecipes : DefaultTask() {
    @get:InputDirectory
    abstract val recipes: DirectoryProperty

    @get:InputDirectory
    abstract val models: DirectoryProperty

    @get:InputFile
    abstract val notice: RegularFileProperty

    @get:InputDirectory
    abstract val licenses: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun run() {
        fs.sync {
            from(recipes) { include("**/*.yaml"); into("den/recipes") }
            from(models) { include("*.yaml"); into("den/models") }
            from(notice) { into("den") }
            from(licenses) { into("den/licenses") }
            into(outputDir)
        }
    }
}

val copyRecipes = tasks.register<CopyRecipes>("copyRecipes") {
    recipes.set(rootProject.layout.projectDirectory.dir("recipes"))
    models.set(rootProject.layout.projectDirectory.dir("models"))
    notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
    licenses.set(rootProject.layout.projectDirectory.dir("licenses"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyRecipes, CopyRecipes::outputDir)
    }
}

dependencies {
    api(project(":core"))
    // No backend here: an app adds one (e.g. :backends:llamacpp) for embedded mode; without one it only uses the Den
    // app and ships no native code.
    testImplementation(project(":backends:llamacpp"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}
