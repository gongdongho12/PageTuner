plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-content"))
    implementation(project(":core-translation"))
    implementation(project(":core-backup"))
    implementation(project(":source-runtime"))
    implementation(project(":translation-runtime"))
    implementation(libs.org.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform {
        if (System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") != "1") excludeTags("live-source")
    }
}

sourceSets.named("test") {
    resources.srcDir(rootProject.file("contracts/fixtures"))
}

// Web remains an independent npm build. Package an explicitly built public shell only on request.
providers.gradleProperty("webDistDir").orNull?.let { path ->
    val webDirectory = rootProject.file(path)
    val indexFile = webDirectory.resolve("index.html")
    tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
        from(webDirectory) { into("BOOT-INF/classes/static") }
        doFirst {
            check(indexFile.isFile) { "Build the web client before supplying -PwebDistDir (missing $indexFile)." }
        }
    }
}
