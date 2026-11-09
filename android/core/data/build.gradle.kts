import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Supabase settings: local.properties overrides, then environment variables,
// then the project defaults in android/gradle.properties. The URL and the
// publishable key are public by design; row-level security protects the data.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(key: String, env: String) =
    (localProps.getProperty(key) ?: System.getenv(env) ?: findProperty(key)?.toString() ?: "")
        .replace("\"", "")

android {
    namespace = "com.cuenote.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 30
        buildConfigField("String", "SUPABASE_URL", "\"${setting("cuenote.supabaseUrl", "CUENOTE_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${setting("cuenote.supabaseAnonKey", "CUENOTE_SUPABASE_ANON_KEY")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Room writes each database version's schema here; the migration test checks against it.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:audio"))
    api(project(":core:pipeline"))
    api(project(":core:ml-android"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.room.runtime) // LessonDatabase is a RoomDatabase, used by :app
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
