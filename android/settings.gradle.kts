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
include(":core:llm-android") // Android: builds llama.cpp, for the card writer on the phone
include(":core:posture")  // Android: camera service and MediaPipe Pose, in its own process
include(":core:pipeline") // pure Kotlin: segmenter, pitch, linker, debug timeline
include(":core:sketch")   // pure Kotlin: find the page in a sketch photo, straighten and whiten it
include(":core:ui")      // Android: the shared look (theme, note cards, level bars)
include(":feature:record")
include(":feature:lesson")
include(":feature:practice") // practice between lessons: takes, the headphone timing check
