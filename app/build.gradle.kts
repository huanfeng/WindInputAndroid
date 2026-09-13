import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.windinput.ime"
    // miuix 0.9.3 要求 compileSdk ≥ 37；AGP 8.13 官方推荐上限 36，
    // 已在 gradle.properties 里 suppressUnsupportedCompileSdk 抑制告警
    //
    // ⚠ API 37 起 SDK 平台包带**小版本号**（platforms;android-37.0 / 37.1 / 37.2），
    // 光写 compileSdk = 37 时 AGP 去找 hash "android-37"，新装的 SDK 里没有这个目录，
    // 报的是「Failed to find target with hash string 'android-37'」——看着像 SDK 没装全，
    // 其实是命名变了。compileSdkMinor 把 hash 补成 "android-37.0"。
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.windinput.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    // TOML 解析（布局/设置清单；正式配置链路由 Rust wind-config 负责）
    implementation(libs.tomlj)
    implementation(libs.androidx.recyclerview)
    // UniFFI 生成绑定的运行时依赖（JNA 需 @aar 变体）
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines)
}
