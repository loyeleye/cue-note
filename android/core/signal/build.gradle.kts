// Pure Kotlin, no Android imports: everything here runs in plain JVM unit
// tests on a laptop or in CI, exactly as it runs on the phone.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Bytecode for Java 17, built with whatever JDK 17+ is installed.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}
