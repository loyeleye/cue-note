// Pure Kotlin, no Android imports: everything here runs in plain JVM unit
// tests on a laptop or in CI, exactly as it runs on the phone.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
