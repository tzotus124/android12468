plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.wolrelay"
    compileSdk = 35
    defaultConfig { applicationId = "com.wolrelay"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0"
        // Tailscale credential for creating the PC's one-time key. Supplied at BUILD time (GitHub secret TS_OAUTH / TS_TAG); never typed in the app.
        fun cfg(n: String) = (System.getenv(n) ?: (project.findProperty(n) as String?) ?: "").trim().replace("\\", "").replace("\"", "")
        buildConfigField("String", "TS_CRED", "\"${cfg("TS_OAUTH")}\"")
        buildConfigField("String", "TS_TAG", "\"${cfg("TS_TAG").ifEmpty { "tag:wake-pc" }}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
