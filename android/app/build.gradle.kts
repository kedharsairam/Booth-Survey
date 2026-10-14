import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing material, read from a file that is deliberately not in the repository.
//
// A clean checkout has to build, so this is optional rather than required: with no
// keystore.properties present the release variant falls back to the debug key and says so
// loudly at build time. That is a far better failure than a build that cannot run, and it
// means CI needs no secret to check that the code still compiles.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystore.getProperty("storeFile") != null

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
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // On, and worth the trouble: the app draws 36 icons and ships material-icons
            // -extended, which is roughly 45,000 icon classes. Unminified the APK was
            // 19 MB for about 13,000 lines of Kotlin. A surveyor installing this over
            // whatever is already on the phone pays all of that, on a connection that is
            // frequently the phone tethered to a laptop or a bad signal.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: no keystore.properties, so the release APK is signed with the " +
                        "shared debug key and is debuggable. That build must not be given to " +
                        "a surveyor: anyone who plugs the phone into a computer can read the " +
                        "whole voter roll with adb."
                )
                signingConfigs.getByName("debug")
            }
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
    // Only kraft-ui: kraft-core holds KraftResult and AppError, and nothing here imports
    // either, so carrying it was a module in the composite build for zero call sites.
    implementation("com.kraft:kraft-ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    // viewmodel-ktx, not viewmodel-compose. What is used is AndroidViewModel and
    // viewModelScope; the -compose artifact exists only for the viewModel() composable,
    // which this app does not call. Lint was already reporting it two majors stale.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")

    // Test artifacts pinned to the BOM above (Compose 1.12.1): the platform constraint does
    // not propagate a version into this classpath, and an unpinned coordinate fails the
    // resolution outright. When the BOM moves, these move with it -- that is the same
    // one-version rule, stated twice.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Provides the empty activity the compose test rule launches its content in.
    // debugImplementation, so this exported activity never reaches a release APK.
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")

    // The real org.json, for tests only. The android.jar that unit tests run against has
    // org.json stubbed to throw, and the whole point of the parsing tests is to pin down what
    // optString does with a JSON null -- which a stub cannot tell you. This never reaches the
    // APK: the app uses the platform's own org.json at runtime, and the build still has zero
    // third-party code in it.
    testImplementation("org.json:json:20240303")
}