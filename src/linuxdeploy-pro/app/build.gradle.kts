plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // 新包名：与原版 ru.meefik.linuxdeploy 彻底区分
    namespace = "io.github.ganyu256.linuxdeploypro"
    // compileSdk 37：仅编译基线。Miuix 0.9.3 / core-ktx 1.19.0 已按 37 编译，
    // 若不跟进会触发 AAR 元数据校验失败。targetSdk 仍保持 36（Android 16 行为）。
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ganyu256.linuxdeploypro"
        // minSdk 28：Android 9，兼顾老设备；私有二进制执行限制也以 28 为基线
        minSdk = 28
        // targetSdk 36：Android 16，启用新一代系统行为与特性
        targetSdk = 36
        versionCode = 40114
        versionName = "4.1.14"
    }

    signingConfigs {
        // 本地 release 签名（keystore/ 不入库；口令仅本地开发用，发布请改用注入）
        create("release") {
            storeFile = rootProject.file("keystore/linuxdeploy-pro.jks")
            storePassword = "linuxdeploy2026"
            keyAlias = "linuxdeploy"
            keyPassword = "linuxdeploy2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 离线环境无 lint-gradle 依赖缓存，release 构建跳过 lintVital（lint 可单独执行）
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME/VERSION_CODE 供 UI 统一显示版本号
        buildConfig = true
    }
}

dependencies {
    // Compose BOM：统一管理 androidx.compose 各组件版本
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // 基础图标集（Home / Settings / Add 等）
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Miuix：HyperOS 风格 UI 组件库（0.9.3）
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    // Miuix preference：官方设置行组件（Arrow / Switch / OverlayDropdown），与 SukiSU 同款
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
