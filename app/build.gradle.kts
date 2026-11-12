plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.brick.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brick.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The David word library is read in place, so it must not be compressed inside the APK.
    androidResources {
        noCompress += "opus"
    }

    testOptions {
        unitTests.all { test ->
            listOf("fxIn", "fxOut", "stitchOut", "liveNet").forEach { key ->
                project.findProperty(key)?.let { test.systemProperty(key, it.toString()) }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Android Auto screen (Car App Library templates).
    implementation("androidx.car.app:app:1.4.0")
    // Brick on the watch talks to the phone over the Wear OS Data Layer.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Offline "Hey Brick" wake word (Vosk, Apache 2.0) and its native bridge.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
