import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release (Play upload key) signing. Secrets live OUTSIDE the repo in a properties file:
//   -ProcketLauncherKeystoreProps=<path>, or env ROCKETLAUNCHER_KEYSTORE_PROPS, or the default below.
// Keys: storeFile, storePassword, keyAlias, keyPassword. If the file is missing, debug builds still
// work and release outputs are simply unsigned.
val releaseKeystorePropsFile = file(
    (findProperty("rocketLauncherKeystoreProps") as String?)
        ?: System.getenv("ROCKETLAUNCHER_KEYSTORE_PROPS")
        ?: "C:/Users/Brandon/AndroidKeys/rocketlauncher-keystore.properties"
)
val releaseKeystoreProps = Properties().apply {
    if (releaseKeystorePropsFile.isFile) releaseKeystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = releaseKeystorePropsFile.isFile

android {
    namespace = "com.brandonwallace.rocketlauncher"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.brandonwallace.rocketlauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3"
    }
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
