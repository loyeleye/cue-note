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
include(":core:ml")       // pure Kotlin: Silero VAD and whisper.cpp behind interfaces
include(":core:ml-android") // Android: builds the native library for the phone
include(":core:pipeline") // pure Kotlin: segmenter, pitch, linker, debug timeline
include(":core:sketch")   // pure Kotlin: find the page in a sketch photo, straighten and whiten it
include(":feature:record")
include(":feature:lesson")
