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
    testOptions {
        unitTests {
            // 网关类里用了 android.util.Log;JVM 单测下让它返回默认值而不是抛 "not mocked"
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // WebSocket 连 OpenClaw gateway
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ed25519 设备身份签名(OpenClaw 网关 connect 鉴权需要;minSdk 26 需第三方实现)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Vosk 离线语音识别(消费设备 BLE PCM;模型需用户放入 filesDir 下 vosk-model-* 目录)
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Opus 编码器(裸 Opus 帧输出) —— 小智识别必须收 16k Opus 帧;App 把设备 PCM 编成 Opus 上送。
    // rifai/android-opus-codec 预编译 aar(libopus 1.3.1),已放 app/libs/opus.aar
    implementation(files("libs/opus.aar"))

    // 单测:HermesGateway 的 HTTP 行为用 MockWebServer 在 JVM 上验证(不依赖真设备/真网关)
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
