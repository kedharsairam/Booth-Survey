plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kraft.voterlist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kraft.voterlist"
        // 26 is where the Storage Access Framework is available on every device, which is
        // what lets the app save an export wherever the user wants without holding any
        // storage permission at all.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so an installable APK comes out of a clean checkout.
            // Swap in a real keystore before this is distributed to anyone else.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}


dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")

    // The real org.json, for tests only. The android.jar that unit tests run against has
    // org.json stubbed to throw, and the whole point of the parsing tests is to pin down what
    // optString does with a JSON null -- which a stub cannot tell you. This never reaches the
    // APK: the app uses the platform's own org.json at runtime, and the build still has zero
    // third-party code in it.
    testImplementation("org.json:json:20240303")
}
