plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// One version for all platforms: the VERSION file, or the release tag in CI
// (-PappVersion=v1.0.1 or v1.1.0-beta2).
val appVersion: String = (findProperty("appVersion") as String?)?.removePrefix("v")
    ?: rootProject.file("../VERSION").readText().trim()

// 1.2.3 → 1020399, 1.2.3-beta4 → 1020304: betas come before their release,
// and every release after all of its betas.
fun versionCodeOf(version: String): Int {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-beta(\d+))?""").matchEntire(version)
        ?: error("Version must look like 1.2.3 or 1.2.3-beta4, got $version")
    val (major, minor, patch, beta) = match.destructured
    val build = if (beta.isEmpty()) 99 else beta.toInt().coerceIn(1, 98)
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + build
}

// The release key comes from CI secrets (never from the repository).
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE_FILE")

android {
    namespace = "app.iykyk.client"

    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        // Never change after the first public release: it breaks updates.
        applicationId = "app.iykyk.client"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion

        // Where this build is distributed: "site" (APK from the project page),
        // "play" or "appstore". Store rules decide whether "Buy"/"Renew" may lead
        // to the Telegram bot (see Purchase.kt). Build with -Pdistribution=play.
        buildConfigField("String", "DISTRIBUTION", "\"${project.findProperty("distribution") ?: "site"}\"")
        buildConfigField("String", "BOT_URL", "\"https://t.me/dlyasvoihVPS_bot\"")
        buildConfigField("boolean", "UPDATES_ENABLED", "false")
    }

    signingConfigs {
        // A fixed debug key (not secret) so each CI build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            // Copies from the site update themselves; store copies update through the store.
            val site = (project.findProperty("distribution") ?: "site") == "site"
            buildConfigField("boolean", "UPDATES_ENABLED", site.toString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // One APK per processor (a third of the size) plus a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // Compressed native libraries: the Go core is large, this roughly halves the APK.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Go core built by core/build.sh (sing-box libbox + Xray + app glue).
    implementation(files("libs/iykyk-core.aar"))
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
