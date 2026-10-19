import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing key, kept out of git in android/signing/ (same key as Pad Games; see android/README.md).
val signingProps = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "net.fp10.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.fp10.app"
        // Android 12+: the BLUETOOTH_SCAN / BLUETOOTH_CONNECT permissions.
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        if (signingProps != null) create("release") {
            storeFile = rootProject.file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The page (index.html in the repo root) is bundled as the offline fallback.
    sourceSets["main"].assets.directories += "build/generated/web"
}

val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.buildDirectory.dir("generated/web"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")
}
