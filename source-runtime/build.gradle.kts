plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(11) }

dependencies {
    api(project(":core-model"))
    api(project(":core-content"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Android supplies org.json; the server explicitly supplies the JVM runtime.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
