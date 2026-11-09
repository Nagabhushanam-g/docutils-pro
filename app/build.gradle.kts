plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.docutility.pro"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.docutility.pro"
        minSdk = 26
        targetSdk = 36
        // The Play release workflow sets a dedicated monotonic version code.
        // Debug APKs keep their GitHub Actions run number for in-place updates.
        versionCode = System.getenv("DOCUTILS_VERSION_CODE")?.toIntOrNull()
            ?: (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 4).coerceAtLeast(5)
        versionName = "1.0.$versionCode"
    }
    // Pin debug APK signing explicitly. Android Gradle Plugin may otherwise
    // discover or generate a different debug keystore than the one restored in CI.
    // The file is kept outside version control and is supplied by GitHub Actions.
    signingConfigs {
        getByName("debug") {
            storeFile = file(
                System.getenv("DOCUTILS_DEBUG_KEYSTORE_PATH")
                    ?: (System.getProperty("user.home") + "/.android/debug.keystore")
            )
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    val admobAppId = System.getenv("DOCUTILS_ADMOB_APP_ID")?.takeIf { it.isNotBlank() } ?: "ca-app-pub-3940256099942544~3347511713"
    val admobBannerId = System.getenv("DOCUTILS_ADMOB_BANNER_ID")?.takeIf { it.isNotBlank() } ?: "ca-app-pub-3940256099942544/6300978111"
    val admobInterstitialId = System.getenv("DOCUTILS_ADMOB_INTERSTITIAL_ID")?.takeIf { it.isNotBlank() } ?: "ca-app-pub-3940256099942544/1033173712"
    defaultConfig {
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
    }
    // Google Play uses a separate upload key, NEVER the GitHub test/debug key.
    // CI supplies the upload key through encrypted Actions secrets.
    val releaseKeystorePath = System.getenv("DOCUTILS_RELEASE_KEYSTORE_PATH")
    if (!releaseKeystorePath.isNullOrBlank()) {
        signingConfigs.create("upload") {
            storeFile = file(releaseKeystorePath)
            storePassword = System.getenv("DOCUTILS_RELEASE_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("DOCUTILS_RELEASE_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("DOCUTILS_RELEASE_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // A production bundle must never silently fall back to Google's test ad IDs.
    val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    if (isReleaseBuild) {
        require(!releaseKeystorePath.isNullOrBlank()) { "Missing Play upload signing keystore" }
        require(!admobAppId.startsWith("ca-app-pub-3940256099942544") &&
            !admobBannerId.startsWith("ca-app-pub-3940256099942544") &&
            !admobInterstitialId.startsWith("ca-app-pub-3940256099942544")
        ) { "Production AdMob IDs are required for a Play release" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-ads:23.0.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
    implementation("com.android.billingclient:billing:8.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
