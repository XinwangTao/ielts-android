plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.txw.ielts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.txw.ielts"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"

        ndk {
            // 只打平板/手机在用的架构，避免 APK 被其它 ABI 撑大
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }

    // JVM 单测里 android.util.Log 等桩方法默认抛 "not mocked"；让它们返回默认值，
    // 才能直接测带日志的纯逻辑（如 routeLlm 的通道回退）。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // 只打 arm64-v8a（本地 ONNX 模型没有 x86 版本）；ChromeOS 的 ARM 翻译用不上这类检查。
        disable += "ChromeOsAbiSupport"
    }
}

// Room 结构快照：导出到 app/schemas 并入库。以后改表（加字段/索引）能对照旧结构写迁移，
// 也能用 MigrationTestHelper 做迁移测试；不导出时一切都只能靠手写 SQL 猜。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // 单元测试：纯函数（题型判定 / 评分规则 / Route 编解码）跑 JVM，不依赖设备。
    // org.json 必须在 android.jar 的 stub 之前生效，否则 JSONObject 会抛 "not mocked"。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
