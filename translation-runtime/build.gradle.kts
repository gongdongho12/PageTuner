plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(11) }

dependencies {
    api(project(":core-translation"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { exclude("**/GoogleWebTranslationLiveTest.class") }

// Explicit network check: never silently skipped by the ordinary unit suite.
tasks.register<Test>("googleWebTranslationLiveTest") {
    description = "Translate a real paragraph with the shared no-key Google Web provider."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/GoogleWebTranslationLiveTest.class")
    dependsOn(tasks.testClasses)
}
