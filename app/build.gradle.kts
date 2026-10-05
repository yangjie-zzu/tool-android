import java.text.SimpleDateFormat
import java.util.Date

import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

val baseAppName = "tool"

android {
    namespace = "com.yukino.tool"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yukino.tool"
        minSdk = 24
        targetSdk = 34
        versionCode = 33
        versionName = "1.5.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        resValue("string", "app_name", baseAppName)

        // 每次构建记录构建时间,主页展示(配置阶段每次构建都会重新求值)。
        // 用 resValue 而非 buildConfigField: 字符串资源运行时读取,
        // 不会被 Kotlin 内联进调用处,避免增量编译残留旧时间
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        resValue("string", "build_time", "\"$buildTime\"")
        // 版本号同样走 resValue: BuildConfig.VERSION_NAME 是编译期常量,
        // 会被 Kotlin 内联,增量编译下版本升级不重编显示方就残留旧值
        resValue("string", "version_name", "\"${defaultConfig.versionName}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // BuildConfig 里的 BUILD_TIME 每次构建都要刷新:
    // AGP 不感知 buildConfigField 值的变化,不强制重跑会复用旧时间戳
    tasks.configureEach {
        if (name.startsWith("generate") && (name.contains("BuildConfig") || name.contains("ResValues"))) {
            outputs.upToDateWhen { false }
        }
    }

    testOptions {
        // 阅读器排版(JVM单测)需要 android.graphics.Paint 等桩返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
        // fork 的测试 JVM 必须与 daemon 同编码: daemon(file.encoding=UTF-8)写 worker classpath,
        // fork 按系统默认(GBK)读会把 GRADLE_USER_HOME 中文用户名路径读乱 → GradleWorkerMain 加载失败
        unitTests.all { it.jvmArgs("-Dfile.encoding=UTF-8") }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("ANDROID_SIGNE_PATH"))
            storePassword = System.getenv("ANDROID_SIGNE_STORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_SIGNE_ALIAS")
            keyPassword = System.getenv("ANDROID_SIGNE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            resValue("string", "app_name", "$baseAppName(debug)")
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("pubApp") {
            dimension = "env"
            applicationIdSuffix = ".pub"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    android.applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "${applicationId}-${defaultConfig.versionName}.apk"
        }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.9.0")
    // 四期: EPUB 内 SVG 图片/封面栅格化(roadmap 预告的唯一新依赖)
    implementation("com.caverock:androidsvg:1.4")
    // 1.1.0在API 30+有认证成功后CryptoObject为null的bug；1.4.0要求compileSdk 36+AGP 8.9.1，暂用1.2.0
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.fragment:fragment-ktx:1.5.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    // 备忘录2FA扫码: 相机预览+本地二维码识别(内置模型，无需Play服务)
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // QrCodeScanner等扩展图标(仅打包用到的，release由R8裁剪)；BOM已不含icons，需显式版本
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    val navVersion = "2.8.5"
    // Jetpack Compose integration
    implementation("androidx.navigation:navigation-compose:$navVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0-RC")

    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // 备忘录导出: 带密码的加密ZIP(AES-256)
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // 阅读器: TXT编码自动检测(GBK/UTF-8/UTF-16等)
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")

    // 阅读器: EPUB内XHTML解析为纯文本(容错HTML解析)
    implementation("org.jsoup:jsoup:1.18.3")

    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.02")

    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
}