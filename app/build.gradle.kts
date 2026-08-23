plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shinku.aipassport.openclaw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shinku.aipassport.openclaw"
        minSdk = 26          // BLE 前台 Service + 后台扫描需要
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // WebSocket 连 OpenClaw gateway
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 离线 ASR:Vosk(消费设备经 BLE 送来的 PCM)
    implementation("com.alphacephei:vosk-android:0.3.47")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
