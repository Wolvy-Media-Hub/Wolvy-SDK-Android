plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
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
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
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

// net.wolvy:wolvy-player. `./gradlew :wolvy-player:publishToMavenLocal` for the Flutter and React Native
// wrappers while they are developed; Maven Central (namespace net.wolvy, verified) at release.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "net.wolvy"
            artifactId = "wolvy-player"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Wolvy Android SDK")
                description.set("Plays Wolvy's DRM-protected video (DASH, Widevine, Media3) in Android apps.")
                url.set("https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Android")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                organization { name.set("Wolvy"); url.set("https://wolvy.net") }
                scm {
                    url.set("https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Android")
                    connection.set("scm:git:https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Android.git")
                }
            }
        }
    }
}
