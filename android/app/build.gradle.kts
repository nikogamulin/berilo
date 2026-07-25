import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// S2.8: release signing reads android/keystore.properties (gitignored, real
// keystore lives outside the repo). Absent locally/CI -> fall back to debug
// signing so `assembleRelease` never fails for lack of a keystore; see
// android/keystore.properties.example for the template.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "app.berilo.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.berilo.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKeystoreProperties) {
                signingConfigs.getByName("release")
            } else {
                // Dev/CI fallback: debug-signed release build (see comment above).
                logger.warn(
                    "assembleRelease: no keystore.properties -> DEBUG-SIGNED build, " +
                        "not for distribution"
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the Readium Streamer's AAR metadata (java.time/nio API use on minSdk 26).
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the reader's page-turn timing overlay (PerfLog, R2).
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        // S2.9: Compose UI tests (Robolectric-hosted, need a merged ComponentActivity from
        // ui-test-manifest) live in a debug-only unit test source set — `debugImplementation`
        // keeps that manifest out of the release build, so testReleaseUnitTest never needs it.
        getByName("testDebug") {
            kotlin.srcDirs("src/testDebug/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // Hosts Readium's EpubNavigatorFragment: readium-navigator ships androidx.fragment
    // only as a runtime dependency, so the fragment/FragmentActivity/ComposeView
    // interop types are pulled in explicitly here (aar-metadata minCompileSdk 34 ≤ 35).
    implementation(libs.androidx.fragment.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    // S2.9: Robolectric-hosted Compose UI tests for icon/content-description assertions, in the
    // `testDebug` source set (see sourceSets above) — debugImplementation supplies both the
    // test API and the ComponentActivity manifest (`ui-test-manifest`) those tests need.
    debugImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
