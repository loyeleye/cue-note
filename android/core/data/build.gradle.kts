import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Supabase settings come from android/local.properties (never committed):
//   cuenote.supabaseUrl=https://xxxx.supabase.co
//   cuenote.supabaseAnonKey=eyJ...
// or from environment variables of the same names in CI.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(key: String, env: String) =
    (localProps.getProperty(key) ?: System.getenv(env) ?: "").replace("\"", "")

android {
    namespace = "com.cuenote.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        buildConfigField("String", "SUPABASE_URL", "\"${setting("cuenote.supabaseUrl", "CUENOTE_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${setting("cuenote.supabaseAnonKey", "CUENOTE_SUPABASE_ANON_KEY")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:audio"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
