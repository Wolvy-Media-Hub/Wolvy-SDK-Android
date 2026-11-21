plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.wolvy.player.sample"
    compileSdk = 36
    defaultConfig {
        // Add this id to the test account's Allowed apps (Settings → Protection).
        applicationId = "net.wolvy.player.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = providers.gradleProperty("wolvy.sdkVersion").get()
    }
    buildTypes {
        // A sample: signed with the debug key so the release build installs on a test phone.
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":wolvy-player"))
    implementation("androidx.appcompat:appcompat:1.7.1")
}
