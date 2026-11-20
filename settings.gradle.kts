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
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io"); metadataSources { gradleMetadata() } } }
            filter { includeModule("com.github.tobiasschuerg", "android-week-view") }
        }
    }
}

rootProject.name = "StackLife"
include(":app")
