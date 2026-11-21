import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// For the Play internal-testing upload (spike A2): -PuploadKeyProps=/path/upload.properties
// (storeFile, storePassword, keyAlias, keyPassword), kept outside the repository. Without it,
// release builds are signed with the debug key, for sideloading on test phones.
val uploadProps = (findProperty("uploadKeyProps") as String?)?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "net.wolvy.spike.attest"
    compileSdk = 36
    defaultConfig {
        // A customer-like app: not linked to any Cloud project in Play Console.
        applicationId = "net.wolvy.spike.attest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        if (uploadProps != null) create("upload") {
            storeFile = file(uploadProps.getProperty("storeFile"))
            storePassword = uploadProps.getProperty("storePassword")
            keyAlias = uploadProps.getProperty("keyAlias")
            keyPassword = uploadProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        // Test builds only. Without an upload key the debug key signs both, so the APK installs without a keystore.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (uploadProps != null) "upload" else "debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The same Play Integrity library as Wolvy Player.
    implementation("com.google.android.play:integrity:1.5.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
