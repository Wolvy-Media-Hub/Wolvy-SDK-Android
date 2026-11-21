import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
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
    // App protection (phase 5): Classic Play Integrity tokens under the customer's own linked
    // Cloud project. Without Google Play services the app simply plays unproven.
    implementation("com.google.android.play:integrity:1.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}

// net.wolvy:wolvy-player on Maven Central (namespace net.wolvy, verified 2026-11-19): signed, with
// sources and Dokka documentation. `./gradlew :wolvy-player:publishToMavenLocal` for development;
// `./gradlew :wolvy-player:publishToMavenCentral` for a release (credentials and the signing key in
// ~/.gradle/gradle.properties or the release workflow's secrets, never in this repository).
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    // Signed when a key is configured (a release); unsigned for publishToMavenLocal. Maven Central
    // refuses unsigned artifacts, so a release without a key fails there, never silently.
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }
    coordinates("net.wolvy", "wolvy-player", sdkVersion)
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    pom {
        name.set("Wolvy Android SDK")
        description.set("Plays Wolvy's DRM-protected video (DASH, Widevine, Media3) in Android apps, with the viewer watermark, screen-capture protection and the web Player API's methods and events.")
        inceptionYear.set("2026")
        url.set("https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Android")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("wolvy")
                name.set("Wolvy")
                url.set("https://wolvy.net")
            }
        }
        organization {
            name.set("Wolvy")
            url.set("https://wolvy.net")
        }
        scm {
            url.set("https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Android")
            connection.set("scm:git:https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Wolvy-Media-Hub/Wolvy-SDK-Android.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Android/issues")
        }
    }
}
