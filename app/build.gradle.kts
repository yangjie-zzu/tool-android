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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        resValue("string", "app_name", baseAppName)
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
        create("priApp") {
            dimension = "env"
            applicationIdSuffix = ".pri"
            resValue("string", "app_name", "$baseAppName(pri)")
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
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

    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.02")

    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
}