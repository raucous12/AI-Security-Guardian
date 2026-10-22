pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories: every artifact comes from these two.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "LIAN"

include(
    ":app",
    ":core:engine",
    ":core:intel",
    ":core:platform",
    ":core:storage",
    ":tools:trainer",   // maintainer tool for the on-device models; not part of the app
)
