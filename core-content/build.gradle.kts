plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
