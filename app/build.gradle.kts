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
}

dependencies {
    implementation(project(":core-model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
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
