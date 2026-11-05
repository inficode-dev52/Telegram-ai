import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val lp = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun secret(k: String) = System.getenv(k) ?: lp.getProperty(k) ?: ""
android {
    namespace = "com.aditya.ai"; compileSdk = 34
    defaultConfig {
        applicationId = "com.aditya.ai"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1"
        buildConfigField("int", "TG_API_ID", (secret("TG_API_ID").ifBlank { "0" }))
        buildConfigField("String", "TG_API_HASH", "\"${secret("TG_API_HASH")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Official TDLib build (org.drinkless.tdlib) as AAR, see README
    implementation(files("libs/tdlib.aar"))
}
