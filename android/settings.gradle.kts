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

rootProject.name = "cue-note"

include(":app")
include(":core:signal")   // pure Kotlin: WAV chunks, levels, setup-check rules (JVM-testable)
include(":core:audio")    // Android: USB mic selection and the recording service
include(":core:data")     // Android: Room database and Supabase client
include(":feature:record")
