plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    api(project(":core-translation"))
    // Android provides org.json; other JVM hosts supply the runtime explicitly.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}

tasks.test {
    val webExchangeFixture = providers.environmentVariable("PAGETURNER_WEB_EXCHANGE_FIXTURE")
    inputs.property("webExchangeFixturePath", webExchangeFixture.orElse(""))
    if (webExchangeFixture.isPresent) {
        inputs.file(webExchangeFixture)
        environment("PAGETURNER_WEB_EXCHANGE_FIXTURE", webExchangeFixture.get())
    }
}
