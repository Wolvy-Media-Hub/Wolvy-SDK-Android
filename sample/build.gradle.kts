import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App protection tests (phase 5) need the sample installed from Google Play under an app that is
// set up in Play Console: -PsampleAppId=net.wolvy.spike.attest -PsampleVersionCode=N
// -PuploadKeyProps=/path/upload.properties (storeFile, storePassword, keyAlias, keyPassword; kept
// outside the repository) for `bundleRelease`. Without them: the usual sample, debug-signed.
val uploadProps = (findProperty("uploadKeyProps") as String?)?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "net.wolvy.player.sample"
    compileSdk = 36
    defaultConfig {
        // Add this id to the test account's Allowed apps (Settings → Protection).
        applicationId = (findProperty("sampleAppId") as String?) ?: "net.wolvy.player.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = ((findProperty("sampleVersionCode") as String?) ?: "1").toInt()
        versionName = providers.gradleProperty("wolvy.sdkVersion").get()
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
        // A sample: signed with the debug key so the release build installs on a test phone
        // (or with the upload key, for a Play internal-testing build).
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (uploadProps != null) "upload" else "debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":wolvy-player"))
    implementation("androidx.appcompat:appcompat:1.7.1")
}
