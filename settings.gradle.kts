rootProject.name = "utaloom"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.MetrolistGroup.innertubex") }
        }
        mavenCentral()
        google()
    }
}

include(":shared")
include(":innertube")
include(":app")
// Desktop builds need no Android SDK. Both platforms use this wrapper and source tree.
if (providers.gradleProperty("android").isPresent || gradle.startParameter.taskNames.any { it.startsWith(":android:") }) {
    include(":android")
}
