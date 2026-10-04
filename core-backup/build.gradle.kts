plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    api(project(":core-model"))
    api(project(":core-translation"))
    testImplementation(libs.junit)
}
