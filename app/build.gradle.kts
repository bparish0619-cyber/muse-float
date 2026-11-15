import java.util.Properties

// Muse Float app module.
//
// Signing: release builds are signed only when a local.properties file in the
// project root provides the four values below (CI creates it from GitHub
// secrets; the keystore itself is never committed). Without it, a local
// release build falls back to unsigned, which is fine for compile checks.
//
//   KEY_PATH=./key.jks          path to the keystore, relative to project root
//   ALIAS_NAME=musefloat       key alias inside the keystore
//   ALIAS_PASSWORD=...          password for that key
//   KEY_PASSWORD=...            password for the keystore itself
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val signingPath: String? = localProps.getProperty("KEY_PATH")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.northbrand.musefloat"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.northbrand.musefloat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0" // CHANGE-ME on each release; bump versionCode too
    }

    signingConfigs {
        if (signingPath != null) {
            create("release") {
                storeFile = rootProject.file(signingPath)
                storePassword = localProps.getProperty("KEY_PASSWORD")
                keyAlias = localProps.getProperty("ALIAS_NAME")
                keyPassword = localProps.getProperty("ALIAS_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Small utility app: no shrinking, keeps builds simple and fast.
            isMinifyEnabled = false
            // Use the real release signature when CI provided one.
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries on purpose: the app is four small Kotlin files
// using only framework APIs, so there is nothing here to add.
