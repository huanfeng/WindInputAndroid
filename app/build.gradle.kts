import java.util.Properties
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
        versionCode = 48
        versionName = "0.3.0-dev.45"
        // 仪器测试（androidTest）：自绘控件的无障碍动作等要在真实 Android 运行时里验
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── 签名 ─────────────────────────────────────────────────────────
    // 一把密钥签**所有**包（debug 与 release 不分），任何机器构出来的包互相都能覆盖安装。
    // 密钥文件与密码放 local.properties（已 gitignore）的 signing.* 四项，绝不进仓库。
    // 没给这四项（别人 clone、CI）：debug 回退到仓库里公开的 keystore/debug.keystore，
    // release 保持未签名——宁可装不上，也不能悄悄签出一个"看着像正式包"的东西。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    val personalSigning = listOf(
        "signing.storeFile", "signing.storePassword", "signing.keyAlias", "signing.keyPassword",
    ).map { localProps.getProperty(it) }.takeIf { values -> values.all { it != null } }

    signingConfigs {
        getByName("debug") {
            if (personalSigning != null) {
                val (store, storePass, alias, keyPass) = personalSigning
                storeFile = rootProject.file(store!!)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                storeFile = rootProject.file("keystore/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8：dex 由约 25MB 缩到约 4MB，类少了冷启动也少加载；JNA / UniFFI 的反射入口在 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            // 正式包只给真机：x86_64 核心 .so 有 21MB，只有模拟器用（模拟器测试走 debug 包）
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 同一把：有个人密钥才签 release
            if (personalSigning != null) signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // release 包直接带版本号命名（WindInputAndroid-0.3.0-dev.N.apk），发出去的文件一眼可辨、
    // 多个版本放一起不互相覆盖；与发版工作流 dist/ 里的命名一致。debug 包名不动，测试脚本照用
    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "WindInputAndroid-${variant.versionName}.apk"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 单测直接读 src/main/assets 下的清单、布局、词库（BundledSchemasTest、LayoutParserTest…），
// 不声明为输入的话只改资源时 Gradle 判定测试 up-to-date、跳过不跑，缺文件也照样「通过」
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // ExploreByTouchHelper：自绘键盘 / 候选栏的无障碍虚拟节点（TalkBack、uiautomator 都靠它看见键）
    implementation(libs.androidx.customview)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    // 设置行（ArrowPreference / SwitchPreference / SliderPreference …），自带图标位与 miuix 的行高、分隔与点按反馈
    implementation(libs.miuix.preference)
    // TOML 解析（布局/设置清单；正式配置链路由 Rust wind-config 负责）
    implementation(libs.tomlj)
    implementation(libs.androidx.recyclerview)
    // UniFFI 生成绑定的运行时依赖（JNA 需 @aar 变体）
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.junit)
    // android.jar 里的 org.json 在 JVM 单测中只是桩（方法全抛异常）；词库统计的格式化要真解析
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
