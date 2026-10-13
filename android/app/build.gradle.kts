plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kraft.voterlist"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kraft.voterlist"
        // UI tests drive the real screens on a device: the drag reorder cannot be
        // finger-tested over adb (no hold-then-move primitive), so the gesture path
        // is proved here instead, through the same production code.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 26 is where the Storage Access Framework is available on every device, which is
        // what lets the app save an export wherever the user wants without holding any
        // storage permission at all.
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}


dependencies {
    // One Compose version everywhere: the same BOM kraft-foundation builds against.
    // A split (compile on one version, run on another) desugars into NoSuchMethodError
    // at the worst moment -- the button that opens the sheet -- instead of a compile
    // error at the desk.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Kraft Foundation — composite build, substituted in settings.gradle.kts.
    implementation("com.kraft:kraft-ui")
    implementation("com.kraft:kraft-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")

    // Test artifacts pinned to the BOM above (Compose 1.12.1): the platform constraint does
    // not propagate a version into this classpath, and an unpinned coordinate fails the
    // resolution outright. When the BOM moves, these move with it -- that is the same
    // one-version rule, stated twice.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Provides the empty activity the compose test rule launches its content in.
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")

    // The real org.json, for tests only. The android.jar that unit tests run against has
    // org.json stubbed to throw, and the whole point of the parsing tests is to pin down what
    // optString does with a JSON null -- which a stub cannot tell you. This never reaches the
    // APK: the app uses the platform's own org.json at runtime, and the build still has zero
    // third-party code in it.
    testImplementation("org.json:json:20240303")
}
