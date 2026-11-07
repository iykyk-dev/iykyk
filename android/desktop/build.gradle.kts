import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The Windows app: the shared screens in a desktop window with a tray icon.
// The VPN itself runs in iykyk-service.exe (core/cmd/iykyk-service), a Windows
// service the installer registers; this app talks to it over a local API.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.components.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.json)
}

// The release tag in CI, else the VERSION file. Windows wants plain numbers
// for the package: "1.1.0-beta2" → "1.1.0".
val fullVersion = (findProperty("appVersion") as String?)?.removePrefix("v")
    ?: rootProject.file("../VERSION").readText().trim()
val appVersion = fullVersion.substringBefore("-")

compose.desktop {
    application {
        mainClass = "app.iykyk.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx256m", "-Diykyk.version=$fullVersion")
        nativeDistributions {
            // The installer is built with Inno Setup (installer/iykyk.iss) from
            // this app image, so it can also register the core service.
            targetFormats(TargetFormat.AppImage)
            packageName = "IYKYK"
            packageVersion = appVersion
            description = "IYKYK «Для своих»"
            vendor = "IYKYK"
            copyright = "GPL-3.0-or-later"
            modules("java.net.http", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("icon.ico"))
                upgradeUuid = "6d3c3f5e-8f0e-4b8e-9a51-1f0b2a7c9e41"
            }
        }
    }
}
