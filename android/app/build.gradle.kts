import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.tapdrop"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tapdrop"
        minSdk = 33
        targetSdk = 36
        versionCode = 12
        versionName = "2.0.7"
    }

    // Signing key and passwords live in android/keystore.properties (not committed).
    // Without it, builds fall back to the debug key.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("local") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("local") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("local")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    // Material 3 Expressive (LoadingIndicator, MaterialShapes, ButtonGroup, MotionScheme.expressive()) only ships
    // in the 1.5 line; the BOM still maps material3 to 1.4.0. 1.5.0-beta02 is built on the BOM's Compose 1.12.
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material:material-icons-extended")
    // Shape morphing for the hero status shape and the tap illustration.
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    // Real backdrop blur for top bars, sheets and floating toolbars.
    implementation("dev.chrisbanes.haze:haze:2.1.0")
    implementation("dev.chrisbanes.haze:haze-blur:2.1.0")
    // Thumbnails (photos and video frames) with caching and crossfades.
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
