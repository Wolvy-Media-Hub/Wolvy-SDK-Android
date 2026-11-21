pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "wolvy-sdk-android"
include(":wolvy-player", ":sample")
// spike/ is a separate Gradle project (phase 3 step 1 test app), not part of this build.
