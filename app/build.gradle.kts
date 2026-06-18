plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quizhelper.dumptool"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quizhelper.dumptool"
        minSdk = 30
        targetSdk = 34
        // 版本号单一来源：gradle.properties 的 appVersionCode/appVersionName，发版只改那里。
        versionCode = (project.property("appVersionCode") as String).toInt()
        versionName = project.property("appVersionName") as String
    }

    buildFeatures {
        buildConfig = true   // 生成 BuildConfig.VERSION_NAME 供 App 内显示版本号
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "NurseQuiz-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../huli_release.jks")
            storePassword = "huli2026"
            keyAlias = "huli"
            keyPassword = "huli2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // 离线中文文字识别（自带模型，无需联网下载）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
