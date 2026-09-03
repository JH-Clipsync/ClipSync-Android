import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 读取项目根目录 local.properties 里的默认配置
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 默认指向公共服务端；本地调试时在 local.properties 覆盖 clipsync.server=ws://host:port
// 注意：服务端走 /clipsync 路径反代（仅 HTTP），WebSocket 只连主机；这里只写主机地址
val defaultServer = localProps.getProperty("clipsync.server", "wss://www.95qw.com")
// token 不再由构建期注入：改为登录接口签发。这里只保留一个默认用户名方便本地联调。
val defaultUsername = localProps.getProperty("clipsync.username", "")

android {
    namespace = "com.clipsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clipsync"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2026.9.3.1"

        // 把默认配置作为 BuildConfig 常量注入到代码里
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
        buildConfigField("String", "DEFAULT_USERNAME", "\"$defaultUsername\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Release 签名：从 local.properties 读取；缺失时不配置（CI 会注入临时 keystore）
    signingConfigs {
        if (localProps.getProperty("clipsync.storeFile") != null) {
            create("release") {
                storeFile = file(localProps.getProperty("clipsync.storeFile"))
                storePassword = localProps.getProperty("clipsync.storePassword")
                keyAlias = localProps.getProperty("clipsync.keyAlias")
                keyPassword = localProps.getProperty("clipsync.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.findByName("release") != null) {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // 端到端加密的跨端一致性测试（纯 JVM，不需要设备）
    testImplementation("junit:junit:4.13.2")
}
