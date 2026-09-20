pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PageTurner"
// Keep Android SDK configuration out of core/server-only work.
val buildTarget = providers.gradleProperty("buildTarget").getOrElse("all")
require(buildTarget in setOf("all", "app", "server", "core")) {
    "buildTarget must be one of all, app, server, core (was $buildTarget)"
}
if (buildTarget in setOf("all", "app")) include(":app")
include(":core-model")
include(":core-content")
include(":core-translation")
include(":core-backup")
if (buildTarget in setOf("all", "server")) include(":server")
