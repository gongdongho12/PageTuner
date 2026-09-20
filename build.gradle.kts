// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

abstract class VerifyModuleBoundaries : DefaultTask() {
    @get:Input
    abstract val violations: ListProperty<String>

    @TaskAction
    fun verify() {
        check(violations.get().isEmpty()) { violations.get().joinToString("\n") }
    }
}

// Capture plain values at configuration time so verification supports the configuration cache.
val verifyModuleBoundaries = tasks.register<VerifyModuleBoundaries>("verifyModuleBoundaries") {
    group = "verification"
    description = "Checks shared-core and app/server project dependency boundaries."
    violations.convention(emptyList())
}
gradle.projectsEvaluated {
    subprojects.forEach { module ->
        val allowedCoreDependencies = mapOf(
            ":core-model" to emptySet<String>(),
            ":core-content" to emptySet<String>(),
            ":core-translation" to setOf(":core-content"),
            ":core-backup" to setOf(":core-translation"),
        )
        val errors = mutableListOf<String>()
        val targets = module.configurations.flatMap { configuration ->
            configuration.dependencies.withType<org.gradle.api.artifacts.ProjectDependency>()
                .map { it.path }
        }.toSet()
        // Android adds a self-reference for its test fixtures during late configuration.
        val allowed = allowedCoreDependencies[module.path] ?: (allowedCoreDependencies.keys + module.path)
        if (!targets.all { it in allowed }) {
            errors += "${module.path} has forbidden project dependencies: ${targets - allowed}"
        }
        if (module.path in allowedCoreDependencies) {
            val productionDependencies = listOf("api", "implementation", "compileOnly", "runtimeOnly")
                .flatMap { module.configurations.findByName(it)?.dependencies.orEmpty() }
            if (!productionDependencies.all {
                it is org.gradle.api.artifacts.ProjectDependency || it.group == "org.jetbrains.kotlin"
            }) { errors += "${module.path} must remain framework-free Kotlin/JVM" }
            if (module.plugins.hasPlugin("com.android.application") ||
                module.plugins.hasPlugin("com.android.library") ||
                module.plugins.hasPlugin("org.springframework.boot")) {
                errors += "${module.path} must not apply a platform framework plugin"
            }
        }
        verifyModuleBoundaries.configure { violations.addAll(errors) }
    }
}
