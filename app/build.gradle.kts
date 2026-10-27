plugins {
    id("com.android.application")
}

android {
    namespace = "com.cx531.adbkeep"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cx531.adbkeep"
        // 覆盖到 Android 4.4：装得上、开机能恢复即可。
        // 周期任务依赖 JobScheduler（API 21+），4.4/4.4W 上自动跳过，不会崩溃。
        minSdk = 19
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 与 Aerial 同用 debug 签名，方便共存/覆盖安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 极简：不需要任何第三方依赖
}
