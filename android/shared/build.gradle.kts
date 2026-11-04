// Code shared by the Android app and the desktop (Windows) app: screens,
// models, strings and icons. Compose Multiplatform; Android + JVM desktop.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    android {
        namespace = "app.iykyk.shared"
        compileSdk {
            version = release(37) {
                minorApiLevel = 2
            }
        }
        minSdk = 26
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.zxing.core)
            // Android has org.json built in; the desktop app adds the library.
            compileOnly(libs.json)
        }
    }
}

compose.resources {
    packageOfResClass = "app.iykyk.shared.resources"
    publicResClass = true
}
