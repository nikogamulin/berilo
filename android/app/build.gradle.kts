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

// S3.2: cloud-sync build configuration, resolved at build time and baked into BuildConfig so
// the compiled APK can reach the sync service while the repository stays value-free.
//
// The Clerk *publishable* key is a public client identifier (the web app serves the same
// string in its JS bundle) — it authorizes nothing on its own. It is kept out of git anyway so
// this open-source repo carries no environment-specific values, and because the secret half of
// the pair (CLERK_SECRET_KEY, SUPABASE_SECRET_KEY) must never come near the app: the client
// only ever holds a short-lived session JWT that Postgres RLS evaluates server-side.
//
// Resolution order, first hit wins: android/berilo.properties (gitignored, see
// berilo.properties.example) -> environment variable -> empty. Empty is not a build failure:
// the app compiles and runs with the sync surface disabled behind a stated message, which
// keeps `assembleDebug` working for any contributor who clones without credentials.
val syncPropertiesFile = rootProject.file("berilo.properties")
val syncProperties = Properties().apply {
    if (syncPropertiesFile.exists()) {
        load(FileInputStream(syncPropertiesFile))
    }
}

fun syncConfig(key: String, envVar: String, default: String = ""): String =
    syncProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envVar)?.takeIf { it.isNotBlank() }
        ?: default

val clerkPublishableKey = syncConfig("clerkPublishableKey", "BERILO_CLERK_PUBLISHABLE_KEY")
val syncApiBaseUrl = syncConfig("apiBaseUrl", "BERILO_API_BASE_URL", "https://berilo.app/api/v1")

if (clerkPublishableKey.isEmpty()) {
    logger.lifecycle(
        "berilo: no clerkPublishableKey -> cloud sync disabled in this build " +
            "(see android/berilo.properties.example)"
    )
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

        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", "\"$clerkPublishableKey\"")
        buildConfigField("String", "SYNC_API_BASE_URL", "\"$syncApiBaseUrl\"")
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
        // BuildConfig.DEBUG gates the reader's page-turn timing overlay (PerfLog, R2); S3.2
        // adds CLERK_PUBLISHABLE_KEY / SYNC_API_BASE_URL (see syncConfig above).
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

    // S3.2: cloud sync — Clerk session auth + background sync scheduling.
    implementation(libs.clerk.android.api) {
        // Boox e-ink tablets frequently ship without Google Play Services, and the app's
        // sign-in is deliberately email-code only (no Google One Tap, no passkeys) so none of
        // these are ever called. Excluding them keeps the APK from carrying Play-dependent
        // code paths that would fail on the target device.
        exclude(group = "com.google.android.gms")
        exclude(group = "com.google.android.play")
        exclude(group = "com.google.android.libraries.identity.googleid")
        exclude(group = "androidx.credentials", module = "credentials-play-services-auth")
    }
    implementation(libs.androidx.work.runtime.ktx)

    constraints {
        // Clerk pulls androidx.browser 1.9.0, whose aar-metadata demands compileSdk 36 +
        // AGP 8.9.1 and so breaks the platform-35 set this repo is pinned to
        // (docs/findings.md). `strictly` holds it at the last 1.x that builds here.
        // Safe because androidx.browser is Custom Tabs, which only Clerk's OAuth/SSO
        // redirect paths use — this app signs in with email codes and passwords only, and
        // the Play-side SSO dependencies are excluded above.
        implementation("androidx.browser:browser") {
            version { strictly("1.8.0") }
            because("androidx.browser 1.9.0+ requires compileSdk 36/AGP 8.9.1 (findings.md)")
        }
    }

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
    // S2.10: screenshot harness (JVM/Robolectric only — no Roborazzi Gradle plugin, so no
    // additional compileSdk requirement on top of the platform-35 ceiling). Pulled in as a
    // debugImplementation testing library, same as the ui-test artifacts above.
    debugImplementation(libs.roborazzi)
    debugImplementation(libs.roborazzi.compose)
}

// B2: the cross-language EPUB identity tests read the real example books in place. `data/` is
// gitignored (copyrighted books) and absent from CI and from every agent worktree, so the
// directory is never hardcoded: it is passed in and the tests skip (JUnit `Assume`) without it.
//   ./gradlew test -Dberilo.examples.dir=/abs/path/to/data/examples
// Gradle test JVMs do not inherit -D from the launcher, hence the explicit forward.
val exampleBooksProperty = "berilo.examples.dir"

// B3: the real-book half of the EPUB *writer*'s byte-identity gate compares against archives
// built by the Python CLI. Those hold copyrighted book text, so they are never committed either
// and the tests skip without them.
//   ./gradlew test -Dberilo.reference.epubs.dir=/abs/path/to/<slug>.reference.epub files
val referenceEpubsProperty = "berilo.reference.epubs.dir"

tasks.withType<Test>().configureEach {
    (System.getProperty(exampleBooksProperty) ?: System.getenv("BERILO_EXAMPLES_DIR"))
        ?.takeIf(String::isNotBlank)
        ?.let { systemProperty(exampleBooksProperty, it) }
    (System.getProperty(referenceEpubsProperty) ?: System.getenv("BERILO_REFERENCE_EPUBS_DIR"))
        ?.takeIf(String::isNotBlank)
        ?.let { systemProperty(referenceEpubsProperty, it) }
}

// S2.10: JVM screenshot harness. `screenshots` clones testDebugUnitTest's fully-configured
// classpath/resources (merged Android resources, Robolectric jars — see testOptions.unitTests
// above) so string/drawable resources resolve, but runs only the screenshot package and always
// re-executes (no UP-TO-DATE skip) so a rerun always regenerates every PNG. Configured in
// afterEvaluate: testDebugUnitTest's classpath/systemProperties aren't populated by AGP until
// its own afterEvaluate wiring has run.
afterEvaluate {
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest").get()
    tasks.register<Test>("screenshots") {
        group = "verification"
        description = "Regenerates the S2.10 JVM screenshot harness PNGs (phone + Boox " +
            "widths, light + dark theme) under build/outputs/roborazzi/."
        testClassesDirs = debugUnitTest.testClassesDirs
        classpath = debugUnitTest.classpath
        systemProperties = debugUnitTest.systemProperties.toMutableMap()
        jvmArgs = debugUnitTest.jvmArgs
        filter {
            includeTestsMatching("app.berilo.reader.screenshot.*")
            isFailOnNoMatchingTests = true
        }
        // Roborazzi is disabled (a silent no-op) unless this system property is set — it's
        // normally set by Roborazzi's own Gradle plugin, which this project doesn't apply.
        systemProperty("roborazzi.test.record", "true")
        outputs.upToDateWhen { false }
        testLogging { events("passed", "skipped", "failed") }
    }
}
