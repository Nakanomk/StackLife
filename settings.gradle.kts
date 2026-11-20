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
        google { content { includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.(android|google)(\\..*)?") } }
        mavenCentral()
    }
}

rootProject.name = "StackLife"
include(":app", ":macrobenchmark")
