plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val sdkVersion = providers.gradleProperty("wolvy.sdkVersion").get()

android {
    namespace = "net.wolvy.player"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { explicitApi() }

val media3 = "1.11.1"
dependencies {
    api("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
