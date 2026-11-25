plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// Release pipeline override: -PvitalsVersionName=<version>.
//
// The version name is the single source of truth for the APK: release-please
// rewrites the literal between the annotations below. The version code is
// derived from it (major*10000 + minor*100 + patch) unless it is passed
// explicitly with -PvitalsVersionCode. The offset keeps published codes above
// the 303/304 builds installed while testing the 0.3.2 UI locally.
val vitalsVersionName = providers.gradleProperty("vitalsVersionName").getOrElse(
    /* x-release-please-start-version */
    "0.3.2"
    /* x-release-please-end */
)
val vitalsVersionCode = providers.gradleProperty("vitalsVersionCode").orNull?.toInt() ?: run {
    val (major, minor, patch) = vitalsVersionName.split(".").map { it.toInt() }
    major * 10000 + minor * 100 + patch + 3
}
val vitalsKeystore = providers.gradleProperty("vitalsKeystore").orNull

android {
    namespace = "dev.vitals.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.vitals.app"
        minSdk = 26
        targetSdk = 35
        versionCode = vitalsVersionCode
        versionName = vitalsVersionName

        // One architecture only: arm64 devices (the target phone) get a much
        // smaller APK; ML Kit and CameraX ship native libs for four ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (vitalsKeystore != null) {
            create("release") {
                storeFile = file(vitalsKeystore)
                storePassword = providers.gradleProperty("vitalsKeystorePassword").orNull
                keyAlias = providers.gradleProperty("vitalsKeyAlias").orNull
                keyPassword = providers.gradleProperty("vitalsKeyPassword").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (vitalsKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
