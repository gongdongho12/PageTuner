plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun readRootDotEnv(): Map<String, String> {
    val envFile = rootProject.file(".env")
    if (!envFile.isFile) return emptyMap()
    return envFile.readLines()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith('#') && '=' in it }
        .associate { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            key to value
        }
}

fun String.asBuildConfigString(): String = buildString {
    append('"')
    append(this@asBuildConfigString.replace("\\", "\\\\").replace("\"", "\\\""))
    append('"')
}

val rootDotEnv = readRootDotEnv()
fun localSecret(name: String): String =
    providers.environmentVariable(name).orNull ?: rootDotEnv[name].orEmpty()

val deepSeekApiKey = localSecret("DEEPSEEK_API_KEY")
val deepSeekApiUrl = localSecret("DEEPSEEK_API_URL")
    .ifBlank { "https://api.deepseek.com/chat/completions" }
val deepSeekModel = localSecret("DEEPSEEK_MODEL").ifBlank { "deepseek-v4-flash" }

// Both the hosted web reader and the phone's offline sharing UI use the same React sources.
// Generate the phone bundle as part of an APK build so a stale checked-in bundle cannot ship.
val sharingWebDirectory = rootProject.layout.projectDirectory.dir("web")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) {
    listOf("cmd", "/c", "npm")
} else listOf("npm")
val installSharingWebDependencies = tasks.register<Exec>("installSharingWebDependencies") {
    group = "build"
    description = "Installs the locked web dependencies for the bundled local sharing reader (requires Node.js)."
    workingDir(sharingWebDirectory)
    commandLine(npmCommand + "ci")
    inputs.files(sharingWebDirectory.file("package.json"), sharingWebDirectory.file("package-lock.json"))
    outputs.file(sharingWebDirectory.file("node_modules/.package-lock.json"))
}
val buildSharingWeb = tasks.register<Exec>("buildSharingWeb") {
    group = "build"
    description = "Builds the self-contained phone sharing UI from the shared web components."
    dependsOn(installSharingWebDependencies)
    workingDir(sharingWebDirectory)
    commandLine(npmCommand + listOf("run", "build:sharing"))
    inputs.files(fileTree(sharingWebDirectory) {
        exclude("node_modules/**", "dist/**", "dist-sharing/**", ".git/**", "coverage/**")
    })
    inputs.files(rootProject.fileTree("contracts") { include("*.openapi.json") })
    outputs.dir(sharingWebDirectory.dir("dist-sharing"))
}
abstract class BundleSharingWebAssets : DefaultTask() {
    @get:InputDirectory
    abstract val webBundle: DirectoryProperty
    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty
    @get:javax.inject.Inject
    abstract val fileSystem: org.gradle.api.file.FileSystemOperations

    @TaskAction
    fun bundle() {
        fileSystem.sync {
            from(webBundle) { into("local-sharing") }
            into(destinationDirectory)
        }
    }
}
val bundleSharingWeb = tasks.register<BundleSharingWebAssets>("bundleSharingWeb") {
    dependsOn(buildSharingWeb)
    webBundle.set(sharingWebDirectory.dir("dist-sharing"))
    destinationDirectory.set(layout.buildDirectory.dir("generated/sharingAssets"))
}

android {
    namespace = "com.dongholab.pagetuner"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.dongholab.pagetuner"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEEPSEEK_API_KEY", deepSeekApiKey.asBuildConfigString())
            buildConfigField("String", "DEEPSEEK_API_URL", deepSeekApiUrl.asBuildConfigString())
            buildConfigField("String", "DEEPSEEK_MODEL", deepSeekModel.asBuildConfigString())
        }
        release {
            // Production credentials must be resolved by a subscription backend, never embedded in the APK.
            buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
            buildConfigField("String", "DEEPSEEK_API_URL", deepSeekApiUrl.asBuildConfigString())
            buildConfigField("String", "DEEPSEEK_MODEL", deepSeekModel.asBuildConfigString())
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("test").resources.srcDir(rootProject.file("contracts/fixtures"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(bundleSharingWeb) { it.destinationDirectory }
}
tasks.named("preBuild").configure { dependsOn(bundleSharingWeb) }

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-translation"))
    implementation(project(":core-backup"))
    implementation(project(":backup-runtime"))
    implementation(project(":source-runtime"))
    implementation(project(":translation-runtime"))
    implementation(project(":sharing-runtime"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Real server validation is opt-in and never appears as a skipped/default unit test.
tasks.withType<Test>().configureEach {
    if (name != "translationServerIntegrationTest") {
        exclude("**/TranslationServerIntegrationTest.class")
    }
}

tasks.register<Test>("translationServerIntegrationTest") {
    group = "verification"
    description = "Runs the Android translation adapter against an explicitly configured local server."
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = unitTest.get().testClassesDirs
    classpath = unitTest.get().classpath
    include("**/TranslationServerIntegrationTest.class")
    useJUnit()
    if (System.getenv("PAGETUNER_LIVE_JOB_CREATE") != "1") {
        filter.excludeTestsMatching("*.TranslationServerIntegrationTest.realGoogleJobCreatedByAndroidAdapterCompletesAndIsReadable")
    }
    // The additional real-provider read check is included only when explicitly supplied records exist.
    // Supplying only one ID still runs the test and fails with the missing environment name.
    if (System.getenv("PAGETUNER_LIVE_TRANSLATION_RECORD_ID").isNullOrBlank() &&
        System.getenv("PAGETUNER_LIVE_CHAPTER_RECORD_ID").isNullOrBlank()) {
        filter.excludeTestsMatching("*.TranslationServerIntegrationTest.realGoogleServerTranslationIsReadableWithMatchingOriginalAndTitles")
    }
    outputs.upToDateWhen { false }
}

// Optional browser/native bridge fixtures must invalidate Gradle's test cache.
tasks.withType<Test>().configureEach {
    val browserFixture = providers.environmentVariable("PAGETUNER_BROWSER_EXCHANGE_FIXTURE")
    inputs.property("browserExchangeFixturePath", browserFixture.orElse(""))
    if (browserFixture.isPresent) {
        inputs.file(browserFixture)
        environment("PAGETUNER_BROWSER_EXCHANGE_FIXTURE", browserFixture.get())
    }
}
