plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 正式签名配置:参数全部从**环境变量**读取,keystore 与密码都不入仓库
 * (仓库里只有变量名,密钥文件放在仓库外,例如 `D:\Git-Workspace\.tools\keys\`)。
 *
 * 四个变量(缺任意一个则 release 构建保持未签名,保证别人 clone 也能正常构建):
 *   AIPASSPORT_KEYSTORE            keystore 绝对路径
 *   AIPASSPORT_KEYSTORE_PASSWORD   keystore 口令
 *   AIPASSPORT_KEY_ALIAS           密钥别名
 *   AIPASSPORT_KEY_PASSWORD        密钥口令
 *
 * 本机用法(凭据文件在仓库外):
 *   source D:/Git-Workspace/.tools/keys/aipassport-openclaw.env.sh
 *   ./gradlew assembleRelease
 */
val releaseKeystorePath: String? = System.getenv("AIPASSPORT_KEYSTORE")
val releaseKeystorePassword: String? = System.getenv("AIPASSPORT_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("AIPASSPORT_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("AIPASSPORT_KEY_PASSWORD")
val hasReleaseSigning: Boolean = !releaseKeystorePath.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseKeystorePath).exists()

android {
    namespace = "com.shinku.aipassport.openclaw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shinku.aipassport.openclaw"
        minSdk = 26          // BLE 前台 Service + 后台扫描需要
        targetSdk = 35
        versionCode = 10
        // 版本号与固件/社区保持一致(硬约定,两段式):固件 tag vX.Y-intercom ↔ App versionName X.Y。
        // 1.12 = 对应固件 v1.12-intercom(固件/App 互报版本 + 不一致双向提示)。
        // 重发记录(versionName 不动,只提 versionCode;App 侧修 bug 用这种办法保持与固件/社区同号):
        //   9  = 设备屏方块提示(版本不匹配文案里的 ↔ / 重连提示分隔符 —)
        //   10 = 更新提醒不再展示旧版本号(升级后横幅还写「当前 1.11」)
        // ⚠ 只提 versionCode 不提 versionName,所以已装 1.12 的设备【不会】收到「有新版本」提醒
        // (更新提醒按 versionName 比对),需要在 Release 页手动下载重装。
        versionName = "1.12"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 只有四个环境变量都在时才签名;否则产出未签名 APK(不静默回退到 debug 签名,
            // 避免“看起来是正式包实际是调试签名”)
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
