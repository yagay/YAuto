pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YAuto"

include(
    ":app",
    ":core:model",
    ":core:logging",
    ":core:diagnostics",
    ":core:capability",
    ":core:registry",
    ":core:engine",
    ":core:runtime",
    ":core:storage",
    ":core:importer",
    ":feature:standard",
    ":plugin:api",
    ":platform:android",
    ":platform:accessibility",
    ":platform:root",
    ":platform:shizuku",
    ":platform:xposed",
    ":ui:design",
    ":ui:home",
    ":ui:editor",
    ":ui:diagnostics",
)
