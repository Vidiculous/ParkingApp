plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.parking"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.parking"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_MOCK_LOCATION", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ALLOW_MOCK_LOCATION", "false")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // Google Play Services — geofencing
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Map
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
