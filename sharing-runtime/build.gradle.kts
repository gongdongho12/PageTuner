plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(11) }
dependencies {
    api(project(":core-sharing"))
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Android provides this API; JVM hosts provide it explicitly, as in backup-runtime.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}
