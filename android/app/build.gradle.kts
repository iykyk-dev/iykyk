plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        versionCode = 1
        versionName = "0.1.0"

        // Where this build is distributed: "site" (APK from the project page),
        // "play" or "appstore". Store rules decide whether "Buy"/"Renew" may lead
        // to the Telegram bot (see Purchase.kt). Build with -Pdistribution=play.
        buildConfigField("String", "DISTRIBUTION", "\"${project.findProperty("distribution") ?: "site"}\"")
        buildConfigField("String", "BOT_URL", "\"https://t.me/dlyasvoihVPS_bot\"")
    }

    signingConfigs {
        // A fixed debug key (not secret) so each CI build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
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

    packaging {
        // Compressed native libraries: the Go core is large, this roughly halves the APK.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Go core built by core/build.sh (sing-box libbox + Xray + app glue).
    implementation(files("libs/iykyk-core.aar"))

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
